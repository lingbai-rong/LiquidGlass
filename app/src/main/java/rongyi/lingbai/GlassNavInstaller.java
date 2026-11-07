package rongyi.lingbai;

import android.animation.StateListAnimator;
import android.app.Activity;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;

public final class GlassNavInstaller {
    private static final String COOLAPK_PACKAGE = "com.coolapk.market";
    private static final String TAG_GLASS_NAV = "rongyi.lingbai.LSP_KSU_DIRECT_DECOR_NAV";
    private static final int ORIGINAL_VIEW_STATE_TAG_KEY = 0x4C475303;
    private static final int ORIGINAL_WINDOW_STATE_TAG_KEY = 0x4C475304;
    private static WeakReference<CoolapkLegacyGlassNavView> cachedNavRef;
    // Keeps the lens position across CoolAPK view/theme recreation inside the same process.
    // It is only a fallback; the hidden original Compose bottom bar remains the source of truth.
    private static int lastKnownSelectedIndex = 0;
    private static long lastConfirmedBackAt = 0L;
    private static long forceHomeUntilAt = 0L;
    private static boolean backHomeLock = false;

    private GlassNavInstaller() {}

    public static void install(Activity activity) {
        try {
            if (activity == null) return;

            Resources res = activity.getResources();
            int containerId = res.getIdentifier("bottom_bar_container", "id", COOLAPK_PACKAGE);
            int composeId = res.getIdentifier("bottom_navigation2", "id", COOLAPK_PACKAGE);
            if (containerId == 0 || composeId == 0) return;

            View containerView = activity.findViewById(containerId);
            View composeView = activity.findViewById(composeId);
            if (!(containerView instanceof FrameLayout) || composeView == null) return;

            Window window = activity.getWindow();
            if (window == null) return;
            View decorView = window.getDecorView();
            if (!(decorView instanceof FrameLayout)) return;

            FrameLayout container = (FrameLayout) containerView;
            FrameLayout decor = (FrameLayout) decorView;
            GlassSettings settings = HookSettingsReader.load(activity);
            if (!settings.enabled) {
                removeAndRestore(activity, container);
                return;
            }

            captureWindowState(activity);
            applyWindow(activity, settings);
            prepareContainer(container);
            prepareContainer(decor);
            applyComposeVisibility(composeView);
            hideOriginalBottomChildren(container, composeView);
            suppressOriginalContainer(container);

            View existed = decor.findViewWithTag(TAG_GLASS_NAV);
            if (existed instanceof CoolapkLegacyGlassNavView) {
                CoolapkLegacyGlassNavView nav = (CoolapkLegacyGlassNavView) existed;
                if (nav.isThemeCompatible(activity)) {
                    nav.applySettings(settings);
                    GlobalRenderSettingsReader.apply(activity, nav);
                    nav.setTargetComposeView(composeView);
                    nav.startRealtimeBackdropRefresh();
                    nav.setSelectedIndexFromHost(lastKnownSelectedIndex);
                    configureSystemNavigationInsets(activity, nav);
                    nav.bringToFront();
                    nav.refreshBackdropSoon();
                    scheduleSelectionSync(nav);
                    return;
                }

                // CoolAPK changed its own day/night mode. Do not mutate MiuixTheme,
                // rememberLayerBackdrop, or LifecycleRegistry inside the existing view.
                // Remove the old instance completely and create a fresh ComposeView.
                try { nav.dispose(); } catch (Throwable ignored) {}
                try { decor.removeView(nav); } catch (Throwable ignored) {}
                CoolapkLegacyGlassNavView cached = cachedNavRef == null ? null : cachedNavRef.get();
                if (cached == nav) cachedNavRef = null;
            }

            // Important: exact KernelSU drawBackdrop/lens must live outside CoolAPK's own
            // bottom_bar_container. Putting it inside that Compose container can recurse into
            // HyperOS native blur (RenderThread / MiBackgroundBlurBlend). The uploaded WeChat
            // module also mounts its glass view on DecorView, not inside the original tab view.
            CoolapkLegacyGlassNavView nav = new CoolapkLegacyGlassNavView(activity);
            nav.setTag(TAG_GLASS_NAV);
            nav.setLabels(new String[]{"首页", "数码", "发现", "我的"});
            nav.applySettings(settings);
            GlobalRenderSettingsReader.apply(activity, nav);
            nav.setTargetComposeView(composeView);
            nav.setSelectedIndexFromHost(lastKnownSelectedIndex);
            cachedNavRef = new WeakReference<>(nav);
            nav.setClickable(true);
            nav.setFocusable(false);
            nav.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(activity, 120f)
            );
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            decor.addView(nav, lp);
            configureSystemNavigationInsets(activity, nav);
            nav.bringToFront();
            nav.refreshBackdropSoon();
            scheduleSelectionSync(nav);

            XposedBridge.log("[LiquidGlass] Coolapk exact 2.25 frame-synchronous backdrop installed");
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass] install error: " + t);
        }
    }


    /** Reload a newly saved module snapshot without requiring CoolAPK to restart. */
    public static void reloadSettings(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            GlassSettings settings = HookSettingsReader.load(activity);
            if (!settings.enabled) {
                install(activity);
                return;
            }
            applyWindow(activity, settings);
            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
            if (nav == null) {
                install(activity);
                return;
            }
            nav.applySettings(settings);
            GlobalRenderSettingsReader.apply(activity, nav);
            nav.bringToFront();
            nav.refreshBackdropSoon();
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass] settings reload error: " + t);
        }
    }


    private static CoolapkLegacyGlassNavView getInstalledNav(Activity activity) {
        try {
            if (activity == null || activity.getWindow() == null) return null;
            View decorView = activity.getWindow().getDecorView();
            CoolapkLegacyGlassNavView cached = cachedNavRef == null ? null : cachedNavRef.get();
            if (cached != null && cached.isAttachedToWindow() && cached.getRootView() == decorView) {
                return cached;
            }
            if (!(decorView instanceof FrameLayout)) return null;
            View existed = ((FrameLayout) decorView).findViewWithTag(TAG_GLASS_NAV);
            if (existed instanceof CoolapkLegacyGlassNavView) {
                cachedNavRef = new WeakReference<>((CoolapkLegacyGlassNavView) existed);
                return (CoolapkLegacyGlassNavView) existed;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void recreateForThemeChange(Activity activity) {
        try {
            if (activity == null || activity.getWindow() == null) return;
            View decorView = activity.getWindow().getDecorView();
            if (!(decorView instanceof FrameLayout)) return;
            FrameLayout decor = (FrameLayout) decorView;
            View existed = decor.findViewWithTag(TAG_GLASS_NAV);
            if (existed instanceof CoolapkLegacyGlassNavView) {
                try { ((CoolapkLegacyGlassNavView) existed).dispose(); } catch (Throwable ignored) {}
                try { decor.removeView(existed); } catch (Throwable ignored) {}
            }
            cachedNavRef = null;
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass] safe theme recreate error: " + t);
        }
    }

    /** Update the glass lens from a confirmed event on CoolAPK's original bottom bar. */
    public static void syncSelectedIndex(Activity activity, int index) {
        try {
            int safe = Math.max(0, Math.min(3, index));
            forceHomeUntilAt = 0L;
            backHomeLock = false;
            lastKnownSelectedIndex = safe;
            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
            if (nav != null) nav.setSelectedIndexFromHost(safe);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Called by Android 16's non-consuming back observer. The original Compose bar
     * is queried several times because CoolAPK commits the root page asynchronously.
     * If that build exposes no usable selected semantics and the activity is still
     * visible, fall back to 首页 instead of leaving the lens on the previous tab.
     */
    public static void onSystemBackInvoked(final Activity activity) {
        // This observer runs after the framework has committed the back action. It is
        // only a fallback; direct callback/key/edge hooks preserve the pre-back state.
        onBackCallbackFinished(activity, shouldReturnHomeOnBack(activity));
    }

    /**
     * Complete a back operation using the root-tab decision captured before the callback ran.
     * A non-home root tab returns to 首页; a pushed detail page keeps its owning tab and only
     * requests a normal state re-read after the host finishes popping the route.
     */
    public static void onBackCallbackFinished(final Activity activity, boolean shouldReturnHome) {
        try {
            if (activity == null) return;
            if (shouldReturnHome) {
                onConfirmedBackGesture(activity);
            } else {
                final View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                if (decor == null) return;
                decor.postDelayed(new Runnable() {
                    @Override public void run() {
                        syncSelectionFromOriginal(activity);
                    }
                }, 140L);
                decor.postDelayed(new Runnable() {
                    @Override public void run() {
                        syncSelectionFromOriginal(activity);
                    }
                }, 360L);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * A completed system/AndroidX back action in CoolAPK MainActivity returns the root
     * navigation to 首页 on the affected builds, while the hidden Compose bottom bar may
     * keep stale semantics on 我的. Do not trust those stale semantics here: force the
     * glass state to 首页 several times while CoolAPK finishes its asynchronous route pop.
     */
    public static void onConfirmedBackGesture(final Activity activity) {
        try {
            if (activity == null || activity.getWindow() == null) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastConfirmedBackAt < 72L) return;
            lastConfirmedBackAt = now;
            forceHomeUntilAt = now + 1400L;
            backHomeLock = true;
            final View decor = activity.getWindow().getDecorView();
            if (decor == null) return;

            // Commit immediately so a stale installer/resume pass cannot restore 我的.
            lastKnownSelectedIndex = 0;
            long[] delays = new long[]{0L, 48L, 120L, 240L, 420L, 700L};
            for (final long delay : delays) {
                decor.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
                            if (nav == null) return;
                            lastKnownSelectedIndex = 0;
                            nav.setSelectedIndexFromHost(0);
                            if (delay == 120L || delay == 420L) nav.refreshBackdropSoon();
                        } catch (Throwable ignored) {
                        }
                    }
                }, delay);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Read the selected semantics from CoolAPK's hidden Compose bottom bar. */
    public static void syncSelectionFromOriginal(Activity activity) {
        try {
            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
            if (nav == null) return;
            int detected = nav.detectOriginalSelectedIndex();
            if (backHomeLock || android.os.SystemClock.uptimeMillis() < forceHomeUntilAt) {
                if (detected == 0) backHomeLock = false;
                lastKnownSelectedIndex = 0;
                nav.setSelectedIndexFromHost(0);
                return;
            }
            if (detected >= 0) {
                lastKnownSelectedIndex = detected;
                nav.setSelectedIndexFromHost(detected);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Decide before a back action whether CoolAPK is on a non-home root tab.
     *
     * The old implementation inspected bottom_bar_container translation. LiquidGlass itself moves
     * that container off-screen, so the check was always false and a back from 我的 could never
     * move the glass indicator to 首页. The selected tab plus the AndroidX fragment back stack is a
     * more useful source of truth: root tabs have no pushed fragment entry, while detail pages do.
     */
    public static boolean shouldReturnHomeOnBack(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
            int selected = nav == null ? -1 : nav.detectOriginalSelectedIndex();
            if (selected < 0) selected = lastKnownSelectedIndex;
            if (selected <= 0) return false;

            int backStackCount = readSupportFragmentBackStackCount(activity);
            if (backStackCount > 0) return false;
            return true;
        } catch (Throwable ignored) {
            return lastKnownSelectedIndex > 0;
        }
    }

    private static int readSupportFragmentBackStackCount(Activity activity) {
        try {
            java.lang.reflect.Method getManager = activity.getClass().getMethod("getSupportFragmentManager");
            Object manager = getManager.invoke(activity);
            if (manager == null) return -1;
            java.lang.reflect.Method getCount = manager.getClass().getMethod("getBackStackEntryCount");
            Object value = getCount.invoke(manager);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void scheduleSelectionSync(final CoolapkLegacyGlassNavView nav) {
        if (nav == null) return;
        Runnable sync = new Runnable() {
            @Override public void run() {
                int detected = nav.detectOriginalSelectedIndex();
                if (backHomeLock || android.os.SystemClock.uptimeMillis() < forceHomeUntilAt) {
                    if (detected == 0) backHomeLock = false;
                    lastKnownSelectedIndex = 0;
                    nav.setSelectedIndexFromHost(0);
                    return;
                }
                if (detected >= 0) {
                    lastKnownSelectedIndex = detected;
                    nav.setSelectedIndexFromHost(detected);
                }
            }
        };
        nav.post(sync);
        long[] delays = new long[]{80L, 220L, 520L};
        for (final long delay : delays) {
            nav.postDelayed(new Runnable() {
                @Override public void run() {
                    int detected = nav.detectOriginalSelectedIndex();
                    if (backHomeLock || android.os.SystemClock.uptimeMillis() < forceHomeUntilAt) {
                        if (detected == 0) backHomeLock = false;
                        lastKnownSelectedIndex = 0;
                        nav.setSelectedIndexFromHost(0);
                        return;
                    }
                    if (detected >= 0) {
                        lastKnownSelectedIndex = detected;
                        nav.setSelectedIndexFromHost(detected);
                    }
                }
            }, delay);
        }
    }

    public static void refreshBackdrop(Activity activity) {
        try {
            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
            if (nav != null) nav.refreshBackdropSoon();
        } catch (Throwable ignored) {
        }
    }

    public static void pauseBackdrop(Activity activity) {
        try {
            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
            if (nav != null) nav.pauseBackdropCapture();
        } catch (Throwable ignored) {
        }
    }

    public static void resumeBackdrop(Activity activity) {
        try {
            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
            if (nav != null && nav.getVisibility() == View.VISIBLE) {
                GlobalRenderSettingsReader.apply(activity, nav, true);
                nav.startRealtimeBackdropRefresh();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void refreshBackdropFrame(Activity activity) {
        try {
            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
            if (nav != null) nav.refreshBackdropFrame();
        } catch (Throwable ignored) {
        }
    }

    public static void markBackdropMoving(Activity activity) {
        try {
            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
            if (nav != null) nav.markBackdropMoving();
        } catch (Throwable ignored) {
        }
    }

    public static void markBackdropIdle(Activity activity) {
        try {
            CoolapkLegacyGlassNavView nav = getInstalledNav(activity);
            if (nav != null) nav.markBackdropIdle();
        } catch (Throwable ignored) {
        }
    }

    private static void applyComposeVisibility(View composeView) {
        try {
            captureOriginalViewState(composeView);
            // Keep CoolAPK's own Compose bottom bar fully rendered while its parent stays
            // transparent and off-screen. CoolapkLegacyGlassNavView mirrors only the native
            // middle post button into the glass slot, so the host-owned icon/press animation
            // remains the source of truth without exposing the other original tabs.
            composeView.setAlpha(1f);
            composeView.setVisibility(View.VISIBLE);
            composeView.setTranslationY(0f);
            composeView.setClickable(true);
            composeView.setFocusable(false);
            composeView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        } catch (Throwable ignored) {
        }
    }

    private static void suppressOriginalContainer(FrameLayout container) {
        if (container == null) return;
        try {
            captureOriginalViewState(container);
            float density = container.getResources().getDisplayMetrics().density;
            float rootHeight = container.getRootView() == null ? 0f : container.getRootView().getHeight();
            float offscreen = Math.max(rootHeight + container.getHeight() + 256f * density, 2048f * density);
            container.setAlpha(0f);
            container.setVisibility(View.VISIBLE);
            container.setClickable(false);
            container.setLongClickable(false);
            container.setFocusable(false);
            container.setFocusableInTouchMode(false);
            container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            container.setTranslationY(offscreen);
            container.setElevation(0f);
            container.setTranslationZ(0f);
            container.setStateListAnimator(null);
            container.setClipChildren(false);
            container.setClipToPadding(false);
        } catch (Throwable ignored) {
        }
    }


    /**
     * Keep the injected bar above classic two/three-button navigation while preserving
     * the current position on gesture navigation. This mirrors the behavior users expect
     * from edge-to-edge apps such as KernelSU: the system navigation mode is detected at
     * runtime and updates immediately when the mode, rotation, or taskbar changes.
     */
    private static void configureSystemNavigationInsets(final Activity activity, final CoolapkLegacyGlassNavView nav) {
        try {
            if (activity == null || nav == null) return;
            nav.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    applySystemNavigationInset(activity, nav, insets);
                    return insets;
                }
            });

            WindowInsets current = nav.getRootWindowInsets();
            if (current != null) applySystemNavigationInset(activity, nav, current);
            nav.requestApplyInsets();
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass] navigation inset listener error: " + t);
        }
    }

    private static void applySystemNavigationInset(
            Activity activity,
            CoolapkLegacyGlassNavView nav,
            WindowInsets insets
    ) {
        try {
            if (insets == null) return;

            android.graphics.Insets navigation = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars()
            );
            android.graphics.Insets tappable = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.tappableElement()
            );
            android.graphics.Insets mandatoryGestures = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.mandatorySystemGestures()
            );

            int interactionMode = readNavigationInteractionMode(activity);
            // A classic navigation bar or a tablet taskbar has a real tappable area that is
            // larger than the mandatory gesture strip. Let this physical inset override an
            // OEM resource that may still report gestural mode after a live mode switch.
            boolean hasTappableBottomSystemBar = navigation.bottom > 0
                    && tappable.bottom > mandatoryGestures.bottom;
            boolean gestureNavigation;
            if (interactionMode == 0 || interactionMode == 1) {
                gestureNavigation = false;
            } else if (interactionMode == 2) {
                gestureNavigation = !hasTappableBottomSystemBar;
            } else {
                // OEM fallback: button navigation exposes a tappable bottom system area;
                // gesture navigation normally exposes only the mandatory gesture strip.
                gestureNavigation = !hasTappableBottomSystemBar
                        && mandatoryGestures.bottom > 0;
            }

            int requiredBottomMargin = gestureNavigation ? 0 : Math.max(0, navigation.bottom);
            nav.setHyperNavigationBottomInset(Math.max(0, navigation.bottom - requiredBottomMargin));
            android.view.ViewGroup.LayoutParams raw = nav.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (lp.bottomMargin == requiredBottomMargin) return;

            lp.bottomMargin = requiredBottomMargin;
            nav.setLayoutParams(lp);
            nav.requestLayout();
            nav.refreshBackdropSoon();
            XposedBridge.log(
                    "[LiquidGlass] navigation mode="
                            + (gestureNavigation ? "gesture" : "buttons")
                            + ", bottomInset=" + requiredBottomMargin
            );
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass] navigation inset apply error: " + t);
        }
    }

    /**
     * Android framework navigation interaction mode: 0=three-button, 1=two-button,
     * 2=fully gestural. Some OEMs hide the resource, so callers must keep an inset fallback.
     */
    private static int readNavigationInteractionMode(Activity activity) {
        try {
            if (activity == null) return -1;
            Resources resources = activity.getResources();
            int id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android");
            return id == 0 ? -1 : resources.getInteger(id);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static void applyWindow(Activity activity, GlassSettings settings) {
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            window.setNavigationBarColor(0x00000000);
            window.setNavigationBarContrastEnforced(false);
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            boolean dark = settings != null && settings.themeMode == GlassSettings.THEME_DARK;
            if (settings == null || settings.themeMode == GlassSettings.THEME_SYSTEM) {
                int night = activity.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                dark = night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            }
            if (dark) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
        }
    }

    private static void prepareContainer(FrameLayout container) {
        try {
            captureOriginalViewState(container);
            container.setClipChildren(false);
            container.setClipToPadding(false);
            container.setBackgroundColor(0x00000000);
            container.setElevation(32f);
        } catch (Throwable ignored) {
        }
    }

    private static void removeAndRestore(Activity activity, FrameLayout container) {
        try {
            if (activity != null && activity.getWindow() != null) {
                View decorView = activity.getWindow().getDecorView();
                if (decorView instanceof FrameLayout) {
                    View existed = ((FrameLayout) decorView).findViewWithTag(TAG_GLASS_NAV);
                    if (existed instanceof CoolapkLegacyGlassNavView) {
                        try { ((CoolapkLegacyGlassNavView) existed).dispose(); } catch (Throwable ignored) {}
                    }
                    if (existed != null) ((FrameLayout) decorView).removeView(existed);
                }
                restoreWindowState(activity, decorView);
                restoreTaggedSubtree(container);
                restoreOriginalViewState(decorView);
            } else {
                restoreTaggedSubtree(container);
            }
            cachedNavRef = null;
        } catch (Throwable ignored) {
        }
    }

    private static void hideOriginalBottomChildren(FrameLayout container, View composeView) {
        try {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                captureOriginalViewState(child);
                // The original ComposeView must keep rendering at alpha=1 so the injected
                // glass can draw CoolAPK's real center post button. The complete parent
                // remains alpha=0 and translated off-screen, therefore this does not reveal
                // CoolAPK's original four navigation items on screen.
                child.setAlpha(child == composeView ? 1f : 0f);
                child.setVisibility(View.VISIBLE);
                child.setTranslationY(0f);
                // Direct dispatchOriginalComposeClick() still targets the Compose controller.
                // Real touches cannot reach it because the complete parent is off-screen.
                child.setFocusable(false);
                child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Save host-owned state before the first mutation. Keyed tags keep the snapshot scoped to the
     * actual host View lifetime and avoid another process-wide Activity/View registry.
     */
    private static void captureOriginalViewState(View view) {
        if (view == null) return;
        try {
            if (!(view.getTag(ORIGINAL_VIEW_STATE_TAG_KEY) instanceof OriginalViewState)) {
                view.setTag(ORIGINAL_VIEW_STATE_TAG_KEY, new OriginalViewState(view));
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreOriginalViewState(View view) {
        if (view == null) return;
        try {
            Object tagged = view.getTag(ORIGINAL_VIEW_STATE_TAG_KEY);
            if (!(tagged instanceof OriginalViewState)) return;
            view.setTag(ORIGINAL_VIEW_STATE_TAG_KEY, null);
            ((OriginalViewState) tagged).restore(view);
        } catch (Throwable ignored) {}
    }

    private static void restoreTaggedSubtree(View root) {
        if (root == null) return;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 512) {
            View view = queue.removeFirst();
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
            restoreOriginalViewState(view);
        }
    }

    private static void captureWindowState(Activity activity) {
        try {
            if (activity == null || activity.getWindow() == null) return;
            View decor = activity.getWindow().getDecorView();
            if (decor == null || decor.getTag(ORIGINAL_WINDOW_STATE_TAG_KEY) instanceof OriginalWindowState) return;
            decor.setTag(ORIGINAL_WINDOW_STATE_TAG_KEY, new OriginalWindowState(activity.getWindow()));
        } catch (Throwable ignored) {}
    }

    private static void restoreWindowState(Activity activity, View decor) {
        try {
            if (activity == null || activity.getWindow() == null || decor == null) return;
            Object tagged = decor.getTag(ORIGINAL_WINDOW_STATE_TAG_KEY);
            if (!(tagged instanceof OriginalWindowState)) return;
            decor.setTag(ORIGINAL_WINDOW_STATE_TAG_KEY, null);
            ((OriginalWindowState) tagged).restore(activity.getWindow());
        } catch (Throwable ignored) {}
    }

    private static final class OriginalWindowState {
        final int navigationBarColor;
        final boolean navigationBarContrastEnforced;

        OriginalWindowState(Window window) {
            navigationBarColor = window.getNavigationBarColor();
            navigationBarContrastEnforced = window.isNavigationBarContrastEnforced();
        }

        void restore(Window window) {
            window.setNavigationBarColor(navigationBarColor);
            window.setNavigationBarContrastEnforced(navigationBarContrastEnforced);
        }
    }

    private static final class OriginalViewState {
        final Drawable background;
        final Drawable foreground;
        final int visibility;
        final int systemUiVisibility;
        final int importantForAccessibility;
        final float alpha;
        final float translationY;
        final float elevation;
        final float translationZ;
        final boolean clickable;
        final boolean longClickable;
        final boolean focusable;
        final boolean focusableInTouchMode;
        final boolean clipChildren;
        final boolean clipToPadding;
        final StateListAnimator stateListAnimator;

        OriginalViewState(View view) {
            background = view.getBackground();
            foreground = view.getForeground();
            visibility = view.getVisibility();
            systemUiVisibility = view.getSystemUiVisibility();
            importantForAccessibility = view.getImportantForAccessibility();
            alpha = view.getAlpha();
            translationY = view.getTranslationY();
            elevation = view.getElevation();
            translationZ = view.getTranslationZ();
            clickable = view.isClickable();
            longClickable = view.isLongClickable();
            focusable = view.isFocusable();
            focusableInTouchMode = view.isFocusableInTouchMode();
            stateListAnimator = view.getStateListAnimator();
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                clipChildren = group.getClipChildren();
                clipToPadding = group.getClipToPadding();
            } else {
                clipChildren = true;
                clipToPadding = true;
            }
        }

        void restore(View view) {
            view.setBackground(background);
            view.setForeground(foreground);
            view.setVisibility(visibility);
            view.setSystemUiVisibility(systemUiVisibility);
            view.setImportantForAccessibility(importantForAccessibility);
            view.setAlpha(alpha);
            view.setTranslationY(translationY);
            view.setElevation(elevation);
            view.setTranslationZ(translationZ);
            view.setClickable(clickable);
            view.setLongClickable(longClickable);
            view.setFocusable(focusable);
            view.setFocusableInTouchMode(focusableInTouchMode);
            view.setStateListAnimator(stateListAnimator);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                group.setClipChildren(clipChildren);
                group.setClipToPadding(clipToPadding);
            }
            view.requestLayout();
        }
    }

    private static int dp(Activity activity, float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
