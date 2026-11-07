package rongyi.lingbai;

import android.animation.StateListAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Installs the shared glass UI and suppresses only APK-verified native navigation views.
 *
 * The installer deliberately has no bottom-screen scan, no text matching and no parent-size guess. The
 * exact adapter supplies three independent target lists: the real navigation row, exact visual
 * plates, and exact content views whose hard-coded bottom margin must be removed.
 */
public final class UniversalGlassInstaller {
    private static final String TAG_PREFIX = "rongyi.lingbai.UNIVERSAL_GLASS_2_23_";
    private static final String MOONDROP_OVERLAY_TAG = "rongyi.lingbai.MOONDROP_OVERLAY_2_23";
    private static final String GALLERY_OVERLAY_TAG = "rongyi.lingbai.GALLERY_WINDOW_3_00";
    private static final String NETEASE_OVERLAY_TAG = "rongyi.lingbai.NETEASE_WINDOW_3_76";
    private static final String DOUYIN_OVERLAY_TAG = "rongyi.lingbai.DOUYIN_WINDOW_26_45";
    private static final float RENDER_GUTTER_HORIZONTAL_DP = 34f;
    private static final float RENDER_HEIGHT_DP = 124f;
    private static final long DEFAULT_POLL_INTERVAL_MS = 1000L;
    private static final long FORCED_SETTINGS_FALLBACK_MS = 30_000L;
    private static final long SUPPRESSION_HEARTBEAT_MS = 12_000L;
    private static final int STATE_TAG_KEY = 0x4C475301; // "LGS" + universal state
    // Meituan 8.81.2 inflates these bottom Mach ViewStubs on demand. The replacement roots are
    // not guaranteed to retain the Stub id, so their original parent/slot is the stable handle.
    private static final String[] MEITUAN_LATE_LAYER_STUB_NAMES = new String[]{
            "promotion_tab_layer",
            "deep_eat_ai_container",
            "feedback_container",
            "feedback_toast_container",
            "wm_page_home_mask_layer",
            "wm_page_home_remind_layer",
            "layout_float_footer"
    };
    private static final String[] FILE_MANAGER_ACTION_LABELS =
            new String[]{"发送", "移动", "删除", "更多"};
    private static final String[] FILE_MANAGER_PRIVATE_ACTION_LABELS =
            new String[]{"解密", "移动", "删除", "更多"};
    private static final String[][] FILE_MANAGER_ACTION_KEYWORDS = new String[][]{
            {"发送", "分享", "傳送", "send", "share", "解密", "decrypt"},
            {"移动", "移動", "move"},
            {"删除", "刪除", "delete", "remove"},
            {"更多", "more", "overflow"}
    };
    private static final String[][] FILE_MANAGER_ACTION_ID_NAMES = new String[][]{
            {"action_send", "action_decrypt", "operation_send", "ic_menu_send"},
            {"action_move", "operation_move", "ic_menu_move"},
            {"action_delete", "operation_delete", "ic_menu_delete"},
            {"action_more", "operation_menu", "more", "overflow", "more_button"}
    };
    // Exact menu ids from the supplied File Explorer 8.2.3.2 APK. The previous builds
    // guessed resource entry names, but this APK's actual contextual menu uses these ids.
    private static final int[] FILE_MANAGER_EXACT_ACTION_IDS = new int[]{
            0x7f0a007b, // send
            0x7f0a006f, // move
            0x7f0a005e, // delete
            0           // more is the navigator overflow item, not a stable ActionMode id
    };
    private static final int[] FILE_MANAGER_PRIVATE_ACTION_IDS = new int[]{
            0x7f0a005d, // decrypt
            0x7f0a006f, // move
            0x7f0a005e, // delete
            0           // repair is in the native overflow presenter
    };

    // Never let a static map own an Activity through its value. The previous WeakHashMap was not
    // actually weak because State -> nav/container -> Context pointed back to the Activity key.
    // The strong state now lives on DecorView, whose lifetime is already the Activity lifetime;
    // this map is only a weak lookup index.
    private static final Map<Activity, WeakReference<State>> STATES =
            Collections.synchronizedMap(new WeakHashMap<Activity, WeakReference<State>>());
    private static final Object LEGACY_NAV_HIDE_REASON = new Object();
    private static final Map<Activity, Set<Object>> NAV_HIDE_REASONS =
            Collections.synchronizedMap(new WeakHashMap<Activity, Set<Object>>());
    private static final Map<Activity, Long> LAST_OVERLAY_REINSTALL =
            Collections.synchronizedMap(new WeakHashMap<Activity, Long>());
    private static final Map<Activity, Integer> DOUYIN_OVERLAY_FAILURES =
            Collections.synchronizedMap(new WeakHashMap<Activity, Integer>());
    // 26.68: WindowManager.addView() can synchronously trigger host layout/Xposed callbacks before
    // putState() commits the new State.  A re-entrant install in that gap used to create a second
    // Douyin TYPE_APPLICATION_PANEL.  Keep a per-Activity install lock and a direct weak registry
    // of the one allowed panel so the window singleton does not depend only on State timing.
    private static final Map<Activity, Boolean> DOUYIN_INSTALL_IN_PROGRESS =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());
    private static final Map<Activity, WeakReference<FrameLayout>> DOUYIN_OVERLAYS =
            Collections.synchronizedMap(new WeakHashMap<Activity, WeakReference<FrameLayout>>());
    private static final Map<Activity, TargetAppSettings> SETTINGS_CACHE =
            Collections.synchronizedMap(new WeakHashMap<Activity, TargetAppSettings>());
    private static final Map<Activity, Boolean> TIM_CONVERSATION_MODE =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());
    private static final Map<Activity, Boolean> NETEASE_DRAWER_OPEN =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());
    // Compass can dispatch H2()/BottomNavigator selection before LiquidGlass finishes installing.
    // Keep that committed native destination so the very first glass frame uses the real page
    // instead of guessing from a navigator whose selected state has not settled yet.
    private static final Map<Activity, Integer> COMPASS_NATIVE_SELECTION =
            Collections.synchronizedMap(new WeakHashMap<Activity, Integer>());
    // 26.60: host visual preview ownership can switch before the injected nav finishes mounting.
    // Cache the exact camera_mask/e1 state so install() never guesses from a still-live Surface.
    private static final Map<Activity, Boolean> COMPASS_CAMERA_PREVIEW_VISIBLE =
            Collections.synchronizedMap(new WeakHashMap<Activity, Boolean>());
    private static final Map<Activity, FileManagerSelectionSession>
            FILE_MANAGER_SELECTIONS = Collections.synchronizedMap(
                    new WeakHashMap<Activity, FileManagerSelectionSession>());
    private static final Map<View, WeakReference<State>> SUPPRESSED_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<View, WeakReference<State>>());
    private static final Map<View, View.OnLayoutChangeListener> SUPPRESSION_LAYOUT_GUARDS =
            Collections.synchronizedMap(new WeakHashMap<View, View.OnLayoutChangeListener>());
    private static final Map<View, ViewTreeObserver.OnPreDrawListener> GALLERY_FRAME_GUARDS =
            Collections.synchronizedMap(
                    new WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>());
    private static final Map<View, GalleryDetachedNavigation> GALLERY_DETACHED_NAVIGATION =
            Collections.synchronizedMap(new WeakHashMap<View, GalleryDetachedNavigation>());
    private static final Map<Activity, Long> XHS_READY_SINCE =
            Collections.synchronizedMap(new WeakHashMap<Activity, Long>());
    private static final Map<Activity, WeakReference<View>> XHS_READY_ROOT =
            Collections.synchronizedMap(new WeakHashMap<Activity, WeakReference<View>>());
    private static final ThreadLocal<Boolean> INTERNAL_VISIBILITY_CHANGE = new ThreadLocal<>();

    private UniversalGlassInstaller() {}

    private static State getState(Activity activity) {
        if (activity == null) return null;
        WeakReference<State> reference = STATES.get(activity);
        State state = reference == null ? null : reference.get();
        if (state != null) return state;
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            Object tagged = decor == null ? null : decor.getTag(STATE_TAG_KEY);
            if (tagged instanceof State) {
                state = (State) tagged;
                STATES.put(activity, new WeakReference<>(state));
                return state;
            }
        } catch (Throwable ignored) {}
        if (reference != null) STATES.remove(activity);
        return null;
    }

    private static void putState(Activity activity, State state) {
        if (activity == null || state == null) return;
        STATES.put(activity, new WeakReference<>(state));
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor != null) decor.setTag(STATE_TAG_KEY, state);
        } catch (Throwable ignored) {}
    }

    private static State takeState(Activity activity) {
        if (activity == null) return null;
        WeakReference<State> reference = STATES.remove(activity);
        State state = reference == null ? null : reference.get();
        try {
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            Object tagged = decor == null ? null : decor.getTag(STATE_TAG_KEY);
            if (state == null && tagged instanceof State) state = (State) tagged;
            if (decor != null && tagged == state) decor.setTag(STATE_TAG_KEY, null);
        } catch (Throwable ignored) {}
        return state;
    }

    private static State getSuppressedState(View view) {
        if (view == null) return null;
        WeakReference<State> reference = SUPPRESSED_VIEWS.get(view);
        State state = reference == null ? null : reference.get();
        if (state == null && reference != null) SUPPRESSED_VIEWS.remove(view);
        return state;
    }

    private static void markSuppressed(View view, State state) {
        if (view != null && state != null) {
            SUPPRESSED_VIEWS.put(view, new WeakReference<>(state));
        }
    }

    private static boolean usesWindowPixelCopyCapture(TargetApp app) {
        // These hosts mount LiquidGlass in their own application-panel window. Gallery and
        // NetEase use Window PixelCopy. Douyin shares the same panel/manager contract,
        // but 26.64 first attempts hidden ScreenCapture.captureDisplay at 0.20x and falls back to
        // Window PixelCopy if the ROM denies the privileged capture path.
        return LiquidTabPortAdapter.supports(app) || app == TargetApp.MI_GALLERY
                || app == TargetApp.NETEASE
                || app == TargetApp.DOUYIN;
    }

    private static boolean usesWindowPixelCopyCapture(State state) {
        return state != null && usesWindowPixelCopyCapture(state.app)
                && state.overlayWindowManager != null;
    }

    private static boolean usesApplicationPanelOverlay(TargetApp app) {
        return LiquidTabPortAdapter.supports(app) || app == TargetApp.MI_GALLERY
                || app == TargetApp.NETEASE
                || app == TargetApp.DOUYIN;
    }

    private static boolean usesCoolapkLegacyCapture(TargetApp app) {
        // Compass uses the stable CoolAPK frame-synchronous Bitmap pipeline in exact physical-
        // coordinate mode. Ordinary hierarchy pixels are drawn into the Bitmap; only the camera's
        // separate SurfaceView layer is copied into that same Bitmap afterwards.
        return app == TargetApp.MI_COMPASS
                || app == TargetApp.BILIBILI
                || app == TargetApp.BILIBILI_HD
                || app == TargetApp.QQMUSIC;
    }

    private static void applyNavigationIcons(
            MiuixGlassNavView nav,
            UniversalHostAdapter adapter
    ) {
        if (nav == null || adapter == null) return;
        Bitmap[] bitmaps = adapter.getNavigationIconBitmaps();
        if (bitmaps != null && bitmaps.length > 0) {
            nav.setNavigationIconBitmaps(bitmaps);
        } else {
            nav.setNavigationIconResourceIds(adapter.getNavigationIconResourceIds());
        }
    }

    public static void install(Activity activity, TargetApp app) {
        if (!isUsable(activity) || app == null
                || !matchesActivityInstance(activity, app)) return;
        boolean douyinInstallGuard = false;
        if (app == TargetApp.DOUYIN) {
            synchronized (DOUYIN_INSTALL_IN_PROGRESS) {
                if (Boolean.TRUE.equals(DOUYIN_INSTALL_IN_PROGRESS.get(activity))) {
                    XposedBridge.log("[LiquidGlass][26.68] Douyin re-entrant install ignored");
                    return;
                }
                DOUYIN_INSTALL_IN_PROGRESS.put(activity, Boolean.TRUE);
                douyinInstallGuard = true;
            }
        }
        FrameLayout uncommittedOverlayWindow = null;
        try {
            TargetAppSettings appSettings = SETTINGS_CACHE.get(activity);
            if (appSettings == null) {
                TargetAppSettings latest = TargetAppHookSettingsReader.load(activity, app);
                if (latest != null && (appSettings == null
                        || latest.revision >= appSettings.revision)) {
                    appSettings = latest;
                    SETTINGS_CACHE.put(activity, latest);
                }
            }
            if (appSettings == null) appSettings = TargetAppSettings.defaults(app);
            if (!appSettings.enabled) {
                remove(activity);
                return;
            }

            State existing = getState(activity);
            // If State is genuinely gone, an attached tracked Douyin panel is orphaned.  Remove it
            // before creating the replacement so one Activity can never retain two of our bars.
            if (app == TargetApp.DOUYIN && existing == null) {
                removeTrackedDouyinOverlay(activity);
            }
            UniversalHostAdapter adapter = UniversalHostAdapter.create(activity, app, appSettings);
            if (adapter == null) return;
            boolean timConversationActive = app == TargetApp.TIM
                    && (Boolean.TRUE.equals(TIM_CONVERSATION_MODE.get(activity))
                    || !adapter.shouldShowInjectedNavigation());
            // TIM opens AIO inside the same SplashActivity. If the module receives a delayed
            // Activity/fragment retry while chat is already active, do not create a new glass row
            // over the conversation. An existing home state is still processed below so its page
            // overscan can be restored immediately.
            if (timConversationActive && existing == null) return;
            View root = adapter.getBottomBarRoot();
            if (root == null && LiquidTabNavigationRules.supports(app)) {
                // A changed tab model must restore the native row, not leave an old suppression
                // snapshot active after the imported bridge refuses to route its new slots.
                if (existing != null) remove(activity);
                return;
            }

            if (app == TargetApp.XHS && existing == null && !isXhsReady(activity, root)) return;
            boolean fileManagerActionOnly = app == TargetApp.MI_FILE_MANAGER
                    && FILE_MANAGER_SELECTIONS.get(activity) != null;
            if (root == null && app != TargetApp.MOONDROP && app != TargetApp.MI_GALLERY
                    && app != TargetApp.DOUYIN && !fileManagerActionOnly) {
                // A remote-configured host may temporarily detach its row. Keep an already mounted
                // glass instance alive, but do not create a new one until the exact row exists.
                // PrivateFolderActivity intentionally has no ordinary root navigator; its exact
                // ActionMode session above is enough to mount an action-only glass row.
                if (existing != null) syncVisibilityAndSelection(existing);
                return;
            }
            if (root == null && app == TargetApp.DOUYIN) {
                // Douyin keeps the injected bar even when the native container class is missing on
                // this build; clicks fall back to text-based dispatch. Suppression becomes a no-op
                // until the container is found by a later heartbeat.
                XposedBridge.log("[LiquidGlass] Douyin bottom container not found; installing bar anyway");
            }

            FrameLayout container = existing != null && existing.container != null
                    && existing.container.isAttachedToWindow()
                    ? existing.container : resolveOverlayContainer(activity, app);
            if (container == null) return;
            if (usesApplicationPanelOverlay(app)
                    && (existing == null || existing.container != container)
                    && container.getLayoutParams() instanceof WindowManager.LayoutParams) {
                uncommittedOverlayWindow = container;
            }

            if (existing != null && existing.nav != null && existing.nav.isAttachedToWindow()) {
                boolean sameRoot = existing.primaryRoot == root;
                boolean sameSettings = sameTargetSettings(existing.appSettings, appSettings);
                existing.adapter = adapter;
                existing.appSettings = appSettings;
                existing.lastAdapterProbeAtMs = SystemClock.uptimeMillis();
                if (app == TargetApp.TIM) existing.timConversationMode = timConversationActive;
                if (app == TargetApp.MI_FILE_MANAGER) {
                    FileManagerSelectionSession session = FILE_MANAGER_SELECTIONS.get(activity);
                    existing.fileManagerSelectionMode = session != null
                            || adapter.isFileManagerSelectionModeVisible();
                    existing.fileManagerSelectionSession = session;
                }
                if (app == TargetApp.XHS && sameRoot && sameSettings) {
                    // XHS emits several lifecycle/TabBar attachment signals during startup. The
                    // old path rebuilt Compose state and requested native layout for every retry,
                    // causing jank independently of backdrop capture.
                    existing.nav.setHostNavigationAdapter(adapter);
                    existing.appliedGlobalRenderRevision =
                            GlobalRenderSettingsReader.apply(activity, existing.nav, false);
                    syncVisibilityAndSelection(existing);
                    ensureNavOnTop(existing);
                    return;
                }
                updatePrimaryRoot(existing, root);
                applyState(activity, existing);
                return;
            }

            State state = new State(activity, app, adapter, appSettings);
            if (app == TargetApp.TIM) state.timConversationMode = timConversationActive;
            if (app == TargetApp.NETEASE) {
                state.neteaseDrawerOpen = Boolean.TRUE.equals(NETEASE_DRAWER_OPEN.get(activity));
            }
            if (app == TargetApp.MI_FILE_MANAGER) {
                FileManagerSelectionSession session = FILE_MANAGER_SELECTIONS.get(activity);
                state.fileManagerSelectionMode = session != null
                        || adapter.isFileManagerSelectionModeVisible();
                state.fileManagerSelectionSession = session;
            }
            updatePrimaryRoot(state, root);

            MiuixGlassNavView nav = new MiuixGlassNavView(activity);
            nav.setTag(TAG_PREFIX + app.key);
            nav.setClickable(true);
            nav.setFocusable(false);
            nav.setUseCoolapkLegacyBackdropCapture(usesCoolapkLegacyCapture(app));
            nav.setUseWindowPixelCopyBackdropCapture(
                    usesWindowPixelCopyCapture(app)
                            && container.getLayoutParams() instanceof WindowManager.LayoutParams
            );
            nav.setCoolapkLegacyCaptureHeightDp(84f);
            nav.setHostNavigationAdapter(adapter);
            nav.setNativeCenterActionView(adapter.getNativeCenterActionView());
            nav.setBackdropExcludedViews(adapter.getBackdropExcludedViews());
            nav.setCoolapkLegacyPreservePhysicalCoordinates(
                    adapter.preservePhysicalBackdropCoordinates()
            );
            nav.setIconMode(LiquidTabPortAdapter.supports(app) ? 4 : LiquidTabNavigationRules.supports(app) ? 3 : 2);
            // Apply the host-specific appearance before publishing a host-specific label count.
            // GlassSettings.defaults() has showPostButton=true; without this ordering a 3-tab host
            // such as TIM can compose one transient 3+post frame during reattach/small-window
            // resume, which used to index a nonexistent fourth navigation item.
            boolean initialShowPost = app.hasPostAction && appSettings.showPost;
            nav.applySettings(resolveAppearance(app, appSettings, initialShowPost));
            applyNavigationIcons(nav, adapter);
            nav.setLabels(adapter.getVisibleLabels());
            nav.setBackdropSourceView(adapter.getBackdropSourceView());
            nav.setExactBackdropSourceEnabled(adapter.useExactBackdropSource());
            state.appliedGlobalRenderRevision =
                    GlobalRenderSettingsReader.apply(activity, nav, false);
            if (app == TargetApp.MI_COMPASS) {
                // Compass uses the bitmap liquid-lens renderer, but 26.57 no longer requires
                // real screen pixels on ordinary Compass/Level pages. Native blur stays disabled
                // so the synthetic/real Bitmap backdrop can drive the pressed refraction shader.
                nav.setNativeBlurEnabled(false);
                nav.setBackdropCaptureEnabled(true);
                // 26.57: ordinary Compass/Level pages do not read host pixels, but they still
                // render the full liquid lens against an internal near-black texture. A real
                // camera Surface automatically replaces that texture with captured pixels.
                nav.setSyntheticLiquidBackdropEnabled(true);
                nav.setCoolapkLegacyRequireVisibleSurfaceForCapture(true);
                // Default is black. Only a previously observed camera_mask_out/e1=true event may
                // expose the real camera backdrop; CameraView2 process existence is ignored.
                nav.setCompassCameraPreviewVisible(Boolean.TRUE.equals(
                        COMPASS_CAMERA_PREVIEW_VISIBLE.get(activity)
                ));
            }
            if (app == TargetApp.DOUYIN) {
                boolean pressOnlyTransparent = !appSettings.forceBlackBackdrop
                        && appSettings.pressOnlyTransparentBackdrop;
                if (pressOnlyTransparent) nav.setNativeBlurEnabled(false);
                nav.setForceSyntheticOnlyBackdrop(appSettings.forceBlackBackdrop);
                nav.setPressOnlyTransparentBackdrop(pressOnlyTransparent);
            }
            nav.setElevation(dp(activity, 48f));
            nav.setTranslationZ(dp(activity, 48f));
            int selected;
            if (app == TargetApp.MI_COMPASS) {
                Integer committedCompassSelection = COMPASS_NATIVE_SELECTION.get(activity);
                // CompassActivity always enters on the Compass destination. Its BottomNavigator
                // selected getter is not reliable during the first layout, so never let that stale
                // value misclassify the startup page. A real H2/setSelectedItem callback, if one
                // already happened, wins through the cached committed destination.
                selected = committedCompassSelection != null
                        && committedCompassSelection >= 0 && committedCompassSelection <= 1
                        ? committedCompassSelection : 0;
            } else {
                selected = adapter.detectSelectedIndex();
            }
            if (selected < 0) selected = adapter.getDefaultSelectedIndex();
            if (selected >= 0) nav.setSelectedIndexFromHost(selected);

            // Douyin's independent circular "+" floats beside the pill. Inside the
            // application-panel window the view fills that panel; on the decor path it spans the
            // full screen width instead of the compact 340dp navigation row.
            FrameLayout.LayoutParams lp;
            if (app == TargetApp.DOUYIN && usesApplicationPanelOverlay(app)) {
                lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, RENDER_HEIGHT_DP)
                );
            } else {
                float navWidthDp = app == TargetApp.DOUYIN
                        ? activity.getResources().getDisplayMetrics().widthPixels
                                / activity.getResources().getDisplayMetrics().density
                        : 340f;
                lp = new FrameLayout.LayoutParams(
                        dp(activity, navWidthDp), dp(activity, RENDER_HEIGHT_DP)
                );
            }
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            container.addView(nav, lp);

            state.nav = nav;
            state.container = container;
            nav.setOnNavigationStyleChanged(() -> {
                if (state.nav != nav || state.adapter == null || state.appSettings == null) return;
                boolean post = state.app.hasPostAction && state.appSettings.showPost;
                boolean center = !post && state.adapter.centerActionTabIndex() >= 0
                        && state.adapter.getVisibleLabels().length == 5;
                updateNavBounds(activity, state, resolveAppearance(state.app, state.appSettings, post), post, center);
            });
            if (usesApplicationPanelOverlay(app)) {
                ViewGroup.LayoutParams rawOverlayParams = container.getLayoutParams();
                if (rawOverlayParams instanceof WindowManager.LayoutParams) {
                    state.overlayWindowManager =
                            (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
                    state.overlayWindowParams = (WindowManager.LayoutParams) rawOverlayParams;
                }
            }
            putState(activity, state);
            uncommittedOverlayWindow = null;

            if (!usesApplicationPanelOverlay(app)) applyInsets(nav);
            applyState(activity, state);
            if (app == TargetApp.MI_COMPASS) {
                final int initialCompassSelection = selected >= 0 ? selected : 0;
                nav.postOnAnimation(() -> nav.forceHostPageBackdropResync(initialCompassSelection));
            }
            scheduleInitialBackdropFrames(state);
            startPoll(state);
            XposedBridge.log("[LiquidGlass][3.06] installed " + app.displayName
                    + (usesApplicationPanelOverlay(app) ? " in application-panel window" : ""));
        } catch (Throwable t) {
            if (uncommittedOverlayWindow != null) {
                try {
                    WindowManager manager =
                            (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
                    if (manager != null) manager.removeViewImmediate(uncommittedOverlayWindow);
                } catch (Throwable ignored) {}
            }
            XposedBridge.log("[LiquidGlass][3.06] install error " + app.key + ": " + t);
        } finally {
            if (douyinInstallGuard) {
                synchronized (DOUYIN_INSTALL_IN_PROGRESS) {
                    DOUYIN_INSTALL_IN_PROGRESS.remove(activity);
                }
            }
        }
    }


    /**
     * CoolAPK-legacy and window-PixelCopy hosts build their page body after the native row attaches.
     * Capture a few one-shot settled frames after mounting; this is not a repeating watchdog.
     */
    private static void scheduleInitialBackdropFrames(State state) {
        if (state == null || state.nav == null) return;
        if (!usesCoolapkLegacyCapture(state.app) && !usesWindowPixelCopyCapture(state.app)) return;
        state.nav.post(() -> state.nav.refreshBackdropSoon());
        state.nav.postDelayed(() -> state.nav.refreshBackdropSoon(), 96L);
        state.nav.postDelayed(() -> state.nav.refreshBackdropSoon(), 240L);
        state.nav.postDelayed(() -> state.nav.refreshBackdropSoon(), 520L);
    }

    private static boolean isUsable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.getWindow() != null;
    }

    private static boolean matchesActivityInstance(Activity activity, TargetApp app) {
        if (LiquidTabPortAdapter.supports(app)) return activity != null && LiquidTabPortAdapter.matchesActivity(activity, app);
        if (activity == null || app == null) return false;
        if (app.matchesActivity(activity.getClass().getName())) return true;
        if (app == TargetApp.DOUYIN) {
            // Some Douyin builds wrap MainActivity in a package-local subclass. Fall back to any
            // douyin-package activity whose name contains "MainActivity".
            String name = activity.getClass().getName();
            if (name.startsWith("com.ss.android.ugc.aweme.") && name.contains("MainActivity")) {
                return true;
            }
        }
        if (app != TargetApp.MI_GALLERY) return false;
        for (Class<?> type = activity.getClass().getSuperclass();
             type != null;
             type = type.getSuperclass()) {
            if (app.matchesActivity(type.getName())) return true;
        }
        return false;
    }

    /**
     * XHS must keep its real TabBarView alive because its Rx click subscriptions and presenter are
     * attached to that exact View instance.  Do not create LiquidGlass before the active bar is
     * present.  Wait one second after the same measured bottom row appears, then translate that
     * row below the physical screen and map LiquidGlass taps back to its original slot Views.
     */
    private static boolean isXhsReady(Activity activity, View root) {
        if (!isUsable(activity) || root == null || !root.isAttachedToWindow()) {
            XHS_READY_SINCE.remove(activity);
            XHS_READY_ROOT.remove(activity);
            return false;
        }
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null || !decor.isAttachedToWindow() || !decor.hasWindowFocus()
                    || root.getVisibility() != View.VISIBLE || !root.isShown()
                    || root.getWidth() <= 0 || root.getHeight() <= 0) {
                XHS_READY_SINCE.remove(activity);
                XHS_READY_ROOT.remove(activity);
                return false;
            }
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            if (decor.getHeight() > 0 && location[1] < decor.getHeight() * 0.55f) {
                XHS_READY_SINCE.remove(activity);
                XHS_READY_ROOT.remove(activity);
                return false;
            }
            long now = SystemClock.uptimeMillis();
            WeakReference<View> readyRef = XHS_READY_ROOT.get(activity);
            View readyRoot = readyRef == null ? null : readyRef.get();
            Long since = XHS_READY_SINCE.get(activity);
            if (readyRoot != root || since == null) {
                XHS_READY_ROOT.put(activity, new WeakReference<>(root));
                XHS_READY_SINCE.put(activity, now);
                decor.postDelayed(() -> install(activity, TargetApp.XHS), 1020L);
                return false;
            }
            return now - since >= 1000L;
        } catch (Throwable ignored) {
            XHS_READY_SINCE.remove(activity);
            XHS_READY_ROOT.remove(activity);
            return false;
        }
    }

    private static FrameLayout resolveOverlayContainer(Activity activity, TargetApp app) {
        if (app == TargetApp.NETEASE) return createNeteaseOverlayWindow(activity);
        if (app == TargetApp.DOUYIN) return createDouyinOverlayWindow(activity);
        if (app == TargetApp.MI_GALLERY) {
            FrameLayout panel = createGalleryOverlayWindow(activity);
            return panel != null ? panel : createGalleryDecorFallback(activity);
        }
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) return null;
        FrameLayout frame = (FrameLayout) decor;
        try { frame.setClipChildren(false); } catch (Throwable ignored) {}
        try { frame.setClipToPadding(false); } catch (Throwable ignored) {}

        if (app != TargetApp.MOONDROP) return frame;

        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (MOONDROP_OVERLAY_TAG.equals(child.getTag()) && child instanceof FrameLayout) {
                child.bringToFront();
                return (FrameLayout) child;
            }
        }
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(MOONDROP_OVERLAY_TAG);
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        overlay.setElevation(dp(activity, 64f));
        overlay.setTranslationZ(dp(activity, 64f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        );
        lp.gravity = Gravity.FILL;
        frame.addView(overlay, lp);
        overlay.bringToFront();
        return overlay;
    }

    /**
     * Douyin uses a small TYPE_APPLICATION_PANEL window
     * mounted over the activity, with PixelCopy sampling the real composited window (including
     * video surfaces) for the liquid-glass backdrop. Touches outside the panel fall through to
     * Douyin because the panel is NOT_TOUCH_MODAL and covers only the bar.
     */
    private static FrameLayout createDouyinOverlayWindow(Activity activity) {
        FrameLayout overlay = null;
        WindowManager windowManager = null;
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null || !decor.isAttachedToWindow() || decor.getWindowToken() == null) {
                return null;
            }
            windowManager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return null;

            WeakReference<FrameLayout> trackedRef = DOUYIN_OVERLAYS.get(activity);
            FrameLayout tracked = trackedRef == null ? null : trackedRef.get();
            if (tracked != null && tracked.isAttachedToWindow()) {
                XposedBridge.log("[LiquidGlass][26.68] Reusing unique Douyin panel");
                return tracked;
            }
            if (trackedRef != null) DOUYIN_OVERLAYS.remove(activity);

            overlay = new FrameLayout(activity);
            overlay.setTag(DOUYIN_OVERLAY_TAG);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setClipChildren(false);
            overlay.setClipToPadding(false);
            overlay.setBackgroundColor(android.graphics.Color.TRANSPARENT);

            // Four tabs + independent circular "+": keep the panel close to the pill so the
            // NOT_TOUCH_MODAL fall-through area is as small as possible.
            int width = Math.min(
                    activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 4f),
                    dp(activity, 460f)
            );
            int height = dp(activity, RENDER_HEIGHT_DP);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    width,
                    height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT
            );
            params.token = decor.getWindowToken();
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            // 26.68: literal fixed bottom position.  Do not calibrate from transient insets/layout.
            params.y = 0;
            params.setTitle("LiquidGlass Douyin");
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            windowManager.addView(overlay, params);
            DOUYIN_OVERLAYS.put(activity, new WeakReference<>(overlay));
            XposedBridge.log("[LiquidGlass][26.68] Created unique Douyin panel");
            return overlay;
        } catch (Throwable t) {
            if (overlay != null && windowManager != null) {
                try { windowManager.removeViewImmediate(overlay); } catch (Throwable ignored) {}
            }
            DOUYIN_OVERLAYS.remove(activity);
            XposedBridge.log("[LiquidGlass][26.68] Douyin overlay error: " + t);
            return null;
        }
    }

    private static void removeTrackedDouyinOverlay(Activity activity) {
        if (activity == null) return;
        WeakReference<FrameLayout> ref = DOUYIN_OVERLAYS.remove(activity);
        FrameLayout overlay = ref == null ? null : ref.get();
        if (overlay == null) return;
        try {
            WindowManager manager = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            if (manager != null && overlay.isAttachedToWindow()) {
                manager.removeViewImmediate(overlay);
                XposedBridge.log("[LiquidGlass][26.68] Removed orphan Douyin panel");
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Gallery builds its own floating navigator after Activity content and can insert/reorder that
     * navigator above ordinary DecorView children.  Mount LiquidGlass in an Activity-token-scoped
     * application panel instead.  This is not a system overlay and needs no overlay permission; it
     * lives and dies with HomePageActivity, but remains above every Gallery content child.
     */
    private static FrameLayout createGalleryOverlayWindow(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null || !decor.isAttachedToWindow() || decor.getWindowToken() == null) {
                return null;
            }
            WindowManager windowManager =
                    (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return null;

            FrameLayout overlay = new FrameLayout(activity);
            overlay.setTag(GALLERY_OVERLAY_TAG);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setClipChildren(false);
            overlay.setClipToPadding(false);
            overlay.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            overlay.setElevation(dp(activity, 96f));
            overlay.setTranslationZ(dp(activity, 96f));

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    dp(activity, 340f),
                    dp(activity, RENDER_HEIGHT_DP),
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT
            );
            params.token = decor.getWindowToken();
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            // TYPE_APPLICATION_PANEL is already anchored to the host Activity window bottom.
            // Adding the system navigation-bar inset here raises PixelCopy hosts twice.
            params.y = 0;
            params.setTitle("LiquidGlass Gallery");
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            windowManager.addView(overlay, params);
            return overlay;
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass][3.06] Gallery overlay error: " + t);
            return null;
        }
    }

    /**
     * NetEase 9.5.50 can leave several page roots and mini-player TextureViews attached while a
     * tab transition is settling. Drawing android.R.id.content and then compositing hardware
     * children again therefore produces transparent holes or stretches a stale local frame into
     * the glass. Keep the glass in a separate Activity-token panel and PixelCopy only the original
     * Activity window underneath it. The panel is not part of the sampled window, so it cannot
     * recursively capture itself and it needs no system-overlay permission.
     */
    private static FrameLayout createNeteaseOverlayWindow(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null || !decor.isAttachedToWindow() || decor.getWindowToken() == null) {
                return null;
            }
            WindowManager windowManager =
                    (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return null;

            FrameLayout overlay = new FrameLayout(activity);
            overlay.setTag(NETEASE_OVERLAY_TAG);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setClipChildren(false);
            overlay.setClipToPadding(false);
            overlay.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            overlay.setElevation(dp(activity, 96f));
            overlay.setTranslationZ(dp(activity, 96f));

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    dp(activity, 340f),
                    dp(activity, RENDER_HEIGHT_DP),
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT
            );
            params.token = decor.getWindowToken();
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            // TYPE_APPLICATION_PANEL is already anchored to the host Activity window bottom.
            // Adding the system navigation-bar inset here raises PixelCopy hosts twice.
            params.y = 0;
            params.setTitle("LiquidGlass NetEase");
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            windowManager.addView(overlay, params);
            return overlay;
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass][3.76] NetEase overlay error: " + t);
            return null;
        }
    }

    private static FrameLayout createGalleryDecorFallback(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof FrameLayout)) return null;
            FrameLayout frame = (FrameLayout) decor;
            frame.setClipChildren(false);
            frame.setClipToPadding(false);
            for (int index = 0; index < frame.getChildCount(); index++) {
                View child = frame.getChildAt(index);
                if (GALLERY_OVERLAY_TAG.equals(child.getTag()) && child instanceof FrameLayout) {
                    child.bringToFront();
                    return (FrameLayout) child;
                }
            }
            FrameLayout overlay = new FrameLayout(activity);
            overlay.setTag(GALLERY_OVERLAY_TAG);
            overlay.setClickable(false);
            overlay.setFocusable(false);
            overlay.setClipChildren(false);
            overlay.setClipToPadding(false);
            overlay.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            overlay.setElevation(dp(activity, 96f));
            overlay.setTranslationZ(dp(activity, 96f));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            params.gravity = Gravity.FILL;
            frame.addView(overlay, params);
            overlay.bringToFront();
            XposedBridge.log("[LiquidGlass][3.06] Gallery using DecorView fallback overlay");
            return overlay;
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass][3.06] Gallery DecorView fallback error: " + t);
            return null;
        }
    }

    private static void updatePanelOverlayBounds(State state, int navWidth, int navHeight) {
        if (state == null || state.overlayWindowManager == null || state.container == null) return;
        try {
            WindowManager.LayoutParams params = state.overlayWindowParams;
            if (params == null && state.container.getLayoutParams() instanceof WindowManager.LayoutParams) {
                params = (WindowManager.LayoutParams) state.container.getLayoutParams();
                state.overlayWindowParams = params;
            }
            if (params == null) return;

            // 26.68: Douyin is a literal fixed-bottom panel.  Never derive Y from current
            // getLocationOnScreen()/insets because those values fluctuate during feed/page layout.
            int bottomGap = state.app == TargetApp.DOUYIN
                    ? 0
                    : correctPanelBottomGap(state, 0);
            if (params.width == navWidth && params.height == navHeight && params.y == bottomGap) return;
            params.width = navWidth;
            params.height = navHeight;
            params.y = bottomGap;
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            state.overlayWindowManager.updateViewLayout(state.container, params);
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass][3.76] panel overlay resize error: " + t);
        }
    }


    /**
     * TYPE_APPLICATION_PANEL windows are anchored to the attached Activity window frame. On ROMs
     * where that frame ends above the physical display (gesture/3-button navigation or a host that
     * fits system windows), Gravity.BOTTOM + y=0 leaves the glass visibly raised above the screen
     * bottom. Measure the overlay's real screen position and pull it down to the physical display
     * bottom, preserving any intentional bottom gap.
     */
    private static int correctPanelBottomGap(State state, int intendedGapPx) {
        try {
            if (state == null || state.container == null
                    || state.overlayWindowManager == null
                    || !state.container.isAttachedToWindow()
                    || state.container.getWidth() <= 0
                    || state.container.getHeight() <= 0) {
                return intendedGapPx;
            }
            int[] location = new int[2];
            state.container.getLocationOnScreen(location);
            if (location[1] <= 0) return intendedGapPx;
            int overlayBottom = location[1] + state.container.getHeight();
            android.util.DisplayMetrics real = new android.util.DisplayMetrics();
            state.overlayWindowManager.getDefaultDisplay().getRealMetrics(real);
            int targetBottom = real.heightPixels - intendedGapPx;
            int delta = targetBottom - overlayBottom;
            if (delta == 0) return intendedGapPx;
            // Gravity.BOTTOM: positive y raises the window, negative lowers it toward the
            // physical display bottom.
            WindowManager.LayoutParams params = state.overlayWindowParams;
            int currentY = params == null ? 0 : params.y;
            return currentY - delta;
        } catch (Throwable ignored) {
            return intendedGapPx;
        }
    }

    private static void applyBackdropProfile(State state) {
        if (state == null || state.nav == null || state.adapter == null) return;
        state.nav.setBackdropCaptureProfile(
                state.adapter.backdropMovingCaptureIntervalMs(),
                state.adapter.backdropSettleCaptureDelayMs(),
                state.adapter.captureHardwareBackdrops()
        );
    }

    private static void updatePrimaryRoot(State state, View root) {
        if (state.primaryRoot == root) return;
        state.primaryRoot = root;
        state.backdropRefreshPending = true;
        state.lastNavigationVisibilitySignature = Integer.MIN_VALUE;
    }

    /**
     * Pinduoduo and WeChat reuse their home rows across pages while hiding a parent. Their
     * injected rows live outside that parent, so attachment hooks alone cannot follow it.
     * Observe the exact native row and, for WeChat, the changing window bounds/insets after a
     * rotation. No global View method is hooked and no hierarchy scan runs on ordinary frames.
     */
    private static void attachNavigationVisibilityGuard(State state) {
        if (state == null || (state.app != TargetApp.PDD && state.app != TargetApp.WECHAT)
                || state.nav == null) return;
        View decor;
        try {
            decor = state.activity.getWindow() == null
                    ? null : state.activity.getWindow().getDecorView();
        } catch (Throwable ignored) {
            decor = null;
        }
        if (decor == null) return;
        final View observedDecor = decor;

        try {
            if (state.navigationVisibilityGuardRoot == decor
                    && state.navigationVisibilityGuard != null
                    && decor.getViewTreeObserver().isAlive()) return;
        } catch (Throwable ignored) {}
        detachNavigationVisibilityGuard(state);

        final WeakReference<State> stateRef = new WeakReference<>(state);
        state.navigationVisibilityRefresh = () -> {
            State current = stateRef.get();
            if (current == null) return;
            current.navigationVisibilityRefreshPosted = false;
            if (getState(current.activity) != current || current.nav == null) return;
            if (current.app == TargetApp.WECHAT && current.adapter != null
                    && current.appSettings != null) {
                boolean showPost = current.app.hasPostAction && current.appSettings.showPost;
                GlassSettings appearance = resolveAppearance(
                        current.app, current.appSettings, showPost);
                boolean showCenterAction = !showPost
                        && current.adapter.centerActionTabIndex() >= 0
                        && current.adapter.getVisibleLabels().length == 5;
                updateNavBounds(current.activity, current, appearance,
                        showPost, showCenterAction);
                current.nav.requestApplyInsets();
            }
            syncVisibilityAndSelection(current);
            ensureNavOnTop(current);
        };
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
            State current = stateRef.get();
            if (current == null || getState(current.activity) != current) return;
            int signature = navigationVisibilitySignature(current, observedDecor);
            if (signature == current.lastNavigationVisibilitySignature) return;
            current.lastNavigationVisibilitySignature = signature;
            if (current.navigationVisibilityRefreshPosted || current.nav == null
                    || current.navigationVisibilityRefresh == null) return;
            current.navigationVisibilityRefreshPosted = true;
            current.nav.postOnAnimation(current.navigationVisibilityRefresh);
        };
        try {
            ViewTreeObserver observer = decor.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(listener);
            state.navigationVisibilityGuardRoot = decor;
            state.navigationVisibilityGuard = listener;
            state.lastNavigationVisibilitySignature =
                    navigationVisibilitySignature(state, decor);
        } catch (Throwable ignored) {
            state.navigationVisibilityGuardRoot = null;
            state.navigationVisibilityGuard = null;
            state.navigationVisibilityRefresh = null;
        }
    }

    private static int navigationVisibilitySignature(View root) {
        if (root == null) return 0;
        int signature = System.identityHashCode(root);
        try { signature = 31 * signature + root.getVisibility(); }
        catch (Throwable ignored) {}
        try { signature = 31 * signature + root.getWindowVisibility(); }
        catch (Throwable ignored) {}
        try { signature = 31 * signature + (root.isAttachedToWindow() ? 1 : 0); }
        catch (Throwable ignored) {}
        // isShown() includes every parent visibility state but deliberately ignores alpha and
        // translation, so LiquidGlass's own off-screen suppression cannot hide the injected row.
        try { signature = 31 * signature + (root.isShown() ? 1 : 0); }
        catch (Throwable ignored) {}
        return signature;
    }

    private static int navigationVisibilitySignature(State state, View decor) {
        int signature = navigationVisibilitySignature(state.primaryRoot);
        if (state.app != TargetApp.WECHAT || decor == null) return signature;
        // The initial reinstall can run while DecorView still has its old portrait bounds.
        // A later layout (or a navigation-bar inset change) must resize the glass row again.
        signature = 31 * signature + decor.getWidth();
        signature = 31 * signature + decor.getHeight();
        try {
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) {
                android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.navigationBars());
                android.graphics.Insets tappable = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.tappableElement());
                android.graphics.Insets gestures = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.mandatorySystemGestures());
                signature = 31 * signature + bars.bottom;
                signature = 31 * signature + bars.left;
                signature = 31 * signature + bars.right;
                signature = 31 * signature + tappable.bottom;
                signature = 31 * signature + gestures.bottom;
            }
        } catch (Throwable ignored) {}
        return signature;
    }

    private static void detachNavigationVisibilityGuard(State state) {
        if (state == null) return;
        View root = state.navigationVisibilityGuardRoot;
        ViewTreeObserver.OnGlobalLayoutListener listener = state.navigationVisibilityGuard;
        state.navigationVisibilityGuardRoot = null;
        state.navigationVisibilityGuard = null;
        state.lastNavigationVisibilitySignature = Integer.MIN_VALUE;
        state.navigationVisibilityRefreshPosted = false;
        if (state.nav != null && state.navigationVisibilityRefresh != null) {
            try { state.nav.removeCallbacks(state.navigationVisibilityRefresh); }
            catch (Throwable ignored) {}
        }
        state.navigationVisibilityRefresh = null;
        if (root == null || listener == null) return;
        try {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
        } catch (Throwable ignored) {}
    }

    private static void applyState(Activity activity, State state) {
        if (state == null || state.nav == null || state.adapter == null) return;
        boolean showPost = state.app.hasPostAction && state.appSettings.showPost;
        GlassSettings appearance = resolveAppearance(state.app, state.appSettings, showPost);

        state.nav.setUseCoolapkLegacyBackdropCapture(usesCoolapkLegacyCapture(state.app));
        state.nav.setUseWindowPixelCopyBackdropCapture(usesWindowPixelCopyCapture(state));
        state.nav.setCoolapkLegacyCaptureHeightDp(84f);
        state.nav.setWindowPixelCopyBlankFallbackColor(
                state.app == TargetApp.MI_GALLERY
                        && appearance.themeMode != GlassSettings.THEME_DARK
                        ? android.graphics.Color.WHITE : 0
        );
        state.nav.setHostNavigationAdapter(state.adapter);
        state.nav.setNativeCenterActionView(state.adapter.getNativeCenterActionView());
        state.nav.setBackdropExcludedViews(state.adapter.getBackdropExcludedViews());
        state.nav.setExactBackdropSourceEnabled(state.adapter.useExactBackdropSource());
        state.nav.setCoolapkLegacyPreservePhysicalCoordinates(
                state.adapter.preservePhysicalBackdropCoordinates()
        );
        applyNavigationIcons(state.nav, state.adapter);
        state.nav.setLabels(state.adapter.getVisibleLabels());
        applyBackdropProfile(state);
        state.nav.setBackdropBlankFrameGuard(
                state.adapter.preserveLastBackdropOnBlankFrame()
        );
        View backdropSource = state.adapter.getBackdropSourceView();
        boolean backdropSourceChanged = state.backdropSource != backdropSource;
        state.backdropSource = backdropSource;
        // Store the newest host source before resuming. The old order could restart against a dead
        // pre-toggle View and then never obtain a valid frame in Bilibili/NetEase.
        state.nav.setBackdropSourceView(backdropSource);
        state.appliedGlobalRenderRevision =
                GlobalRenderSettingsReader.apply(activity, state.nav, false);
        if (state.app == TargetApp.MI_COMPASS) {
            state.nav.setNativeBlurEnabled(false);
            state.nav.setBackdropCaptureEnabled(true);
            state.nav.setSyntheticLiquidBackdropEnabled(true);
            state.nav.setCoolapkLegacyRequireVisibleSurfaceForCapture(true);
        }
        if (state.app == TargetApp.DOUYIN) {
            boolean pressOnlyTransparent = !state.appSettings.forceBlackBackdrop
                    && state.appSettings.pressOnlyTransparentBackdrop;
            if (pressOnlyTransparent) state.nav.setNativeBlurEnabled(false);
            state.nav.setForceSyntheticOnlyBackdrop(state.appSettings.forceBlackBackdrop);
            state.nav.setPressOnlyTransparentBackdrop(pressOnlyTransparent);
        }
        boolean showCenterAction = !showPost
                && state.adapter.centerActionTabIndex() >= 0
                && state.adapter.getVisibleLabels().length == 5;
        updateNavBounds(activity, state, appearance, showPost, showCenterAction);
        state.nav.applySettings(appearance);
        state.appliedSettingsRevision = state.appSettings.revision;

        configureFileManagerActionMode(state);
        applySuppressionPlan(state, suppressionPlanForState(state, state.adapter));
        applyWeChatFrostedSuppression(state, null);
        attachNavigationVisibilityGuard(state);
        syncVisibilityAndSelection(state);
        ensureNavOnTop(state);
        if (backdropSourceChanged && (state.app == TargetApp.BILIBILI
                || state.app == TargetApp.BILIBILI_HD
                || state.app == TargetApp.JD
                || state.app == TargetApp.NETEASE
                || state.app == TargetApp.CAINIAO)) {
            // These hosts keep outgoing and incoming page roots alive during tab animation. Finish
            // the frozen-frame hand-off only after the exact active source has changed, then sample
            // again after the host's own delayed layout/render work.
            state.nav.finishHostContentTransition();
            state.nav.post(() -> state.nav.refreshBackdropSoon());
            state.nav.postDelayed(() -> state.nav.refreshBackdropSoon(), 112L);
            state.nav.postDelayed(() -> state.nav.refreshBackdropSoon(), 300L);
        }
        if (state.backdropRefreshPending) {
            state.backdropRefreshPending = false;
            state.nav.refreshBackdropSoon();
        }
    }

    private static boolean sameTargetSettings(TargetAppSettings a, TargetAppSettings b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.revision == b.revision
                && a.enabled == b.enabled
                && a.hiddenMask == b.hiddenMask
                && a.showPost == b.showPost
                && a.themeMode == b.themeMode
                && Float.compare(a.tabWidthDp, b.tabWidthDp) == 0
                && Float.compare(a.bottomPaddingDp, b.bottomPaddingDp) == 0
                && Float.compare(a.blurRadiusDp, b.blurRadiusDp) == 0
                && Float.compare(a.lightAlpha, b.lightAlpha) == 0
                && Float.compare(a.darkAlpha, b.darkAlpha) == 0
                && Float.compare(a.iconScale, b.iconScale) == 0
                && Float.compare(a.textSizeSp, b.textSizeSp) == 0
                && a.iconOnly == b.iconOnly
                && a.customPostColorEnabled == b.customPostColorEnabled
                && a.customPostColor == b.customPostColor
                && a.forceBlackBackdrop == b.forceBlackBackdrop
                && a.pressOnlyTransparentBackdrop == b.pressOnlyTransparentBackdrop;
    }

    private static GlassSettings resolveAppearance(
            TargetApp app,
            TargetAppSettings perApp,
            boolean showPost
    ) {
        boolean bilibili = app == TargetApp.BILIBILI;
        boolean xhs = app == TargetApp.XHS;
        boolean midea = app == TargetApp.MIDEA;
        boolean meituanMain = app == TargetApp.MEITUAN_MAIN;
        boolean colorEnabled = bilibili || xhs || midea || meituanMain
                || perApp.customPostColorEnabled;
        int color;
        if (!perApp.customPostColorEnabled && bilibili) {
            color = 0xFFFB7299;
        } else if (!perApp.customPostColorEnabled && xhs) {
            color = 0xFFFF2442;
        } else if (!perApp.customPostColorEnabled && meituanMain) {
            color = 0xFFFFD100; // Meituan yellow
        } else if (!perApp.customPostColorEnabled && midea) {
            color = 0xFF0092D8; // Midea blue
        } else {
            color = perApp.customPostColor;
        }
        int resolvedThemeMode = perApp.themeMode;
        // MOONDROP reports a dark Android configuration while its Flutter page is visibly light.
        // Treat "follow system" as light for this host; an explicit per-app dark choice still wins.
        if (app == TargetApp.MOONDROP && resolvedThemeMode == GlassSettings.THEME_SYSTEM) {
            resolvedThemeMode = GlassSettings.THEME_LIGHT;
        }
        // Compass 26.56 intentionally has no normal-page backdrop capture. Its neutral fallback
        // therefore uses a fixed dark glass until a real camera SurfaceView becomes visible.
        if (app == TargetApp.MI_COMPASS) {
            resolvedThemeMode = GlassSettings.THEME_DARK;
        }
        return new GlassSettings(
                perApp.revision,
                true,
                resolvedThemeMode,
                perApp.tabWidthDp,
                perApp.bottomPaddingDp,
                perApp.blurRadiusDp,
                perApp.lightAlpha,
                perApp.darkAlpha,
                perApp.iconScale,
                perApp.textSizeSp,
                perApp.iconOnly,
                showPost,
                colorEnabled,
                color,
                false
        );
    }

    private static void updateNavBounds(
            Activity activity,
            State state,
            GlassSettings settings,
            boolean showPost,
            boolean showCenterAction
    ) {
        int count = state.app == TargetApp.MI_FILE_MANAGER && state.fileManagerSelectionMode
                ? FILE_MANAGER_ACTION_LABELS.length
                : Math.max(1, state.adapter.getVisibleLabels().length);
        float pillContentDp;
        if (showPost) {
            // Four logical tabs plus the independent CoolAPK-style center action.
            pillContentDp = settings.tabWidthDp * count + 48f;
        } else if (showCenterAction) {
            // Five logical tabs, with the middle AI replacing one normal-width tab slot.
            pillContentDp = settings.tabWidthDp * Math.max(1, count - 1) + 48f;
        } else {
            pillContentDp = settings.tabWidthDp * count;
        }
        int pillWidth = dp(activity, pillContentDp + 8f);
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        String style = state.nav.getNavigationStyle();
        boolean hyper = !InjectedNavigationStyle.LIQUID_GLASS.equals(style)
                && !(state.app == TargetApp.MI_FILE_MANAGER && state.fileManagerSelectionMode);
        int viewportWidth = activity.getWindow().getDecorView().getWidth();
        if (viewportWidth <= 0) viewportWidth = screenWidth;
        state.nav.setHyperNavigationViewport(viewportWidth);
        // Application panels are placed at the physical bottom; ordinary views already receive
        // the classic-button inset as an Android margin. Only add the remaining inset in Compose.
        int hyperBottomInset = 0;
        try {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets != null) {
                int navigation = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
                int tappable = insets.getInsetsIgnoringVisibility(WindowInsets.Type.tappableElement()).bottom;
                int gestures = insets.getInsetsIgnoringVisibility(WindowInsets.Type.mandatorySystemGestures()).bottom;
                hyperBottomInset = !usesApplicationPanelOverlay(state.app) && tappable > gestures ? 0 : navigation;
                state.nav.setHyperNavigationBottomInset(hyperBottomInset);
            }
        } catch (Throwable ignored) {}
        float density = activity.getResources().getDisplayMetrics().density;
        int width = hyper ? Math.min(viewportWidth, dp(activity, InjectedNavigationStyle.overlayWidthDp(
                style, viewportWidth / density, count, showPost, state.adapter.separateCirclePlus() && count >= 5))) : state.app == TargetApp.MI_FILE_MANAGER && state.fileManagerSelectionMode
                ? screenWidth
                : Math.min(
                        screenWidth - dp(activity, 4f),
                        pillWidth + dp(activity, RENDER_GUTTER_HORIZONTAL_DP * 2f)
                );
        int height = hyper ? dp(activity, Math.max(RENDER_HEIGHT_DP, 76f + hyperBottomInset / density + 32f))
                : dp(activity, Math.max(RENDER_HEIGHT_DP, 56f + settings.bottomPaddingDp + 46f));

        ViewGroup.LayoutParams raw = state.nav.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        if (lp.width != width || lp.height != height) {
            lp.width = width;
            lp.height = height;
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            state.nav.setLayoutParams(lp);
        }
        if (state.app == TargetApp.MI_GALLERY
                || state.app == TargetApp.NETEASE
                || state.app == TargetApp.DOUYIN) {
            updatePanelOverlayBounds(state, width, height);
        }
        if (state.app == TargetApp.MI_GALLERY
                || state.app == TargetApp.NETEASE
                || state.app == TargetApp.DOUYIN) {
            // The first measurement may run before the panel window is laid out. Re-measure once
            // after layout so the physical-bottom correction converges to the exact position.
            final int correctedWidth = width;
            final int correctedHeight = height;
            try {
                state.container.post(() -> {
                    State current = getState(state.activity);
                    if (current != state || current.container == null) return;
                    updatePanelOverlayBounds(state, correctedWidth, correctedHeight);
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void applySuppressionPlan(
            State state,
            UniversalHostAdapter.SuppressionPlan plan
    ) {
        if (state == null || plan == null) return;

        Set<View> desired = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        desired.addAll(plan.overscanBottomViews);
        desired.addAll(plan.extendBottomViews);
        desired.addAll(plan.zeroBottomMarginViews);
        desired.addAll(plan.zeroBottomPaddingViews);
        desired.addAll(plan.alignAboveGlassViews);
        for (UniversalHostAdapter.SuppressionPlan.PhoneDialerExpansion expansion
                : plan.phoneDialerExpansions) {
            if (expansion == null) continue;
            desired.add(expansion.keysContainer);
            desired.add(expansion.callContainer);
        }
        desired.addAll(plan.clearOnlyRoots);
        desired.addAll(plan.detachRoots);
        desired.addAll(plan.transparentRoots);
        desired.addAll(plan.offscreenRoots);
        desired.addAll(plan.collapseRoots);
        desired.remove(null);
        desired.remove(state.nav);

        Set<View> guarded = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        guarded.addAll(plan.offscreenRoots);
        // Overscanned roots (UU Compose home in particular) are remeasured by the host after
        // composition. Keep their layout guard attached so the reserved native bar cannot return.
        guarded.addAll(plan.overscanBottomViews);
        guarded.addAll(plan.extendBottomViews);
        guarded.addAll(plan.collapseRoots);
        guarded.addAll(plan.transparentRoots);
        guarded.addAll(plan.clearOnlyRoots);
        guarded.addAll(plan.alignAboveGlassViews);
        for (UniversalHostAdapter.SuppressionPlan.PhoneDialerExpansion expansion
                : plan.phoneDialerExpansions) {
            if (expansion == null) continue;
            guarded.add(expansion.keysContainer);
            guarded.add(expansion.callContainer);
        }

        // Host apps can replace their native navigation subtree without destroying the Activity.
        // Restore and release views that disappeared from the new plan instead of retaining every
        // historical row until onDestroy.
        for (View old : new ArrayList<>(state.snapshots.keySet())) {
            if (old == null) continue;
            if (!desired.contains(old)) {
                releaseSuppressedView(state, old, true);
            } else if (!guarded.contains(old)) {
                removeSuppressionLayoutGuard(old);
            }
        }

        // A view may move from collapse to offscreen (or another category) across host rebuilds.
        // Rebuild category membership from the current plan while retaining its original snapshot.
        state.collapsedRoots.clear();
        state.offscreenRoots.clear();
        state.detachedRoots.clear();
        state.transparentRoots.clear();
        state.clearRoots.clear();
        state.zeroMarginRoots.clear();
        state.zeroBottomPaddingRoots.clear();
        state.alignAboveGlassGapDp.clear();
        state.phoneDialerExpansions.clear();
        state.overscanBottomDp.clear();
        state.extendBottomDp.clear();

        for (int i = 0; i < plan.extendBottomViews.size(); i++) {
            View view = plan.extendBottomViews.get(i);
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            float dp = i < plan.extendBottomDp.size() ? plan.extendBottomDp.get(i) : 0f;
            state.extendBottomDp.put(view, dp);
            markSuppressed(view, state);
            ensureSuppressionLayoutGuard(view);
            applyBottomExtension(state, view, dp);
        }
        for (int i = 0; i < plan.overscanBottomViews.size(); i++) {
            View view = plan.overscanBottomViews.get(i);
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            float dp = i < plan.overscanBottomDp.size() ? plan.overscanBottomDp.get(i) : 0f;
            state.overscanBottomDp.put(view, dp);
            markSuppressed(view, state);
            ensureSuppressionLayoutGuard(view);
            applyBottomOverscan(view, dp);
        }
        for (View view : plan.zeroBottomMarginViews) {
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            state.zeroMarginRoots.add(view);
            markSuppressed(view, state);
            zeroBottomMargin(view);
        }
        for (View view : plan.zeroBottomPaddingViews) {
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            state.zeroBottomPaddingRoots.add(view);
            markSuppressed(view, state);
            zeroBottomPadding(view);
        }
        for (int i = 0; i < plan.alignAboveGlassViews.size(); i++) {
            View view = plan.alignAboveGlassViews.get(i);
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            float gapDp = i < plan.alignAboveGlassGapDp.size()
                    ? plan.alignAboveGlassGapDp.get(i) : 0f;
            state.alignAboveGlassGapDp.put(view, gapDp);
            markSuppressed(view, state);
            ensureSuppressionLayoutGuard(view);
            alignExactViewAboveGlass(state, view, gapDp);
        }
        for (int i = 0; i < plan.alignAboveGlassByTranslationViews.size(); i++) {
            View view = plan.alignAboveGlassByTranslationViews.get(i);
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            float gapDp = i < plan.alignAboveGlassByTranslationGapDp.size()
                    ? plan.alignAboveGlassByTranslationGapDp.get(i) : 0f;
            state.alignAboveGlassTranslationGapDp.put(view, gapDp);
            markSuppressed(view, state);
            ensureSuppressionLayoutGuard(view);
            alignExactViewAboveGlassByTranslation(state, view, gapDp);
        }
        for (UniversalHostAdapter.SuppressionPlan.PhoneDialerExpansion requested
                : plan.phoneDialerExpansions) {
            if (requested == null
                    || requested.anchor == null
                    || requested.keysContainer == null
                    || requested.callContainer == null
                    || requested.keysContainer == state.nav
                    || requested.callContainer == state.nav) continue;
            snapshot(state, requested.keysContainer);
            snapshot(state, requested.callContainer);
            markSuppressed(requested.keysContainer, state);
            markSuppressed(requested.callContainer, state);
            ensureSuppressionLayoutGuard(requested.keysContainer);
            ensureSuppressionLayoutGuard(requested.callContainer);
            UniversalHostAdapter.SuppressionPlan.PhoneDialerExpansion active =
                    new UniversalHostAdapter.SuppressionPlan.PhoneDialerExpansion(
                            requested.anchor,
                            requested.keysContainer,
                            requested.callContainer,
                            requested.gapDp
                    );
            state.phoneDialerExpansions.add(active);
            expandPhoneDialerAboveGlass(state, active);
        }
        for (View view : plan.clearOnlyRoots) {
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            state.clearRoots.add(view);
            markSuppressed(view, state);
            ensureSuppressionLayoutGuard(view);
            clearExactPlate(view);
        }
        for (View view : plan.detachRoots) {
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            state.detachedRoots.add(view);
            markSuppressed(view, state);
            detachGalleryNativeNavigation(view);
        }
        for (View view : plan.transparentRoots) {
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            state.transparentRoots.add(view);
            markSuppressed(view, state);
            ensureSuppressionLayoutGuard(view);
            if (state.app == TargetApp.MI_GALLERY
                    || state.app == TargetApp.MI_FILE_MANAGER) ensureGalleryFrameGuard(view);
            if (state.app == TargetApp.MI_FILE_MANAGER) {
                makeFileManagerNativeActionsTransparent(view);
            } else {
                makeGalleryNativeNavigationTransparent(view);
            }
        }
        for (View view : plan.offscreenRoots) {
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            state.offscreenRoots.add(view);
            markSuppressed(view, state);
            ensureSuppressionLayoutGuard(view);
            if (state.app == TargetApp.MI_GALLERY) ensureGalleryFrameGuard(view);
            moveExactNavigationRootOffscreen(view);
            if (state.app == TargetApp.MI_GALLERY) hideGalleryNativeNavigation(view);
        }
        for (View view : plan.collapseRoots) {
            if (view == null || view == state.nav) continue;
            snapshot(state, view);
            state.collapsedRoots.add(view);
            markSuppressed(view, state);
            ensureSuppressionLayoutGuard(view);
            if (state.app == TargetApp.MI_GALLERY) ensureGalleryFrameGuard(view);
            collapseExactNavigationRoot(view);
            if (state.app == TargetApp.MI_GALLERY) hideGalleryNativeNavigation(view);
        }
        attachSuppressionFrameGuard(state);
    }

    private static void removeSuppressionLayoutGuard(View view) {
        if (view == null) return;
        View.OnLayoutChangeListener guard = SUPPRESSION_LAYOUT_GUARDS.remove(view);
        if (guard != null) {
            try { view.removeOnLayoutChangeListener(guard); } catch (Throwable ignored) {}
        }
    }

    private static void releaseSuppressedView(State state, View view, boolean restore) {
        if (state == null || view == null) return;
        removeSuppressionLayoutGuard(view);
        removeGalleryFrameGuard(view);
        if (getSuppressedState(view) == state) SUPPRESSED_VIEWS.remove(view);
        state.collapsedRoots.remove(view);
        state.offscreenRoots.remove(view);
        boolean wasDetached = state.detachedRoots.remove(view);
        state.transparentRoots.remove(view);
        state.clearRoots.remove(view);
        state.meituanDynamicClearRoots.remove(view);
        state.zeroMarginRoots.remove(view);
        state.zeroBottomPaddingRoots.remove(view);
        state.alignAboveGlassGapDp.remove(view);
        state.alignAboveGlassTranslationGapDp.remove(view);
        state.alignAboveGlassTranslationApplied.remove(view);
        state.phoneDialerExpansions.removeIf(expansion -> expansion == null
                || expansion.keysContainer == view
                || expansion.callContainer == view);
        state.phoneDialerGeometry.remove(view);
        state.overscanBottomDp.remove(view);
        state.extendBottomDp.remove(view);
        ViewSnapshot snapshot = state.snapshots.remove(view);
        if (restore && wasDetached) restoreGalleryDetachedNavigation(view);
        if (restore && snapshot != null) {
            runInternalVisibilityChange(() -> snapshot.restore(view));
        }
    }

    private static void ensureSuppressionLayoutGuard(View view) {
        if (view == null || SUPPRESSION_LAYOUT_GUARDS.containsKey(view)) return;
        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(
                    View changed,
                    int left,
                    int top,
                    int right,
                    int bottom,
                    int oldLeft,
                    int oldTop,
                    int oldRight,
                    int oldBottom
            ) {
                if (Boolean.TRUE.equals(INTERNAL_VISIBILITY_CHANGE.get())) return;
                State state = getSuppressedState(changed);
                if (state == null) return;
                boolean needsRepair = false;
                try {
                    ViewGroup.LayoutParams raw = changed.getLayoutParams();
                    if (state.extendBottomDp.containsKey(changed)) {
                        needsRepair = bottomExtensionNeedsRepair(
                                state, changed, state.extendBottomDp.get(changed));
                    } else if (state.overscanBottomDp.containsKey(changed)) {
                        needsRepair = bottomOverscanNeedsRepair(
                                changed, state.overscanBottomDp.get(changed));
                    } else if (state.transparentRoots.contains(changed)) {
                        needsRepair = state.app == TargetApp.MI_FILE_MANAGER
                                ? fileManagerNativeTransparencyNeedsRepair(changed)
                                : galleryNativeTransparencyNeedsRepair(changed);
                    } else if (state.collapsedRoots.contains(changed)) {
                        needsRepair = raw == null || raw.height != 0 || changed.getAlpha() > 0.01f
                                || (state.app == TargetApp.MI_GALLERY
                                && changed.getVisibility() != View.INVISIBLE);
                    } else if (state.clearRoots.contains(changed)) {
                        needsRepair = changed.getVisibility() != View.INVISIBLE
                                || changed.getAlpha() > 0.01f
                                || changed.isClickable() || changed.isFocusable();
                    } else if (state.offscreenRoots.contains(changed)) {
                        if (state.app == TargetApp.MI_GALLERY) {
                            needsRepair = galleryNativeNavigationNeedsRepair(changed);
                        } else {
                            needsRepair = changed.getAlpha() > 0.01f
                                    || changed.getTranslationY()
                                    < offscreenTranslation(changed) * 0.75f;
                        }
                    } else if (state.alignAboveGlassGapDp.containsKey(changed)) {
                        // A floating host control can be laid out again after its native row
                        // collapses. Re-run the idempotent alignment against current glass bounds.
                        needsRepair = true;
                    } else if (state.alignAboveGlassTranslationGapDp.containsKey(changed)) {
                        // Some hosts reposition a control without a layout pass; re-run
                        // its translation alignment against the glass.
                        needsRepair = true;
                    } else if (phoneDialerExpansionForView(state, changed) != null) {
                        // Contacts can remeasure the keypad after its own animations. Reapply the
                        // original-height-plus-inset relationship without moving the bottom row.
                        needsRepair = true;
                    }
                } catch (Throwable ignored) {}
                if (needsRepair) onSuppressedViewMutated(changed);
            }
        };
        try {
            view.addOnLayoutChangeListener(listener);
            SUPPRESSION_LAYOUT_GUARDS.put(view, listener);
        } catch (Throwable ignored) {}
    }

    private static void ensureGalleryFrameGuard(View view) {
        if (view == null) return;
        ViewTreeObserver.OnPreDrawListener listener = GALLERY_FRAME_GUARDS.get(view);
        if (listener == null) {
            WeakReference<View> target = new WeakReference<>(view);
            listener = () -> {
                View guarded = target.get();
                if (guarded == null) return true;
                State state = getSuppressedState(guarded);
                if (state == null) return true;
                try {
                    if (!guarded.isAttachedToWindow()) return true;
                    if (state.transparentRoots.contains(guarded)) {
                        if (state.app == TargetApp.MI_FILE_MANAGER) {
                            if (fileManagerNativeTransparencyNeedsRepair(guarded)) {
                                makeFileManagerNativeActionsTransparent(guarded);
                            }
                        } else if (state.app == TargetApp.MI_GALLERY
                                && galleryNativeTransparencyNeedsRepair(guarded)) {
                            // NavigatorImpl may restart its alpha/visibility animator after a tab
                            // change. Re-apply transparency in the same frame before any native
                            // pixels can appear above the LiquidGlass overlay.
                            makeGalleryNativeNavigationTransparent(guarded);
                        }
                    } else if (state.app == TargetApp.MI_GALLERY
                            && galleryNativeNavigationNeedsRepair(guarded)) {
                        // Legacy off-screen/collapse fallback retained for non-transparent plans.
                        moveExactNavigationRootOffscreen(guarded);
                        hideGalleryNativeNavigation(guarded);
                    }
                } catch (Throwable ignored) {}
                return true;
            };
            GALLERY_FRAME_GUARDS.put(view, listener);
        }
        try {
            ViewTreeObserver observer = view.getViewTreeObserver();
            if (!observer.isAlive()) return;
            // Rebind on every exact adapter pass. Gallery can detach/reparent the same row during
            // its show animation, which replaces the underlying ViewTreeObserver.
            try { observer.removeOnPreDrawListener(listener); } catch (Throwable ignored) {}
            observer.addOnPreDrawListener(listener);
        } catch (Throwable ignored) {}
    }

    private static void removeGalleryFrameGuard(View view) {
        if (view == null) return;
        ViewTreeObserver.OnPreDrawListener listener = GALLERY_FRAME_GUARDS.remove(view);
        if (listener == null) return;
        try {
            ViewTreeObserver observer = view.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
        } catch (Throwable ignored) {}
    }

    private static boolean isGalleryBottomNavigationView(View view) {
        if (view == null) return false;
        try {
            String name = view.getClass().getName();
            return "miuix.navigator.bottomnavigation.BottomNavigationView".equals(name)
                    || (name.startsWith("miuix.navigator.")
                    && UniversalHostAdapter.call(view, "getMenu") != null
                    && UniversalHostAdapter.call(view, "getSelectedItemId") != null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean galleryNativeTransparencyNeedsRepair(View view) {
        if (view == null) return false;
        try {
            if (view.getVisibility() != View.VISIBLE) return true;
            if (view.getAlpha() > 0.001f) return true;
            Object menuView = UniversalHostAdapter.call(view, "getMenuView");
            if (menuView instanceof View) {
                View menu = (View) menuView;
                if (menu.getAlpha() > 0.001f
                        || menu.getVisibility() != View.INVISIBLE) return true;
            }
            // HyperMaterial blur is a compositor-level surface. Alpha/visibility do not always
            // remove it, so treat an applied blur as a repair condition and disable it again.
            try {
                Object applied = UniversalHostAdapter.call(view, "isApplyBlur");
                if (Boolean.TRUE.equals(applied)) return true;
            } catch (Throwable ignored) {}
            if (view.isClickable() || view.isLongClickable() || view.isFocusable()
                    || view.isFocusableInTouchMode()) return true;
            if (view.getImportantForAccessibility()
                    != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return true;
            View current = view;
            for (int depth = 0; depth < 3 && current != null; depth++) {
                if ((depth == 0 || isGalleryNavigatorChrome(current))
                        && (current.getBackground() != null
                        || current.getForeground() != null
                        || current.getElevation() != 0f
                        || current.getTranslationZ() != 0f
                        || current.getStateListAnimator() != null)) return true;
                if (!(current.getParent() instanceof View)) break;
                View parent = (View) current.getParent();
                if (!isGalleryNavigatorChrome(parent)) break;
                current = parent;
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isGalleryNavigatorChrome(View view) {
        if (view == null) return false;
        try {
            String className = view.getClass().getName();
            if (className.startsWith("miuix.navigator.")) return true;
            int id = view.getId();
            if (id != View.NO_ID) {
                String name = view.getResources().getResourceEntryName(id)
                        .toLowerCase(java.util.Locale.ROOT);
                return name.contains("bottom_navigation")
                        || name.contains("bottom_nav")
                        || name.contains("navigator");
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void clearGalleryNavigatorWrapperChrome(View navigation) {
        View current = navigation;
        // BottomNavigationView sometimes sits inside a separate miuix NavigationView whose rounded
        // white plate is not a descendant of the row. Clear chrome only on verified navigator
        // wrappers; never touch the Activity content root or the page background.
        for (int depth = 0; depth < 3 && current != null; depth++) {
            if (depth == 0 || isGalleryNavigatorChrome(current)) clearChrome(current);
            if (!(current.getParent() instanceof View)) break;
            View parent = (View) current.getParent();
            if (!isGalleryNavigatorChrome(parent)) break;
            current = parent;
        }
    }

    /**
     * Keep Gallery's exact native navigation attached, measured and owned by NavigatorImpl while
     * making it fully transparent. Keeping visibility VISIBLE prevents the host from treating the
     * row as missing and creating another copy at a different coordinate. Root alpha suppresses
     * every icon/text child at once; verified navigator wrapper chrome is cleared separately.
     */
    private static boolean fileManagerNativeTransparencyNeedsRepair(View view) {
        if (view == null) return false;
        try {
            return view.getVisibility() != View.VISIBLE
                    || view.getAlpha() > 0.001f
                    || view.isClickable()
                    || view.isLongClickable()
                    || view.isFocusable()
                    || view.isFocusableInTouchMode()
                    || view.getImportantForAccessibility()
                    != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void makeFileManagerNativeActionsTransparent(View view) {
        if (view == null) return;
        runInternalVisibilityChange(() -> {
            try {
                try { view.animate().cancel(); } catch (Throwable ignored) {}
                try { view.clearAnimation(); } catch (Throwable ignored) {}
                if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
                if (view.getAlpha() != 0f) view.setAlpha(0f);
                view.setClickable(false);
                view.setLongClickable(false);
                view.setFocusable(false);
                view.setFocusableInTouchMode(false);
                view.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                try { view.invalidate(); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                XposedBridge.log("[LiquidGlass][3.33] File Manager native action suppression error: " + t);
            }
        });
    }

    public static void makeGalleryNativeNavigationTransparent(View view) {
        if (view == null || !isGalleryBottomNavigationView(view)) return;
        runInternalVisibilityChange(() -> {
            try {
                try { view.animate().cancel(); } catch (Throwable ignored) {}
                try { view.clearAnimation(); } catch (Throwable ignored) {}
                stopMiuixFolmeAnimations(view);
                disableMiuixNavigationBlur(view);
                clearGalleryNavigatorWrapperChrome(view);
                if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
                if (view.getAlpha() != 0f) view.setAlpha(0f);
                hideGalleryNavigationMenuView(view);
                view.setClickable(false);
                view.setLongClickable(false);
                view.setFocusable(false);
                view.setFocusableInTouchMode(false);
                view.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                try { view.invalidate(); } catch (Throwable ignored) {}
            } catch (Throwable t) {
                XposedBridge.log("[LiquidGlass][3.07] Gallery native transparency error: " + t);
            }
        });
    }

    /**
     * Gallery 4.3.1.16 animates its BottomNavigationView through miuix Folme (not View animations),
     * so view.animate().cancel()/clearAnimation() cannot stop the alpha/visibility fight that
     * re-shows the native row. Folme.clean() detaches those controllers from the row and its menu
     * view; the existing per-frame guard then holds alpha/visibility.
     */
    private static void stopMiuixFolmeAnimations(View view) {
        try {
            Class<?> folme = Class.forName(
                    "miuix.animation.Folme",
                    true,
                    view.getClass().getClassLoader()
            );
            java.lang.reflect.Method clean = folme.getMethod("clean", View.class);
            clean.invoke(null, view);
            Object menuView = UniversalHostAdapter.call(view, "getMenuView");
            if (menuView instanceof View) clean.invoke(null, (View) menuView);
        } catch (Throwable ignored) {}
    }

    /**
     * The 4.3.1.16 row uses HyperMaterial blur through MiuiBlurUiHelper. setSupportBlur(false)
     * tears the blur down permanently, applyBlur(false) removes the current surface, and the frame
     * guard re-checks isApplyBlur() on the next host show().
     */
    private static void disableMiuixNavigationBlur(View view) {
        try { UniversalHostAdapter.call(view, "setSupportBlur", false); } catch (Throwable ignored) {}
        try { UniversalHostAdapter.call(view, "setEnableBlur", false); } catch (Throwable ignored) {}
        try { UniversalHostAdapter.call(view, "applyBlur", false); } catch (Throwable ignored) {}
    }

    private static void hideGalleryNavigationMenuView(View view) {
        try {
            Object menuView = UniversalHostAdapter.call(view, "getMenuView");
            if (menuView instanceof View) {
                View menu = (View) menuView;
                if (menu.getAlpha() != 0f) menu.setAlpha(0f);
                if (menu.getVisibility() != View.INVISIBLE) menu.setVisibility(View.INVISIBLE);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Remove Gallery's native bottom navigation from every render tree while preserving its exact
     * controller-owned View object. The supplied APK contains NavigatorImpl.add/remove/setup/
     * setBottomNavigation and repeatedly reattaches the same row during its show animation, so
     * translation/alpha/INVISIBLE cannot be treated as a permanent suppression mechanism.
     *
     * An INVISIBLE placeholder keeps the original parent measurement and white bottom floor. The
     * real row remains detached but callable: Gallery navigation is performed through
     * MiuixNavigatorImpl.findTab/navigate/selectTab, and its Menu remains available off-window.
     */
    public static void detachGalleryNativeNavigation(View view) {
        if (view == null || !isGalleryBottomNavigationView(view)) return;
        runInternalVisibilityChange(() -> {
            try {
                GalleryDetachedNavigation existing = GALLERY_DETACHED_NAVIGATION.get(view);
                ViewSnapshot originalSnapshot = existing == null ? new ViewSnapshot(view) : null;
                try { view.animate().cancel(); } catch (Throwable ignored) {}
                try { view.clearAnimation(); } catch (Throwable ignored) {}
                view.setAlpha(0f);
                view.setVisibility(View.INVISIBLE);
                view.setClickable(false);
                view.setLongClickable(false);
                view.setFocusable(false);
                view.setFocusableInTouchMode(false);
                view.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

                ViewGroup currentParent = view.getParent() instanceof ViewGroup
                        ? (ViewGroup) view.getParent() : null;
                GalleryDetachedNavigation record = existing;
                if (currentParent == null) return;

                // A late Gallery animator/controller may attach the same row again. If a record
                // already exists, remove only that reattachment and retain the first placeholder.
                if (record != null) {
                    try { currentParent.removeView(view); } catch (Throwable ignored) {}
                    return;
                }

                int index = Math.max(0, currentParent.indexOfChild(view));
                ViewGroup.LayoutParams originalParams = view.getLayoutParams();
                View placeholder = new View(view.getContext());
                placeholder.setVisibility(View.INVISIBLE);
                placeholder.setAlpha(0f);
                placeholder.setClickable(false);
                placeholder.setFocusable(false);
                placeholder.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

                currentParent.removeView(view);
                int safeIndex = Math.min(index, currentParent.getChildCount());
                try {
                    if (originalParams != null) {
                        currentParent.addView(placeholder, safeIndex, originalParams);
                    } else {
                        currentParent.addView(placeholder, safeIndex);
                    }
                } catch (Throwable addFailure) {
                    // Some vendor parents reject a generic child with their private LayoutParams.
                    // Visual removal is still the priority; omit the placeholder rather than
                    // reattaching the native row.
                    try {
                        if (placeholder.getParent() instanceof ViewGroup) {
                            ((ViewGroup) placeholder.getParent()).removeView(placeholder);
                        }
                    } catch (Throwable ignored) {}
                    placeholder = null;
                }
                GALLERY_DETACHED_NAVIGATION.put(view, new GalleryDetachedNavigation(
                        currentParent, safeIndex, originalParams, placeholder, originalSnapshot));
                XposedBridge.log("[LiquidGlass][3.06] detached Gallery native navigation "
                        + view.getClass().getName());
            } catch (Throwable t) {
                XposedBridge.log("[LiquidGlass][3.06] Gallery native detach error: " + t);
            }
        });
    }

    private static void restoreGalleryDetachedNavigation(View view) {
        GalleryDetachedNavigation record = GALLERY_DETACHED_NAVIGATION.remove(view);
        if (record == null || view == null) return;
        runInternalVisibilityChange(() -> {
            try {
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                View placeholder = record.placeholder == null ? null : record.placeholder.get();
                if (placeholder != null && placeholder.getParent() instanceof ViewGroup) {
                    ((ViewGroup) placeholder.getParent()).removeView(placeholder);
                }
                ViewGroup parent = record.parent == null ? null : record.parent.get();
                if (parent != null && parent.isAttachedToWindow()) {
                    int index = Math.min(Math.max(0, record.index), parent.getChildCount());
                    if (record.layoutParams != null) {
                        parent.addView(view, index, record.layoutParams);
                    } else {
                        parent.addView(view, index);
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    private static boolean galleryNativeNavigationNeedsRepair(View view) {
        if (view == null) return false;
        try {
            if (view.getVisibility() != View.INVISIBLE || view.getAlpha() > 0.01f) return true;
            Object menuView = UniversalHostAdapter.call(view, "getMenuView");
            if (menuView instanceof View) {
                View menu = (View) menuView;
                if (menu.getAlpha() > 0.01f
                        || menu.getVisibility() != View.INVISIBLE) return true;
            }
            try {
                Object applied = UniversalHostAdapter.call(view, "isApplyBlur");
                if (Boolean.TRUE.equals(applied)) return true;
            } catch (Throwable ignored) {}
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            int safety = dp(view, 24f);
            return location[1] < galleryPhysicalDisplayBottom(view) + safety;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Return the bottom edge of the physical display in screen coordinates. Prefer real display
     * metrics because app/window metrics may exclude the gesture or three-button navigation area.
     * The root-view bottom is also considered for vendor windows whose coordinate origin is shifted.
     */
    private static int galleryPhysicalDisplayBottom(View view) {
        int bottom = 0;
        try {
            android.util.DisplayMetrics real = new android.util.DisplayMetrics();
            Object service = view.getContext().getSystemService(Context.WINDOW_SERVICE);
            if (service instanceof WindowManager) {
                android.view.Display display = ((WindowManager) service).getDefaultDisplay();
                if (display != null) {
                    display.getRealMetrics(real);
                    bottom = real.heightPixels;
                }
            }
        } catch (Throwable ignored) {}
        try {
            bottom = Math.max(bottom,
                    view.getResources().getDisplayMetrics().heightPixels);
        } catch (Throwable ignored) {}
        try {
            View root = view.getRootView();
            if (root != null) {
                int[] rootLocation = new int[2];
                root.getLocationOnScreen(rootLocation);
                int rootHeight = Math.max(root.getHeight(), root.getMeasuredHeight());
                bottom = Math.max(bottom, rootLocation[1] + rootHeight);
            }
        } catch (Throwable ignored) {}
        return Math.max(1, bottom);
    }

    /**
     * Calculate a translation that places the row's physical top edge below the display bottom.
     * The delta is based on getLocationOnScreen(), so it remains correct when Gallery changes the
     * layout top, calls setY(), applies translationY or reparents the row during its show animation.
     */
    private static float galleryBottomOffscreenTranslation(View view) {
        try {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            float safety = dp(view, 24f);
            float targetTop = galleryPhysicalDisplayBottom(view) + safety;
            float delta = targetTop - location[1];
            return view.getTranslationY() + delta;
        } catch (Throwable ignored) {
            float density = view.getResources().getDisplayMetrics().density;
            return Math.max(2048f * density, 8192f);
        }
    }

    private static void snapshot(State state, View view) {
        if (!state.snapshots.containsKey(view)) {
            GalleryDetachedNavigation detached = GALLERY_DETACHED_NAVIGATION.get(view);
            state.snapshots.put(view, detached != null && detached.snapshot != null
                    ? detached.snapshot : new ViewSnapshot(view));
        }
    }

    /**
     * Keep the exact row attached to its original Navigator controller while moving it outside the
     * physical display. Gallery is intentionally moved below the screen using its current absolute
     * screen coordinates: its show animator can rebase Y/translationY after layout, so a one-time
     * translation is not stable there. Other hosts retain the established below-screen translation.
     */
    private static void moveExactNavigationRootOffscreen(View view) {
        runInternalVisibilityChange(() -> {
            try {
                clearChrome(view);
                if (view.getAlpha() != 0f) view.setAlpha(0f);
                if (view.getTranslationX() != 0f) view.setTranslationX(0f);
                State state = getSuppressedState(view);
                float translation = state != null && state.app == TargetApp.MI_GALLERY
                        ? galleryBottomOffscreenTranslation(view)
                        : offscreenTranslation(view);
                if (Math.abs(view.getTranslationY() - translation) > 0.5f) {
                    view.setTranslationY(translation);
                }
                if (view.isClickable()) view.setClickable(false);
                if (view.isLongClickable()) view.setLongClickable(false);
                if (view.isFocusable()) view.setFocusable(false);
                if (view.isFocusableInTouchMode()) view.setFocusableInTouchMode(false);
                if (view.getImportantForAccessibility()
                        != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                    view.setImportantForAccessibility(
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                }
                // Translation and alpha do not change measurement. requestLayout() here forced
                // XHS's deep feed hierarchy through another layout pass on every install retry.
            } catch (Throwable ignored) {}
        });
    }

    /**
     * Keep the exact controller object attached/callable, while making its visual row occupy no
     * layout space, draw no pixels and receive no real touch. Child listeners remain intact for
     * adapter-driven performClick/onClick fallbacks.
     */
    private static void collapseExactNavigationRoot(View view) {
        runInternalVisibilityChange(() -> {
            try {
                ViewGroup.LayoutParams raw = view.getLayoutParams();
                if (raw != null) {
                    raw.height = 0;
                    if (raw instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
                        margins.topMargin = 0;
                        margins.bottomMargin = 0;
                    }
                    view.setLayoutParams(raw);
                }
                view.setMinimumHeight(0);
                view.setPadding(0, 0, 0, 0);
                clearChrome(view);
                view.setAlpha(0f);
                State state = getSuppressedState(view);
                view.setTranslationY(state != null && state.app == TargetApp.MI_GALLERY
                        ? galleryBottomOffscreenTranslation(view)
                        : offscreenTranslation(view));
                view.setClickable(false);
                view.setLongClickable(false);
                view.setFocusable(false);
                view.setFocusableInTouchMode(false);
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                try { view.requestLayout(); } catch (Throwable ignored) {}
                try {
                    if (view.getParent() instanceof View) ((View) view.getParent()).requestLayout();
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        });
    }

    /**
     * Gallery continuously drives show/hide animations on its BottomNavigationView. Height zero and
     * alpha zero can still be overwritten by a late animator, and that View allows descendants to
     * draw outside their measured bounds. INVISIBLE is the final visual gate while keeping the
     * Navigator presenter/controller object attached for findTab/navigate/selectTab.
     */
    private static void hideGalleryNativeNavigation(View view) {
        if (view == null) return;
        runInternalVisibilityChange(() -> {
            try {
                try { view.animate().cancel(); } catch (Throwable ignored) {}
                try { view.clearAnimation(); } catch (Throwable ignored) {}
                stopMiuixFolmeAnimations(view);
                disableMiuixNavigationBlur(view);
                hideGalleryNavigationMenuView(view);
                // Recompute from the row's current physical position every time. setY(),
                // translationY animations and re-parent/layout changes therefore all converge on
                // the same result: the entire native row, including its top edge, is below the
                // bottom of the physical display.
                float translation = galleryBottomOffscreenTranslation(view);
                if (Math.abs(view.getTranslationY() - translation) > 0.5f) {
                    view.setTranslationY(translation);
                }
                if (view.getVisibility() != View.INVISIBLE) {
                    view.setVisibility(View.INVISIBLE);
                }
                if (view.getAlpha() != 0f) view.setAlpha(0f);
                view.invalidate();
            } catch (Throwable ignored) {}
        });
    }

    /**
     * Extend a verified page only through its bottom edge. Constraint-style 0dp content keeps its
     * original top anchor and receives a negative bottom margin; fixed/wrap content receives an
     * explicit original-rendered-height plus the native reservation. This avoids the old
     * parent-height overscan that also resized Bilibili HD's top channel container.
     */
    private static void applyBottomExtension(State state, View view, float extraDp) {
        if (state == null || view == null || extraDp <= 0f) return;
        try {
            ViewSnapshot snapshot = state.snapshots.get(view);
            if (snapshot == null) return;
            int extraPx = dp(view, extraDp);
            if (extraPx <= 0) return;
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (raw == null) return;
            if (raw instanceof ViewGroup.MarginLayoutParams && snapshot.height == 0) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
                int targetMargin = snapshot.bottomMargin - extraPx;
                if (margins.bottomMargin != targetMargin) margins.bottomMargin = targetMargin;
                if (raw.height != 0) raw.height = 0;
            } else {
                int base = snapshot.renderedHeight > 0
                        ? snapshot.renderedHeight
                        : Math.max(view.getHeight(), view.getMeasuredHeight());
                int targetHeight = Math.max(1, base + extraPx);
                if (raw.height != targetHeight) raw.height = targetHeight;
                if (raw instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) raw).bottomMargin = snapshot.bottomMargin;
                }
            }
            view.setLayoutParams(raw);
            // Some HD builds wrap the pager/recycler in one or two containers whose original
            // bottom still ends at the native shell. Allow the verified page to draw through that
            // vacated slot without resizing those ancestors (which would move the top channel row).
            View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
            for (int depth = 0; parent instanceof ViewGroup && depth < 5; depth++) {
                ViewGroup group = (ViewGroup) parent;
                try { group.setClipChildren(false); } catch (Throwable ignored) {}
                try { group.setClipToPadding(false); } catch (Throwable ignored) {}
                parent = group.getParent() instanceof View ? (View) group.getParent() : null;
            }
            view.requestLayout();
        } catch (Throwable ignored) {}
    }

    private static boolean bottomExtensionNeedsRepair(State state, View view, float extraDp) {
        try {
            ViewSnapshot snapshot = state.snapshots.get(view);
            if (snapshot == null) return false;
            int extraPx = dp(view, extraDp);
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (raw == null) return true;
            if (raw instanceof ViewGroup.MarginLayoutParams && snapshot.height == 0) {
                return raw.height != 0
                        || ((ViewGroup.MarginLayoutParams) raw).bottomMargin
                        != snapshot.bottomMargin - extraPx;
            }
            int targetHeight = Math.max(1, snapshot.renderedHeight + extraPx);
            return raw.height != targetHeight;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean bottomOverscanNeedsRepair(View view, float extraDp) {
        if (view == null || extraDp <= 0f) return false;
        try {
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (raw == null) return true;
            int baseHeight = 0;
            if (view.getParent() instanceof View) baseHeight = ((View) view.getParent()).getHeight();
            if (baseHeight <= 0 && view.getRootView() != null) baseHeight = view.getRootView().getHeight();
            if (baseHeight <= 0) baseHeight = view.getResources().getDisplayMetrics().heightPixels;
            int target = baseHeight + (int) (extraDp
                    * view.getResources().getDisplayMetrics().density + 0.5f);
            if (raw.height != target || Math.abs(view.getTranslationY()) > 0.5f) return true;
            if (raw instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
                return margins.topMargin != 0 || margins.bottomMargin != 0;
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void applyBottomOverscan(View view, float extraDp) {
        if (view == null || extraDp <= 0f) return;
        try {
            int baseHeight = 0;
            if (view.getParent() instanceof View) baseHeight = ((View) view.getParent()).getHeight();
            if (baseHeight <= 0 && view.getRootView() != null) baseHeight = view.getRootView().getHeight();
            if (baseHeight <= 0) baseHeight = view.getResources().getDisplayMetrics().heightPixels;
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (raw == null) return;
            int target = baseHeight + (int) (extraDp * view.getResources().getDisplayMetrics().density + 0.5f);
            boolean layoutChanged = false;
            if (raw.height != target) {
                raw.height = target;
                layoutChanged = true;
            }
            if (raw instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
                if (margins.topMargin != 0 || margins.bottomMargin != 0) {
                    margins.topMargin = 0;
                    margins.bottomMargin = 0;
                    layoutChanged = true;
                }
            }
            if (layoutChanged) {
                view.setLayoutParams(raw);
            }
            if (Math.abs(view.getTranslationY()) > 0.5f) view.setTranslationY(0f);
            if (layoutChanged) view.requestLayout();
        } catch (Throwable ignored) {}
    }

    private static void alignExactViewAboveGlass(State state, View view, float gapDp) {
        if (state == null || state.nav == null || view == null) return;
        Runnable action = () -> {
            try {
                if (!view.isAttachedToWindow() || !state.nav.isAttachedToWindow()
                        || view.getHeight() <= 0 || state.nav.getHeight() <= 0) return;
                View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
                if (parent == null) return;

                int[] navLocation = new int[2];
                state.nav.getLocationOnScreen(navLocation);

                float density = view.getResources().getDisplayMetrics().density;
                float pillTop = navLocation[1] + state.nav.getHeight()
                        - (56f + state.appSettings.bottomPaddingDp) * density;
                float targetBottom = pillTop - Math.max(0f, gapDp) * density;

                int[] parentLocation = new int[2];
                parent.getLocationOnScreen(parentLocation);
                float parentBottom = parentLocation[1] + parent.getHeight();
                int desiredBottomMargin = Math.max(0, Math.round(parentBottom - targetBottom));

                ViewGroup.LayoutParams raw = view.getLayoutParams();
                if (raw instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
                    if (margins.bottomMargin != desiredBottomMargin) {
                        margins.bottomMargin = desiredBottomMargin;
                        view.setLayoutParams(margins);
                        view.requestLayout();
                    }
                    if (Math.abs(view.getTranslationY()) > 0.5f) view.setTranslationY(0f);
                } else {
                    float untransformedBottom = parentLocation[1] + view.getTop() + view.getHeight();
                    float translation = targetBottom - untransformedBottom;
                    if (Math.abs(view.getTranslationY() - translation) > 0.5f) {
                        view.setTranslationY(translation);
                    }
                }
            } catch (Throwable ignored) {}
        };
        try { view.post(action); } catch (Throwable ignored) {}
        try { state.nav.post(action); } catch (Throwable ignored) {}
    }

    /**
     * Translation-based variant of alignExactViewAboveGlass for hosts whose shell has no usable
     * bottom constraint. The
     * translation is derived from current screen coordinates minus the previously applied offset,
     * so it converges on "shell bottom == glass pill top - gap" regardless of the host's own
     * layout/offset math.
     */
    private static void alignExactViewAboveGlassByTranslation(State state, View view, float gapDp) {
        if (state == null || state.nav == null || view == null) return;
        Runnable action = () -> applyAlignAboveGlassByTranslation(state, view, gapDp);
        try { view.post(action); } catch (Throwable ignored) {}
        try { state.nav.post(action); } catch (Throwable ignored) {}
    }

    private static void applyAlignAboveGlassByTranslation(State state, View view, float gapDp) {
        try {
            float last = state.alignAboveGlassTranslationApplied.containsKey(view)
                    ? state.alignAboveGlassTranslationApplied.get(view) : 0f;
            boolean douyinHiddenChatComposer = state.app == TargetApp.DOUYIN
                    && isNavigationExplicitlyHidden(state.activity)
                    && UniversalHostAdapter.isCachedAwemeChatInput(view);
            boolean visible = view.isAttachedToWindow()
                    && view.isShown()
                    && view.getAlpha() > 0.01f
                    && view.getHeight() > 0
                    && (douyinHiddenChatComposer
                    || (state.nav.isAttachedToWindow() && state.nav.getHeight() > 0));
            if (!visible) {
                if (Math.abs(view.getTranslationY()) > 0.5f) view.setTranslationY(0f);
                state.alignAboveGlassTranslationApplied.put(view, 0f);
                return;
            }

            int[] viewLocation = new int[2];
            view.getLocationOnScreen(viewLocation);
            float density = view.getResources().getDisplayMetrics().density;
            float targetBottom;
            if (douyinHiddenChatComposer) {
                // 26.76 extends MainActivity's content through the native bottom-tab slot. When
                // an in-main Rips chat replaces the feed, its composer inherits that extra height
                // and can fall below the usable screen after LiquidGlass itself is hidden. Keep the
                // exact cached IM input shell above the real navigation/IME inset instead of
                // aligning it against a GONE glass window. This fixes both gesture-navigation and
                // keyboard-open cases without touching android.R.id.content or the 26.76 feed path.
                View decor = state.activity.getWindow() == null ? null
                        : state.activity.getWindow().getDecorView();
                int[] decorLocation = new int[2];
                float screenBottom = view.getResources().getDisplayMetrics().heightPixels;
                int safeBottomInset = 0;
                if (decor != null) {
                    decor.getLocationOnScreen(decorLocation);
                    if (decor.getHeight() > 0) screenBottom = decorLocation[1] + decor.getHeight();
                    WindowInsets insets = decor.getRootWindowInsets();
                    if (insets != null) {
                        android.graphics.Insets safe = insets.getInsets(
                                WindowInsets.Type.navigationBars() | WindowInsets.Type.ime()
                        );
                        safeBottomInset = Math.max(0, safe.bottom);
                    }
                }
                targetBottom = screenBottom - safeBottomInset - 8f * density;
            } else {
                int[] navLocation = new int[2];
                state.nav.getLocationOnScreen(navLocation);
                float pillTop = navLocation[1] + state.nav.getHeight()
                        - (56f + state.appSettings.bottomPaddingDp) * density;
                targetBottom = pillTop - Math.max(0f, gapDp) * density;
            }
            float baseBottom = viewLocation[1] + view.getHeight() - last;
            float translation = targetBottom - baseBottom;
            if (douyinHiddenChatComposer) {
                // Never push the IM composer farther down. If Douyin already keeps it above the
                // safe inset, leave the native position intact; only compensate when 26.76's
                // content overscan has actually placed its bottom below the usable area.
                translation = Math.min(0f, translation);
            }
            if (Math.abs(view.getTranslationY() - translation) > 0.5f) {
                view.setTranslationY(translation);
            }
            state.alignAboveGlassTranslationApplied.put(view, translation);
        } catch (Throwable ignored) {}
    }

    /**
     * One pre-draw listener per active State. Hosts continuously re-apply their own translations,
     * animations and visibility after the suppression plan has been installed; the layout guard
     * only fires on layout passes, so off-screen/collapsed/cleared rows can visibly return between
     * polls. Re-asserting the plan before each frame keeps XHS/Meituan native bars and Apple
     * Music's mini player in their suppressed positions without any allocation-heavy work.
     */
    private static void attachSuppressionFrameGuard(State state) {
        if (state == null || state.nav == null) return;
        View root = state.nav.getRootView();
        if (root == null) {
            try {
                root = state.activity.getWindow().getDecorView();
            } catch (Throwable ignored) {}
        }
        if (root == null) return;
        if (state.suppressionFrameGuard != null && state.suppressionFrameGuardRoot == root) {
            return;
        }
        detachSuppressionFrameGuard(state);
        ViewTreeObserver.OnPreDrawListener listener = () -> {
            if (state.nav == null || !state.nav.isAttachedToWindow()) return true;
            try {
                for (View view : state.extendBottomDp.keySet()) {
                    if (view != null && bottomExtensionNeedsRepair(
                            state, view, state.extendBottomDp.get(view))) {
                        applyBottomExtension(state, view, state.extendBottomDp.get(view));
                    }
                }
                // Compose may restore the AndroidComposeView height during a later composition
                // without emitting a layout callback on the guarded instance. Reassert UU's
                // bottom overscan immediately before draw so its native NavigationBar stays below
                // the window while page pixels still reach the glass strip.
                for (Map.Entry<View, Float> entry : state.overscanBottomDp.entrySet()) {
                    View view = entry.getKey();
                    if (view != null && bottomOverscanNeedsRepair(view, entry.getValue())) {
                        applyBottomOverscan(view, entry.getValue());
                    }
                }
                for (View view : state.alignAboveGlassTranslationGapDp.keySet()) {
                    if (view != null) {
                        applyAlignAboveGlassByTranslation(
                                state, view, state.alignAboveGlassTranslationGapDp.get(view)
                        );
                    }
                }
                for (View view : state.offscreenRoots) {
                    if (view != null && offscreenSuppressionNeedsRepair(state, view)) {
                        moveExactNavigationRootOffscreen(view);
                        if (state.app == TargetApp.MI_GALLERY) {
                            hideGalleryNativeNavigation(view);
                        }
                    }
                }
                for (View view : state.collapsedRoots) {
                    if (view != null && collapsedSuppressionNeedsRepair(state, view)) {
                        collapseExactNavigationRoot(view);
                    }
                }
                for (View view : state.clearRoots) {
                    if (view != null && clearSuppressionNeedsRepair(view)) {
                        clearExactPlate(view);
                    }
                }
                if (state.app == TargetApp.XHS) {
                    long now = SystemClock.uptimeMillis();
                    if (now - state.lastXhsBarScanAtMs >= 800L) {
                        state.lastXhsBarScanAtMs = now;
                        suppressNewXhsBars(state);
                    }
                } else if (state.app == TargetApp.MEITUAN) {
                    long now = SystemClock.uptimeMillis();
                    if (now - state.lastXhsBarScanAtMs >= 800L) {
                        state.lastXhsBarScanAtMs = now;
                        refreshMeituanSuppression(state);
                    }
                }
            } catch (Throwable ignored) {}
            return true;
        };
        try {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (!observer.isAlive()) return;
            observer.addOnPreDrawListener(listener);
            state.suppressionFrameGuard = listener;
            state.suppressionFrameGuardRoot = root;
        } catch (Throwable ignored) {}
    }

    private static void detachSuppressionFrameGuard(State state) {
        if (state == null) return;
        ViewTreeObserver.OnPreDrawListener listener = state.suppressionFrameGuard;
        View root = state.suppressionFrameGuardRoot;
        state.suppressionFrameGuard = null;
        state.suppressionFrameGuardRoot = null;
        if (listener == null || root == null) return;
        try {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(listener);
        } catch (Throwable ignored) {}
    }

    private static boolean offscreenSuppressionNeedsRepair(State state, View view) {
        try {
            if (state.app == TargetApp.MI_GALLERY) {
                return galleryNativeNavigationNeedsRepair(view);
            }
            return view.getAlpha() > 0.01f
                    || view.getTranslationY() < offscreenTranslation(view) * 0.75f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean collapsedSuppressionNeedsRepair(State state, View view) {
        try {
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (state.app == TargetApp.MI_GALLERY) {
                return galleryNativeNavigationNeedsRepair(view);
            }
            return raw == null || raw.height != 0 || view.getAlpha() > 0.01f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean clearSuppressionNeedsRepair(View view) {
        try {
            return view.getVisibility() != View.INVISIBLE
                    || view.getAlpha() > 0.01f
                    || view.isClickable()
                    || view.isFocusable();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * XHS attaches the shop fragment's mall TabBarView lazily; a tab switch can surface a bar that
     * was not present when the suppression plan was built. Re-scan the decor on a low frequency and
     * park any new verified row immediately.
     */
    private static void suppressNewXhsBars(State state) {
        if (state == null || state.app != TargetApp.XHS || state.activity == null) return;
        try {
            View root = state.activity.getWindow().getDecorView();
            if (root == null) return;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 5000) {
                View view = queue.removeFirst();
                String name = view.getClass().getName();
                if (("com.xingin.xhs.homepage.tabbar.TabBarView".equals(name)
                        || "com.xingin.commercial.shop.malltabbar.TabBarView".equals(name))
                        && view.isAttachedToWindow()
                        && view.getWidth() > 0
                        && view.getHeight() > 0
                        && !state.offscreenRoots.contains(view)
                        && !state.snapshots.containsKey(view)) {
                    snapshot(state, view);
                    state.offscreenRoots.add(view);
                    markSuppressed(view, state);
                    ensureSuppressionLayoutGuard(view);
                    moveExactNavigationRootOffscreen(view);
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.addLast(group.getChildAt(i));
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Meituan's Mach layers are ViewStubs in more than the home fragment. Inflation replaces a
     * Stub in-place and several replacement roots have no id, so findViewById() cannot discover
     * them after the first layout. Keep a small identity map of each Stub's immediate parent and
     * child slot, then observe only those parents for a replacement.
     */
    private static void ensureMeituanLateLayerGuards(final State state) {
        if (state == null || state.app != TargetApp.MEITUAN || state.activity == null) return;
        try {
            Set<ViewGroup> activeParents = collectMeituanLateLayerParents(state);
            rememberMeituanLateLayerSlots(state, activeParents);

            // A replacement root no longer has the Stub id, but its recorded parent/slot remains
            // the authoritative handle. Keep attached recorded parents in the active set until
            // the host actually detaches the old Fragment tree.
            for (ViewGroup parent : new ArrayList<>(state.meituanLateLayerSlots.keySet())) {
                if (parent == null) continue;
                try {
                    if (parent.isAttachedToWindow()) activeParents.add(parent);
                } catch (Throwable ignored) {}
            }
            pruneMeituanLateLayerGuards(state, activeParents);
            for (ViewGroup parent : activeParents) {
                ensureMeituanLateLayerParentGuard(state, parent);
            }
            refreshMeituanInflatedLayers(state);
        } catch (Throwable ignored) {}
    }

    private static Set<ViewGroup> collectMeituanLateLayerParents(State state) {
        Set<ViewGroup> parents = Collections.newSetFromMap(
                new IdentityHashMap<ViewGroup, Boolean>()
        );
        if (state == null || state.activity == null) return parents;
        try {
            View pager = findMeituanId(state, "pager_container");
            ViewGroup pagerParent = asViewGroupParent(pager);
            if (pagerParent != null) parents.add(pagerParent);

            View home = pager == null ? findMeituanId(state, "wm_page_main_home")
                    : findMeituanDescendant(pager, state, "wm_page_main_home");
            if (home instanceof ViewGroup) parents.add((ViewGroup) home);
            View remind = home == null ? findMeituanId(state, "remind_container")
                    : findMeituanDescendant(home, state, "remind_container");
            if (remind instanceof ViewGroup) parents.add((ViewGroup) remind);

            // Activity-level Mach stubs live in this content shell in takeout_activity_main.
            View navigationShell = findMeituanId(
                    state, "navigation_bar_margin_layout_container"
            );
            if (navigationShell instanceof ViewGroup) parents.add((ViewGroup) navigationShell);
            ViewGroup navigationParent = asViewGroupParent(navigationShell);
            if (navigationParent != null) parents.add(navigationParent);

            // KingKong pages put layout_float_footer beside fl_king_kong_container. Observe both
            // possible immediate parents because the XML wrapper differs between destinations.
            View kingKong = findMeituanId(state, "fl_king_kong_container");
            if (kingKong instanceof ViewGroup) parents.add((ViewGroup) kingKong);
            ViewGroup kingParent = asViewGroupParent(kingKong);
            if (kingParent != null) parents.add(kingParent);

            // If a host moved one of the stubs into a version-specific wrapper, its own parent is
            // still exact and cheap to resolve by id. This also covers an already-inflated root
            // whose id was retained by the layout XML.
            for (String name : MEITUAN_LATE_LAYER_STUB_NAMES) {
                View target = findMeituanId(state, name);
                ViewGroup parent = asViewGroupParent(target);
                if (parent != null) parents.add(parent);
            }
            View remindRoot = findMeituanId(state, "layout_remind");
            ViewGroup remindRootParent = asViewGroupParent(remindRoot);
            if (remindRootParent != null) parents.add(remindRootParent);
        } catch (Throwable ignored) {}
        return parents;
    }

    private static View findMeituanId(State state, String name) {
        if (state == null || state.activity == null || name == null) return null;
        try {
            int id = state.activity.getResources().getIdentifier(
                    name, "id", state.app.packageName
            );
            return id == 0 ? null : state.activity.findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View findMeituanDescendant(View root, State state, String name) {
        if (root == null || state == null || state.activity == null || name == null) return null;
        try {
            int id = state.activity.getResources().getIdentifier(
                    name, "id", state.app.packageName
            );
            return id == 0 ? null : root.findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ViewGroup asViewGroupParent(View view) {
        try {
            return view != null && view.getParent() instanceof ViewGroup
                    ? (ViewGroup) view.getParent() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void rememberMeituanLateLayerSlots(
            State state,
            Set<ViewGroup> candidateParents
    ) {
        if (state == null || state.activity == null) return;
        try {
            for (String name : MEITUAN_LATE_LAYER_STUB_NAMES) {
                View target = findMeituanId(state, name);
                rememberMeituanLateLayerSlot(state, name, target);
                if (candidateParents != null) {
                    for (ViewGroup parent : candidateParents) {
                        View nested = findMeituanDescendant(parent, state, name);
                        rememberMeituanLateLayerSlot(state, name, nested);
                    }
                }
            }
            // wm_page_home_remind_layer inflates a root named layout_remind rather than retaining
            // the Stub id. Record the alias while it is still present with either id.
            View remindRoot = findMeituanId(state, "layout_remind");
            rememberMeituanLateLayerSlot(state, "wm_page_home_remind_layer", remindRoot);
        } catch (Throwable ignored) {}
    }

    private static void rememberMeituanLateLayerSlot(
            State state,
            String name,
            View target
    ) {
        if (state == null || name == null || target == null) return;
        try {
            ViewGroup parent = asViewGroupParent(target);
            if (parent == null) return;
            int index = parent.indexOfChild(target);
            if (index < 0) return;
            Map<String, Integer> slots = state.meituanLateLayerSlots.get(parent);
            if (slots == null) {
                slots = new java.util.HashMap<>();
                state.meituanLateLayerSlots.put(parent, slots);
            }
            // A ViewStub replacement stays in the same slot. Do not overwrite the first observed
            // slot with a later descendant that happens to reuse the same resource id.
            if (!slots.containsKey(name)) slots.put(name, index);
        } catch (Throwable ignored) {}
    }

    /** Drop listeners owned by a previous home Fragment before binding the current one. */
    private static void pruneMeituanLateLayerGuards(
            State state,
            Set<ViewGroup> activeParents
    ) {
        if (state == null) return;
        for (Map.Entry<ViewGroup, View.OnLayoutChangeListener> entry
                : new ArrayList<>(state.meituanLateLayerGuards.entrySet())) {
            ViewGroup parent = entry.getKey();
            boolean keep = parent != null && activeParents != null && activeParents.contains(parent);
            if (keep) {
                try { keep = parent.isAttachedToWindow(); } catch (Throwable ignored) { keep = false; }
            }
            if (keep) continue;
            View.OnLayoutChangeListener listener = entry.getValue();
            if (parent != null && listener != null) {
                try { parent.removeOnLayoutChangeListener(listener); } catch (Throwable ignored) {}
            }
            state.meituanLateLayerGuards.remove(parent);
        }
        for (ViewGroup parent : new ArrayList<>(state.meituanHomeMaskSlots.keySet())) {
            if (parent == null || activeParents == null || !activeParents.contains(parent)) {
                state.meituanHomeMaskSlots.remove(parent);
            }
        }
        for (ViewGroup parent : new ArrayList<>(state.meituanLateLayerSlots.keySet())) {
            if (parent == null || activeParents == null || !activeParents.contains(parent)) {
                state.meituanLateLayerSlots.remove(parent);
            }
        }
    }

    private static void ensureMeituanLateLayerParentGuard(
            final State state,
            final ViewGroup parent
    ) {
        if (state == null || parent == null || state.meituanLateLayerGuards.containsKey(parent)) {
            return;
        }
        View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(
                    View changed,
                    int left,
                    int top,
                    int right,
                    int bottom,
                    int oldLeft,
                    int oldTop,
                    int oldRight,
                    int oldBottom
            ) {
                if (state.nav == null || !state.nav.isAttachedToWindow()) return;
                // The callback runs after ViewStub has replaced itself in the parent. The helper
                // only checks two direct/ID-based candidates, so it is safe on layout callbacks.
                refreshMeituanInflatedLayers(state);
            }
        };
        try {
            parent.addOnLayoutChangeListener(listener);
            state.meituanLateLayerGuards.put(parent, listener);
        } catch (Throwable ignored) {}
    }

    private static void rememberMeituanHomeMaskSlot(State state, ViewGroup home) {
        if (state == null || home == null || state.meituanHomeMaskSlots.containsKey(home)) return;
        try {
            int maskId = state.activity.getResources().getIdentifier(
                    "wm_page_home_mask_layer", "id", state.app.packageName
            );
            View stub = maskId == 0 ? null : home.findViewById(maskId);
            if (stub != null && stub.getParent() == home) {
                state.meituanHomeMaskSlots.put(home, home.indexOfChild(stub));
            }
        } catch (Throwable ignored) {}
        rememberMeituanLateLayerSlot(state, "wm_page_home_mask_layer", homeChildById(state, home,
                "wm_page_home_mask_layer"));
    }

    private static View homeChildById(State state, ViewGroup home, String name) {
        return state == null || home == null ? null : findMeituanDescendant(home, state, name);
    }

    /** Resolve late ViewStub roots without scanning order-item descendants. */
    private static Set<View> findMeituanInflatedLayers(State state) {
        Set<View> found = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        if (state == null || state.activity == null) return found;
        try {
            Set<ViewGroup> parents = collectMeituanLateLayerParents(state);
            rememberMeituanLateLayerSlots(state, parents);

            // Roots that retain an id are cheap to resolve directly. Do not accept ViewStub
            // objects here: the adapter may still be holding the old Stub while its replacement
            // is being installed in the same frame.
            for (String name : MEITUAN_LATE_LAYER_STUB_NAMES) {
                View direct = findMeituanId(state, name);
                if (isMeituanLateLayerCandidate(state, name, direct, asViewGroupParent(direct))) {
                    found.add(direct);
                }
            }
            View remindRoot = findMeituanId(state, "layout_remind");
            if (isMeituanLateLayerCandidate(
                    state, "wm_page_home_remind_layer", remindRoot,
                    asViewGroupParent(remindRoot))) {
                found.add(remindRoot);
            }

            // Id-less replacement roots are resolved only through the recorded parent/slot. This
            // is deliberately narrower than a bottom-screen hierarchy scan: an ordinary order
            // card can be a FrameLayout too, but it cannot occupy this exact Stub slot.
            for (Map.Entry<ViewGroup, Map<String, Integer>> entry
                    : new ArrayList<>(state.meituanLateLayerSlots.entrySet())) {
                ViewGroup parent = entry.getKey();
                Map<String, Integer> slots = entry.getValue();
                if (parent == null || slots == null) continue;
                for (Map.Entry<String, Integer> slot : new ArrayList<>(slots.entrySet())) {
                    String name = slot.getKey();
                    Integer index = slot.getValue();
                    if (index == null || index < 0 || index >= parent.getChildCount()) continue;
                    View candidate = parent.getChildAt(index);
                    if (isMeituanLateLayerCandidate(state, name, candidate, parent)) {
                        found.add(candidate);
                    }
                }
            }

            // Preserve the older stable-neighbour fallback for an install that starts after the
            // home Stub was already inflated and no slot could be recorded.
            View pager = findMeituanId(state, "pager_container");
            View home = pager == null ? findMeituanId(state, "wm_page_main_home")
                    : findMeituanDescendant(pager, state, "wm_page_main_home");
            if (home instanceof ViewGroup) {
                View mask = findMeituanHomeMaskReplacement(state, (ViewGroup) home);
                if (mask != null) found.add(mask);
            }
        } catch (Throwable ignored) {}
        return found;
    }

    private static boolean isMeasuredAttachedMeituanLayer(View view) {
        try {
            return view != null
                    && !(view instanceof ViewStub)
                    && view.isAttachedToWindow()
                    && view.getWidth() > 0
                    && view.getHeight() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isMeituanLateLayerCandidate(
            State state,
            String name,
            View candidate,
            ViewGroup parent
    ) {
        if (!isMeasuredAttachedMeituanLayer(candidate)) return false;
        if (parent != null) {
            try {
                if (candidate.getParent() != parent) return false;
            } catch (Throwable ignored) {
                return false;
            }
        }
        if ("promotion_tab_layer".equals(name)) {
            return isMeituanPromotionLayer(candidate, parent);
        }
        if ("wm_page_home_remind_layer".equals(name)
                || "layout_remind".equals(name)) {
            int expected = state == null || state.activity == null ? 0
                    : state.activity.getResources().getIdentifier(
                    "layout_remind", "id", state.app.packageName
            );
            return expected == 0 || candidate.getId() == expected;
        }
        // deep_eat_ai_container, feedback*, wm_page_home_mask_layer and layout_float_footer all
        // inflate wm_mach_container.xml in the supplied APK: plain id-less FrameLayouts. Keep the
        // exact class signature so an unrelated custom card in a nearby slot is never blanked.
        if (!isLikelyMeituanMachRoot(candidate)) return false;
        return hasMeituanFullWidthGeometry(candidate, parent, false);
    }

    private static boolean isMeituanPromotionLayer(View candidate, ViewGroup parent) {
        try {
            String name = candidate.getClass().getName();
            if (!(name.endsWith(".TabLayerCircleLayout")
                    || "TabLayerCircleLayout".equals(name))) return false;
            return hasMeituanFullWidthGeometry(candidate, parent, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasMeituanFullWidthGeometry(
            View candidate,
            ViewGroup parent,
            boolean requireTall
    ) {
        if (candidate == null) return false;
        try {
            ViewGroup.LayoutParams lp = candidate.getLayoutParams();
            boolean matchWidth = lp != null
                    && lp.width == ViewGroup.LayoutParams.MATCH_PARENT;
            int parentWidth = parent == null ? 0
                    : Math.max(parent.getWidth(), parent.getMeasuredWidth());
            if (!matchWidth && parentWidth > 0
                    && candidate.getWidth() < (int) (parentWidth * 0.80f)) return false;
            if (requireTall && parent != null) {
                int parentHeight = Math.max(parent.getHeight(), parent.getMeasuredHeight());
                if (parentHeight > 0
                        && candidate.getHeight() < (int) (parentHeight * 0.70f)) return false;
            }
            return matchWidth || parentWidth <= 0 || candidate.getWidth() >= (int) (parentWidth * 0.80f);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * wm_page_home_mask_layer.xml uses the generic wm_mach_container root, which has no id. The
     * ViewStub's child slot is stable in the supplied 8.81.2 layout; verify its MATCH_PARENT
     * geometry before treating the replacement as a mask so normal home overlays remain intact.
     */
    private static View findMeituanHomeMaskReplacement(State state, ViewGroup home) {
        if (state == null || home == null) return null;
        try {
            int maskId = state.activity.getResources().getIdentifier(
                    "wm_page_home_mask_layer", "id", state.app.packageName
            );
            View byId = maskId == 0 ? null : home.findViewById(maskId);
            if (isMeituanHomeMaskCandidate(byId, home)) return byId;

            Integer recorded = state.meituanHomeMaskSlots.get(home);
            if (recorded != null) {
                int slot = recorded;
                if (slot >= 0 && slot < home.getChildCount()) {
                    View candidate = home.getChildAt(slot);
                    if (isMeituanHomeMaskCandidate(candidate, home)) return candidate;
                }
            }

            // If installation starts after the ViewStub was already inflated, the old id and
            // recorded slot are both unavailable. Derive the slot from the two stable neighbors
            // around the mask stub instead of blindly treating child 14 as a Mach layer.
            int bubbleId = state.activity.getResources().getIdentifier(
                    "wm_page_home_action_bar_bubble", "id", state.app.packageName
            );
            int addressId = state.activity.getResources().getIdentifier(
                    "address_suggestion_container_vs", "id", state.app.packageName
            );
            int bubbleIndex = directChildIndex(home, bubbleId);
            int addressIndex = directChildIndex(home, addressId);
            int candidateIndex = -1;
            if (bubbleIndex >= 0 && bubbleIndex + 1 < home.getChildCount()
                    && (addressIndex < 0 || bubbleIndex + 1 < addressIndex)) {
                candidateIndex = bubbleIndex + 1;
            } else if (addressIndex > 0) {
                candidateIndex = addressIndex - 1;
            }
            if (candidateIndex >= 0 && candidateIndex < home.getChildCount()) {
                View candidate = home.getChildAt(candidateIndex);
                if (isMeituanHomeMaskCandidate(candidate, home)
                        && isLikelyMeituanMachRoot(candidate)) return candidate;
            }

            // Both neighboring stubs can also have inflated and dropped their ids. The supplied
            // 8.81.2 layout places the mask at child 14; retain that as a last resort only when the
            // replacement still has the id-less plain-FrameLayout Mach signature above.
            final int knownMaskSlot = 14;
            if (knownMaskSlot < home.getChildCount()) {
                View candidate = home.getChildAt(knownMaskSlot);
                if (isMeituanHomeMaskCandidate(candidate, home)
                        && isLikelyMeituanMachRoot(candidate)) return candidate;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int directChildIndex(ViewGroup parent, int id) {
        if (parent == null || id == 0 || id == View.NO_ID) return -1;
        try {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child != null && child.getId() == id) return i;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static boolean isLikelyMeituanMachRoot(View candidate) {
        if (!(candidate instanceof FrameLayout)) return false;
        try {
            // wm_mach_container.xml is a plain, id-less FrameLayout. Other full-screen stubs in
            // WmHomeRooView carry their own resource id, so this structural check avoids clearing
            // an empty/second-floor page when the mask stub was inflated before we attached.
            return candidate.getId() == View.NO_ID
                    && "android.widget.FrameLayout".equals(candidate.getClass().getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isMeituanHomeMaskCandidate(View candidate, ViewGroup home) {
        if (!isMeasuredAttachedMeituanLayer(candidate) || candidate == home
                || candidate instanceof ViewStub) return false;
        try {
            ViewGroup.LayoutParams lp = candidate.getLayoutParams();
            if (lp == null || lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                    || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) return false;
            int homeWidth = Math.max(home.getWidth(), home.getMeasuredWidth());
            int homeHeight = Math.max(home.getHeight(), home.getMeasuredHeight());
            if (homeWidth <= 0 || homeHeight <= 0) return false;
            return candidate.getWidth() >= (int) (homeWidth * 0.85f)
                    && candidate.getHeight() >= (int) (homeHeight * 0.75f);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Register a late root with the same snapshot/repair path as the initial suppression plan. */
    private static void registerMeituanDynamicClearView(State state, View view) {
        if (state == null || view == null || view == state.nav) return;
        try {
            if (!view.isAttachedToWindow()) return;
        } catch (Throwable ignored) {
            return;
        }
        if (!state.meituanDynamicClearRoots.add(view)) {
            state.clearRoots.add(view);
            if (clearSuppressionNeedsRepair(view)) clearExactPlate(view);
            return;
        }
        snapshot(state, view);
        state.clearRoots.add(view);
        markSuppressed(view, state);
        ensureSuppressionLayoutGuard(view);
        clearExactPlate(view);
    }

    private static void refreshMeituanInflatedLayers(State state) {
        if (state == null || state.app != TargetApp.MEITUAN) return;
        for (View view : findMeituanInflatedLayers(state)) {
            registerMeituanDynamicClearView(state, view);
        }
    }

    private static void detachMeituanLateLayerGuards(State state) {
        if (state == null) return;
        for (Map.Entry<ViewGroup, View.OnLayoutChangeListener> entry
                : new ArrayList<>(state.meituanLateLayerGuards.entrySet())) {
            ViewGroup parent = entry.getKey();
            View.OnLayoutChangeListener listener = entry.getValue();
            if (parent != null && listener != null) {
                try { parent.removeOnLayoutChangeListener(listener); } catch (Throwable ignored) {}
            }
        }
        state.meituanLateLayerGuards.clear();
        state.meituanLateLayerSlots.clear();
        state.meituanHomeMaskSlots.clear();
    }

    /** Re-resolve the original promotion layer after its ViewStub is inflated. */
    private static void refreshMeituanSuppression(State state) {
        if (state == null || state.app != TargetApp.MEITUAN || state.activity == null) return;
        try {
            int id = state.activity.getResources().getIdentifier(
                    "promotion_tab_layer", "id", state.app.packageName
            );
            if (id == 0) return;
            View layer = state.activity.findViewById(id);
            if (layer == null || state.clearRoots.contains(layer)
                    || state.snapshots.containsKey(layer)) return;
            snapshot(state, layer);
            state.clearRoots.add(layer);
            markSuppressed(layer, state);
            ensureSuppressionLayoutGuard(layer);
            clearExactPlate(layer);
        } catch (Throwable ignored) {}
    }

    /**
     * Extend Contacts' dialer upward instead of translating its complete root. The key container
     * receives the same extra height and bottom padding, so its 4x3 content moves up rigidly while
     * the following bottom_container remains at its original absolute Y. CallButtonLayout receives
     * the same bottom padding; its action moves up, but the left/right dialer controls never move.
     */
    private static void expandPhoneDialerAboveGlass(
            State state,
            UniversalHostAdapter.SuppressionPlan.PhoneDialerExpansion expansion
    ) {
        if (state == null || state.nav == null || expansion == null
                || expansion.anchor == null
                || expansion.keysContainer == null
                || expansion.callContainer == null) return;
        Runnable action = () -> {
            try {
                if (!state.phoneDialerExpansions.contains(expansion)
                        || !expansion.anchor.isAttachedToWindow()
                        || !expansion.anchor.isShown()
                        || expansion.anchor.getHeight() <= 0
                        || !expansion.keysContainer.isAttachedToWindow()
                        || !expansion.callContainer.isAttachedToWindow()
                        || !state.nav.isAttachedToWindow()
                        || state.nav.getHeight() <= 0) return;

                int[] navLocation = new int[2];
                int[] anchorLocation = new int[2];
                int[] callLocation = new int[2];
                state.nav.getLocationOnScreen(navLocation);
                expansion.anchor.getLocationOnScreen(anchorLocation);
                expansion.callContainer.getLocationOnScreen(callLocation);

                float density = expansion.anchor.getResources().getDisplayMetrics().density;
                float pillTop = navLocation[1] + state.nav.getHeight()
                        - (56f + state.appSettings.bottomPaddingDp) * density;
                float targetBottom = pillTop - Math.max(0f, expansion.gapDp) * density;

                ViewSnapshot keysSnapshot = state.snapshots.get(expansion.keysContainer);
                ViewSnapshot callSnapshot = state.snapshots.get(expansion.callContainer);
                if (keysSnapshot == null || callSnapshot == null) return;

                int currentCallInset = Math.max(
                        0,
                        expansion.callContainer.getPaddingBottom() - callSnapshot.paddingBottom
                );
                float callBottom = callLocation[1] + expansion.callContainer.getHeight();
                PhoneDialerGeometry geometry =
                        state.phoneDialerGeometry.get(expansion.callContainer);
                if (geometry == null || geometry.anchor != expansion.anchor) {
                    float originalBottomOffset;
                    if (expansion.anchor == expansion.callContainer) {
                        // The verified fallback anchor is full-width. Approximate the action's lower
                        // edge from the stable container bottom and its original bottom padding.
                        originalBottomOffset = -callSnapshot.paddingBottom;
                    } else {
                        originalBottomOffset = anchorLocation[1]
                                + expansion.anchor.getHeight()
                                + currentCallInset
                                - callBottom;
                    }
                    geometry = new PhoneDialerGeometry(
                            expansion.anchor, originalBottomOffset
                    );
                    state.phoneDialerGeometry.put(expansion.callContainer, geometry);
                }
                float originalAnchorBottom = callBottom + geometry.originalBottomOffset;
                final int extra = Math.max(
                        0,
                        (int) Math.ceil(originalAnchorBottom - targetBottom)
                );

                int currentKeysInset = Math.max(
                        0,
                        expansion.keysContainer.getPaddingBottom() - keysSnapshot.paddingBottom
                );
                int baseKeysHeight = keysSnapshot.height > 0
                        ? keysSnapshot.height : keysSnapshot.renderedHeight;
                if (baseKeysHeight <= 0) {
                    baseKeysHeight = Math.max(
                            expansion.keysContainer.getHeight(),
                            expansion.keysContainer.getMeasuredHeight()
                    ) - currentKeysInset;
                }
                if (baseKeysHeight <= 0) return;
                final int originalKeysHeight = baseKeysHeight;
                runInternalVisibilityChange(() -> {
                    boolean keysChanged = false;
                    ViewGroup.LayoutParams keysParams =
                            expansion.keysContainer.getLayoutParams();
                    int wantedKeysHeight = originalKeysHeight + extra;
                    if (keysParams != null && keysParams.height != wantedKeysHeight) {
                        keysParams.height = wantedKeysHeight;
                        keysChanged = true;
                    }
                    int wantedKeysPaddingBottom = keysSnapshot.paddingBottom + extra;
                    if (expansion.keysContainer.getPaddingBottom()
                            != wantedKeysPaddingBottom) {
                        expansion.keysContainer.setPadding(
                                expansion.keysContainer.getPaddingLeft(),
                                expansion.keysContainer.getPaddingTop(),
                                expansion.keysContainer.getPaddingRight(),
                                wantedKeysPaddingBottom
                        );
                        keysChanged = true;
                    }
                    if (keysChanged) {
                        if (keysParams != null) {
                            expansion.keysContainer.setLayoutParams(keysParams);
                        }
                        expansion.keysContainer.requestLayout();
                    }

                    int wantedCallPaddingBottom = callSnapshot.paddingBottom + extra;
                    if (expansion.callContainer.getPaddingBottom()
                            != wantedCallPaddingBottom) {
                        expansion.callContainer.setPadding(
                                expansion.callContainer.getPaddingLeft(),
                                expansion.callContainer.getPaddingTop(),
                                expansion.callContainer.getPaddingRight(),
                                wantedCallPaddingBottom
                        );
                        expansion.callContainer.requestLayout();
                    }
                });
            } catch (Throwable ignored) {}
        };
        try { expansion.anchor.post(action); } catch (Throwable ignored) {}
        try { expansion.keysContainer.post(action); } catch (Throwable ignored) {}
        try { state.nav.post(action); } catch (Throwable ignored) {}
    }

    private static UniversalHostAdapter.SuppressionPlan.PhoneDialerExpansion
    phoneDialerExpansionForView(
            State state,
            View view
    ) {
        if (state == null || view == null) return null;
        for (UniversalHostAdapter.SuppressionPlan.PhoneDialerExpansion expansion
                : state.phoneDialerExpansions) {
            if (expansion != null
                    && (expansion.keysContainer == view || expansion.callContainer == view)) {
                return expansion;
            }
        }
        return null;
    }

    private static void clearExactPlate(View view) {
        runInternalVisibilityChange(() -> {
            try {
                clearChrome(view);
                view.setAlpha(0f);
                view.setVisibility(View.INVISIBLE);
                view.setClickable(false);
                view.setLongClickable(false);
                view.setFocusable(false);
                view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            } catch (Throwable ignored) {}
        });
    }

    private static void zeroBottomMargin(View view) {
        try {
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
            if (margins.bottomMargin != 0) {
                margins.bottomMargin = 0;
                view.setLayoutParams(margins);
                view.requestLayout();
            }
        } catch (Throwable ignored) {}
    }

    private static void zeroBottomPadding(View view) {
        if (view == null) return;
        try {
            if (view.getPaddingBottom() != 0) {
                view.setPadding(
                        view.getPaddingLeft(), view.getPaddingTop(),
                        view.getPaddingRight(), 0
                );
                view.requestLayout();
            }
        } catch (Throwable ignored) {}
    }

    private static void clearChrome(View view) {
        try { view.setBackground(null); } catch (Throwable ignored) {}
        try { view.setForeground(null); } catch (Throwable ignored) {}
        try { view.setElevation(0f); } catch (Throwable ignored) {}
        try { view.setTranslationZ(0f); } catch (Throwable ignored) {}
        try { view.setStateListAnimator(null); } catch (Throwable ignored) {}
    }

    public static void onSuppressedViewMutated(View view) {
        if (view == null || Boolean.TRUE.equals(INTERNAL_VISIBILITY_CHANGE.get())) return;
        State state = getSuppressedState(view);
        if (state == null) return;
        try {
            view.post(() -> {
                if (state.detachedRoots.contains(view)) {
                    detachGalleryNativeNavigation(view);
                } else if (state.transparentRoots.contains(view)) {
                    if (state.app == TargetApp.MI_FILE_MANAGER) {
                        makeFileManagerNativeActionsTransparent(view);
                    } else {
                        makeGalleryNativeNavigationTransparent(view);
                    }
                } else if (state.offscreenRoots.contains(view)) {
                    moveExactNavigationRootOffscreen(view);
                    if (state.app == TargetApp.MI_GALLERY) {
                        hideGalleryNativeNavigation(view);
                    }
                } else if (state.collapsedRoots.contains(view)) {
                    collapseExactNavigationRoot(view);
                    if (state.app == TargetApp.MI_GALLERY) {
                        hideGalleryNativeNavigation(view);
                    }
                } else if (state.clearRoots.contains(view)) {
                    clearExactPlate(view);
                } else if (state.alignAboveGlassGapDp.containsKey(view)) {
                    alignExactViewAboveGlass(state, view, state.alignAboveGlassGapDp.get(view));
                } else if (state.alignAboveGlassTranslationGapDp.containsKey(view)) {
                    alignExactViewAboveGlassByTranslation(
                            state, view, state.alignAboveGlassTranslationGapDp.get(view)
                    );
                } else if (phoneDialerExpansionForView(state, view) != null) {
                    expandPhoneDialerAboveGlass(
                            state, phoneDialerExpansionForView(state, view)
                    );
                } else if (state.extendBottomDp.containsKey(view)) {
                    applyBottomExtension(state, view, state.extendBottomDp.get(view));
                } else if (state.overscanBottomDp.containsKey(view)) {
                    applyBottomOverscan(view, state.overscanBottomDp.get(view));
                } else if (state.zeroMarginRoots.contains(view)) {
                    zeroBottomMargin(view);
                } else if (state.zeroBottomPaddingRoots.contains(view)) {
                    zeroBottomPadding(view);
                }
            });
        } catch (Throwable ignored) {}
    }

    public static void onGalleryNativeNavigationMutated(View view) {
        if (view == null || Boolean.TRUE.equals(INTERNAL_VISIBILITY_CHANGE.get())) return;
        // Apply alpha-zero synchronously at the verified Navigator lifecycle/attach callback. The
        // row stays attached and measured, so Gallery has no reason to create a replacement at a
        // second coordinate; only its rendering/touch surface is suppressed.
        if (isGalleryBottomNavigationView(view)) makeGalleryNativeNavigationTransparent(view);

        State state = getSuppressedState(view);
        if (state == null || state.app != TargetApp.MI_GALLERY) return;
        if (state.transparentRoots.contains(view)) {
            makeGalleryNativeNavigationTransparent(view);
        } else if (state.detachedRoots.contains(view)) {
            detachGalleryNativeNavigation(view);
        } else if (state.offscreenRoots.contains(view)) {
            moveExactNavigationRootOffscreen(view);
            hideGalleryNativeNavigation(view);
        } else if (state.collapsedRoots.contains(view)) {
            collapseExactNavigationRoot(view);
            hideGalleryNativeNavigation(view);
        }
    }

    private static void runInternalVisibilityChange(Runnable action) {
        INTERNAL_VISIBILITY_CHANGE.set(Boolean.TRUE);
        try { action.run(); } finally { INTERNAL_VISIBILITY_CHANGE.remove(); }
    }

    private static void ensureNavOnTop(State state) {
        if (state == null || state.nav == null) return;
        try {
            if (!(state.nav.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) state.nav.getParent();
            int index = parent.indexOfChild(state.nav);
            if (index >= 0 && index != parent.getChildCount() - 1) state.nav.bringToFront();
        } catch (Throwable ignored) {}
    }

    private static void startPoll(final State state) {
        if (state.poll != null || state.nav == null) return;
        state.poll = new Runnable() {
            @Override public void run() {
                Activity activity = state.activity;
                if (!isUsable(activity)) return;
                if (state.nav == null || !state.nav.isAttachedToWindow()) {
                    // The panel window or nav was removed (window token change, host recreation,
                    // or the system dropped the TYPE_APPLICATION_PANEL window). Reinstall with a
                    // throttle instead of letting the poll die; the bar must come back.
                    if (state.overlayWindowManager != null && state.container != null) {
                        try {
                            if (state.container.isAttachedToWindow()) {
                                state.overlayWindowManager.removeViewImmediate(state.container);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    long now = SystemClock.uptimeMillis();
                    Long last = LAST_OVERLAY_REINSTALL.get(activity);
                    if (last == null || now - last >= 2000L) {
                        LAST_OVERLAY_REINSTALL.put(activity, now);
                        if (state.app == TargetApp.DOUYIN) {
                            Integer failures = DOUYIN_OVERLAY_FAILURES.get(activity);
                            DOUYIN_OVERLAY_FAILURES.put(activity, failures == null ? 1 : failures + 1);
                        }
                        install(activity, state.app);
                    }
                    reschedulePoll(state, activity);
                    return;
                }
                try {
                    long now = SystemClock.uptimeMillis();
                    TargetAppSettings previousSettings = state.appSettings;
                    boolean settingsChanged = false;
                    // The registered ContentObserver applies settings immediately. A slow fallback
                    // remains for OEMs that drop cross-process notifications, but remote preference
                    // reads must not run every watchdog tick.
                    if (now - state.lastForcedSettingsReadAtMs >= FORCED_SETTINGS_FALLBACK_MS) {
                        // Advance before reading so a transient transport failure cannot retry I/O
                        // on every following one-second heartbeat.
                        state.lastForcedSettingsReadAtMs = now;
                        TargetAppSettings latest = TargetAppHookSettingsReader.load(activity, state.app);
                        if (latest != null) {
                            state.appSettings = latest;
                            SETTINGS_CACHE.put(activity, latest);
                        }
                        if (state.appSettings != null && !state.appSettings.enabled) {
                            remove(activity);
                            return;
                        }
                        // Global render switches are intentionally not part of TargetAppSettings.
                        // Poll their durable revision independently so an OEM-dropped
                        // cross-package observer cannot leave one foreground host stale forever.
                        state.appliedGlobalRenderRevision =
                                GlobalRenderSettingsReader.apply(activity, state.nav, true);
                        settingsChanged =
                                !sameTargetSettings(previousSettings, state.appSettings);
                    }

                    boolean rootLost = state.primaryRoot != null
                            && !state.primaryRoot.isAttachedToWindow();
                    boolean sourceLost = state.backdropSource != null
                            && !state.backdropSource.isAttachedToWindow();
                    boolean adapterDue = settingsChanged || rootLost || sourceLost
                            || now - state.lastAdapterProbeAtMs
                            >= adapterProbeIntervalMs(state.app);
                    if (!adapterDue) {
                        // A one-second heartbeat is retained only to keep the injected row above
                        // host overlays. Do not rebuild adapters, scan trees or inspect semantics.
                        ensureNavOnTop(state);
                    } else {
                        state.lastAdapterProbeAtMs = now;
                    UniversalHostAdapter fresh = UniversalHostAdapter.create(
                            activity, state.app, state.appSettings
                    );
                    if (fresh != null) {
                        View freshRoot = fresh.getBottomBarRoot();
                        if (freshRoot == null && LiquidTabNavigationRules.supports(state.app)) {
                            remove(activity);
                            return;
                        }
                        if (freshRoot != null || state.app == TargetApp.MOONDROP
                                || state.app == TargetApp.XHS
                                || state.app == TargetApp.MI_GALLERY) {
                            boolean periodicSourceProbe = (
                                    state.app == TargetApp.BILIBILI
                                    || state.app == TargetApp.BILIBILI_HD
                                    || state.app == TargetApp.JD
                                    || state.app == TargetApp.NETEASE
                                     || state.app == TargetApp.UUREMOTE
                                     || state.app == TargetApp.STARDESK
                                     || state.app == TargetApp.CAINIAO
                             );
                            View freshSource = periodicSourceProbe
                                    ? fresh.getBackdropSourceView() : state.backdropSource;
                            boolean rootChanged = freshRoot != state.primaryRoot;
                            boolean sourceChanged = freshSource != state.backdropSource;
                            boolean moondropNavigationModelChanged = state.app == TargetApp.MOONDROP
                                    && previousSettings != null
                                    && previousSettings.hiddenMask != state.appSettings.hiddenMask;
                            if (moondropNavigationModelChanged) {
                                SETTINGS_CACHE.put(activity, state.appSettings);
                                recreate(activity, state.app);
                                return;
                            }
                            state.adapter = fresh;
                            if (rootChanged || sourceChanged || settingsChanged) {
                                updatePrimaryRoot(state, freshRoot);
                                applyState(activity, state);
                                state.lastSuppressionHeartbeatAtMs = now;
                            } else {
                                // The old poll rebuilt Compose settings and re-applied every layout
                                // mutation on every tick even when nothing changed. On deep host
                                // trees this became a visible periodic hitch. Keep callbacks fresh, sync
                                // only the selected state, and use a sparse suppression heartbeat.
                                if (state.app == TargetApp.MI_FILE_MANAGER
                                        && state.fileManagerSelectionMode) {
                                    // The visible row is now 发送/移动/删除/更多. Never replace its
                                    // action adapter with the ordinary 最近/浏览/云盘 adapter during
                                    // the one-second watchdog tick.
                                    configureFileManagerActionMode(state);
                                } else {
                                    state.nav.setHostNavigationAdapter(fresh);
                                    if (state.app == TargetApp.UUREMOTE) {
                                        state.nav.setLabels(fresh.getVisibleLabels());
                                    }
                                    // NetEase can create or replace the complete mini-player shell
                                    // after the first Activity layout. Refresh dynamic exclusions
                                    // and coordinate policy whenever the periodic adapter probe runs.
                                    state.nav.setBackdropExcludedViews(fresh.getBackdropExcludedViews());
                                    state.nav.setCoolapkLegacyPreservePhysicalCoordinates(
                                            fresh.preservePhysicalBackdropCoordinates()
                                    );
                                }
                                if (state.app != TargetApp.XHS
                                        && now - state.lastSuppressionHeartbeatAtMs
                                        >= SUPPRESSION_HEARTBEAT_MS) {
                                    applySuppressionPlan(
                                            state, suppressionPlanForState(state, fresh)
                                    );
                                    state.lastSuppressionHeartbeatAtMs = now;
                                }
                                syncVisibilityAndSelection(state);
                                ensureNavOnTop(state);
                                ensureNavVisible(state);
                            }
                        }
                    }
                    }
                } catch (Throwable ignored) {}
                if (state.app == TargetApp.DOUYIN
                        && !(state.adapter instanceof LiquidTabPortAdapter)
                        && !isNavigationExplicitlyHidden(activity)) {
                    // Music-disc-area components align below the 分享 button; re-run every
                    // heartbeat so freshly created items move immediately.
                    if (!UniversalHostAdapter.alignAwemeMusicCluster(activity)) {
                        View collection = UniversalHostAdapter.refreshAwemeCollection(activity);
                        if (collection != null) {
                            alignExactViewAboveGlassByTranslation(state, collection, 6f);
                        }
                    }
                    View chatInput = UniversalHostAdapter.refreshAwemeChatInput(activity);
                    if (chatInput != null) {
                        alignExactViewAboveGlassByTranslation(state, chatInput, 41f);
                    }
                }
                ensureNavVisible(state);
                reschedulePoll(state, activity);
            }
        };
        state.nav.postDelayed(state.poll, pollIntervalMs(state.app));
    }

    /** Keep the heartbeat alive even when the injected window/view is temporarily detached. */
    private static void reschedulePoll(State state, Activity activity) {
        if (state == null || state.poll == null || activity == null) return;
        try {
            View host = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (host != null) {
                host.postDelayed(state.poll, pollIntervalMs(state.app));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * QQ-style active detection: the injected bar must be visible whenever it is installed and
     * the user did not explicitly hide it (comment panel / friend chat). The poll calls this on
     * every heartbeat so a missed hook, a transiently missing native root or a stale visibility
     * flag cannot leave the bar missing.
     */
    private static void ensureNavVisible(State state) {
        try {
            if (state == null || state.nav == null || state.activity == null) return;
            if (!state.nav.isAttachedToWindow()) return;
            if (isNavigationExplicitlyHidden(state.activity)) return;
            if (state.adapter != null && !state.adapter.shouldShowInjectedNavigation()) return;
            if (state.adapter != null && !state.adapter.isNavigationVisible()) return;
            if (state.nav.getVisibility() != View.VISIBLE) {
                state.nav.setVisibility(View.VISIBLE);
                state.nav.refreshBackdropSoon();
                XposedBridge.log("[LiquidGlass] watchdog restored nav visibility for "
                        + state.activity.getClass().getName());
            }
        } catch (Throwable ignored) {
        }
    }

    private static long pollIntervalMs(TargetApp app) {
        // Exact TabBar attachment hooks and RecyclerView state hooks handle normal XHS changes.
        // Keep only a very slow watchdog for external navigation or an OEM-dropped callback.
        return app == TargetApp.XHS ? 4000L : DEFAULT_POLL_INTERVAL_MS;
    }

    private static long adapterProbeIntervalMs(TargetApp app) {
        if (app == TargetApp.MOONDROP || app == TargetApp.UUREMOTE) return 2000L;
        if (app == TargetApp.TIM || app == TargetApp.MI_FILE_MANAGER) return 3000L;
        if (app == TargetApp.XHS) return 4000L;
        return 6000L;
    }

    private static UniversalHostAdapter.SuppressionPlan suppressionPlanForState(
            State state,
            UniversalHostAdapter adapter
    ) {
        if (adapter == null) return new UniversalHostAdapter.SuppressionPlan();
        if (state != null && state.app == TargetApp.TIM
                && (state.timConversationMode || !adapter.shouldShowInjectedNavigation())) {
            // Keep the native TIM home row away from the conversation while restoring every
            // home-only content mutation. In particular, do not overscan android.R.id.content in
            // AIO: that container also owns the message composer and keyboard inset handling.
            View nativeBar = adapter.getBottomBarRoot();
            UniversalHostAdapter.SuppressionPlan plan =
                    new UniversalHostAdapter.SuppressionPlan();
            if (nativeBar != null) plan.offscreen(nativeBar);
            return plan;
        }
        UniversalHostAdapter.SuppressionPlan plan = adapter.getSuppressionPlan();
        if (state != null && state.app == TargetApp.MI_FILE_MANAGER
                && state.fileManagerSelectionMode) {
            FileManagerSelectionSession session = state.fileManagerSelectionSession;
            if (session == null) session = FILE_MANAGER_SELECTIONS.get(state.activity);
            if (session != null) {
                // This ephemeral ResponsiveActionMenuView has its own identity-bound alpha guard.
                // Keep it outside the generic suppression snapshot system so a stale ActionMode
                // row can never be restored over the next selection session.
                session.captureNativeActions(state.activity);
                session.suppressNativeActionContainer(state.activity);
            }
        }
        return plan;
    }

    private static boolean isNavigationExplicitlyHidden(Activity activity) {
        if (activity == null) return false;
        synchronized (NAV_HIDE_REASONS) {
            Set<Object> reasons = NAV_HIDE_REASONS.get(activity);
            if (reasons == null || reasons.isEmpty()) {
                if (reasons != null) NAV_HIDE_REASONS.remove(activity);
                return false;
            }
            return true;
        }
    }

    private static void setDouyinPanelTouchable(State state, boolean touchable) {
        if (state == null || state.app != TargetApp.DOUYIN
                || state.overlayWindowManager == null || state.container == null) return;
        try {
            WindowManager.LayoutParams params = state.overlayWindowParams;
            if (params == null && state.container.getLayoutParams() instanceof WindowManager.LayoutParams) {
                params = (WindowManager.LayoutParams) state.container.getLayoutParams();
                state.overlayWindowParams = params;
            }
            if (params == null) return;
            final int flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            boolean currentlyTouchable = (params.flags & flag) == 0;
            if (currentlyTouchable == touchable) return;
            if (touchable) params.flags &= ~flag;
            else params.flags |= flag;
            state.overlayWindowManager.updateViewLayout(state.container, params);
        } catch (Throwable ignored) {
        }
    }

    private static void syncVisibilityAndSelection(State state) {
        if (state == null || state.nav == null || state.adapter == null) return;
        boolean fileActionMode = state.app == TargetApp.MI_FILE_MANAGER
                && state.fileManagerSelectionMode;
        boolean neteaseDrawerOpen = state.app == TargetApp.NETEASE
                && (state.neteaseDrawerOpen
                || Boolean.TRUE.equals(NETEASE_DRAWER_OPEN.get(state.activity)));
        boolean visible = fileActionMode || (
                !(state.app == TargetApp.TIM && state.timConversationMode)
                && !neteaseDrawerOpen
                && !isNavigationExplicitlyHidden(state.activity)
                && state.adapter.shouldShowInjectedNavigation()
                && state.adapter.isNavigationVisible());
        int wanted = visible ? View.VISIBLE : View.GONE;
        if (state.app == TargetApp.DOUYIN && state.container != null) {
            // Douyin lives in a separate TYPE_APPLICATION_PANEL. Hiding only the Compose nav
            // leaves that small window alive over the comment/chat composer. Hide the panel root
            // as well and toggle FLAG_NOT_TOUCHABLE, without changing any 26.76 geometry.
            if (state.container.getVisibility() != wanted) {
                state.container.setVisibility(wanted);
            }
            setDouyinPanelTouchable(state, visible);
        }
        if (state.nav.getVisibility() != wanted) {
            state.nav.setVisibility(wanted);
            if (visible) {
                state.nav.startRealtimeBackdropRefresh();
                state.nav.refreshBackdropSoon();
            } else {
                state.nav.pauseBackdropCapture();
            }
        }
        if (visible && !fileActionMode) {
            int selected;
            if (state.app == TargetApp.MI_COMPASS) {
                Integer committed = COMPASS_NATIVE_SELECTION.get(state.activity);
                // Compass selection is callback-driven. Do not let BottomNavigator's transient
                // startup/animation value overwrite the page already committed by H2(int).
                selected = committed != null && committed >= 0 && committed <= 1
                        ? committed : state.nav.getSelectedIndexForHost();
            } else {
                selected = state.adapter.detectSelectedIndex();
            }
            if (selected >= 0) {
                if (state.lastDetectedIndex >= 0 && selected != state.lastDetectedIndex) {
                    if (state.app == TargetApp.NETEASE) applyBackdropProfile(state);
                    // NetEase already freezes the frame before dispatching the native tab click.
                    // When the host later reports the same committed index, do not freeze a second
                    // time during the watchdog poll; that duplicate hand-off made tab changes feel
                    // delayed even though the new page was already visible.
                    boolean alreadyCommittedByGlass = state.app == TargetApp.NETEASE
                            && state.nav.getSelectedIndexForHost() == selected;
                    long delay = state.adapter.backdropTransitionDelayMs();
                    if (!alreadyCommittedByGlass && delay > 0L) {
                        state.nav.notifyHostContentTransition(delay);
                    }
                }
                state.lastDetectedIndex = selected;
                state.nav.setSelectedIndexFromHost(selected);
            }
        }
    }

    static void prepareNeteaseBackdropSelection(Activity activity, int nativeIndex) {
        State state = getState(activity);
        if (state == null || state.nav == null || state.app != TargetApp.NETEASE) return;
        state.nav.setCoolapkLegacyHardwareLayerCaptureEnabled(nativeIndex != 3);
    }

    /**
     * Exact state from NetEase 9.5.50 MainActivity's DrawerLayout callbacks. The state is also
     * cached before LiquidGlass finishes mounting, so opening the drawer during Activity startup
     * cannot leave the independent application-panel window visible above it.
     */
    public static void setNeteaseDrawerOpen(Activity activity, boolean open) {
        if (activity == null) return;
        NETEASE_DRAWER_OPEN.put(activity, open);
        State state = getState(activity);
        if (state == null || state.app != TargetApp.NETEASE) return;
        if (state.neteaseDrawerOpen == open) {
            syncVisibilityAndSelection(state);
            return;
        }
        state.neteaseDrawerOpen = open;
        syncVisibilityAndSelection(state);
        if (!open && state.nav != null && state.nav.getVisibility() == View.VISIBLE) {
            state.nav.refreshBackdropSoon();
        }
    }

    public static void notifyHostPageTransition(Activity activity, TargetApp app) {
        State state = getState(activity);
        if (state == null || state.nav == null || state.app != app
                || state.nav.getVisibility() != View.VISIBLE) return;
        try {
            long delay = state.adapter == null ? 0L : state.adapter.backdropTransitionDelayMs();
            if (delay > 0L) state.nav.notifyHostContentTransition(delay);
        } catch (Throwable ignored) {}
    }

    /** Commit WeChat's native pager index immediately instead of waiting for the poll heartbeat. */
    public static void syncWeChatSelection(Activity activity, int nativeIndex) {
        if (activity == null || nativeIndex < 0) return;
        State state = getState(activity);
        if (state == null || state.nav == null || state.adapter == null
                || state.app != TargetApp.WECHAT) return;
        int logicalIndex = state.adapter.logicalForNative(nativeIndex);
        // When “隐藏发现” is enabled, a host-side jump to native page 2 intentionally has no
        // visible glass selection. Keep the last visible destination instead of coercing -1 to 0.
        if (logicalIndex < 0) {
            state.nav.refreshBackdropSoon();
            return;
        }
        state.lastDetectedIndex = logicalIndex;
        final MiuixGlassNavView nav = state.nav;
        nav.setSelectedIndexFromHost(logicalIndex);
        nav.refreshBackdropSoon();
        nav.postOnAnimation(() -> {
            State current = getState(activity);
            if (current == null || current.app != TargetApp.WECHAT || current.nav != nav) return;
            nav.setSelectedIndexFromHost(logicalIndex);
            nav.refreshBackdropSoon();
        });
        nav.postDelayed(nav::refreshBackdropSoon, 96L);
        nav.postDelayed(nav::refreshBackdropSoon, 240L);
    }

    /**
     * Compass exposes the destination index directly to BottomNavigator#setSelectedItem and
     * CompassActivity#H2. Consume that committed index instead of waiting for a later reflective
     * getSelectedItem() poll. The committed destination is cached immediately, then applied on the
     * next choreographer frame after CompassScreenView has finished replacing the page hierarchy.
     */
    public static void syncCompassSelection(Activity activity, int nativeIndex) {
        if (activity == null || nativeIndex < 0 || nativeIndex > 1) return;
        COMPASS_NATIVE_SELECTION.put(activity, nativeIndex);
        State state = getState(activity);
        // H2()/setSelectedItem() can run during CompassActivity startup before install(). Cache the
        // destination above and let install() consume it for the first frame.
        if (state == null || state.nav == null || state.app != TargetApp.MI_COMPASS) return;

        // BottomNavigator#setSelectedItem may itself be called from inside H2(int). Even an AFTER
        // hook on that nested call is still too early because CompassScreenView can replace its
        // CameraView2 later in the outer H2 stack. Commit on the next choreographer frame so both
        // callbacks have unwound and the capture manager sees the actual incoming hierarchy.
        final MiuixGlassNavView nav = state.nav;
        nav.postOnAnimation(() -> {
            State current = getState(activity);
            Integer committed = COMPASS_NATIVE_SELECTION.get(activity);
            if (current == null || current.nav != nav || current.app != TargetApp.MI_COMPASS
                    || committed == null || committed != nativeIndex) return;
            current.lastDetectedIndex = nativeIndex;
            if (nav.getVisibility() == View.VISIBLE) {
                nav.forceHostPageBackdropResync(nativeIndex);
                // 26.59: selecting Compass/Gradienter means the visible page, not the still-live
                // camera process, owns the screen. Re-evaluate sibling draw order immediately and
                // again after the host transition settles so a covered CameraView2 is retracted.
                nav.recheckCompassCameraFrontVisibility();
                nav.postDelayed(nav::recheckCompassCameraFrontVisibility, 48L);
                nav.postDelayed(nav::recheckCompassCameraFrontVisibility, 140L);
            } else {
                nav.setSelectedIndexFromHost(nativeIndex);
            }
        });
    }

    /**
     * CameraView2 reported close. Clear the real bitmap synchronously. A later process open/start
     * still does not expose it; only camera_mask_out/e1=true can re-enable real capture.
     */
    public static void onCompassCameraClosed(Activity activity) {
        if (activity == null) return;
        COMPASS_CAMERA_PREVIEW_VISIBLE.put(activity, false);
        State state = getState(activity);
        if (state == null || state.nav == null || state.app != TargetApp.MI_COMPASS) return;
        state.nav.forceCompassCameraBackdropBlack();
    }

    /**
     * CameraView2 reported start/open. On Compass 17.1.0.1 this only means the camera resource is
     * alive; it can keep streaming behind the normal black Compass/Gradienter page. Do not expose
     * that Surface to LiquidGlass here. The host camera-mask/e1 signal below is authoritative.
     */
    public static void onCompassCameraProcessStarted(Activity activity) {
        State state = getState(activity);
        if (state == null || state.nav == null || state.app != TargetApp.MI_COMPASS) return;
        state.nav.noteCompassCameraProcessStarted();
    }

    /**
     * Exact visual preview ownership from CompassActivity.e1 / camera_mask_in/out.
     * true = camera mask is out and the live preview is intended to be visible.
     * false = normal black Compass/Gradienter UI owns the screen, even if CameraView2 keeps
     *         streaming in the background process.
     */
    public static void onCompassCameraPreviewVisibility(Activity activity, boolean visible) {
        if (activity == null) return;
        COMPASS_CAMERA_PREVIEW_VISIBLE.put(activity, visible);
        State state = getState(activity);
        if (state == null || state.nav == null || state.app != TargetApp.MI_COMPASS) return;
        state.nav.setCompassCameraPreviewVisible(visible);
    }

    /** Kept as a compatibility alias for older hook paths; process start does not expose preview. */
    public static void onCompassCameraOpened(Activity activity) {
        onCompassCameraProcessStarted(activity);
    }

    /** Clear any camera-backed frame when CompassActivity returns to the foreground. */
    public static void resyncCompassBackdrop(Activity activity) {
        State state = getState(activity);
        if (state == null || state.nav == null || state.app != TargetApp.MI_COMPASS) return;
        int selected = -1;
        Integer committed = COMPASS_NATIVE_SELECTION.get(activity);
        if (committed != null && committed >= 0 && committed <= 1) selected = committed;
        if (selected < 0) {
            try { selected = state.adapter == null ? -1 : state.adapter.detectSelectedIndex(); }
            catch (Throwable ignored) {}
        }
        if (selected < 0) selected = state.nav.getSelectedIndexForHost();
        state.lastDetectedIndex = selected;
        state.nav.setCompassCameraPreviewVisible(Boolean.TRUE.equals(
                COMPASS_CAMERA_PREVIEW_VISIBLE.get(activity)
        ));
        state.nav.forceHostPageBackdropResync(selected);
        state.nav.recheckCompassCameraFrontVisibility();
    }

    public static void markBackdropMoving(Activity activity) {
        State state = getState(activity);
        if (state != null && state.nav != null && state.nav.getVisibility() == View.VISIBLE) {
            state.nav.markBackdropMoving();
        }
    }

    public static void markBackdropIdle(Activity activity) {
        State state = getState(activity);
        if (state != null && state.nav != null && state.nav.getVisibility() == View.VISIBLE) {
            state.nav.markBackdropIdle();
        }
    }

    public static void pause(Activity activity) {
        State state = getState(activity);
        if (state != null && state.nav != null) state.nav.pauseBackdropCapture();
    }

    public static void resume(Activity activity, TargetApp app) {
        if (!isUsable(activity) || app == null) return;
        // Cross-package ContentObserver delivery is unreliable on some OEM builds. Always refresh
        // once when the host returns to foreground so settings changed while it was backgrounded
        // cannot remain stuck in the Activity cache.
        TargetAppSettings latest = TargetAppHookSettingsReader.load(activity, app);
        if (latest != null) SETTINGS_CACHE.put(activity, latest);
        State state = getState(activity);
        if (state == null || state.nav == null) {
            install(activity, app);
            return;
        }
        state.lastForcedSettingsReadAtMs = SystemClock.uptimeMillis();
        // Apply the forced global snapshot before resuming. Otherwise a stale enabled state can
        // restart capture while the persisted "关闭所有背景取景" switch is already on.
        state.appliedGlobalRenderRevision =
                GlobalRenderSettingsReader.apply(activity, state.nav, true);
        state.nav.startRealtimeBackdropRefresh();
        if (app == TargetApp.MI_COMPASS) resyncCompassBackdrop(activity);
        install(activity, app);
    }

    public static void recreate(Activity activity, TargetApp app) {
        // onConfigurationChanged() recreates the overlay inside the same LauncherUI. Keep the
        // current chat/task-bar hide tokens across remove(), which ordinarily clears them when
        // an Activity is destroyed. Otherwise rotating in a chat shows glass over its composer.
        Set<Object> preservedWeChatReasons = null;
        if (activity != null && app == TargetApp.WECHAT) {
            synchronized (NAV_HIDE_REASONS) {
                Set<Object> reasons = NAV_HIDE_REASONS.get(activity);
                if (reasons != null && !reasons.isEmpty()) {
                    preservedWeChatReasons = Collections.newSetFromMap(
                            new WeakHashMap<Object, Boolean>());
                    preservedWeChatReasons.addAll(reasons);
                }
            }
        }
        remove(activity);
        if (preservedWeChatReasons != null) {
            synchronized (NAV_HIDE_REASONS) {
                Set<Object> reasons = NAV_HIDE_REASONS.get(activity);
                if (reasons == null) {
                    reasons = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
                    NAV_HIDE_REASONS.put(activity, reasons);
                }
                reasons.addAll(preservedWeChatReasons);
            }
        }
        if (isUsable(activity)) {
            activity.getWindow().getDecorView().postDelayed(() -> install(activity, app), 48L);
        }
    }

    public static void reloadSettings(Activity activity, TargetApp app) {
        if (!isUsable(activity) || app == null) return;
        TargetAppSettings latest = TargetAppHookSettingsReader.load(activity, app);
        if (latest != null) SETTINGS_CACHE.put(activity, latest);
        State state = getState(activity);
        if (state != null) {
            state.lastForcedSettingsReadAtMs = SystemClock.uptimeMillis();
        }

        // setLabels() now rebuilds the navigation model when its count changes, so MOONDROP can
        // apply [设置(1), 我的(2)] in place. Avoid removing/recreating the overlay, which created a
        // short native-bar flash and made a missed observer look like the option did nothing.
        install(activity, app);
    }

    /**
     * TIM keeps both MainFragment and ChatFragment inside SplashActivity. Conversation mode must
     * hide LiquidGlass and, more importantly, restore the home-only android.R.id.content overscan
     * before the AIO input panel is measured.
     */
    public static void setTimConversationMode(Activity activity, boolean active) {
        if (activity == null) return;
        TIM_CONVERSATION_MODE.put(activity, active);
        State state = getState(activity);
        if (state == null || state.app != TargetApp.TIM) {
            if (!active && isUsable(activity)) install(activity, TargetApp.TIM);
            return;
        }
        if (state.timConversationMode == active) {
            syncVisibilityAndSelection(state);
            return;
        }
        state.timConversationMode = active;
        if (active) {
            if (state.nav != null) state.nav.pauseBackdropCapture();
            applyState(activity, state);
        } else {
            // Re-resolve the newly resumed MainFragment and its three native slots before
            // reapplying the home overscan and showing the glass row.
            install(activity, TargetApp.TIM);
            if (state.nav != null) state.nav.startRealtimeBackdropRefresh();
        }
    }

    public static void setFileManagerSelectionMode(Activity activity, boolean active) {
        setFileManagerSelectionMode(activity, active, null, null);
    }

    /** Exact callback from ActionBarContextView.initForMode(), after its split menu is created. */
    public static void bindFileManagerActionModeView(
            Activity activity,
            Object actionMode,
            View actionModeView
    ) {
        if (activity == null || !(actionMode instanceof ActionMode)) return;
        FileManagerSelectionSession session = FILE_MANAGER_SELECTIONS.get(activity);
        if (session == null) {
            session = new FileManagerSelectionSession(null, actionMode);
            FILE_MANAGER_SELECTIONS.put(activity, session);
        }
        session.bindActionModeView(actionModeView, actionMode);
        setFileManagerSelectionMode(activity, true, null, actionMode);
    }

    /** Ignore a delayed destroy callback from an older ActionMode after a new mode has started. */
    public static void finishFileManagerSelectionMode(
            Activity activity,
            Object callback,
            Object actionMode
    ) {
        if (activity == null) return;
        FileManagerSelectionSession current = FILE_MANAGER_SELECTIONS.get(activity);
        if (current == null) return;
        if (actionMode instanceof ActionMode && current.actionMode() != actionMode) return;
        Object currentCallback = current.callback();
        if (!(actionMode instanceof ActionMode) && callback != null
                && currentCallback != null && currentCallback != callback) return;
        setFileManagerSelectionMode(activity, false, callback, actionMode);
    }

    public static void setFileManagerSelectionMode(
            Activity activity,
            boolean active,
            Object callback,
            Object actionMode
    ) {
        if (activity == null) return;
        FileManagerSelectionSession session = null;
        if (active) {
            session = FILE_MANAGER_SELECTIONS.get(activity);
            if (session == null) {
                session = new FileManagerSelectionSession(callback, actionMode);
                FILE_MANAGER_SELECTIONS.put(activity, session);
            } else {
                session.update(callback, actionMode);
            }
            session.captureNativeActions(activity);
            session.suppressNativeActionContainer(activity);
        } else {
            FileManagerSelectionSession finished = FILE_MANAGER_SELECTIONS.remove(activity);
            if (finished != null) finished.release(activity);
        }

        State state = getState(activity);
        if (state == null || state.app != TargetApp.MI_FILE_MANAGER) {
            if (active && isUsable(activity)) install(activity, TargetApp.MI_FILE_MANAGER);
            return;
        }
        boolean enteringSelection = active && !state.fileManagerSelectionMode;
        state.fileManagerSelectionMode = active;
        state.fileManagerSelectionSession = session;
        configureFileManagerActionMode(state);
        // Only the first transition changes the Compose model/geometry. Exact Miuix builds enter
        // here from ActionBarContextView.initForMode, after the action menu exists; later menu
        // invalidations only update enabled flags in refreshFileManagerSelectionMode().
        if (enteringSelection || !active) {
            applyState(activity, state);
        }
        syncVisibilityAndSelection(state);
        ensureNavOnTop(state);
        if (state.nav != null) {
            state.nav.refreshBackdropSoon();
        }

        if (active && session != null && activity.getWindow() != null
                && session.markNativeBindingScheduled()) {
            final FileManagerSelectionSession activeSession = session;
            View decor = activity.getWindow().getDecorView();
            Runnable bindNativeActions = () -> {
                if (!isUsable(activity)) return;
                FileManagerSelectionSession currentSession = FILE_MANAGER_SELECTIONS.get(activity);
                if (currentSession != activeSession) return;
                activeSession.captureNativeActions(activity);
                activeSession.suppressNativeActionContainer(activity);
                State current = getState(activity);
                if (current == null || !current.fileManagerSelectionMode) return;
                current.fileManagerSelectionSession = activeSession;
                configureFileManagerActionMode(current);
                syncVisibilityAndSelection(current);
                ensureNavOnTop(current);
            };
            decor.postOnAnimation(bindNativeActions);
        }
    }

    public static void refreshFileManagerSelectionMode(
            Activity activity,
            Object callback,
            Object actionMode
    ) {
        if (activity == null) return;
        FileManagerSelectionSession session = FILE_MANAGER_SELECTIONS.get(activity);
        if (session == null) {
            // invalidate() can run before ActionBarContextView.initForMode(). Entering here would
            // scan an action row that does not exist yet and was the main long-press stall.
            return;
        }
        session.update(callback, actionMode);
        // File Explorer invalidates the menu and then reports the item-change callback. Several
        // inherited hooks therefore describe the same update in one frame. Coalesce them so a
        // checkbox tap does not rebuild the glass row three or four times.
        if (!session.markRefreshPosted()) return;
        View decor = activity.getWindow() == null
                ? null : activity.getWindow().getDecorView();
        Runnable refresh = () -> {
            session.clearRefreshPosted();
            if (FILE_MANAGER_SELECTIONS.get(activity) != session) return;
            State state = getState(activity);
            if (state == null || state.app != TargetApp.MI_FILE_MANAGER) return;
            state.fileManagerSelectionMode = true;
            state.fileManagerSelectionSession = session;
            configureFileManagerActionMode(state);
            syncVisibilityAndSelection(state);
            ensureNavOnTop(state);
        };
        if (decor != null) decor.post(refresh); else refresh.run();
    }

    private static void configureFileManagerActionMode(State state) {
        if (state == null || state.nav == null || state.app != TargetApp.MI_FILE_MANAGER) return;
        if (!state.fileManagerSelectionMode) {
            state.nav.setTransientActionMode(false);
            state.nav.setTransientActionEnabled(null);
            state.nav.setHostNavigationAdapter(state.adapter);
            state.nav.setLabels(state.adapter.getVisibleLabels());
            return;
        }
        if (state.fileManagerActionAdapter == null) {
            state.fileManagerActionAdapter = new FileManagerActionAdapter(state);
        }
        state.nav.setTransientActionMode(true);
        FileManagerSelectionSession session = state.fileManagerSelectionSession;
        state.nav.setLabels(session != null && session.isPrivateActionMode()
                ? FILE_MANAGER_PRIVATE_ACTION_LABELS : FILE_MANAGER_ACTION_LABELS);
        state.nav.setTransientActionEnabled(
                session == null ? null : session.enabledActions(state.activity)
        );
        state.nav.setHostNavigationAdapter(state.fileManagerActionAdapter);
    }

    private static boolean dispatchFileManagerAction(State state, int actionIndex) {
        if (state == null || state.activity == null || actionIndex < 0
                || actionIndex >= FILE_MANAGER_ACTION_KEYWORDS.length) return false;
        FileManagerSelectionSession session = state.fileManagerSelectionSession;
        if (session == null) session = FILE_MANAGER_SELECTIONS.get(state.activity);
        if (session == null) return false;

        Object callback = session.callback();
        ActionMode mode = session.actionMode();
        if (mode == null) mode = resolveActionMode(callback);

        Menu menu = null;
        if (mode != null) {
            try { menu = mode.getMenu(); } catch (Throwable ignored) {}
        }

        // More belongs to ActionMenuPresenter rather than the app's operation Menu. Open the
        // presenter owned by this exact ActionMode; no decor scan or positional click is needed.
        if (actionIndex == 3) {
            if (openFileManagerOverflow(state.activity, session)) return true;
            View moreView = session.actionView(3);
            try { return moreView != null && moreView.performClick(); }
            catch (Throwable ignored) { return false; }
        }

        MenuItem item = findFileManagerMenuItem(state.activity, menu, actionIndex);
        if (item != null) {
            // File Explorer disables Send/Move/Delete for empty selections, directories, mixed
            // cloud/local sets and protected volumes. Calling its callback directly bypassed
            // MenuBuilder's guard and allowed commands that the native row explicitly disabled.
            try {
                if (!item.isVisible() || !item.isEnabled()) return false;
            } catch (Throwable ignored) {}

            // Use the menu's own dispatch first. ActionModeImpl is the MenuBuilder callback, so
            // this follows the exact native route and preserves enabled/visible checks.
            try {
                if (menu != null && menu.performIdentifierAction(item.getItemId(), 0)) return true;
            } catch (Throwable ignored) {}
            // Compatibility fallback: invoke ActionModeImpl's actual callback wrapper first. It
            // is the same MultiChoiceModeListenerWrapper used by the native menu and forwards to
            // FileMultiChoiceCallback/SearchMultiChoiceCallback with the current checked items.
            Object nativeCallback = resolveActionModeCallback(mode);
            if (nativeCallback != null
                    && invokeActionModeCallback(nativeCallback, mode, item)) return true;
            if (callback != null && mode != null
                    && callback != nativeCallback
                    && invokeActionModeCallback(callback, mode, item)) return true;
            try {
                View actionView = item.getActionView();
                if (actionView != null && actionView.performClick()) return true;
            } catch (Throwable ignored) {}
        }

        // Final exact-view fallback for send/move/delete. findFileManagerActionView now returns
        // only views whose id/text/description matches the requested action inside the verified
        // ActionMode menu; it no longer guesses by visual position.
        View exact = session.actionView(actionIndex);
        if (exact != null) {
            try { if (exact.performClick()) return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    private static ActionMode resolveActionMode(Object callback) {
        if (callback == null) return null;
        for (Class<?> type = callback.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(callback);
                    if (value instanceof ActionMode) return (ActionMode) value;
                } catch (Throwable ignored) {}
            }
        }
        for (String name : new String[]{"getActionMode", "actionMode", "getCurrentActionMode"}) {
            try {
                Method method = callback.getClass().getMethod(name);
                method.setAccessible(true);
                Object value = method.invoke(callback);
                if (value instanceof ActionMode) return (ActionMode) value;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object resolveActionModeCallback(ActionMode mode) {
        if (mode == null) return null;
        for (Class<?> type = mode.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!"mCallback".equals(field.getName())
                        && !"callback".equalsIgnoreCase(field.getName())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(mode);
                    if (value != null) return value;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static MenuItem findFileManagerMenuItem(Activity activity, Menu menu, int actionIndex) {
        if (menu == null) return null;
        int[] exactIds = isFileManagerPrivateMenu(menu)
                ? FILE_MANAGER_PRIVATE_ACTION_IDS : FILE_MANAGER_EXACT_ACTION_IDS;
        if (actionIndex >= 0 && actionIndex < exactIds.length) {
            int exactId = exactIds[actionIndex];
            if (exactId != 0) {
                try {
                    MenuItem exact = menu.findItem(exactId);
                    if (exact != null) return exact;
                } catch (Throwable ignored) {}
            }
        }
        if (actionIndex >= 0 && actionIndex < FILE_MANAGER_ACTION_ID_NAMES.length) {
            for (String name : FILE_MANAGER_ACTION_ID_NAMES[actionIndex]) {
                try {
                    int id = activity.getResources().getIdentifier(
                            name, "id", TargetApp.MI_FILE_MANAGER.packageName);
                    if (id != 0) {
                        MenuItem exact = menu.findItem(id);
                        if (exact != null) return exact;
                    }
                } catch (Throwable ignored) {}
            }
        }
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item;
            try { item = menu.getItem(i); } catch (Throwable ignored) { continue; }
            if (item == null) continue;
            StringBuilder descriptor = new StringBuilder();
            try { descriptor.append(item.getTitle()).append(' '); } catch (Throwable ignored) {}
            try { descriptor.append(item.getContentDescription()).append(' '); } catch (Throwable ignored) {}
            try {
                int id = item.getItemId();
                if (id != 0) descriptor.append(activity.getResources().getResourceEntryName(id));
            } catch (Throwable ignored) {}
            if (matchesFileManagerAction(descriptor.toString(), actionIndex)) return item;
        }
        return null;
    }

    private static boolean isFileManagerPrivateMenu(Menu menu) {
        if (menu == null) return false;
        try {
            return menu.findItem(FILE_MANAGER_PRIVATE_ACTION_IDS[0]) != null
                    && menu.findItem(FILE_MANAGER_PRIVATE_ACTION_IDS[1]) != null
                    && menu.findItem(FILE_MANAGER_PRIVATE_ACTION_IDS[2]) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isSupportedFileManagerOperationMenu(Menu menu) {
        if (menu == null) return false;
        try {
            boolean shared = menu.findItem(FILE_MANAGER_EXACT_ACTION_IDS[1]) != null
                    && menu.findItem(FILE_MANAGER_EXACT_ACTION_IDS[2]) != null;
            boolean primary = menu.findItem(FILE_MANAGER_EXACT_ACTION_IDS[0]) != null
                    || menu.findItem(FILE_MANAGER_PRIVATE_ACTION_IDS[0]) != null;
            return shared && primary;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean invokeActionModeCallback(Object callback, ActionMode mode, MenuItem item) {
        for (Class<?> type = callback.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!"onActionItemClicked".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2) continue;
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(callback, mode, item);
                    return !(result instanceof Boolean) || (Boolean) result;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static View findFileManagerNativeActionContainer(
            Activity activity,
            Object callback,
            ActionMode actionMode
    ) {
        if (activity == null || activity.getWindow() == null) return null;
        if (actionMode == null) actionMode = resolveActionMode(callback);

        // File Explorer 8.2.3.2 uses EditActionModeImpl. Its mActionModeView points to the
        // reusable ActionBarContextView, whose public getMenuView() returns the *current*
        // ResponsiveActionMenuView. That menu view is rebuilt for every selection session.
        View actionModeView = resolveFileManagerActionModeView(actionMode);
        View menuView = fileManagerMenuViewFromActionModeView(actionModeView);
        if (isFileManagerActionMenuView(menuView, actionMode)) return menuView;

        // initForMode() creates the menu view after onCreateActionMode. During that short window,
        // resolve the visible ActionBarContextView and ask it for the newly attached menu row.
        View contextView = findFileManagerActionModeRoot(activity);
        menuView = fileManagerMenuViewFromActionModeView(contextView);
        if (isFileManagerActionMenuView(menuView, actionMode)) return menuView;

        // Compatibility fallback for a vendor build that hides mActionModeView. Accept only the
        // exact MIUI action-menu class and require the operation-menu actions; this cannot select
        // the ordinary Miuix BottomNavigation used by 最近/浏览/云盘.
        View decor;
        try { decor = activity.getWindow().getDecorView(); }
        catch (Throwable ignored) { return null; }
        if (decor == null) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(decor);
        View best = null;
        int bestY = Integer.MIN_VALUE;
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 3500) {
            View view = queue.removeFirst();
            String className = view.getClass().getName();
            boolean responsiveMenu = className.endsWith("ResponsiveActionMenuView")
                    || className.endsWith("ActionMenuView");
            if (isFileManagerActionMenuView(view, actionMode)
                    || (responsiveMenu
                    && fileManagerActionNavigationScore(activity, view) >= 5)) {
                int[] location = new int[2];
                try { view.getLocationOnScreen(location); } catch (Throwable ignored) {}
                if (best == null || location[1] >= bestY) {
                    best = view;
                    bestY = location[1];
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.addLast(group.getChildAt(i));
                }
            }
        }
        return best;
    }

    private static View resolveFileManagerActionModeView(ActionMode actionMode) {
        if (actionMode == null) return null;
        for (Class<?> type = actionMode.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!"mActionModeView".equals(field.getName())
                        && !"actionModeView".equalsIgnoreCase(field.getName())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(actionMode);
                    if (value instanceof WeakReference) value = ((WeakReference<?>) value).get();
                    if (value instanceof View) return (View) value;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static View fileManagerMenuViewFromActionModeView(View actionModeView) {
        if (actionModeView == null) return null;
        try {
            Object value = UniversalHostAdapter.call(actionModeView, "getMenuView");
            return value instanceof View ? (View) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isFileManagerActionMenuView(View view, ActionMode actionMode) {
        if (view == null || view.getParent() == null || actionMode == null) return false;
        String name = view.getClass().getName();
        if (!name.endsWith("ResponsiveActionMenuView")
                && !name.endsWith("ActionMenuView")) return false;
        try {
            return isSupportedFileManagerOperationMenu(actionMode.getMenu());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int fileManagerActionNavigationScore(Activity activity, View view) {
        if (activity == null || view == null) return 0;
        int score = view.getClass().getName().endsWith("ResponsiveActionMenuView") ? 2 : 0;
        Set<Integer> matched = new java.util.HashSet<>();
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(view);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 64) {
            View child = queue.removeFirst();
            try {
                Object data = UniversalHostAdapter.call(child, "getItemData");
                if (data instanceof MenuItem) {
                    int id = ((MenuItem) data).getItemId();
                    if (id == FILE_MANAGER_EXACT_ACTION_IDS[0]
                            || id == FILE_MANAGER_PRIVATE_ACTION_IDS[0]
                            || id == FILE_MANAGER_EXACT_ACTION_IDS[1]
                            || id == FILE_MANAGER_EXACT_ACTION_IDS[2]) matched.add(id);
                }
            } catch (Throwable ignored) {}
            if (child instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) child;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.addLast(group.getChildAt(i));
                }
            }
        }
        score += matched.size();
        return score;
    }

    private static boolean openFileManagerOverflow(
            Activity activity,
            FileManagerSelectionSession session
    ) {
        if (activity == null || session == null) return false;
        // ResponsiveActionMenuView lives in the split bottom container, so walking its parents
        // never reaches ActionBarContextView. Ask the exact view owned by this ActionMode first.
        View actionModeView = session.actionModeView();
        if (actionModeView == null) {
            actionModeView = resolveFileManagerActionModeView(session.actionMode());
        }
        if (invokeFileManagerOverflow(actionModeView)) return true;

        View container = session.nativeActionContainer();
        for (View current = container; current != null; ) {
            if (invokeFileManagerOverflow(current)) return true;
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }

        View root = findFileManagerActionModeRoot(activity);
        if (invokeFileManagerOverflow(root)) return true;
        View more = findFileManagerActionView(activity, 3, root);
        if (more != null) {
            try { return more.performClick(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private static boolean invokeFileManagerOverflow(View view) {
        if (view == null) return false;
        for (String methodName : new String[]{
                "showOverflowMenu", "openOverflowMenu", "showMoreMenu", "showMenu"
        }) {
            try {
                Method method = view.getClass().getMethod(methodName);
                method.setAccessible(true);
                Object result = method.invoke(view);
                if (!(result instanceof Boolean) || (Boolean) result) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static View findFileManagerActionModeRoot(Activity activity) {
        View decor;
        try { decor = activity == null || activity.getWindow() == null
                ? null : activity.getWindow().getDecorView(); }
        catch (Throwable ignored) { return null; }
        if (decor == null) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(decor);
        View best = null;
        int bestY = Integer.MIN_VALUE;
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 3500) {
            View view = queue.removeFirst();
            String name = view.getClass().getName();
            boolean actionModeRoot = name.endsWith("ActionBarContextView")
                    || name.contains("ActionModeContextView")
                    || (name.contains("ActionModeView") && !name.contains("SearchActionMode"));
            if (actionModeRoot && view.isAttachedToWindow()
                    && Math.max(view.getHeight(), view.getMeasuredHeight()) > 0) {
                int[] location = new int[2];
                try { view.getLocationOnScreen(location); } catch (Throwable ignored) {}
                if (best == null || location[1] >= bestY) {
                    best = view;
                    bestY = location[1];
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.addLast(group.getChildAt(i));
                }
            }
        }
        return best;
    }

    private static View findFileManagerActionView(Activity activity, int actionIndex) {
        return findFileManagerActionView(activity, actionIndex, null);
    }

    private static View findFileManagerActionView(
            Activity activity,
            int actionIndex,
            View preferredRoot
    ) {
        if (activity == null || actionIndex < 0
                || actionIndex >= FILE_MANAGER_ACTION_ID_NAMES.length) return null;
        View decor;
        try { decor = activity.getWindow().getDecorView(); }
        catch (Throwable ignored) { return null; }
        if (decor == null) return null;

        // First use the exact menu/view resource names from the supplied File Explorer APK.
        for (String name : FILE_MANAGER_ACTION_ID_NAMES[actionIndex]) {
            try {
                int id = activity.getResources().getIdentifier(
                        name, "id", TargetApp.MI_FILE_MANAGER.packageName);
                if (id == 0) continue;
                View direct = searchViewById(preferredRoot == null ? decor : preferredRoot, id);
                View clickable = nearestClickable(direct);
                if (clickable != null && clickable.isAttachedToWindow()) return clickable;
            } catch (Throwable ignored) {}
        }

        View searchRoot = preferredRoot;
        if (searchRoot == null) searchRoot = findFileManagerNativeActionContainer(activity, null, null);
        if (searchRoot == null) searchRoot = findFileManagerActionModeRoot(activity);
        if (searchRoot == null) searchRoot = decor;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(searchRoot);
        View best = null;
        int bestY = Integer.MIN_VALUE;
        List<View> clickableCandidates = new ArrayList<>();
        Set<View> candidateSet = Collections.newSetFromMap(
                new IdentityHashMap<View, Boolean>());
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 3500) {
            View view = queue.removeFirst();
            StringBuilder descriptor = new StringBuilder();
            try {
                CharSequence description = view.getContentDescription();
                if (description != null) descriptor.append(description).append(' ');
            } catch (Throwable ignored) {}
            if (view instanceof TextView) {
                try { descriptor.append(((TextView) view).getText()).append(' '); }
                catch (Throwable ignored) {}
            }
            // MIUI ActionMenuItemView does not assign its MenuItem id to View.id. The stable
            // identity is exposed by getItemData(), including the presenter-created More item.
            try {
                Object itemData = UniversalHostAdapter.call(view, "getItemData");
                if (itemData instanceof MenuItem) {
                    MenuItem item = (MenuItem) itemData;
                    descriptor.append(item.getTitle()).append(' ');
                    descriptor.append(item.getContentDescription()).append(' ');
                    int itemId = item.getItemId();
                    if (itemId != 0) {
                        descriptor.append(activity.getResources()
                                .getResourceEntryName(itemId)).append(' ');
                    }
                }
            } catch (Throwable ignored) {}
            try {
                int id = view.getId();
                if (id != View.NO_ID && id != 0) {
                    descriptor.append(activity.getResources().getResourceEntryName(id)).append(' ');
                }
            } catch (Throwable ignored) {}
            try { descriptor.append(view.getClass().getName()); } catch (Throwable ignored) {}

            View clickable = nearestClickable(view);
            if (clickable != null && clickable.isAttachedToWindow()
                    && candidateSet.add(clickable)) {
                clickableCandidates.add(clickable);
            }
            if (matchesFileManagerAction(descriptor.toString(), actionIndex)
                    && clickable != null && clickable.isAttachedToWindow()) {
                int[] location = new int[2];
                try { clickable.getLocationOnScreen(location); } catch (Throwable ignored) {}
                if (best == null || location[1] >= bestY) {
                    best = clickable;
                    bestY = location[1];
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.addLast(group.getChildAt(i));
                }
            }
        }
        if (best != null) return best;

        return null;
    }

    private static View searchViewById(View root, int id) {
        if (root == null || id == 0 || id == View.NO_ID) return null;
        try {
            if (root.getId() == id) return root;
            return root.findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View nearestClickable(View view) {
        for (View current = view; current != null; ) {
            if (current.isClickable() || current.hasOnClickListeners()) return current;
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return null;
    }

    private static boolean matchesFileManagerAction(String text, int actionIndex) {
        if (text == null || actionIndex < 0 || actionIndex >= FILE_MANAGER_ACTION_KEYWORDS.length) {
            return false;
        }
        String normalized = text.toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        for (String keyword : FILE_MANAGER_ACTION_KEYWORDS[actionIndex]) {
            if (normalized.contains(keyword.toLowerCase(java.util.Locale.ROOT).replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }

    public static void refreshHostVisibility(Activity activity) {
        State state = getState(activity);
        if (state == null) return;
        applyWeChatFrostedSuppression(state, null);
        syncVisibilityAndSelection(state);
        ensureNavOnTop(state);
    }

    /** Reassert the exact WeChat bottom-frost field after its host setters/layout initializer. */
    public static void onWeChatFrostedContentMutated(View view) {
        if (view == null) return;
        Activity activity = ActivityFinder.from(view.getContext());
        State state = getState(activity);
        if (state == null || state.app != TargetApp.WECHAT) return;
        applyWeChatFrostedSuppression(state, view);
    }

    private static void applyWeChatFrostedSuppression(State state, View candidate) {
        if (state == null || state.app != TargetApp.WECHAT || state.activity == null) return;
        View frosted = candidate;
        if (frosted == null) {
            View decor = state.activity.getWindow() == null
                    ? null : state.activity.getWindow().getDecorView();
            frosted = UniversalHostAdapter.findViewByExactClass(
                    decor, "com.tencent.mm.ui.FrostedContentView");
        }
        if (frosted == null || !"com.tencent.mm.ui.FrostedContentView".equals(
                frosted.getClass().getName())) return;
        try {
            Object value = UniversalHostAdapter.call(frosted, "getBottomBlurAreaHeight");
            int current = value instanceof Number ? ((Number) value).intValue() : 0;
            if (!state.wechatFrostedBottomHeights.containsKey(frosted) || current != 0) {
                // A late FrostedContentView.a(...) call can publish the real host height after an
                // early install observed zero. Keep the latest non-zero host value for rollback;
                // our own zero write never overwrites it.
                state.wechatFrostedBottomHeights.put(frosted, current);
            }
            if (current != 0) {
                UniversalHostAdapter.call(frosted, "setBottomBlurAreaHeight", 0);
                frosted.invalidate();
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreWeChatFrostedSuppression(State state) {
        if (state == null || state.wechatFrostedBottomHeights.isEmpty()) return;
        for (Map.Entry<View, Integer> entry :
                new ArrayList<>(state.wechatFrostedBottomHeights.entrySet())) {
            View view = entry.getKey();
            Integer height = entry.getValue();
            if (view == null || height == null) continue;
            try {
                UniversalHostAdapter.call(view, "setBottomBlurAreaHeight", height);
                view.invalidate();
            } catch (Throwable ignored) {}
        }
        state.wechatFrostedBottomHeights.clear();
    }

    public static void remove(Activity activity) {
        XHS_READY_SINCE.remove(activity);
        XHS_READY_ROOT.remove(activity);
        NAV_HIDE_REASONS.remove(activity);
        FileManagerSelectionSession selection = FILE_MANAGER_SELECTIONS.remove(activity);
        if (selection != null) selection.release(activity);
        State state = takeState(activity);
        if (state == null) {
            removeTrackedDouyinOverlay(activity);
            return;
        }
        try {
            detachNavigationVisibilityGuard(state);
            detachSuppressionFrameGuard(state);
            if (state.nav != null && state.poll != null) state.nav.removeCallbacks(state.poll);
            restoreSnapshots(state);
            restoreWeChatFrostedSuppression(state);
            UniversalHostAdapter.restoreAwemeRightRail(activity);

            ViewGroup parent = state.nav != null && state.nav.getParent() instanceof ViewGroup
                    ? (ViewGroup) state.nav.getParent() : null;
            if (state.nav != null) state.nav.dispose();
            if (state.overlayWindowManager != null && state.container != null) {
                try { state.overlayWindowManager.removeViewImmediate(state.container); }
                catch (Throwable ignored) {}
            } else {
                if (parent != null && state.nav != null) parent.removeView(state.nav);
                if (parent != null && (MOONDROP_OVERLAY_TAG.equals(parent.getTag())
                        || GALLERY_OVERLAY_TAG.equals(parent.getTag())
                        || NETEASE_OVERLAY_TAG.equals(parent.getTag())
                        || DOUYIN_OVERLAY_TAG.equals(parent.getTag()))
                        && parent.getChildCount() == 0 && parent.getParent() instanceof ViewGroup) {
                    ((ViewGroup) parent.getParent()).removeView(parent);
                }
            }
        } catch (Throwable ignored) {}
        if (state.app == TargetApp.DOUYIN) {
            DOUYIN_OVERLAYS.remove(activity);
            synchronized (DOUYIN_INSTALL_IN_PROGRESS) {
                DOUYIN_INSTALL_IN_PROGRESS.remove(activity);
            }
        }
    }

    /**
     * Re-resolve Douyin's in-main IM composer after RipsChatRoomFragment is attached and register
     * it with the existing translation guard. The actual target while the glass is hidden is
     * calculated in applyAlignAboveGlassByTranslation() from the navigation/IME insets, so this
     * method only binds the exact component and triggers an immediate pass.
     */
    public static void refreshDouyinChatComposerPosition(Activity activity) {
        try {
            State state = getState(activity);
            if (state == null || state.app != TargetApp.DOUYIN || state.nav == null) return;
            View chatInput = UniversalHostAdapter.refreshAwemeChatInput(activity);
            if (chatInput == null || !chatInput.isAttachedToWindow()) return;
            if (!state.alignAboveGlassTranslationGapDp.containsKey(chatInput)) {
                snapshot(state, chatInput);
                state.alignAboveGlassTranslationGapDp.put(chatInput, 41f);
                markSuppressed(chatInput, state);
                ensureSuppressionLayoutGuard(chatInput);
            }
            alignExactViewAboveGlassByTranslation(state, chatInput, 41f);
        } catch (Throwable ignored) {
        }
    }

    /** Add or remove one hide reason without letting overlapping panels restore too early. */
    public static void setNavigationHidden(Activity activity, Object reason, boolean hidden) {
        try {
            if (activity == null) return;
            Object token = reason == null ? LEGACY_NAV_HIDE_REASON : reason;
            synchronized (NAV_HIDE_REASONS) {
                Set<Object> reasons = NAV_HIDE_REASONS.get(activity);
                if (hidden) {
                    if (reasons == null) {
                        reasons = Collections.newSetFromMap(
                                new WeakHashMap<Object, Boolean>()
                        );
                        NAV_HIDE_REASONS.put(activity, reasons);
                    }
                    reasons.add(token);
                } else if (reasons != null) {
                    reasons.remove(token);
                    if (reasons.isEmpty()) NAV_HIDE_REASONS.remove(activity);
                }
            }
            State state = getState(activity);
            if (state == null || state.nav == null) return;
            syncVisibilityAndSelection(state);
            if (state.nav.getVisibility() == View.VISIBLE) ensureNavOnTop(state);
        } catch (Throwable ignored) {
        }
    }

    /** Compatibility wrapper retained for existing host-specific callers. */
    public static void setNavigationVisible(Activity activity, boolean visible) {
        setNavigationHidden(activity, LEGACY_NAV_HIDE_REASON, !visible);
    }

    private static void restoreSnapshots(State state) {
        detachMeituanLateLayerGuards(state);
        for (View view : new ArrayList<>(state.snapshots.keySet())) {
            releaseSuppressedView(state, view, true);
        }
        state.snapshots.clear();
        state.collapsedRoots.clear();
        state.offscreenRoots.clear();
        state.detachedRoots.clear();
        state.transparentRoots.clear();
        state.clearRoots.clear();
        state.meituanDynamicClearRoots.clear();
        state.zeroMarginRoots.clear();
        state.zeroBottomPaddingRoots.clear();
        state.alignAboveGlassGapDp.clear();
        state.alignAboveGlassTranslationGapDp.clear();
        state.alignAboveGlassTranslationApplied.clear();
        detachSuppressionFrameGuard(state);
        state.phoneDialerExpansions.clear();
        state.phoneDialerGeometry.clear();
        state.overscanBottomDp.clear();
    }

    private static void applyInsets(final MiuixGlassNavView nav) {
        if (nav == null) return;
        nav.setOnApplyWindowInsetsListener((v, insets) -> {
            try {
                android.graphics.Insets bars = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.navigationBars()
                );
                android.graphics.Insets tappable = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.tappableElement()
                );
                android.graphics.Insets gestures = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.mandatorySystemGestures()
                );
                boolean buttons = bars.bottom > 0 && tappable.bottom > gestures.bottom;
                ViewGroup.LayoutParams raw = nav.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                    int margin = buttons ? bars.bottom : 0;
                    nav.setHyperNavigationBottomInset(Math.max(0, bars.bottom - margin));
                    if (lp.bottomMargin != margin) {
                        lp.bottomMargin = margin;
                        nav.setLayoutParams(lp);
                    }
                }
            } catch (Throwable ignored) {}
            return insets;
        });
        nav.requestApplyInsets();
    }

    private static float offscreenTranslation(View view) {
        try {
            float density = view.getResources().getDisplayMetrics().density;
            View root = view.getRootView();
            float rootHeight = root == null ? 0f : root.getHeight();
            return Math.max(rootHeight + Math.max(0, view.getHeight()) + 256f * density, 2048f * density);
        } catch (Throwable ignored) {
            return 8192f;
        }
    }

    private static int dp(Activity activity, float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int dp(View view, float value) {
        return (int) (value * view.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class ViewSnapshot {
        final ViewGroup.LayoutParams originalLayoutParams;
        final int width;
        final int height;
        final int leftMargin;
        final int topMargin;
        final int rightMargin;
        final int bottomMargin;
        final int paddingLeft;
        final int paddingTop;
        final int paddingRight;
        final int paddingBottom;
        final int renderedHeight;
        final int minimumHeight;
        final int visibility;
        final float alpha;
        final float translationX;
        final float translationY;
        final float elevation;
        final float translationZ;
        final Drawable background;
        final Drawable foreground;
        final StateListAnimator stateListAnimator;
        final boolean clickable;
        final boolean longClickable;
        final boolean focusable;
        final boolean focusableInTouchMode;
        final int importantForAccessibility;

        ViewSnapshot(View view) {
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            originalLayoutParams = raw;
            width = raw == null ? ViewGroup.LayoutParams.WRAP_CONTENT : raw.width;
            height = raw == null ? ViewGroup.LayoutParams.WRAP_CONTENT : raw.height;
            if (raw instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
                leftMargin = margins.leftMargin;
                topMargin = margins.topMargin;
                rightMargin = margins.rightMargin;
                bottomMargin = margins.bottomMargin;
            } else {
                leftMargin = topMargin = rightMargin = bottomMargin = 0;
            }
            paddingLeft = view.getPaddingLeft();
            paddingTop = view.getPaddingTop();
            paddingRight = view.getPaddingRight();
            paddingBottom = view.getPaddingBottom();
            renderedHeight = Math.max(view.getHeight(), view.getMeasuredHeight());
            minimumHeight = view.getMinimumHeight();
            visibility = view.getVisibility();
            alpha = view.getAlpha();
            translationX = view.getTranslationX();
            translationY = view.getTranslationY();
            elevation = view.getElevation();
            translationZ = view.getTranslationZ();
            background = view.getBackground();
            foreground = view.getForeground();
            stateListAnimator = view.getStateListAnimator();
            clickable = view.isClickable();
            longClickable = view.isLongClickable();
            focusable = view.isFocusable();
            focusableInTouchMode = view.isFocusableInTouchMode();
            importantForAccessibility = view.getImportantForAccessibility();
        }

        void restore(View view) {
            try {
                ViewGroup.LayoutParams raw = originalLayoutParams != null
                        ? originalLayoutParams : view.getLayoutParams();
                if (raw != null) {
                    raw.width = width;
                    raw.height = height;
                    if (raw instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
                        margins.leftMargin = leftMargin;
                        margins.topMargin = topMargin;
                        margins.rightMargin = rightMargin;
                        margins.bottomMargin = bottomMargin;
                    }
                }
                view.setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom);
                view.setMinimumHeight(minimumHeight);
                view.setBackground(background);
                view.setForeground(foreground);
                view.setElevation(elevation);
                view.setTranslationZ(translationZ);
                view.setStateListAnimator(stateListAnimator);
                view.setAlpha(alpha);
                view.setTranslationX(translationX);
                view.setTranslationY(translationY);
                view.setClickable(clickable);
                view.setLongClickable(longClickable);
                view.setFocusable(focusable);
                view.setFocusableInTouchMode(focusableInTouchMode);
                view.setImportantForAccessibility(importantForAccessibility);
                view.setVisibility(visibility);
                if (raw != null) view.setLayoutParams(raw);
                view.requestLayout();
            } catch (Throwable ignored) {}
        }
    }

    private static final class PhoneDialerGeometry {
        final View anchor;
        final float originalBottomOffset;

        PhoneDialerGeometry(View anchor, float originalBottomOffset) {
            this.anchor = anchor;
            this.originalBottomOffset = originalBottomOffset;
        }
    }

    private static final class GalleryDetachedNavigation {
        final WeakReference<ViewGroup> parent;
        final int index;
        final ViewGroup.LayoutParams layoutParams;
        final WeakReference<View> placeholder;
        final ViewSnapshot snapshot;

        GalleryDetachedNavigation(
                ViewGroup parent,
                int index,
                ViewGroup.LayoutParams layoutParams,
                View placeholder,
                ViewSnapshot snapshot
        ) {
            this.parent = new WeakReference<>(parent);
            this.index = index;
            this.layoutParams = layoutParams;
            this.placeholder = placeholder == null ? null : new WeakReference<>(placeholder);
            this.snapshot = snapshot;
        }
    }

    private static final class FileManagerSelectionSession {
        private WeakReference<Object> callback;
        private WeakReference<Object> actionMode;
        private WeakReference<View> actionModeView;
        private WeakReference<View> nativeActionContainer;
        private WeakReference<View> guardedNativeContainer;
        private ViewTreeObserver.OnPreDrawListener nativeSuppressionGuard;
        private float guardedOriginalAlpha = 1f;
        private int guardedOriginalAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        private boolean guardedStateCaptured;
        private boolean nativeBindingScheduled;
        private boolean refreshPosted;
        @SuppressWarnings("unchecked")
        private final WeakReference<View>[] actionViews = new WeakReference[
                FILE_MANAGER_ACTION_LABELS.length];

        FileManagerSelectionSession(Object callback, Object actionMode) {
            update(callback, actionMode);
        }

        void update(Object newCallback, Object newActionMode) {
            if (newCallback != null) callback = new WeakReference<>(newCallback);
            if (newActionMode instanceof ActionMode) {
                actionMode = new WeakReference<>(newActionMode);
                View resolved = resolveFileManagerActionModeView((ActionMode) newActionMode);
                if (resolved != null) actionModeView = new WeakReference<>(resolved);
            }
        }

        Object callback() { return callback == null ? null : callback.get(); }
        ActionMode actionMode() {
            Object value = actionMode == null ? null : actionMode.get();
            return value instanceof ActionMode ? (ActionMode) value : null;
        }

        View actionModeView() {
            View view = actionModeView == null ? null : actionModeView.get();
            if (view == null) {
                view = resolveFileManagerActionModeView(actionMode());
                if (view != null) actionModeView = new WeakReference<>(view);
            }
            return view;
        }

        void bindActionModeView(View view, Object mode) {
            update(null, mode);
            if (view != null) actionModeView = new WeakReference<>(view);
        }

        synchronized boolean markNativeBindingScheduled() {
            if (nativeBindingScheduled) return false;
            nativeBindingScheduled = true;
            return true;
        }

        synchronized boolean markRefreshPosted() {
            if (refreshPosted) return false;
            refreshPosted = true;
            return true;
        }

        synchronized void clearRefreshPosted() { refreshPosted = false; }

        void captureNativeActions(Activity activity) {
            if (activity == null) return;
            ActionMode mode = actionMode();
            View modeView = actionModeView();
            View container = fileManagerMenuViewFromActionModeView(modeView);
            // initForMode is the verified creation point. If its presenter has not attached the
            // exact operation menu yet, wait for the one postOnAnimation retry instead of walking
            // thousands of unrelated host Views on the main thread.
            if (!isFileManagerActionMenuView(container, mode)) return;
            View exactRoot = container;
            int[] currentActionIds = isFileManagerPrivateMenu(mode.getMenu())
                    ? FILE_MANAGER_PRIVATE_ACTION_IDS : FILE_MANAGER_EXACT_ACTION_IDS;

            int resolved = 0;
            for (int index = 0; index < actionViews.length; index++) {
                View view = null;
                if (exactRoot != null && index < 3) {
                    int id = currentActionIds[index];
                    if (id != 0) view = nearestClickable(searchViewById(exactRoot, id));
                }
                if (view == null) view = findFileManagerActionView(activity, index, exactRoot);
                if (view != null) {
                    actionViews[index] = new WeakReference<>(view);
                    resolved++;
                } else {
                    actionViews[index] = null;
                }
            }
            // ResponsiveActionMenuView is rebuilt for every ActionMode. Replace by object identity
            // whenever initForMode creates the next exact row; never keep a stale detached row.
            if (resolved >= 3 || fileManagerActionNavigationScore(activity, container) >= 5) {
                setNativeActionContainer(container);
            }
        }

        void suppressNativeActionContainer(Activity activity) {
            View container = nativeActionContainer();
            if (container == null || !container.isAttachedToWindow()) return;
            installNativeSuppressionGuard(container);
            applyNativeSuppression(container);
            container.post(() -> applyNativeSuppression(container));
        }

        private void installNativeSuppressionGuard(View container) {
            View guarded = guardedNativeContainer == null ? null : guardedNativeContainer.get();
            if (guarded == container && nativeSuppressionGuard != null) return;
            releaseGuardedContainerNow();
            guardedOriginalAlpha = container.getAlpha();
            guardedOriginalAccessibility = container.getImportantForAccessibility();
            guardedStateCaptured = true;
            nativeSuppressionGuard = () -> {
                View current = guardedNativeContainer == null ? null : guardedNativeContainer.get();
                if (current != null && current.isAttachedToWindow()) applyNativeSuppression(current);
                return true;
            };
            guardedNativeContainer = new WeakReference<>(container);
            try {
                container.getViewTreeObserver().addOnPreDrawListener(nativeSuppressionGuard);
            } catch (Throwable ignored) {}
        }

        private void applyNativeSuppression(View container) {
            if (container == null) return;
            try {
                // Keep the real action row attached, measured and at its native coordinate so the
                // presenter can anchor More. The full-width injected host owns bottom touches;
                // mutating OnTouchListener here used to survive ActionMode and break later menus.
                if (container.getAlpha() != 0f) container.setAlpha(0f);
                container.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                container.invalidate();
            } catch (Throwable ignored) {}
        }

        private void removeNativeSuppressionGuard(View guarded) {
            if (guarded == null || nativeSuppressionGuard == null) return;
            try {
                ViewTreeObserver observer = guarded.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnPreDrawListener(nativeSuppressionGuard);
            } catch (Throwable ignored) {}
            nativeSuppressionGuard = null;
        }

        private void releaseGuardedContainerNow() {
            View guarded = guardedNativeContainer == null ? null : guardedNativeContainer.get();
            removeNativeSuppressionGuard(guarded);
            if (guarded != null && guardedStateCaptured) {
                try { guarded.setAlpha(guardedOriginalAlpha); } catch (Throwable ignored) {}
                try { guarded.setImportantForAccessibility(guardedOriginalAccessibility); }
                catch (Throwable ignored) {}
            }
            guardedNativeContainer = null;
            guardedStateCaptured = false;
        }

        void release(Activity activity) {
            View guarded = guardedNativeContainer == null ? null : guardedNativeContainer.get();
            removeNativeSuppressionGuard(guarded);
            final float originalAlpha = guardedOriginalAlpha;
            final int originalAccessibility = guardedOriginalAccessibility;
            final boolean restore = guardedStateCaptured;
            guardedNativeContainer = null;
            guardedStateCaptured = false;
            if (guarded != null && restore) {
                WeakReference<Activity> owner = new WeakReference<>(activity);
                guarded.postDelayed(() -> {
                    Activity currentActivity = owner.get();
                    FileManagerSelectionSession current = currentActivity == null
                            ? null : FILE_MANAGER_SELECTIONS.get(currentActivity);
                    if (current != null && current.nativeActionContainer() == guarded) return;
                    try { guarded.setAlpha(originalAlpha); } catch (Throwable ignored) {}
                    try { guarded.setImportantForAccessibility(originalAccessibility); }
                    catch (Throwable ignored) {}
                }, 280L);
            }
            nativeActionContainer = null;
            actionModeView = null;
            actionMode = null;
            callback = null;
            for (int index = 0; index < actionViews.length; index++) actionViews[index] = null;
        }

        boolean[] enabledActions(Activity activity) {
            boolean[] enabled = new boolean[]{true, true, true, true};
            ActionMode mode = actionMode();
            Menu menu = null;
            try { if (mode != null) menu = mode.getMenu(); } catch (Throwable ignored) {}
            if (menu == null) return enabled;
            int[] currentActionIds = isFileManagerPrivateMenu(menu)
                    ? FILE_MANAGER_PRIVATE_ACTION_IDS : FILE_MANAGER_EXACT_ACTION_IDS;
            for (int index = 0; index < 3; index++) {
                MenuItem item = findFileManagerMenuItem(activity, menu, index);
                if (item == null) continue;
                try { enabled[index] = item.isVisible() && item.isEnabled(); }
                catch (Throwable ignored) {}
            }
            boolean hasOverflow = false;
            try {
                for (int index = 0; index < menu.size(); index++) {
                    MenuItem item = menu.getItem(index);
                    if (item == null || !item.isVisible()) continue;
                    int id = item.getItemId();
                    if (id != currentActionIds[0]
                            && id != currentActionIds[1]
                            && id != currentActionIds[2]) {
                        hasOverflow = true;
                        break;
                    }
                }
                enabled[3] = hasOverflow;
            } catch (Throwable ignored) {}
            return enabled;
        }

        boolean isPrivateActionMode() {
            ActionMode mode = actionMode();
            try { return mode != null && isFileManagerPrivateMenu(mode.getMenu()); }
            catch (Throwable ignored) { return false; }
        }

        void setNativeActionContainer(View view) {
            nativeActionContainer = view == null ? null : new WeakReference<>(view);
        }

        View nativeActionContainer() {
            return nativeActionContainer == null ? null : nativeActionContainer.get();
        }

        View actionView(int index) {
            if (index < 0 || index >= actionViews.length) return null;
            WeakReference<View> reference = actionViews[index];
            return reference == null ? null : reference.get();
        }
    }

    private static final class FileManagerActionAdapter implements HostNavigationAdapter {
        private final WeakReference<State> state;

        FileManagerActionAdapter(State state) {
            this.state = new WeakReference<>(state);
        }

        @Override public boolean select(int index) {
            return dispatchFileManagerAction(state.get(), index);
        }

        @Override public boolean post() { return false; }
        @Override public int detectSelectedIndex() { return -1; }

        @Override public View getBackdropSourceView() {
            State current = state.get();
            return current == null || current.adapter == null
                    ? null : current.adapter.getBackdropSourceView();
        }
    }

    private static final class State {
        final Activity activity;
        final TargetApp app;
        TargetAppSettings appSettings;
        UniversalHostAdapter adapter;
        MiuixGlassNavView nav;
        FrameLayout container;
        WindowManager overlayWindowManager;
        WindowManager.LayoutParams overlayWindowParams;
        View primaryRoot;
        View backdropSource;
        boolean backdropRefreshPending = true;
        Runnable poll;
        final Map<View, ViewSnapshot> snapshots = new WeakHashMap<>();
        final Map<View, Integer> wechatFrostedBottomHeights = new WeakHashMap<>();
        final Set<View> collapsedRoots = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        final Set<View> offscreenRoots = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        final Set<View> detachedRoots = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        final Set<View> transparentRoots = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        final Set<View> clearRoots = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        // Meituan's page-level Mach/ViewStub layers are discovered after the initial plan and
        // need identity tracking so replaced Fragment views can be restored and released.
        final Set<View> meituanDynamicClearRoots =
                Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        final Map<ViewGroup, View.OnLayoutChangeListener> meituanLateLayerGuards =
                new IdentityHashMap<>();
        final Map<ViewGroup, Map<String, Integer>> meituanLateLayerSlots =
                new IdentityHashMap<>();
        final Map<ViewGroup, Integer> meituanHomeMaskSlots = new IdentityHashMap<>();
        final Set<View> zeroMarginRoots = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        final Set<View> zeroBottomPaddingRoots = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
        final Map<View, Float> alignAboveGlassGapDp = new IdentityHashMap<>();
        final Map<View, Float> alignAboveGlassTranslationGapDp = new IdentityHashMap<>();
        final Map<View, Float> alignAboveGlassTranslationApplied = new IdentityHashMap<>();
        final List<UniversalHostAdapter.SuppressionPlan.PhoneDialerExpansion>
                phoneDialerExpansions = new ArrayList<>();
        final Map<View, PhoneDialerGeometry> phoneDialerGeometry = new IdentityHashMap<>();
        final Map<View, Float> overscanBottomDp = new IdentityHashMap<>();
        final Map<View, Float> extendBottomDp = new IdentityHashMap<>();
        ViewTreeObserver.OnPreDrawListener suppressionFrameGuard;
        View suppressionFrameGuardRoot;
        ViewTreeObserver.OnGlobalLayoutListener navigationVisibilityGuard;
        View navigationVisibilityGuardRoot;
        Runnable navigationVisibilityRefresh;
        boolean navigationVisibilityRefreshPosted;
        int lastNavigationVisibilitySignature = Integer.MIN_VALUE;
        long lastXhsBarScanAtMs;
        int lastDetectedIndex = -1;
        long lastAdapterProbeAtMs;
        long lastForcedSettingsReadAtMs;
        long lastSuppressionHeartbeatAtMs;
        boolean fileManagerSelectionMode;
        FileManagerSelectionSession fileManagerSelectionSession;
        HostNavigationAdapter fileManagerActionAdapter;
        boolean timConversationMode;
        boolean neteaseDrawerOpen;
        long appliedSettingsRevision = Long.MIN_VALUE;
        long appliedGlobalRenderRevision = Long.MIN_VALUE;

        State(
                Activity activity,
                TargetApp app,
                UniversalHostAdapter adapter,
                TargetAppSettings settings
        ) {
            this.activity = activity;
            this.app = app;
            this.adapter = adapter;
            this.appSettings = settings;
            long now = SystemClock.uptimeMillis();
            this.lastAdapterProbeAtMs = now;
            this.lastForcedSettingsReadAtMs = now;
            this.lastSuppressionHeartbeatAtMs = now;
        }
    }
}
