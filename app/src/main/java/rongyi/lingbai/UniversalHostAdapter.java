package rongyi.lingbai;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.TextView;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Exact navigation bridges for the uploaded host APKs.
 *
 * Navigation suppression stays limited to classes, fields and resources verified in the
 * supplied APK versions. Backdrop selection may validate whether a page root actually covers the
 * glass sampling strip, but it is never used to guess or suppress a bottom navigation view.
 */
public abstract class UniversalHostAdapter implements HostNavigationAdapter {
    protected final Activity activity;
    protected final TargetApp app;
    protected final TargetAppSettings settings;
    protected final int[] visibleNativeIds;
    protected final String[] visibleLabels;

    // UU's Compose home keeps its tab presenter in the composable argument rather than in a View.
    // The exact native navigation composable is hooked before the first composition and records
    // this weak reference so the replacement glass bar can still select the host tab directly.
    private static volatile WeakReference<Object> UU_COMPOSE_STATE =
            new WeakReference<>(null);

    static void cacheUuComposeState(Object state) {
        if (state != null) UU_COMPOSE_STATE = new WeakReference<>(state);
    }

    private static Object cachedUuComposeState() {
        WeakReference<Object> reference = UU_COMPOSE_STATE;
        return reference == null ? null : reference.get();
    }

    static int[] uuComposeNativeIds(Object state) {
        Object tabs = call(getField(state, "x", "f7073x"), "getValue");
        if (!(tabs instanceof List)) return null;
        List<?> values = (List<?>) tabs;
        if (values.size() != 3 && values.size() != 4) return null;
        int[] ids = new int[values.size()];
        int[] expected = values.size() == 3 ? new int[]{0, 2, 3} : new int[]{0, 1, 2, 3};
        for (int i = 0; i < ids.length; i++) {
            Object tab = values.get(i);
            if (!(tab instanceof Enum)) return null;
            ids[i] = ((Enum<?>) tab).ordinal();
            if (ids[i] != expected[i]) return null;
        }
        return ids;
    }

    /**
     * Douyin constructs its stable BottomTab controller objects separately from the visual
     * MainBottomTabContainer. UniversalHookEntry records the exact controller/view pairs here as
     * BottomTab#getView() is called, allowing the adapter to keep routing through Douyin's own
     * tab objects after the native row is translated off-screen.
     */
    private static final Map<Object, WeakReference<View>> AWEME_BOTTOM_TAB_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<View>>());
    private static WeakReference<View> AWEME_PUBLISH_BUTTON = new WeakReference<>(null);
    private static WeakReference<View.OnClickListener> AWEME_PUBLISH_LISTENER = new WeakReference<>(null);
    private static WeakReference<View> AWEME_RIGHT_RAIL = new WeakReference<>(null);
    private static WeakReference<View> AWEME_LEFT_INFO = new WeakReference<>(null);
    private static WeakReference<View> AWEME_MUSIC_BLOCK = new WeakReference<>(null);
    private static WeakReference<View> AWEME_MIX_BLOCK = new WeakReference<>(null);
    private static WeakReference<View> AWEME_CUT_SAME_BLOCK = new WeakReference<>(null);
    private static WeakReference<View> AWEME_COLLECTION_CACHE = new WeakReference<>(null);
    private static WeakReference<View> AWEME_CHAT_INPUT_CACHE = new WeakReference<>(null);
    private static WeakReference<View> AWEME_SHARE_CACHE = new WeakReference<>(null);
    private static WeakReference<View> AWEME_LISTEN_CACHE = new WeakReference<>(null);
    private static final String[] AWEME_RIGHT_RAIL_CLASS_MARKERS = {
            "feed.widget.right",
            "FeedItemRight",
            "FeedRight",
            // Music disc (below the share button), video progress bar and 合集 block are also
            // bottom-anchored and follow the same overscan displacement.
            "MusicCover",
            "FeedBottomMusic",
            "BaseVideoMusic",
            "FeedVideoMusic",
            "LineProgressBar",
            "FeedBottomMix",
            "VideoCutSame",
            // In-main chat input box and related bottom-anchored IM views.
            "InputPanel",
            "SoftInputResize",
            "InputSend",
            "InputBar",
            "PriorityLinearLayout",
            "InputChannel",
            "ChatInput",
            "MsgInput",
    };

    static boolean registerAwemeBottomTab(Object tab, View view) {
        if (tab == null || view == null) return false;
        synchronized (AWEME_BOTTOM_TAB_VIEWS) {
            WeakReference<View> previous = AWEME_BOTTOM_TAB_VIEWS.get(tab);
            View previousView = previous == null ? null : previous.get();
            if (previousView == view) return false;
            AWEME_BOTTOM_TAB_VIEWS.put(tab, new WeakReference<>(view));
            return true;
        }
    }

    static boolean registerAwemePublishButton(View view) {
        if (view == null) return false;
        View previous = AWEME_PUBLISH_BUTTON == null ? null : AWEME_PUBLISH_BUTTON.get();
        if (previous == view) return false;
        AWEME_PUBLISH_BUTTON = new WeakReference<>(view);
        return true;
    }

    static boolean registerAwemePublishListener(View.OnClickListener listener) {
        if (listener == null) return false;
        AWEME_PUBLISH_LISTENER = new WeakReference<>(listener);
        return true;
    }

    /** Captured feed-item right rail (avatar / 点赞 / 评论 / 收藏 / 分享) container. */
    static boolean registerAwemeRightRail(View view) {
        if (view == null) return false;
        View previous = AWEME_RIGHT_RAIL == null ? null : AWEME_RIGHT_RAIL.get();
        if (previous == view) return false;
        AWEME_RIGHT_RAIL = new WeakReference<>(view);
        return true;
    }

    /** Captured feed-item bottom-left info container (author / 作品 / 简介 / 合集). */
    static boolean registerAwemeLeftInfo(View view) {
        if (view == null) return false;
        View previous = AWEME_LEFT_INFO == null ? null : AWEME_LEFT_INFO.get();
        if (previous == view) return false;
        AWEME_LEFT_INFO = new WeakReference<>(view);
        return true;
    }

    /** Captured feed-item music disc block container. */
    static boolean registerAwemeMusicBlock(View view) {
        if (view == null) return false;
        View previous = AWEME_MUSIC_BLOCK == null ? null : AWEME_MUSIC_BLOCK.get();
        if (previous == view) return false;
        AWEME_MUSIC_BLOCK = new WeakReference<>(view);
        return true;
    }

    /** Captured feed-item 合集 (collection/series) block container. */
    static boolean registerAwemeMixBlock(View view) {
        if (view == null) return false;
        View previous = AWEME_MIX_BLOCK == null ? null : AWEME_MIX_BLOCK.get();
        if (previous == view) return false;
        AWEME_MIX_BLOCK = new WeakReference<>(view);
        return true;
    }

    /** Captured feed-item 拍同款 (make-same-video) block container. */
    static boolean registerAwemeCutSameBlock(View view) {
        if (view == null) return false;
        View previous = AWEME_CUT_SAME_BLOCK == null ? null : AWEME_CUT_SAME_BLOCK.get();
        if (previous == view) return false;
        AWEME_CUT_SAME_BLOCK = new WeakReference<>(view);
        return true;
    }

    /**
     * Resolve the current 合集 (collection/series) block for the poll heartbeat. Prefers the
     * captured module view, then locates the container by its "合集" text. The result is cached
     * until it detaches, so the heartbeat is cheap after the first find.
     */
    static View refreshAwemeCollection(Activity activity) {
        if (activity == null) return null;
        try {
            View cached = AWEME_COLLECTION_CACHE == null ? null : AWEME_COLLECTION_CACHE.get();
            if (cached != null && cached.isAttachedToWindow()
                    && ActivityFinder.from(cached.getContext()) == activity) {
                return cached;
            }
            View found = null;
            View captured = AWEME_MIX_BLOCK == null ? null : AWEME_MIX_BLOCK.get();
            if (captured != null && captured.isAttachedToWindow()
                    && ActivityFinder.from(captured.getContext()) == activity) {
                found = captured;
            } else {
                found = findAwemeCollectionByText(activity);
            }
            if (found != null) {
                AWEME_COLLECTION_CACHE = new WeakReference<>(found);
                return found;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static View findAwemeCollectionByText(Activity activity) {
        try {
            View content = activity.findViewById(android.R.id.content);
            if (content == null) return null;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(content);
            int visited = 0;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            while (!queue.isEmpty() && visited++ < 1500) {
                View view = queue.removeFirst();
                String text = view instanceof TextView
                        ? String.valueOf(((TextView) view).getText()) : "";
                CharSequence desc = view.getContentDescription();
                String key = text + " " + (desc == null ? "" : desc.toString());
                if (key.contains("合集")) {
                    // Walk up to the nearest enclosing non-full-screen container.
                    View target = view;
                    View current = view;
                    for (int depth = 0; depth < 5 && current != null; depth++) {
                        View parent = current.getParent() instanceof View
                                ? (View) current.getParent() : null;
                        if (parent == null) break;
                        String parentName = parent.getClass().getName();
                        boolean scrollHost = parentName.contains("RecyclerView")
                                || parentName.contains("ViewPager");
                        boolean fullScreenShell = parent.getHeight() > 0
                                && parent.getHeight() >= screenH * 0.9f;
                        if (scrollHost || fullScreenShell) break;
                        target = parent;
                        current = parent;
                    }
                    return target;
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.addLast(group.getChildAt(i));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Resolve the in-main chat input box for the poll heartbeat. Locates the input by its
     * "发消息" hint text (version-independent) and caches the enclosing container until detach.
     */
    static boolean isCachedAwemeChatInput(View view) {
        if (view == null) return false;
        View cached = AWEME_CHAT_INPUT_CACHE == null ? null : AWEME_CHAT_INPUT_CACHE.get();
        return cached == view;
    }

    static View refreshAwemeChatInput(Activity activity) {
        if (activity == null) return null;
        try {
            View cached = AWEME_CHAT_INPUT_CACHE.get();
            if (cached != null && cached.isAttachedToWindow() && cached.isShown()
                    && cached.getAlpha() > 0.01f
                    && ActivityFinder.from(cached.getContext()) == activity) {
                return cached;
            }
            View found = findAwemeChatInputByText(activity);
            if (found != null) {
                AWEME_CHAT_INPUT_CACHE = new WeakReference<>(found);
                return found;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static View findAwemeChatInputByText(Activity activity) {
        try {
            View content = activity.findViewById(android.R.id.content);
            if (content == null) return null;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(content);
            int visited = 0;
            float density = activity.getResources().getDisplayMetrics().density;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            while (!queue.isEmpty() && visited++ < 2600) {
                View view = queue.removeFirst();
                String className = view.getClass().getName();
                String text = view instanceof TextView
                        ? String.valueOf(((TextView) view).getText()) : "";
                String hint = view instanceof TextView
                        ? String.valueOf(((TextView) view).getHint()) : "";
                CharSequence desc = view.getContentDescription();
                String key = text + " " + hint + " " + (desc == null ? "" : desc.toString());

                // The supplied Douyin APK exposes this concrete IM editor. Text/hint matching is
                // retained as a version-independent fallback, but preferring RichEditText avoids
                // accidentally grabbing a feed/comment button whose accessibility text also says
                // 发消息.
                boolean exactImEditor = className.equals(
                        "com.ss.android.ugc.aweme.im.sdk.chat.view.RichEditText");
                boolean messageEditor = exactImEditor
                        || key.contains("发消息") || key.contains("发送消息");
                if (messageEditor && view.isAttachedToWindow() && view.isShown()) {
                    View target = view;
                    View current = view;
                    for (int depth = 0; depth < 7 && current != null; depth++) {
                        View parent = current.getParent() instanceof View
                                ? (View) current.getParent() : null;
                        if (parent == null) break;
                        String parentName = parent.getClass().getName();
                        boolean scrollHost = parentName.contains("RecyclerView")
                                || parentName.contains("ViewPager");
                        boolean fullScreenShell = parent.getHeight() > 0
                                && parent.getHeight() >= screenH * 0.72f;
                        if (scrollHost || fullScreenShell) break;

                        int[] loc = new int[2];
                        try { parent.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                        int bottom = loc[1] + Math.max(0, parent.getHeight());
                        boolean nearBottom = bottom >= screenH - (int) (360f * density);
                        boolean rowSized = parent.getHeight() > 0
                                && parent.getHeight() <= (int) (220f * density)
                                && parent.getWidth() >= Math.min(screenW, (int) (240f * density));
                        if (nearBottom && rowSized) target = parent;

                        // Verified in the uploaded APK: PriorityLinearLayout is the compact input
                        // drawer row. Stop here rather than translating SoftInputResizeFuncLayoutView
                        // or another full chat shell.
                        if (parentName.equals(
                                "com.ss.android.ugc.aweme.im.business.chat.feature.input.drawer.view.PriorityLinearLayout")) {
                            target = parent;
                            break;
                        }
                        if (parentName.contains("SoftInputResizeFuncLayoutView")) break;
                        current = parent;
                    }
                    return target;
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.addLast(group.getChildAt(i));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static View refreshAwemeShareButton(Activity activity) {
        if (activity == null) return null;
        try {
            View cached = AWEME_SHARE_CACHE.get();
            if (cached != null && cached.isAttachedToWindow()
                    && ActivityFinder.from(cached.getContext()) == activity) {
                return cached;
            }
            View found = findAwemeShareButtonByText(activity);
            if (found != null) {
                AWEME_SHARE_CACHE = new WeakReference<>(found);
                return found;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static View findAwemeShareButtonByText(Activity activity) {
        try {
            View content = activity.findViewById(android.R.id.content);
            if (content == null) return null;
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(content);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 2000) {
                View view = queue.removeFirst();
                String text = view instanceof TextView
                        ? String.valueOf(((TextView) view).getText()) : "";
                CharSequence desc = view.getContentDescription();
                String key = text + " " + (desc == null ? "" : desc.toString());
                if (key.contains("分享")) {
                    int[] loc = new int[2];
                    try {
                        view.getLocationOnScreen(loc);
                        // Prefer the right-rail share (right side of the screen).
                        if (loc[0] > screenW * 0.55f
                                || view.getClass().getName().contains("FeedShare")) {
                            return view;
                        }
                    } catch (Throwable ignored) {
                        return view;
                    }
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.addLast(group.getChildAt(i));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Align every music-disc-area component (音乐圆盘 / 合集 / 拍同款 / 听抖音) so its bottom sits
     * just below the 分享 button. Returns false when the share button cannot be found.
     */
    static boolean alignAwemeMusicCluster(Activity activity) {
        if (activity == null) return false;
        View share = refreshAwemeShareButton(activity);
        if (share == null) return false;
        int[] loc = new int[2];
        try {
            share.getLocationOnScreen(loc);
        } catch (Throwable ignored) {
            return false;
        }
        if (loc[1] <= 0) return false;
        float density = activity.getResources().getDisplayMetrics().density;
        int targetBottom = loc[1] + share.getHeight() + Math.round(4f * density);

        View[] targets = {
                AWEME_MUSIC_BLOCK == null ? null : AWEME_MUSIC_BLOCK.get(),
                refreshAwemeCollection(activity),
                AWEME_CUT_SAME_BLOCK == null ? null : AWEME_CUT_SAME_BLOCK.get(),
                refreshAwemeListenBlock(activity),
        };
        for (View target : targets) {
            if (target == null || !target.isAttachedToWindow()
                    || ActivityFinder.from(target.getContext()) != activity) continue;
            try {
                target.getLocationOnScreen(loc);
                int currentBottom = loc[1] + target.getHeight();
                int shift = targetBottom - currentBottom;
                if (Math.abs(target.getTranslationY() - shift) > 0.5f) {
                    target.setTranslationY(shift);
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    /** The 听抖音 text container, used when the music block capture is unavailable. */
    static View refreshAwemeListenBlock(Activity activity) {
        if (activity == null) return null;
        try {
            View cached = AWEME_LISTEN_CACHE.get();
            if (cached != null && cached.isAttachedToWindow()
                    && ActivityFinder.from(cached.getContext()) == activity) {
                return cached;
            }
            View content = activity.findViewById(android.R.id.content);
            if (content == null) return null;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(content);
            int visited = 0;
            int screenH = activity.getResources().getDisplayMetrics().heightPixels;
            while (!queue.isEmpty() && visited++ < 2000) {
                View view = queue.removeFirst();
                String text = view instanceof TextView
                        ? String.valueOf(((TextView) view).getText()) : "";
                CharSequence desc = view.getContentDescription();
                String key = text + " " + (desc == null ? "" : desc.toString());
                if (key.contains("听抖音")) {
                    View target = view;
                    View current = view;
                    for (int depth = 0; depth < 5 && current != null; depth++) {
                        View parent = current.getParent() instanceof View
                                ? (View) current.getParent() : null;
                        if (parent == null) break;
                        String parentName = parent.getClass().getName();
                        boolean scrollHost = parentName.contains("RecyclerView")
                                || parentName.contains("ViewPager");
                        boolean fullScreenShell = parent.getHeight() > 0
                                && parent.getHeight() >= screenH * 0.9f;
                        if (scrollHost || fullScreenShell) break;
                        target = parent;
                        current = parent;
                    }
                    AWEME_LISTEN_CACHE = new WeakReference<>(target);
                    return target;
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.addLast(group.getChildAt(i));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static void restoreAwemeRightRail(Activity activity) {
        if (activity == null) return;
        try {
            View[] captured = {
                    AWEME_RIGHT_RAIL == null ? null : AWEME_RIGHT_RAIL.get(),
                    AWEME_LEFT_INFO == null ? null : AWEME_LEFT_INFO.get(),
                    AWEME_MUSIC_BLOCK == null ? null : AWEME_MUSIC_BLOCK.get(),
                    AWEME_MIX_BLOCK == null ? null : AWEME_MIX_BLOCK.get(),
                    AWEME_CUT_SAME_BLOCK == null ? null : AWEME_CUT_SAME_BLOCK.get(),
            };
            for (View view : captured) {
                if (view != null && ActivityFinder.from(view.getContext()) == activity) {
                    view.setTranslationY(0f);
                }
            }
            View content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(content);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 800) {
                View view = queue.removeFirst();
                if ((isAwemeRightRailClass(view.getClass().getName())
                        || isAwemeLeftOverlayClass(view.getClass().getName()))
                        && Math.abs(view.getTranslationY()) > 0.5f) {
                    view.setTranslationY(0f);
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isAwemeRightRailClass(String name) {
        if (name == null) return false;
        for (String marker : AWEME_RIGHT_RAIL_CLASS_MARKERS) {
            if (name.contains(marker)) return true;
        }
        return false;
    }

    private static boolean isAwemeLeftOverlayClass(String name) {
        if (name == null) return false;
        return name.contains("FeedItemLeft")
                || name.contains("FeedVideoMusic")
                || name.contains("FeedDesc")
                || name.contains("FeedPostTime")
                || name.contains("FeedLabel");
    }

    protected UniversalHostAdapter(Activity activity, TargetApp app, TargetAppSettings settings) {
        this.activity = activity;
        this.app = app;
        this.settings = settings;
        this.visibleNativeIds = app.visibleNativeIds(settings);
        this.visibleLabels = app.visibleLabels(settings);
    }

    public static UniversalHostAdapter create(Activity activity, TargetApp app, TargetAppSettings settings) {
        if (activity == null || app == null) return null;
        if (LiquidTabPortAdapter.supports(app)) return LiquidTabPortAdapter.create(activity, app, settings);
        switch (app.key) {
            case "magisk": return new MagiskAdapter(activity, app, settings);
            case "github": return new GithubAdapter(activity, app, settings);
            case "wechat": return new WeChatAdapter(activity, app, settings);
            case "jd": return new JdAdapter(activity, app, settings);
            case "tim": return new TimAdapter(activity, app, settings);
            case "moondrop": return new FlutterAdapter(activity, app, settings);
            case "bilibili":
            case "bilibili_hd":
                return new BilibiliAdapter(activity, app, settings);
            case "bilibili_in":
            case "mi_market":
            case "mi_community":
            case "weibo":
            case "tieba":
            case "taobao":
                return new LiquidTabHostAdapter(activity, app, settings);
            case "midea": return new MideaAdapter(activity, app, settings);
            case "meituan": return new MeituanAdapter(activity, app, settings);
            case "cainiao": return new CainiaoAdapter(activity, app, settings);
            case "meituan_main": return new MeituanMainAdapter(activity, app, settings);
            case "mihome": return new MiHomeAdapter(activity, app, settings);
            case "mi_fitness": return new MiFitnessAdapter(activity, app, settings);
            case "netease": return new NeteaseAdapter(activity, app, settings);
            case "qqmusic": return new QQMusicAdapter(activity, app, settings);
            case "uuremote": return new UuRemoteAdapter(activity, app, settings);
            case "stardesk": return new StarDeskAdapter(activity, app, settings);
            case "xhs": return new XhsAdapter(activity, app, settings);
            case "pdd": return new PddAdapter(activity, app, settings);
            case "douyin": return new AwemeAdapter(activity, app, settings);
            case "mi_file_manager":
            case "mi_phone":
            case "mi_messages":
            case "mi_gallery":
            case "mi_notes":
                return new MiuixNavigatorAdapter(activity, app, settings);
            case "mi_calendar": return new MiCalendarAdapter(activity, app, settings);
            case "mi_compass": return new MiCompassAdapter(activity, app, settings);
            default: return null;
        }
    }

    static void invalidateCachedHostRoot(Activity activity, TargetApp app) {
        if (activity != null && app == TargetApp.XHS) XhsAdapter.invalidate(activity);
    }

    public String[] getVisibleLabels() { return visibleLabels; }

    @Override public int centerActionTabIndex() {
        return app.visibleCenterActionIndex(settings);
    }

    /** Initial glass selection used only when the host exposes no selected-state signal yet. */
    public int getDefaultSelectedIndex() { return 0; }

    /** Exact host navigation object or exact navigation row. */
    public abstract View getBottomBarRoot();

    /** Exact suppression/layout plan. No heuristic targets may be added by the installer. */
    public SuppressionPlan getSuppressionPlan() {
        SuppressionPlan plan = new SuppressionPlan();
        plan.collapse(getBottomBarRoot());
        return plan;
    }

    /** Flutter can provide a more useful capture source than android.R.id.content. */
    @Override public View getBackdropSourceView() { return null; }

    /** Keep the capture bitmap mapped to the glass View's real screen rectangle. */
    public boolean preservePhysicalBackdropCoordinates() { return false; }

    /** Per-host moving capture budget. Heavy host trees may opt into a lower refresh rate. */
    public long backdropMovingCaptureIntervalMs() { return 28L; }

    /** Delay before the final sharp frame after a host draw burst stops. */
    public long backdropSettleCaptureDelayMs() { return 104L; }

    /** Whether TextureView/SurfaceView descendants must be composited over View.draw(). */
    public boolean captureHardwareBackdrops() { return true; }

    /** Keep the previous valid bitmap when a transient host frame renders fully blank/black. */
    public boolean preserveLastBackdropOnBlankFrame() { return false; }

    /** Host page eligibility independent of the native row's attached/visible state. */
    public boolean shouldShowInjectedNavigation() { return true; }

    /** Current host-requested visibility before LiquidGlass suppresses the native row. */
    public boolean isNavigationVisible() {
        View root = getBottomBarRoot();
        return root != null && root.getVisibility() == View.VISIBLE && root.isAttachedToWindow();
    }

    /** File Explorer is currently showing its native multi-selection action mode. */
    public boolean isFileManagerSelectionModeVisible() { return false; }

    protected int nativeForLogical(int logical) {
        if (logical < 0 || logical >= visibleNativeIds.length) return -1;
        return visibleNativeIds[logical];
    }

    protected int logicalForNative(int nativeValue) {
        for (int i = 0; i < visibleNativeIds.length; i++) {
            if (visibleNativeIds[i] == nativeValue) return i;
        }
        return -1;
    }

    protected View decor() {
        try { return activity.getWindow() == null ? null : activity.getWindow().getDecorView(); }
        catch (Throwable ignored) { return null; }
    }

    protected View findId(String... names) {
        if (names == null) return null;
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            try {
                int id = activity.getResources().getIdentifier(name, "id", app.packageName);
                if (id != 0) {
                    View view = activity.findViewById(id);
                    if (view != null) return view;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    protected View findExactClass(String... classNames) {
        return findViewByExactClass(decor(), classNames);
    }

    protected static View findViewByExactClass(View root, String... classNames) {
        if (root == null || classNames == null) return null;
        Set<String> wanted = new LinkedHashSet<>();
        Collections.addAll(wanted, classNames);
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 3500) {
            View view = queue.removeFirst();
            if (wanted.contains(view.getClass().getName())) return view;
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return null;
    }

    /**
     * Resolve only the verified, bottom-anchored Mach layers that live below Meituan's
     * pager_container. The returned views are intentionally identity-deduplicated and include
     * ViewStub instances: a later inflate replaces the stub with a new object and the installer
     * will catch that replacement on its next pre-draw pass.
     */
    static View[] findMeituanDynamicSuppressionViews(Activity activity) {
        if (activity == null) return new View[0];
        final String packageName = TargetApp.MEITUAN.packageName;
        final String[] names = new String[]{
                // wm_page_home_fragment_layout.xml (8.81.2). Keep the wrapper containers
                // (floating_container/remind_container) intact: they also own the functional
                // back-to-top, cart and fault-tip controls. Only clear their explicit Mach
                // plates, which are the full-width layers that can sit over the glass strip.
                "layout_bottom_banner_mach",
                "layout_order_status_banner_mach",
                "wm_page_home_remind_layer",
                // The ViewStub inflates wm_page_home_remind_layer.xml whose root keeps this id.
                "layout_remind",
                "matrix_mach_container",
                "wm_page_home_mask_layer",
                // KingKong/search pages expose these explicit bottom Mach shells in the same
                // pager. They are full-width overlays rather than content-item containers.
                "layout_float_footer",
                "search_bottom_float_mach_card_container",
                "search_bottom_float_mp_container"
        };
        try {
            int pagerId = activity.getResources().getIdentifier(
                    "pager_container", "id", packageName
            );
            if (pagerId == 0) return new View[0];
            View pager = activity.findViewById(pagerId);
            if (pager == null) return new View[0];

            Set<Integer> ids = new LinkedHashSet<>();
            for (String name : names) {
                int id = activity.getResources().getIdentifier(name, "id", packageName);
                if (id != 0) ids.add(id);
            }
            if (ids.isEmpty()) return new View[0];

            Set<View> found = Collections.newSetFromMap(
                    new IdentityHashMap<View, Boolean>()
            );
            // These ids are stable resource anchors. Resolving each one directly avoids an
            // O(page-tree) breadth-first walk on every suppression refresh while keeping the
            // search scoped to pager_container rather than matching an Activity-level duplicate.
            for (Integer id : ids) {
                if (id == null || id == 0) continue;
                View view = pager.findViewById(id);
                if (view != null && view != pager && view.isAttachedToWindow()) {
                    found.add(view);
                }
            }
            return found.toArray(new View[0]);
        } catch (Throwable ignored) {
            return new View[0];
        }
    }

    /** Uploaded MOONDROP obfuscates FlutterView to qs1. Structural fallback is version-safe. */
    protected static View findFlutterView(View root) {
        if (root == null) return null;
        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 3500) {
            View view = queue.removeFirst();
            Class<?> type = view.getClass();
            String name = type.getName();
            if ("qs1".equals(name)
                    || (hasMethod(type, "getAttachedFlutterEngine")
                    && hasMethod(type, "getCurrentImageSurface")
                    && hasMethod(type, "getAccessibilityNodeProvider"))) {
                return view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
            }
        }
        return null;
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { current.getDeclaredMethod(name); return true; }
            catch (Throwable ignored) {}
        }
        return false;
    }

    protected static boolean dispatchClick(View view) {
        if (view == null) return false;
        // callOnClick() directly invokes the host listener and does not depend on the native row
        // still having a visible/measured hit rectangle. This is important after LiquidGlass
        // collapses Douyin's bottom reservation to let video draw through to the screen edge.
        try { if (view.callOnClick()) return true; } catch (Throwable ignored) {}
        try { if (view.performClick()) return true; } catch (Throwable ignored) {}
        return dispatchTap(view);
    }

    protected static View nearestClickableAncestor(View view, View limit) {
        if (view == null) return null;
        View current = view;
        View fallback = view;
        for (int depth = 0; depth < 10 && current != null; depth++) {
            fallback = current;
            try {
                if (current.isClickable() || current.hasOnClickListeners()) return current;
            } catch (Throwable ignored) {}
            if (current == limit || !(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return fallback;
    }

    protected static boolean dispatchTap(View view) {
        if (view == null) return false;
        float x = Math.max(1f, view.getWidth() * 0.5f);
        float y = Math.max(1f, view.getHeight() * 0.5f);
        return dispatchTapAt(view, x, y);
    }

    protected static boolean dispatchTapAt(View view, float x, float y) {
        if (view == null) return false;
        try {
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 52L, MotionEvent.ACTION_UP, x, y, 0);
            boolean result = view.dispatchTouchEvent(down) | view.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            return result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    protected static Object call(Object target, String methodName, Object... args) {
        if (target == null || methodName == null) return null;
        Method method = findCompatibleMethod(target.getClass(), methodName, args);
        if (method == null) return null;
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    protected static boolean callExists(Object target, String methodName, Object... args) {
        if (target == null || methodName == null) return false;
        Method method = findCompatibleMethod(target.getClass(), methodName, args);
        if (method == null) return false;
        try {
            method.setAccessible(true);
            method.invoke(target, args);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object[] args) {
        Object[] values = args == null ? new Object[0] : args;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != values.length) continue;
                boolean compatible = true;
                for (int i = 0; i < parameters.length; i++) {
                    if (!isCompatible(parameters[i], values[i])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) return method;
            }
        }
        return null;
    }

    private static boolean isCompatible(Class<?> parameter, Object value) {
        if (value == null) return !parameter.isPrimitive();
        Class<?> actual = value.getClass();
        if (parameter.isAssignableFrom(actual)) return true;
        return (parameter == int.class && actual == Integer.class)
                || (parameter == boolean.class && actual == Boolean.class)
                || (parameter == long.class && actual == Long.class)
                || (parameter == float.class && actual == Float.class)
                || (parameter == double.class && actual == Double.class)
                || (parameter == short.class && actual == Short.class)
                || (parameter == byte.class && actual == Byte.class)
                || (parameter == char.class && actual == Character.class);
    }

    protected static Object getField(Object target, String... names) {
        if (target == null || names == null) return null;
        for (String name : names) {
            for (Class<?> current = target.getClass(); current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    protected static Object getStaticField(Class<?> type, String... names) {
        if (type == null || names == null) return null;
        for (String name : names) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(null);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    protected static int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    /** Return the exact runtime row height in dp before the native row is detached. */
    protected static float measuredHeightDp(View view, float fallbackDp) {
        if (view == null) return fallbackDp;
        try {
            int px = Math.max(view.getHeight(), view.getMeasuredHeight());
            ViewGroup.LayoutParams raw = view.getLayoutParams();
            if (px <= 0 && raw != null && raw.height > 0) px = raw.height;
            float density = view.getResources().getDisplayMetrics().density;
            if (px > 0 && density > 0f) return px / density;
        } catch (Throwable ignored) {}
        return fallbackDp;
    }

    /**
     * Resolve a host-declared one-pixel separator without ever falling back to a positional scan.
     * Remote's ActivityMain layout keeps {@code split_line} beside (not inside) its TabLayout, so
     * collapsing only the row leaves that separator visible.  The structural checks below keep a
     * same-named view from a Fragment or list item out of the suppression plan.
     */
    protected View findVerifiedSiblingDivider(
            View bindingRoot,
            View navigation,
            String... resourceNames
    ) {
        if (bindingRoot == null || navigation == null || resourceNames == null
                || !(navigation.getParent() instanceof ViewGroup)) return null;
        ViewGroup navigationParent = (ViewGroup) navigation.getParent();
        View divider = null;
        for (String resourceName : resourceNames) {
            if (resourceName == null || resourceName.isEmpty()) continue;
            try {
                int id = activity.getResources().getIdentifier(
                        resourceName, "id", app.packageName
                );
                if (id == 0) continue;
                View candidate = bindingRoot.getId() == id
                        ? bindingRoot : bindingRoot.findViewById(id);
                if (candidate != null) {
                    divider = candidate;
                    break;
                }
            } catch (Throwable ignored) {}
        }
        if (divider == null || divider == bindingRoot || divider == navigation
                || divider instanceof ViewGroup || divider.getParent() != navigationParent
                || divider.isClickable() || divider.hasOnClickListeners()
                || divider.isLongClickable() || divider.isFocusable()) return null;

        try {
            float density = divider.getResources().getDisplayMetrics().density;
            if (density <= 0f) density = 1f;
            int maxHeight = Math.max(1, Math.round(4f * density));
            ViewGroup.LayoutParams raw = divider.getLayoutParams();
            int height = Math.max(divider.getHeight(), divider.getMeasuredHeight());
            if (height <= 0 && raw != null && raw.height > 0) height = raw.height;
            if (height > maxHeight) return null;

            int dividerWidth = Math.max(divider.getWidth(), divider.getMeasuredWidth());
            int parentWidth = Math.max(
                    navigationParent.getWidth(), navigationParent.getMeasuredWidth()
            );
            boolean declaredFullWidth = raw != null
                    && (raw.width == ViewGroup.LayoutParams.MATCH_PARENT || raw.width == 0);
            if (parentWidth > 0 && dividerWidth > 0
                    && dividerWidth < parentWidth * 0.80f && !declaredFullWidth) return null;

            // Enforce adjacency once both views have completed their first layout. Before that,
            // the exact id, common parent and <=4dp leaf checks remain authoritative.
            if (navigation.getHeight() > 0 && divider.getHeight() > 0) {
                int tolerance = Math.max(1, Math.round(4f * density));
                boolean touchesTop = Math.abs(divider.getBottom() - navigation.getTop()) <= tolerance
                        || Math.abs(divider.getTop() - navigation.getTop()) <= tolerance;
                if (!touchesTop) return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return divider;
    }

    protected static boolean isSelectedOrParent(View view, View limit) {
        View current = view;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (current.isSelected() || current.isActivated()) return true;
            if (current == limit || !(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return false;
    }

    public static final class SuppressionPlan {
        public final List<View> collapseRoots = new ArrayList<>();
        public final List<View> offscreenRoots = new ArrayList<>();
        public final List<View> detachRoots = new ArrayList<>();
        public final List<View> transparentRoots = new ArrayList<>();
        public final List<View> clearOnlyRoots = new ArrayList<>();
        public final List<View> zeroBottomMarginViews = new ArrayList<>();
        public final List<View> zeroBottomPaddingViews = new ArrayList<>();
        public final List<View> alignAboveGlassViews = new ArrayList<>();
        public final List<Float> alignAboveGlassGapDp = new ArrayList<>();
        // Mirrors alignAboveGlass but uses translationY instead of bottom margins. Hosts whose
        // floating control lives inside a ConstraintLayout without a bottom
        // constraint, so a margin change alone cannot lift it; the installer re-applies the
        // translation while the state is active.
        public final List<View> alignAboveGlassByTranslationViews = new ArrayList<>();
        public final List<Float> alignAboveGlassByTranslationGapDp = new ArrayList<>();
        public final List<PhoneDialerExpansion> phoneDialerExpansions = new ArrayList<>();
        public final List<View> overscanBottomViews = new ArrayList<>();
        public final List<Float> overscanBottomDp = new ArrayList<>();
        // Extend only the bottom edge of a verified page/content view. Unlike overscanBottom,
        // this preserves the view's measured top and is intended for hosts whose native bottom
        // bar reserves a separate layout slot (Bilibili HD).
        public final List<View> extendBottomViews = new ArrayList<>();
        public final List<Float> extendBottomDp = new ArrayList<>();

        public SuppressionPlan collapse(View view) { addUnique(collapseRoots, view); return this; }
        public SuppressionPlan offscreen(View view) { addUnique(offscreenRoots, view); return this; }
        public SuppressionPlan detach(View view) { addUnique(detachRoots, view); return this; }
        public SuppressionPlan transparent(View view) { addUnique(transparentRoots, view); return this; }
        public SuppressionPlan clear(View view) { addUnique(clearOnlyRoots, view); return this; }
        public SuppressionPlan zeroBottomMargin(View view) {
            addUnique(zeroBottomMarginViews, view);
            return this;
        }
        public SuppressionPlan zeroBottomPadding(View view) {
            addUnique(zeroBottomPaddingViews, view);
            return this;
        }
        public SuppressionPlan alignAboveGlass(View view, float gapDp) {
            if (view != null && !alignAboveGlassViews.contains(view)) {
                alignAboveGlassViews.add(view);
                alignAboveGlassGapDp.add(gapDp);
            }
            return this;
        }
        public SuppressionPlan alignAboveGlassByTranslation(View view, float gapDp) {
            if (view != null && !alignAboveGlassByTranslationViews.contains(view)) {
                alignAboveGlassByTranslationViews.add(view);
                alignAboveGlassByTranslationGapDp.add(gapDp);
            }
            return this;
        }
        public SuppressionPlan expandPhoneDialerAboveGlass(
                View anchor,
                View keysContainer,
                View callContainer,
                float gapDp
        ) {
            if (anchor != null && keysContainer != null && callContainer != null) {
                phoneDialerExpansions.add(new PhoneDialerExpansion(
                        anchor, keysContainer, callContainer, gapDp
                ));
            }
            return this;
        }
        public SuppressionPlan overscanBottom(View view, float dp) {
            if (view != null && !overscanBottomViews.contains(view)) {
                overscanBottomViews.add(view);
                overscanBottomDp.add(dp);
            }
            return this;
        }
        public SuppressionPlan extendBottom(View view, float dp) {
            if (view != null && !extendBottomViews.contains(view)) {
                extendBottomViews.add(view);
                extendBottomDp.add(dp);
            }
            return this;
        }
        private static void addUnique(List<View> list, View view) {
            if (view != null && !list.contains(view)) list.add(view);
        }

        public static final class PhoneDialerExpansion {
            public final View anchor;
            public final View keysContainer;
            public final View callContainer;
            public final float gapDp;

            public PhoneDialerExpansion(
                    View anchor,
                    View keysContainer,
                    View callContainer,
                    float gapDp
            ) {
                this.anchor = anchor;
                this.keysContainer = keysContainer;
                this.callContainer = callContainer;
                this.gapDp = gapDp;
            }
        }
    }

    private static final class MideaAdapter extends UniversalHostAdapter {
        private static final String[] TAB_IDS = {"tab_home", "tab_service", "tab_ai", "tab_mall", "tab_me"};
        private final View cachedBottomBar;
        private final View[] cachedTabs = new View[TAB_IDS.length];

        MideaAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
            cachedBottomBar = findId("ll_tab");
            for (int i = 0; i < TAB_IDS.length; i++) cachedTabs[i] = findId(TAB_IDS[i]);
        }

        @Override public View getBottomBarRoot() {
            return cachedBottomBar != null ? cachedBottomBar : findId("ll_tab");
        }

        private View tabAt(int nativeIndex) {
            if (nativeIndex < 0 || nativeIndex >= TAB_IDS.length) return null;
            View cached = cachedTabs[nativeIndex];
            return cached != null ? cached : findId(TAB_IDS[nativeIndex]);
        }

        /**
         * app_activity_main_for_elder contains a second, ID-less 93dp FrameLayout immediately
         * before ll_tab. It is the native plate, not page content. The normal five-tab layout does
         * not contain this sibling, so the exact parent/sibling relation safely distinguishes it.
         */
        private View elderBottomPlate() {
            View tabs = getBottomBarRoot();
            if (tabs == null || !(tabs.getParent() instanceof ViewGroup)) return null;
            if (!"androidx.constraintlayout.widget.ConstraintLayout".equals(tabs.getClass().getName())) {
                return null;
            }
            ViewGroup parent = (ViewGroup) tabs.getParent();
            View mainContent = findId("main_content");
            if (mainContent != null && parent != mainContent) return null;
            int index = parent.indexOfChild(tabs);
            if (index <= 0) return null;
            View sibling = parent.getChildAt(index - 1);
            return sibling != null && "android.widget.FrameLayout".equals(sibling.getClass().getName())
                    ? sibling : null;
        }

        @Override public View getBackdropSourceView() {
            // MainActivity's exact FragmentContainerView. The host home fragments reserve their
            // own bottom-navigation inset even though this container itself is MATCH_PARENT.
            View content = findId("container");
            return content != null ? content : findId("main_content");
        }

        /**
         * APK verified values:
         * - app_activity_main: ll_tab uses @dimen/navigation_tab_height = 64dp.
         * - app_activity_main_for_elder: the native plate is exactly 93dp.
         *
         * Removing ll_tab alone leaves the current Fragment's own bottom-safe band in place. Make
         * the FragmentContainerView taller by exactly that host inset, so the reserved white band
         * is laid out below the physical screen and real page pixels reach the glass position.
         */
        private float nativeBottomInsetDp() {
            View tabs = getBottomBarRoot();
            return tabs != null
                    && "androidx.constraintlayout.widget.ConstraintLayout".equals(
                            tabs.getClass().getName())
                    ? 93f : 64f;
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            View content = getBackdropSourceView();
            // Keep the controller-owned tab row attached. Detaching ll_tab made tab_ai
            // undiscoverable after installation and broke its original click route.
            return new SuppressionPlan()
                    .overscanBottom(content, nativeBottomInsetDp())
                    .offscreen(getBottomBarRoot())
                    .offscreen(elderBottomPlate());
        }
        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0 || nativeIndex >= TAB_IDS.length) return false;
            View tab = tabAt(nativeIndex);
            // XML onClick is the real navigation chain. updateTab() is only a state/render
            // helper on some builds, so it must be the last fallback rather than the first.
            if (callExists(activity, "onTabClick", tab)) return true;
            if (dispatchClick(tab)) return true;
            return callExists(activity, "updateTab", nativeIndex);
        }
        private boolean hasStandaloneAiAction() {
            return visibleLabels.length == 5 && centerActionTabIndex() >= 0;
        }
        @Override public boolean post() {
            int center = centerActionTabIndex();
            return hasStandaloneAiAction() && select(center);
        }
        @Override public int detectSelectedIndex() {
            int current = intValue(getField(activity, "curIndex"), -1);
            int logical = current >= 0 ? logicalForNative(current) : -1;
            // AI behaves like CoolAPK's independent center action only in the normal five-item
            // layout. If the user hides a Midea item, AI returns to an ordinary selectable tab.
            if (hasStandaloneAiAction() && logical == centerActionTabIndex()) return -1;
            if (logical >= 0) return logical;
            View root = getBottomBarRoot();
            for (int candidate = 0; candidate < visibleNativeIds.length; candidate++) {
                if (hasStandaloneAiAction() && candidate == centerActionTabIndex()) continue;
                int nativeIndex = visibleNativeIds[candidate];
                View tab = tabAt(nativeIndex);
                if (tab != null && isSelectedOrParent(tab, root)) return candidate;
            }
            return -1;
        }
    }

    private static final class MeituanAdapter extends UniversalHostAdapter {
        MeituanAdapter(Activity a, TargetApp app, TargetAppSettings s) { super(a, app, s); }
        private View indicator() { return findId("pagerindicator"); }
        @Override public View getBottomBarRoot() { return indicator(); }
        @Override public SuppressionPlan getSuppressionPlan() {
            // takeout_activity_main: pager_container reserves the 73dp row using bottomMargin.
            // navigation_bar_margin_layout_container also owns valid floating/promotion overlays and
            // must never be removed. 8.79.4 keeps three overlay layers inside that container which
            // survive the indicator collapse and render as a half mask over the glass:
            // promotion_tab_layer (full-screen TabLayerCircleLayout), floating_container (order /
            // weather status widgets) and iv_promotion_holder_night_snack (73dp night-snack badge).
            // They are cleared individually so the container itself stays available for future
            // host-owned floating content.
            return new SuppressionPlan()
                    .collapse(indicator())
                    .clear(findId("indicator_bg_container"))
                    .zeroBottomMargin(findId("pager_container"))
                    .clear(findId("promotion_tab_layer"))
                    .clear(findId("floating_container"))
                    .clear(findId("iv_promotion_holder_night_snack"));
        }
        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            View indicator = indicator();
            if (callExists(indicator, "k", nativeIndex, false)) return true;
            return callExists(indicator, "setCurrentTab", nativeIndex);
        }
        @Override public boolean post() { return false; }
        @Override public int detectSelectedIndex() {
            View indicator = indicator();
            int current = intValue(call(indicator, "getCurrentTab"), intValue(getField(indicator, "i"), -1));
            return logicalForNative(current);
        }
    }

    /**
     * Cainiao 8.11.825 ships several remotely selected navigation models. Resolve the four
     * LiquidGlass destinations by their business route keys; a positional mapping can silently
     * turn all_packagelist into retrieve or cainiao_score into station on another model.
     */
    private static final class CainiaoAdapter extends UniversalHostAdapter {
        private static final String NAVIGATION_VIEW_CLASS =
                "com.cainiao.commonlibrary.navigation.NavigationView";
        private static final String ALL_PACKAGE_ROUTE = "guoguo://go/allpackage";
        private static final String SCORE_ROUTE =
                "https://page.cainiao.com/mcn/cainiao-score/web/index.html?disableNav=YES"
                        + "&__webview_options__=openPullRefresh%3DYES%26fullScreen%3DYES"
                        + "%26fullscreen%3DYES%26showStateBar%3DYES#/?source=agedtab2";
        private static final String[] TARGET_KEYS = {
                "homepage", "all_packagelist", null, "cainiao_score", "personal_center"
        };
        /**
         * Only aliases for the same destination belong together. In particular, retrieve is not
         * all_packagelist and station is not cainiao_score even when those tabs occupy the same
         * visual position in two different remote configurations.
         */
        private static final String[][] TARGET_KEY_ALIASES = {
                {"homepage"},
                {"all_packagelist"},
                {},
                {"cainiao_score", "hudong"},
                {"personal_center", "personal_center_h5", "personal"}
        };

        private static final class RuntimeTab {
            final View view;
            final String key;
            final int position;

            RuntimeTab(View view, String key, int position) {
                this.view = view;
                this.key = key;
                this.position = position;
            }
        }

        CainiaoAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
        }

        private View navigationView() {
            Object field = getField(activity, "mNavigationView");
            if (field instanceof View) return (View) field;
            View byId = findId("navigation_tab_view");
            return byId != null ? byId : findExactClass(NAVIGATION_VIEW_CLASS);
        }

        private View navigationBarLayout() {
            Object field = getField(activity, "mNavigationBarLayout");
            if (field instanceof View) return (View) field;
            View byId = findId("navigation_bar_layout");
            return byId != null ? byId : navigationView();
        }

        private View navigationDivider() {
            Object field = getField(activity, "mNavigationDividerView");
            if (field instanceof View) return (View) field;
            return findId("navigation_tab_view_divider");
        }

        private String keyForNative(int nativeIndex) {
            return nativeIndex >= 0 && nativeIndex < TARGET_KEYS.length
                    ? TARGET_KEYS[nativeIndex] : null;
        }

        private List<?> runtimeIcons() {
            Object icons = getField(navigationView(), "icons", "mIcons", "tabs", "mTabs");
            return icons instanceof List ? (List<?>) icons : Collections.emptyList();
        }

        private static String normalizeKey(Object value) {
            if (!(value instanceof CharSequence)) return null;
            String key = value.toString().trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) return null;
            key = key.replace('-', '_').replace(' ', '_').replace('/', '_').replace('.', '_');
            // A few regional builds prefix the same key with tab_/nav_/navigation_.  Strip only
            // those structural prefixes; do not use substring matching for business keys.
            boolean changed;
            do {
                changed = false;
                if (key.startsWith("navigation_")) {
                    key = key.substring("navigation_".length());
                    changed = true;
                } else if (key.startsWith("nav_")) {
                    key = key.substring("nav_".length());
                    changed = true;
                } else if (key.startsWith("tab_")) {
                    key = key.substring("tab_".length());
                    changed = true;
                }
            } while (changed && !key.isEmpty());
            return key.isEmpty() ? null : key;
        }

        private String runtimeKey(Object tab) {
            return normalizeKey(rawRuntimeKey(tab));
        }

        private Object rawRuntimeKey(Object tab) {
            if (tab == null) return null;
            Object value = call(tab, "getKey");
            if (!(value instanceof CharSequence)) value = getField(
                    tab, "key", "tabKey", "mKey", "navigationKey", "mNavigationKey"
            );
            if (!(value instanceof CharSequence)) value = call(tab, "getTabKey");
            return value;
        }

        private int nativeForRuntimeKey(String key) {
            String normalized = normalizeKey(key);
            if (normalized == null) return -1;
            for (int nativeIndex = 0; nativeIndex < TARGET_KEY_ALIASES.length; nativeIndex++) {
                for (String alias : TARGET_KEY_ALIASES[nativeIndex]) {
                    if (normalized.equals(alias)) return nativeIndex;
                }
            }
            return -1;
        }

        private int logicalForKey(Object value) {
            return logicalForNative(nativeForRuntimeKey(normalizeKey(value)));
        }

        private RuntimeTab runtimeTabForNative(int nativeIndex) {
            List<?> tabs = runtimeIcons();
            if (tabs.isEmpty()) return null;
            for (int position = 0; position < tabs.size(); position++) {
                Object candidate = tabs.get(position);
                if (!(candidate instanceof View)) continue;
                String key = runtimeKey(candidate);
                if (key != null && nativeForRuntimeKey(key) == nativeIndex) {
                    return new RuntimeTab((View) candidate, key, position);
                }
            }
            return null;
        }

        private View tabForNative(int nativeIndex) {
            String key = keyForNative(nativeIndex);
            if (key == null) return null;

            RuntimeTab runtime = runtimeTabForNative(nativeIndex);
            if (runtime != null) return runtime.view;

            View navigation = navigationView();
            Object tab = call(navigation, "getTargetTabView", key);
            if (tab instanceof View) return (View) tab;

            // getTargetTabView() is exact-key based.  Keep a second scan for regional builds that
            // retain the icons list but rename the accessor while preserving the key field.
            List<?> icons = runtimeIcons();
            for (Object candidate : icons) {
                if (!(candidate instanceof View)) continue;
                if (key.equals(runtimeKey(candidate))) return (View) candidate;
            }
            // Some regional builds expose getTargetTabView() but populate the list with a newer
            // route key (for example cainiao_score). Try the verified aliases before giving up;
            // this keeps the original NavigationTabView listener reachable after the native row
            // has been collapsed.
            if (nativeIndex >= 0 && nativeIndex < TARGET_KEY_ALIASES.length) {
                for (String alias : TARGET_KEY_ALIASES[nativeIndex]) {
                    Object aliasTab = call(navigation, "getTargetTabView", alias);
                    if (aliasTab instanceof View) return (View) aliasTab;
                }
            }
            return null;
        }

        private boolean isSelectedTab(View tab) {
            if (tab == null) return false;
            try {
                if (tab.isSelected() || tab.isActivated()) return true;
            } catch (Throwable ignored) {}
            if (Boolean.TRUE.equals(call(tab, "isSelected"))
                    || Boolean.TRUE.equals(call(tab, "getIsSelected"))) return true;
            Object value = getField(tab, "isSelected", "selected", "mIsSelected");
            return Boolean.TRUE.equals(value);
        }

        private boolean invokeNavigationClick(View navigation, View tab, String key) {
            // Never report a navigation success without the concrete tab that owns the host
            // listener.  setNavigationIndex/updateSelectState can otherwise move only the glass
            // selection while HomePageActivity stays on the previous Fragment.
            if (navigation == null || tab == null || key == null) return false;

            // Mirror NavigationView$1.onClick. The public tab hook is an analytics callback; the
            // actual Fragment/H5 switch lives in NavigationView's private pre/on/post helpers.
            // dispatchClick(tab) normally executes this listener directly, while this branch is
            // only used when the collapsed row rejects the synthetic click.
            call(tab, "onNavigationViewClick");
            call(navigation, "tabControlClick", key);
            Object blocked = call(navigation, "preTabClicked", key);
            if (Boolean.TRUE.equals(blocked)) return true;
            call(navigation, "onTabClicked", key);
            if (callExists(navigation, "postTabClicked", key)) return true;

            // Some regional builds keep the TabClickListener but rename/remove NavigationView's
            // private helpers.  Preserve the same pre/post order against the actual tab object.
            Object listener = getField(navigation, "mTabClickListener", "tabClickListener");
            if (listener == null) return false;
            Object listenerBlocked = call(listener, "preOnClick", tab);
            if (Boolean.TRUE.equals(listenerBlocked)) return true;
            if (key != null) call(navigation, "onTabClicked", key);
            return callExists(listener, "postOnClick", tab);
        }

        private boolean dispatchCainiaoRoute(int nativeIndex) {
            String route;
            if (nativeIndex == 1) {
                route = ALL_PACKAGE_ROUTE;
            } else if (nativeIndex == 3) {
                route = SCORE_ROUTE;
            } else {
                return false;
            }
            try {
                Class<?> type = Class.forName(
                        "com.cainiao.commonlibrary.navigation.nav.Nav",
                        false,
                        activity.getClassLoader()
                );
                Method from = findCompatibleMethod(type, "from", new Object[]{activity});
                if (from == null) return false;
                from.setAccessible(true);
                Object nav = from.invoke(null, activity);
                return Boolean.TRUE.equals(call(nav, "toUri", route));
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override public View getBottomBarRoot() {
            return navigationView();
        }

        @Override public View getBackdropSourceView() {
            Object currentFragment = getField(activity, "mCurrentFragment");
            Object currentView = call(currentFragment, "getView");
            View fragmentView = currentView instanceof View ? (View) currentView : null;
            if (usableBackdropView(fragmentView)) return fragmentView;

            // During the short transaction window the Fragment view can be detached.  Keep the
            // fallback to Cainiao's own page host only; never widen the exact source to the whole
            // Activity/content root, which keeps outgoing/hidden Fragments in the captured bitmap.
            View aboveTabs = findId("above_tab_container");
            return usableBackdropView(aboveTabs) ? aboveTabs : null;
        }

        @Override public boolean useExactBackdropSource() { return true; }

        @Override public long backdropTransitionDelayMs() { return 160L; }

        private static boolean usableBackdropView(View view) {
            if (view == null) return false;
            try {
                return view.isAttachedToWindow()
                        && view.getVisibility() == View.VISIBLE
                        && view.isShown()
                        && view.getAlpha() > 0.01f
                        && view.getWidth() > 0
                        && view.getHeight() > 0;
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            // Collapse the exact reservation while keeping NavigationView and all of its listeners
            // attached. callOnClick() can then route through Cainiao's original analytics/fragment
            // chain even though the native row has no visible hit rectangle.
            return new SuppressionPlan()
                    .collapse(navigationBarLayout())
                    .clear(navigationDivider());
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            String key = keyForNative(nativeIndex);
            if (key == null) return false;

            View tab = tabForNative(nativeIndex);
            if (dispatchClick(tab)) return true;
            Object rawKey = rawRuntimeKey(tab);
            String runtimeKey = rawKey instanceof CharSequence
                    ? rawKey.toString().trim() : key;
            if (runtimeKey == null || runtimeKey.isEmpty()) runtimeKey = key;

            // When the requested business tab is absent from the active remote model there is no
            // native click listener to preserve. Use the APK-provided route instead of letting the
            // generic NavigationView helpers operate on a missing tab and report a false success.
            if (tab == null && dispatchCainiaoRoute(nativeIndex)) return true;

            View navigation = navigationView();
            if (invokeNavigationClick(navigation, tab, runtimeKey)) return true;

            // The normal four-tab model has no all_packagelist View and older five-tab models have
            // neither cainiao_score nor hudong. Route those two verified default actions through
            // Cainiao's own Nav implementation instead of borrowing retrieve/station by position.
            if (dispatchCainiaoRoute(nativeIndex)) return true;

            // This only synchronizes the host's visual state.  It is deliberately not reported as
            // a successful selection because setNavigationIndex/updateSelectState do not switch
            // HomePageActivity's current Fragment.
            if (callExists(navigation, "setNavigationIndex", runtimeKey)) {
                callExists(navigation, "updateSelectState");
            }
            return false;
        }

        @Override public boolean post() { return false; }

        @Override public int detectSelectedIndex() {
            int logical = logicalForKey(getField(activity, "mTabKey"));
            if (logical >= 0) return logical;
            View navigation = navigationView();
            logical = logicalForKey(getField(navigation, "mNavigationKey"));
            if (logical >= 0) return logical;
            logical = logicalForKey(call(navigation, "getNavigationKey"));
            if (logical >= 0) return logical;

            List<?> tabs = runtimeIcons();
            for (int position = 0; position < tabs.size(); position++) {
                Object candidate = tabs.get(position);
                if (!(candidate instanceof View) || !isSelectedTab((View) candidate)) continue;
                String key = runtimeKey(candidate);
                int nativeIndex = key == null ? -1 : nativeForRuntimeKey(key);
                logical = logicalForNative(nativeIndex);
                if (logical >= 0) return logical;
            }

            // A few builds expose the selected key only through the activity's current fragment
            // transaction.  Re-read the canonical fields after the icons pass in case that
            // transaction committed while the row was being collapsed.
            logical = logicalForKey(getField(activity, "mTabKey", "currentTabKey"));
            if (logical >= 0) return logical;
            logical = logicalForKey(getField(navigation, "mNavigationKey", "currentKey"));
            if (logical >= 0) return logical;
            return -1;
        }

        @Override public Bitmap[] getNavigationIconBitmaps() {
            Bitmap[] result = new Bitmap[visibleNativeIds.length];
            int found = 0;
            for (int logical = 0; logical < visibleNativeIds.length; logical++) {
                View tab = tabForNative(visibleNativeIds[logical]);
                Drawable drawable = null;
                Object selected = getField(tab, "selectedImg", "mSelectedImg");
                if (selected instanceof Drawable) drawable = (Drawable) selected;
                if (drawable == null) {
                    Object normal = getField(tab, "normalImg", "mNormalImg");
                    if (normal instanceof Drawable) drawable = (Drawable) normal;
                }
                if (drawable == null) {
                    Object image = getField(tab, "navTabIcon", "mNavTabIcon");
                    if (image instanceof ImageView) drawable = ((ImageView) image).getDrawable();
                }
                Bitmap bitmap = drawableToBitmap(drawable);
                if (bitmap != null && !bitmap.isRecycled()) {
                    result[logical] = bitmap;
                    found++;
                }
            }
            return found == 0 ? new Bitmap[0] : result;
        }

        private static Bitmap drawableToBitmap(Drawable original) {
            if (original == null) return null;
            try {
                if (original instanceof BitmapDrawable) {
                    Bitmap bitmap = ((BitmapDrawable) original).getBitmap();
                    if (bitmap != null && !bitmap.isRecycled()) return bitmap;
                }
                Drawable drawable = original.getConstantState() == null
                        ? original.mutate()
                        : original.getConstantState().newDrawable().mutate();
                int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 96;
                int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 96;
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, width, height);
                drawable.draw(canvas);
                return bitmap;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    /**
     * Meituan 12.61.402 main-app adapter. The uploaded APK exposes the exact bottom row as
     * TabBlockV2 and keeps its original click listeners on the five native tab Views. LiquidGlass
     * parks that row off-screen and forwards every selection to the original View first.
     */
    private static final class MeituanMainAdapter extends UniversalHostAdapter {
        private static final String[] TAB_NAMES = {
                "homepage", "video", "xiaotuan", "shoppingcart", "mine"
        };

        MeituanMainAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
        }

        @Override public View getBottomBarRoot() {
            View byId = findId("bottom_tab_block");
            return byId != null ? byId
                    : findExactClass("com.meituan.android.pt.homepage.tab.TabBlockV2");
        }

        @Override public View getBackdropSourceView() {
            return findId("main");
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            View bar = getBottomBarRoot();
            return new SuppressionPlan()
                    .overscanBottom(getBackdropSourceView(), measuredHeightDp(bar, 64f))
                    .offscreen(bar);
        }

        private Object tabAreaData(String tabName) {
            Object value = call(getBottomBarRoot(), "getTabViewDataMap");
            if (!(value instanceof Map)) return null;
            return ((Map<?, ?>) value).get(tabName);
        }

        private View nativeTabView(String tabName) {
            Object data = tabAreaData(tabName);
            Object view = getField(data, "a");
            return view instanceof View ? (View) view : null;
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0 || nativeIndex >= TAB_NAMES.length) return false;
            String tabName = TAB_NAMES[nativeIndex];

            // Preserve Meituan's own analytics, repeat-click behavior and AI route.
            View nativeView = nativeTabView(tabName);
            if (dispatchClick(nativeView)) return true;

            // Early-start fallback for the short interval before native listeners are attached.
            Object root = getBottomBarRoot();
            Object next = getField(tabAreaData(tabName), "b");
            Object current = call(root, "getCurrentTabArea");
            Object controller = getField(root, "d");
            Object listener = getField(controller, "j");
            boolean switched = callExists(listener, "a", next, current);
            if (!switched) switched = callExists(activity, "T5", next, current, false, true);
            if (next != null) callExists(root, "t", next);
            return switched;
        }

        @Override public boolean post() {
            int center = centerActionTabIndex();
            return center >= 0 && select(center);
        }

        @Override public int detectSelectedIndex() {
            Object current = call(getBottomBarRoot(), "getCurrentTabArea");
            Object nameValue = getField(current, "tabName");
            if (!(nameValue instanceof String)) return -1;
            String name = (String) nameValue;
            for (int nativeIndex = 0; nativeIndex < TAB_NAMES.length; nativeIndex++) {
                if (!TAB_NAMES[nativeIndex].equals(name)) continue;
                int logical = logicalForNative(nativeIndex);
                // xiaotuan is a standalone center action. Keep the lens on the previous
                // ordinary tab while the AI page is open.
                return logical == centerActionTabIndex() ? -1 : logical;
            }
            return -1;
        }
    }

    private static final class MiHomeAdapter extends UniversalHostAdapter {
        MiHomeAdapter(Activity a, TargetApp app, TargetAppSettings s) { super(a, app, s); }
        @Override public View getBottomBarRoot() {
            Object field = getField(activity, "Oooo0o0");
            if (field instanceof View) return (View) field;
            return findExactClass("com.xiaomi.smarthome.newui.buttomtab.TabPageIndicatorNew");
        }
        @Override public SuppressionPlan getSuppressionPlan() {
            // X2C layout abm: LinearViewPager is layout_above the exact TabPageIndicatorNew.
            return new SuppressionPlan().collapse(getBottomBarRoot());
        }
        @Override public boolean select(int index) {
            return callExists(getBottomBarRoot(), "setCurrentItem", nativeForLogical(index));
        }
        @Override public boolean post() { return false; }
        @Override public int detectSelectedIndex() {
            return logicalForNative(intValue(call(getBottomBarRoot(), "getSelectedTabIndex"), -1));
        }
    }

    /**
     * Xiaomi Fitness 3.58.0 keeps its four destinations in a real Material TabLayout:
     * main_fl_content is the weighted page host and main_fl_bottom_container owns the native
     * bottom reservation. Keep the TabLayout object attached for its original listeners, while
     * collapsing only the exact native container so the content area can reclaim that space.
     */
    private static final class MiFitnessAdapter extends UniversalHostAdapter {
        MiFitnessAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
        }

        private View tabLayout() {
            return findId("main_tl_bottom");
        }

        private View bottomContainer() {
            return findId("main_fl_bottom_container");
        }

        private View content() {
            return findId("main_fl_content");
        }

        @Override public View getBottomBarRoot() {
            View container = bottomContainer();
            return container != null ? container : tabLayout();
        }

        @Override public View getBackdropSourceView() {
            return content();
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            View container = bottomContainer();
            View row = tabLayout();
            // The parent is the exact layout reservation verified in main_activity_main.xml.
            // Falling back to the row keeps older regional builds functional if the wrapper id is
            // stripped, without scanning arbitrary bottom-screen Views.
            return new SuppressionPlan().collapse(container != null ? container : row);
        }

        private Object tabAt(int nativeIndex) {
            View layout = tabLayout();
            if (layout == null || nativeIndex < 0) return null;
            Object tab = call(layout, "getTabAt", nativeIndex);
            if (tab == null) tab = call(layout, "B", nativeIndex);
            return tab;
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            Object tab = tabAt(nativeIndex);
            if (tab == null) return false;

            // MainActivity's own code calls TabLayout.Tab.m() in this APK (the obfuscated form of
            // select()). Try the public name first for un-obfuscated regional builds, then the
            // verified obfuscated method and finally TabLayout's controller-level route.
            if (callExists(tab, "select")) return true;
            if (callExists(tab, "m")) return true;
            View layout = tabLayout();
            if (callExists(layout, "selectTab", tab)) return true;
            if (callExists(layout, "h", tab, nativeIndex)) return true;

            Object tabView = call(tab, "getCustomView");
            if (!(tabView instanceof View)) tabView = call(tab, "e");
            if (tabView instanceof View) return dispatchClick(nearestClickableAncestor(
                    (View) tabView, layout
            ));
            return false;
        }

        @Override public boolean post() { return false; }

        @Override public int detectSelectedIndex() {
            View layout = tabLayout();
            int selected = intValue(call(layout, "getSelectedTabPosition"), -1);
            if (selected < 0) selected = intValue(call(layout, "getSelectedPosition"), -1);
            return logicalForNative(selected);
        }

        @Override public long backdropTransitionDelayMs() { return 96L; }

        @Override public boolean preserveLastBackdropOnBlankFrame() { return true; }
    }

    private static final class BilibiliAdapter extends UniversalHostAdapter {
        BilibiliAdapter(Activity a, TargetApp app, TargetAppSettings s) { super(a, app, s); }
        private View tabHost() {
            View id = findId("bottom_navigation");
            return id != null ? id : findExactClass("com.bilibili.lib.homepage.widget.TabHost");
        }
        private List<?> runtimeTabs() {
            Object tabs = call(tabHost(), "getTabs");
            return tabs instanceof List ? (List<?>) tabs : Collections.emptyList();
        }
        private View tab(int index) {
            Object value = call(tabHost(), "k", index);
            // Bilibili HD 2.5.1 exposes the concrete tab slot through A(int), while some
            // phone builds used k(int). Keep both paths so the shared adapter works for both APKs.
            if (!(value instanceof View)) value = call(tabHost(), "A", index);
            return value instanceof View ? (View) value : null;
        }
        private int publishIndex() {
            List<?> tabs = runtimeTabs();
            for (int i = 0; i < tabs.size(); i++) {
                if (Boolean.TRUE.equals(getField(tabs.get(i), "r"))) return i;
            }
            return 2;
        }
        private int runtimeIndexForLogical(int logical) {
            if (logical < 0 || logical >= visibleLabels.length) return -1;
            // The uploaded HD build has exactly three fixed entries in this order and no
            // publish slot: 首页 / 动态 / 我的. Avoid relying on its obfuscated tab-model fields.
            if (app == TargetApp.BILIBILI_HD) return nativeForLogical(logical);
            String wanted = visibleLabels[logical];
            List<?> tabs = runtimeTabs();
            List<Integer> normal = new ArrayList<>();
            for (int i = 0; i < tabs.size(); i++) {
                Object item = tabs.get(i);
                if (Boolean.TRUE.equals(getField(item, "r"))) continue;
                normal.add(i);
                String text = String.valueOf(getField(item, "b"));
                if ("首页".equals(wanted) && text.contains("首页")) return i;
                if ("关注".equals(wanted) && (text.contains("关注") || text.contains("动态"))) return i;
                if ("会员购".equals(wanted) && text.contains("会员")) return i;
                if ("我的".equals(wanted) && text.contains("我的")) return i;
            }
            return logical < normal.size() ? normal.get(logical) : nativeForLogical(logical);
        }
        private int logicalForRuntime(int runtime) {
            for (int i = 0; i < visibleLabels.length; i++) {
                if (runtimeIndexForLogical(i) == runtime) return i;
            }
            return -1;
        }
        @Override public View getBottomBarRoot() { return tabHost(); }

        private int px(float valueDp) {
            try {
                return Math.round(valueDp * activity.getResources().getDisplayMetrics().density);
            } catch (Throwable ignored) {
                return Math.round(valueDp);
            }
        }

        /**
         * HD keeps a separate opaque bottom shell around TabHost. The phone build usually places
         * the exact TabHost directly over full-height content, so collapsing that one View is
         * enough. On HD, collapsing only TabHost leaves the shell's black plate and the page still
         * measured above the old navigation height.
         */
        private View hdBottomShell() {
            if (app != TargetApp.BILIBILI_HD) return null;
            View host = tabHost();
            if (host == null) return null;
            View best = null;
            View current = host;
            int hostHeight = Math.max(host.getHeight(), host.getMeasuredHeight());
            int rootHeight = host.getRootView() == null ? 0
                    : Math.max(host.getRootView().getHeight(), host.getRootView().getMeasuredHeight());
            int maxShellHeight = Math.max(px(112f), Math.max(1, hostHeight) + px(32f));
            for (int depth = 0; depth < 4 && current.getParent() instanceof ViewGroup; depth++) {
                ViewGroup parent = (ViewGroup) current.getParent();
                if (parent.getId() == android.R.id.content || parent == decor()) break;
                int parentHeight = Math.max(parent.getHeight(), parent.getMeasuredHeight());
                int parentWidth = Math.max(parent.getWidth(), parent.getMeasuredWidth());
                int currentWidth = Math.max(current.getWidth(), current.getMeasuredWidth());
                boolean compactBottomShell = parentHeight > 0
                        && parentHeight <= maxShellHeight
                        && (rootHeight <= 0 || parentHeight < rootHeight * 0.55f)
                        && (currentWidth <= 0 || parentWidth <= 0
                        || parentWidth >= currentWidth * 0.75f);
                if (!compactBottomShell) break;
                best = parent;
                current = parent;
            }
            return best;
        }

        private static boolean containsView(View branch, View child) {
            if (branch == null || child == null) return false;
            View current = child;
            for (int depth = 0; current != null && depth < 20; depth++) {
                if (current == branch) return true;
                if (!(current.getParent() instanceof View)) break;
                current = (View) current.getParent();
            }
            return false;
        }

        /**
         * Find the large page surface that ends immediately above the native HD bottom shell.
         * The earlier implementation enlarged the largest root container, which also owned the
         * top channel row. This geometry-based scan deliberately excludes roots near the screen
         * top and selects only a wide, tall descendant whose physical bottom meets the shell top.
         */
        private View hdPageContent() {
            if (app != TargetApp.BILIBILI_HD) return null;
            View host = tabHost();
            View shell = hdBottomShell();
            if (host == null) return null;
            if (shell == null) shell = host;
            View root = decor();
            if (!(root instanceof ViewGroup)) return null;

            int[] rootLocation = new int[2];
            int[] shellLocation = new int[2];
            try {
                root.getLocationOnScreen(rootLocation);
                shell.getLocationOnScreen(shellLocation);
            } catch (Throwable ignored) {
                return null;
            }
            int rootWidth = Math.max(root.getWidth(), root.getMeasuredWidth());
            int rootHeight = Math.max(root.getHeight(), root.getMeasuredHeight());
            int shellTop = shellLocation[1];
            int shellHeight = Math.max(shell.getHeight(), shell.getMeasuredHeight());
            if (rootWidth <= 0 || rootHeight <= 0 || shellTop <= rootLocation[1]) return null;

            int minimumTop = rootLocation[1] + px(36f);
            int bottomToleranceAbove = Math.max(px(120f), shellHeight * 2);
            int bottomToleranceBelow = px(28f);
            View best = null;
            long bestScore = Long.MIN_VALUE;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 6000) {
                View candidate = queue.removeFirst();
                if (candidate == null) continue;
                boolean insideShell = containsView(shell, candidate);
                boolean shellInsideCandidate = containsView(candidate, shell);
                if (!insideShell && !shellInsideCandidate && candidate != root
                        && candidate.getVisibility() == View.VISIBLE
                        && candidate.getAlpha() > 0.01f) {
                    try {
                        int width = Math.max(candidate.getWidth(), candidate.getMeasuredWidth());
                        int height = Math.max(candidate.getHeight(), candidate.getMeasuredHeight());
                        int[] location = new int[2];
                        candidate.getLocationOnScreen(location);
                        int top = location[1];
                        int bottom = top + height;
                        boolean geometryMatches = width >= rootWidth * 0.55f
                                && height >= rootHeight * 0.32f
                                && top >= minimumTop
                                && bottom >= shellTop - bottomToleranceAbove
                                && bottom <= shellTop + bottomToleranceBelow;
                        if (geometryMatches) {
                            long area = (long) width * height;
                            long bottomPenalty = Math.abs((long) bottom - shellTop) * 10000L;
                            int depth = 0;
                            View ancestor = candidate;
                            while (ancestor != null && ancestor != root && depth < 30) {
                                depth++;
                                ancestor = ancestor.getParent() instanceof View
                                        ? (View) ancestor.getParent() : null;
                            }
                            // Prefer the outermost content surface. Extending an inner RecyclerView
                            // is ineffective when its parent still clips at the old shell top.
                            long score = area - bottomPenalty - depth * 100_000_000L;
                            if (score > bestScore) {
                                bestScore = score;
                                best = candidate;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                if (candidate instanceof ViewGroup && !insideShell) {
                    ViewGroup group = (ViewGroup) candidate;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child != null) queue.addLast(child);
                    }
                }
            }
            return best;
        }

        private float hdBottomReservationDp() {
            View host = tabHost();
            View shell = hdBottomShell();
            int height = 0;
            if (shell != null) height = Math.max(shell.getHeight(), shell.getMeasuredHeight());
            if (height <= 0 && host != null) height = Math.max(host.getHeight(), host.getMeasuredHeight());
            float density = activity.getResources().getDisplayMetrics().density;
            return height > 0 && density > 0f ? height / density : 64f;
        }

        // Keep Bilibili on the original automatic Activity-content capture path. Selecting a
        // "dominant" descendant is unsafe because the homepage Fragment remains attached behind
        // other tabs and can win the visibility score after a page switch.
        @Override public View getBackdropSourceView() { return null; }
        @Override public SuppressionPlan getSuppressionPlan() {
            View host = tabHost();
            if (app != TargetApp.BILIBILI_HD) {
                // Phone bili_app_fragment_main overlays TabHost on full-height page content.
                return new SuppressionPlan().collapse(host);
            }

            View shell = hdBottomShell();
            if (shell == null) shell = host;
            View page = hdPageContent();
            // Translation removes the opaque native shell visually but intentionally retains its
            // layout slot. Fill only that vacated bottom slot by extending the verified page surface
            // from its current bottom edge; its top coordinate and the independent channel bar are
            // left untouched.
            return new SuppressionPlan()
                    .extendBottom(page, hdBottomReservationDp())
                    .offscreen(shell);
        }
        @Override public boolean select(int index) {
            int runtime = runtimeIndexForLogical(index);
            if (dispatchClick(tab(runtime))) return true;
            return callExists(tabHost(), "setCurrentItem", runtime);
        }
        @Override public boolean post() {
            View slot = tab(publishIndex());
            View publish = findViewByExactClass(slot, "com.bilibili.lib.homepage.widget.HomeTabPublishView");
            if (publish == null) publish = slot;
            if (publish == null) return false;
            try {
                long now = SystemClock.uptimeMillis();
                float x = Math.max(1f, publish.getWidth() * 0.5f);
                float y = Math.max(1f, publish.getHeight() * 0.5f);
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 52L, MotionEvent.ACTION_UP, x, y, 0);
                boolean handled = callExists(publish, "onTouch", publish, down);
                handled = callExists(publish, "onTouch", publish, up) || handled;
                down.recycle();
                up.recycle();
                return handled || dispatchClick(slot);
            } catch (Throwable ignored) {
                return dispatchClick(slot);
            }
        }
        @Override public int detectSelectedIndex() {
            return logicalForRuntime(intValue(call(tabHost(), "getCurrentItem"), -1));
        }
    }

    private static final class NeteaseAdapter extends UniversalHostAdapter {
        private static final String[] CODES = {"main", "search", "social", "mine"};
        NeteaseAdapter(Activity a, TargetApp app, TargetAppSettings s) { super(a, app, s); }
        private Object tabsObject() {
            Object field = getField(activity, "C");
            if (field != null && "com.netease.cloudmusic.theme.ui.NavigationTabLayout"
                    .equals(field.getClass().getName())) return field;
            View id = findId("bottomNav");
            return id != null ? id : findExactClass("com.netease.cloudmusic.theme.ui.NavigationTabLayout");
        }
        private View tabsView() { Object value = tabsObject(); return value instanceof View ? (View) value : null; }
        @Override public View getBottomBarRoot() { return tabsView(); }

        // Use the same stable Activity content root as CoolAPK. NetEase keeps several tab pages
        // attached at once, so binding to top_header or a pager child can freeze the first page.
        // NetEase uses physical screen-coordinate capture so the requested strip is never shifted
        // upward when the page content stops above the mini-player/navigation shell.
        @Override public View getBackdropSourceView() {
            try {
                View content = activity.findViewById(android.R.id.content);
                if (content != null && content.isAttachedToWindow()) return content;
            } catch (Throwable ignored) {}
            return decor();
        }

        private static final String[] MINI_PLAYER_IDS = {
                // 9.5.50 uses these outer shells and a TextureView-backed artwork layer.
                "miniPlayBarReallyRoot", "miniPlayBarLayout", "minPlayBarHolderView",
                "miniTextureView", "minibarContentContainerNew", "minibarContentBg",
                "minibarAvatar", "minibarSpace",
                "minPlayerBarContainer", "miniPlayerBarContainer", "miniPlayerBar",
                "mini_player_bar", "miniPlayer", "mini_player", "playerBarContainer",
                "bottomPlayerBar", "bottom_player_bar"
        };

        private View miniPlayerShell() {
            View best = null;
            for (String name : MINI_PLAYER_IDS) {
                View seed = findId(name);
                View root = compactOverlayCaptureRoot(seed);
                if (root == null) continue;
                if (best == null || root.getWidth() > best.getWidth()) best = root;
            }
            return best;
        }

        @Override public View[] getBackdropExcludedViews() {
            // The album image, centre play plate and menu button are siblings on some NetEase
            // builds. Exclude their compact common shell, not just the centre container.
            List<View> excluded = new ArrayList<>();
            View shell = miniPlayerShell();
            if (shell != null) excluded.add(shell);
            for (String name : MINI_PLAYER_IDS) {
                View seed = findId(name);
                if (seed != null && !excluded.contains(seed)) excluded.add(seed);
            }
            return excluded.toArray(new View[0]);
        }

        private View compactOverlayCaptureRoot(View seed) {
            if (seed == null) return null;
            View best = seed;
            View content = null;
            try { content = activity.findViewById(android.R.id.content); }
            catch (Throwable ignored) {}
            float density = Math.max(0.5f,
                    seed.getResources().getDisplayMetrics().density);
            int maxHeight = (int) (220f * density + 0.5f);
            View current = seed;
            for (int depth = 0; depth < 3; depth++) {
                if (!(current.getParent() instanceof View)) break;
                View parent = (View) current.getParent();
                if (parent == content || parent == decor()) break;
                int height = Math.max(parent.getHeight(), parent.getMeasuredHeight());
                int width = Math.max(parent.getWidth(), parent.getMeasuredWidth());
                int screenWidth = Math.max(1,
                        parent.getResources().getDisplayMetrics().widthPixels);
                if (height <= 0 || height > maxHeight || width < screenWidth * 0.55f) break;
                best = parent;
                current = parent;
            }
            return best;
        }

        @Override public boolean preserveLastBackdropOnBlankFrame() { return true; }

        // 9.5.50 is sampled from the Activity window by physical screen rectangle. The legacy
        // content-root coordinate switch is intentionally disabled and retained only as fallback
        // compatibility for older module builds.
        @Override public boolean preservePhysicalBackdropCoordinates() { return false; }

        // “我的”页面会同时保留头像/动效使用的硬件子层。普通层级已经能绘制真实
        // 页面，再叠加自动找到的 TextureView/SurfaceView 会把局部旧帧拉伸到玻璃中。
        // 只在 native mine(3) 页面关闭二次合成，离开“我的”后立即恢复。
        @Override public boolean captureHardwareBackdrops() {
            int selected = intValue(call(tabsObject(), "getSelectedTabPosition"), -1);
            return selected != 3;
        }
        @Override public SuppressionPlan getSuppressionPlan() {
            // Move the complete mini-player shell above the glass. Aligning only the centre plate
            // left the album artwork and menu siblings underneath the injected bar.
            return new SuppressionPlan()
                    .collapse(tabsView())
                    .clear(findId("bottomNavDividerLine"))
                    .zeroBottomPadding(findId("top_header"))
                    .alignAboveGlass(miniPlayerShell(), 8f);
        }
        private Object tabForCode(String code) {
            Object tabs = tabsObject();
            Object value = call(tabs, "n", code);
            if (value != null) return value;
            Object list = getField(tabs, "e");
            if (list instanceof List) {
                for (Object item : (List<?>) list) {
                    Object itemCode = call(item, "j");
                    if (!(itemCode instanceof String)) itemCode = getField(item, "a");
                    if (code.equals(itemCode)) return item;
                }
            }
            return null;
        }
        private View tabView(Object tab) {
            Object value = call(tab, "h");
            return value instanceof View ? (View) value : null;
        }
        private boolean invokeNativeTabClick(Object tabs, Object tab) {
            if (tabs == null || tab == null) return false;
            try {
                Method route = null;
                for (Class<?> current = tabs.getClass(); current != null && route == null;
                     current = current.getSuperclass()) {
                    for (Method method : current.getDeclaredMethods()) {
                        Class<?>[] p = method.getParameterTypes();
                        if ("t".equals(method.getName()) && p.length == 2
                                && p[0].isAssignableFrom(tab.getClass())
                                && p[1].getName().endsWith("NavigationTabLayout$d")) {
                            route = method;
                            break;
                        }
                    }
                }
                if (route == null) return false;
                Object click = getStaticField(route.getParameterTypes()[1], "b");
                if (click == null) return false;
                route.setAccessible(true);
                route.invoke(tabs, tab, click);
                // NavigationTabLayout updates selected position through listeners/animation. Do
                // not treat a not-yet-updated synchronous read as failure and dispatch a second
                // click; duplicate native routes were most visible when entering 我的.
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0 || nativeIndex >= CODES.length) return false;
            UniversalGlassInstaller.prepareNeteaseBackdropSelection(activity, nativeIndex);
            Object tabs = tabsObject();
            Object tab = tabForCode(CODES[nativeIndex]);
            if (tab == null) tab = call(tabs, "k", nativeIndex);
            // NavigationTabLayout.t(tab, TAB_CLICK) is the exact native pipeline in 9.5.45:
            // it updates selected state, runs the interceptor and dispatches page listeners.
            if (invokeNativeTabClick(tabs, tab)) return true;
            return dispatchClick(tabView(tab));
        }
        @Override public boolean post() { return false; }
        @Override public long backdropTransitionDelayMs() { return 104L; }
        @Override public int detectSelectedIndex() {
            Object tabs = tabsObject();
            int selected = intValue(call(tabs, "getSelectedTabPosition"), -1);
            if (selected < 0) return -1;
            Object tab = call(tabs, "k", selected);
            Object code = call(tab, "j");
            if (!(code instanceof String)) code = getField(tab, "a");
            for (int i = 0; i < CODES.length; i++) {
                if (CODES[i].equals(code)) return logicalForNative(i);
            }
            // Remote configuration may rename a tab code while preserving its native position.
            return logicalForNative(selected);
        }
    }

    /**
     * QQ Music 20.6.5.8 exact bridge.
     *
     * The visual order is not the host's numeric order. MainDesktopFragment builds its native
     * items with keys: 首页=1, 视频=2, 刷歌/雷达=4, 星光=3, 我的=0. TargetApp.nativeIds keeps that
     * verified mapping, while this adapter dispatches the original item View click so the host's
     * TabFragmentClickListener, reporting and reselect behavior all remain intact.
     */
    private static final class QQMusicAdapter extends UniversalHostAdapter {
        private static final String NAV_CLASS =
                "com.tencent.qqmusic.ui.minibar.navigation.MainDeskNavigateContainer";

        QQMusicAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
        }

        private View navigateContainer() {
            return findExactClass(NAV_CLASS);
        }

        private Object itemForNative(int nativeIndex) {
            return call(navigateContainer(), "getItem", nativeIndex);
        }

        private Object mainDesktopFragment() {
            return findFragment(call(activity, "getSupportFragmentManager"), 0);
        }

        private Object findFragment(Object fragmentManager, int depth) {
            if (fragmentManager == null || depth > 5) return null;
            Object fragments = call(fragmentManager, "getFragments");
            if (!(fragments instanceof List)) return null;
            for (Object fragment : (List<?>) fragments) {
                if (fragment == null) continue;
                if ("com.tencent.qqmusic.fragment.mainpage.MainDesktopFragment"
                        .equals(fragment.getClass().getName())) return fragment;
                Object nested = findFragment(call(fragment, "getChildFragmentManager"), depth + 1);
                if (nested != null) return nested;
            }
            return null;
        }

        private View itemRoot(Object item) {
            if (item == null) return null;
            Object holder = call(item, "h");
            Object root = call(holder, "m");
            if (root instanceof View) return (View) root;
            Object holderField = getField(item, "i");
            Object rootField = getField(holderField, "a");
            return rootField instanceof View ? (View) rootField : null;
        }

        @Override public View getBottomBarRoot() {
            return navigateContainer();
        }

        @Override public View getBackdropSourceView() {
            Object container = call(mainDesktopFragment(), "getContainer");
            if (container instanceof View) return (View) container;
            return findId("main_desk_fragment_pager");
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            View navigation = navigateContainer();
            float hostHeightDp = measuredHeightDp(navigation, 58f);
            // Preserve the original controller and all five click listeners. Moving it off-screen
            // is safer than detaching/collapsing it because MainDesktopFragment keeps mNavigate
            // bound to this exact instance. Extend the pager by the measured native row height so
            // page pixels continue behind the replacement glass instead of leaving a blank strip.
            return new SuppressionPlan()
                    .overscanBottom(getBackdropSourceView(), hostHeightDp)
                    .offscreen(navigation);
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0) return false;
            Object item = itemForNative(nativeIndex);
            View root = itemRoot(item);

            // The original item root owns TabFragmentClickListener.onClick(). performClick keeps
            // QQ Music's page switching, analytics, repeat-tap and AB-test behavior together.
            if (dispatchClick(root)) return true;

            Object listener = call(item, "a");
            if (root != null && callExists(listener, "onClick", root)) return true;

            // MainDesktopFragment exposes the same verified page key through gotoSelectedTab().
            // Use it only when the native item View has not finished attaching its listener.
            Object fragment = mainDesktopFragment();
            if (callExists(fragment, "gotoSelectedTab", nativeIndex, false)) return true;
            if (callExists(fragment, "gotoSelectedTab", nativeIndex)) return true;

            // Final visual-only fallback. The exact view hooks schedule another adapter retry.
            return callExists(navigateContainer(), "setSelectedItem", nativeIndex);
        }

        @Override public boolean post() {
            return false;
        }

        @Override public int detectSelectedIndex() {
            Object selectedItem = call(navigateContainer(), "getSelectedItem");
            int nativeIndex = intValue(call(selectedItem, "g"), -1);
            if (nativeIndex < 0) nativeIndex = intValue(getField(navigateContainer(), "f"), -1);
            if (nativeIndex < 0) {
                nativeIndex = intValue(call(mainDesktopFragment(), "getCurrentSubFragmentIndex"), -1);
            }
            return logicalForNative(nativeIndex);
        }
    }

    /**
     * HyperOS File Manager, Gallery, Notes and Messages all expose the same public Miuix
     * NavigationBarView contract. The resource names below were decoded from the supplied APKs;
     * navigation is sent through setSelectedItemId so the host's own NavigatorInfoProvider remains
     * the only code that creates/switches pages. Gallery additionally resolves the bar through its
     * NavigatorActivity API because its floating row is not guaranteed to be found by a decor-tree
     * scan. The Phone entry uses the same bridge only when the separately installed Contacts/Dialer
     * package exposes that contract.
     */
    private static final class MiuixNavigatorAdapter extends UniversalHostAdapter {
        private final View cachedNavigation;
        private final String[][] menuIdNames;
        private final int[] effectiveNativeIds;
        private final String[] effectiveLabels;

        MiuixNavigatorAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
            cachedNavigation = resolveNavigation();
            switch (app.key) {
                case "mi_file_manager":
                    menuIdNames = new String[][]{
                            {"recent"}, {"home_file"}, {"drive"}
                    };
                    break;
                case "mi_gallery":
                    menuIdNames = new String[][]{
                            {"home_page"}, {"album_page"}
                    };
                    break;
                case "mi_notes":
                    menuIdNames = new String[][]{
                            {"action_page_1"}, {"action_page_2"}
                    };
                    break;
                case "mi_messages":
                    menuIdNames = new String[][]{
                            {"action_page_1", "action_page_1_gl"},
                            {"action_page_2", "action_page_2_gl"}
                    };
                    break;
                case "mi_phone":
                    // The uploaded Phone APK is InCallUI and contains no launcher/navigation
                    // screen. These names are intentionally limited to the real Contacts/Dialer
                    // package and are accepted only when they are members of its active menu.
                    menuIdNames = new String[][]{
                            {"call_log", "call_log_tab", "tab_calls", "tab_call",
                                    "navigation_calls", "action_page_1"},
                            {"contacts", "contacts_tab", "tab_contacts", "tab_contact",
                                    "navigation_contacts", "action_page_2"}
                    };
                    break;
                default:
                    menuIdNames = new String[0][];
                    break;
            }
            if (app == TargetApp.MI_FILE_MANAGER && intValue(call(menu(), "size"), 0) > 0) {
                List<Integer> nativeIds = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                for (int logical = 0; logical < visibleNativeIds.length; logical++) {
                    int nativeId = visibleNativeIds[logical];
                    int source = -1;
                    for (int index = 0; index < app.nativeIds.length; index++) {
                        if (app.nativeIds[index] == nativeId) {
                            source = index;
                            break;
                        }
                    }
                    int menuId = declaredMenuId(source);
                    if (menuId != 0 && activeMenuContains(menuId)) {
                        nativeIds.add(nativeId);
                        labels.add(visibleLabels[logical]);
                    }
                }
                if (!nativeIds.isEmpty()) {
                    effectiveNativeIds = new int[nativeIds.size()];
                    for (int index = 0; index < nativeIds.size(); index++) {
                        effectiveNativeIds[index] = nativeIds.get(index);
                    }
                    effectiveLabels = labels.toArray(new String[0]);
                } else {
                    effectiveNativeIds = visibleNativeIds;
                    effectiveLabels = visibleLabels;
                }
            } else {
                effectiveNativeIds = visibleNativeIds;
                effectiveLabels = visibleLabels;
            }
        }

        @Override public String[] getVisibleLabels() { return effectiveLabels; }

        @Override protected int nativeForLogical(int logical) {
            if (logical < 0 || logical >= effectiveNativeIds.length) return -1;
            return effectiveNativeIds[logical];
        }

        @Override protected int logicalForNative(int nativeValue) {
            for (int index = 0; index < effectiveNativeIds.length; index++) {
                if (effectiveNativeIds[index] == nativeValue) return index;
            }
            return -1;
        }

        private View resolveNavigation() {
            // Gallery 4.3.1.13 creates its floating BottomNavigationView inside
            // MiuixNavigatorImpl. getNavigator().getBottomTabView() is the stable public path and
            // remains reliable even after the photo grid grows beyond the bounded decor scan.
            View galleryNavigation = galleryBottomNavigation();
            if (galleryNavigation != null) return galleryNavigation;

            View byId = findId(
                    "miuix_bottom_navigation_bar", "bottom_navigation", "bottom_navigation_view",
                    "bottom_nav", "bottom_nav_view", "navigation_bar"
            );
            if (byId != null && isMiuixNavigation(byId)) return byId;
            return findExactClass("miuix.navigator.bottomnavigation.BottomNavigationView");
        }

        private static boolean isMiuixNavigation(View view) {
            if (view == null) return false;
            String className = view.getClass().getName();
            return "miuix.navigator.bottomnavigation.BottomNavigationView".equals(className)
                    || (className.startsWith("miuix.navigator.")
                    && hasMethod(view.getClass(), "getSelectedItemId")
                    && hasMethod(view.getClass(), "getMenu"));
        }

        private Object galleryNavigator() {
            return app == TargetApp.MI_GALLERY ? call(activity, "getNavigator") : null;
        }

        private View galleryBottomNavigation() {
            Object navigator = galleryNavigator();
            Object bottomTab = call(navigator, "getBottomTabView");
            if (!(bottomTab instanceof View)) {
                // Verified field in Gallery 4.3.1.13's MiuixNavigatorImpl. This covers the short
                // interval after ensureBottomNavigationExist() assigns the field but before the
                // NavigationView parent finishes attaching it.
                bottomTab = getField(navigator, "mBottomNavigationView");
            }
            if (!(bottomTab instanceof View)) {
                Object navigationView = call(navigator, "getNavigationView");
                bottomTab = call(navigationView, "getBottomNavigation");
            }
            return bottomTab instanceof View && isMiuixNavigation((View) bottomTab)
                    ? (View) bottomTab : null;
        }

        private View navigation() {
            return cachedNavigation != null ? cachedNavigation : resolveNavigation();
        }

        private int sourceIndexForLogical(int logicalIndex) {
            int nativeValue = nativeForLogical(logicalIndex);
            for (int i = 0; i < app.nativeIds.length; i++) {
                if (app.nativeIds[i] == nativeValue) return i;
            }
            return -1;
        }

        private int logicalForSourceIndex(int sourceIndex) {
            if (sourceIndex < 0 || sourceIndex >= app.nativeIds.length) return -1;
            return logicalForNative(app.nativeIds[sourceIndex]);
        }

        private int declaredMenuId(int sourceIndex) {
            if (sourceIndex < 0 || sourceIndex >= menuIdNames.length) return 0;
            for (String name : menuIdNames[sourceIndex]) {
                try {
                    int id = activity.getResources().getIdentifier(name, "id", app.packageName);
                    if (id != 0) return id;
                } catch (Throwable ignored) {}
            }
            return 0;
        }

        private Object menu() {
            return call(navigation(), "getMenu");
        }

        private Object menuItemAt(int position) {
            Object menu = menu();
            int size = intValue(call(menu, "size"), 0);
            if (position < 0 || position >= size) return null;
            return call(menu, "getItem", position);
        }

        private int itemId(Object item) {
            return intValue(call(item, "getItemId"), 0);
        }

        private boolean activeMenuContains(int id) {
            if (id == 0) return false;
            Object menu = menu();
            return call(menu, "findItem", id) != null;
        }

        @Override public View getBottomBarRoot() { return navigation(); }

        @Override public boolean shouldShowInjectedNavigation() {
            if (app != TargetApp.MI_FILE_MANAGER) return true;
            return isFileManagerPrimaryContentVisible();
        }

        /**
         * File Explorer deliberately reuses the main-tab navigation ids for secondary pages. For
         * example, opening a folder from 浏览 navigates to PadFileDetailFragment with id 1001 and
         * NAVIGATOR_FRAGMENT_WONT_REFRESH_NAVIGATION=true. The selected bottom item therefore
         * cannot tell a root page from a folder/search page.
         *
         * The three real root pages all live inside one PhoneMainFragment ViewPager. Resolve the
         * navigator's actual content Fragment and allow only that exact class. Folder, search,
         * category, NAS and every other secondary destination then hide LiquidGlass without a
         * growing id/class blacklist.
         */
        private boolean isFileManagerPrimaryContentVisible() {
            Object navigator = call(activity, "getNavigator");
            Object content = call(navigator, "getByTag", "miuix.content");
            Object fragmentManager = call(content, "getFragmentManager");
            Object fragment = call(fragmentManager, "findFragmentByTag", "miuix.content");
            if (fragment == null) {
                // AndroidX FragmentManager.findFragmentByTag is named C(String) in the supplied
                // File Explorer 8.2.3.2 bytecode.
                fragment = call(fragmentManager, "C", "miuix.content");
            }
            if (fragment == null || !"com.android.fileexplorer.fragment.PhoneMainFragment"
                    .equals(fragment.getClass().getName())) return false;
            if (Boolean.TRUE.equals(call(fragment, "isHidden"))) return false;
            return !Boolean.FALSE.equals(call(fragment, "isAdded"));
        }

        @Override public boolean isNavigationVisible() {
            if (app == TargetApp.MI_GALLERY) {
                // HomePageActivity is itself the two-tab destination. Gallery calls hide() while
                // its floating native row is being created, animated or reparented; mirroring that
                // transient GONE state also hid our independent bar forever on some HyperOS builds.
                // Keep LiquidGlass visible for this Activity and suppress the native row separately.
                return true;
            }
            if (app == TargetApp.MI_FILE_MANAGER
                    && hasVisibleFileManagerActionMode(false)) {
                // Hide the ordinary navigation for every contextual ActionMode. Unsupported
                // private/favorite action sets keep their own native contextual menu instead of
                // being mislabeled as Send/Move/Delete.
                return false;
            }
            return super.isNavigationVisible();
        }

        @Override public boolean isFileManagerSelectionModeVisible() {
            return app == TargetApp.MI_FILE_MANAGER
                    && hasVisibleFileManagerActionMode(true);
        }

        private boolean hasVisibleFileManagerActionMode(boolean requireOperationMenu) {
            if (app != TargetApp.MI_FILE_MANAGER) return false;
            View root = decor();
            if (root == null) return false;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 2600) {
                View view = queue.removeFirst();
                String name = view.getClass().getName();
                boolean selectionActionMode = name.endsWith("ActionBarContextView")
                        || name.contains("ActionModeContextView")
                        || (name.contains("ActionModeView")
                        && !name.contains("SearchActionMode"));
                if (selectionActionMode
                        && view.isAttachedToWindow() && view.isShown()
                        && view.getAlpha() > 0.01f
                        && Math.max(view.getHeight(), view.getMeasuredHeight()) > 0) {
                    if (!requireOperationMenu || hasFileManagerOperationActions(view)) {
                        return true;
                    }
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.addLast(group.getChildAt(i));
                    }
                }
            }
            return false;
        }

        private boolean hasFileManagerOperationActions(View actionModeView) {
            Object candidate = call(actionModeView, "getMenuView");
            if (!(candidate instanceof View)) return false;
            int send = activity.getResources().getIdentifier(
                    "action_send", "id", app.packageName);
            int decrypt = activity.getResources().getIdentifier(
                    "action_decrypt", "id", app.packageName);
            int move = activity.getResources().getIdentifier(
                    "action_move", "id", app.packageName);
            int delete = activity.getResources().getIdentifier(
                    "action_delete", "id", app.packageName);
            if ((send == 0 && decrypt == 0) || move == 0 || delete == 0) return false;
            Set<Integer> found = new LinkedHashSet<>();
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add((View) candidate);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 80) {
                View view = queue.removeFirst();
                Object item = call(view, "getItemData");
                int id = intValue(call(item, "getItemId"), 0);
                if (id == send || id == decrypt || id == move || id == delete) found.add(id);
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int index = 0; index < group.getChildCount(); index++) {
                        queue.addLast(group.getChildAt(index));
                    }
                }
            }
            boolean primary = found.contains(send) || found.contains(decrypt);
            return primary && found.contains(move) && found.contains(delete);
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            if (app == TargetApp.MI_GALLERY) {
                // 4.3.1.16 keeps the row VISIBLE+alpha=0 under our transparent plan, yet the
                // HyperMaterial blur surface and the measured row can still be composited above
                // the independent glass window (users see the native bar lifted above the glass).
                // Park the exact controller-owned row below the physical display instead: the
                // lifecycle hooks in UniversalHookEntry still catch every re-attach and the frame
                // guard re-applies the off-screen translation every frame, so a second row cannot
                // flash. Selection/menu/controller state remains native and callable.
                return new SuppressionPlan().offscreen(navigation());
            }
            // Height zero reclaims the Navigator content inset but leaves the real menu,
            // presenter and listeners attached for setSelectedItemId/selection polling.
            SuppressionPlan plan = new SuppressionPlan().collapse(navigation());
            if (app == TargetApp.MI_PHONE) {
                // Grow the fixed-height keypad region upward while adding the same amount to its
                // bottom padding. The 12 keys therefore move as one rigid block, DialpadLayout's
                // rounded background/input area grows upward, and bottom_container (menu_dialpad +
                // close_dialpad) keeps its original screen coordinates. Raising the call action via
                // its container padding avoids translating or clipping either assembly.
                View action = phoneDialAction();
                plan.expandPhoneDialerAboveGlass(
                        action,
                        findId("dialpad_keys_container", "dialpadKeysContainer"),
                        phoneCallContainer(),
                        12f
                );
            }
            return plan;
        }

        private View phoneDialAction() {
            if (app != TargetApp.MI_PHONE) return null;

            // The supplied APK has separate single/double-SIM action Views. Try each visible action
            // rather than accepting the full-width call_button_container as the collision anchor.
            String[] actionIds = {
                    "single_call_button", "single_call_container",
                    "double_call_button", "double_call_container",
                    "dial_button_container", "dialButtonContainer",
                    "dialer_button_container", "dialerButtonContainer",
                    "dialpad_call_button_container", "dialpadCallButtonContainer",
                    "floating_call_button", "floatingCallButton",
                    "dialpad_call_button", "dialpadCallButton",
                    "phone_call_button", "phoneCallButton",
                    "dialer_call_button", "dialerCallButton",
                    "call_button", "callButton", "call_btn",
                    "dial_button", "dialButton", "dial_btn",
                    "dialer_button", "dialerButton",
                    "dialpad_fab", "dialpadFab", "call_fab", "fab_call",
                    "dialpad_floating_action_button", "floating_action_button"
            };
            View nav = navigation();
            for (String id : actionIds) {
                View exact = findId(id);
                if (isReasonablePhoneActionTarget(exact, nav)) return exact;
            }

            // OEM variants sometimes rename the concrete id while preserving a semantic resource
            // name/content description. Keep this fallback local to the Phone activity and require
            // both dial/call semantics and bottom-centre action geometry to avoid touching keypad
            // digits, call-log rows or the Contacts tab.
            View semantic = findPhoneDialActionBySemantics(decor(), nav);
            if (semantic != null) return semantic;

            // Last resort for the verified layout: its bottom edge includes the call-button padding,
            // so it remains a safe (slightly conservative) collision anchor.
            View container = phoneCallContainer();
            return container != null && container.isShown() ? container : null;
        }

        private boolean isReasonablePhoneActionTarget(View view, View nav) {
            if (view == null || isDescendantOf(view, nav)) return false;
            try {
                if (!view.isShown()) return false;
                float density = Math.max(0.5f, view.getResources().getDisplayMetrics().density);
                ViewGroup.LayoutParams raw = view.getLayoutParams();
                if (raw != null && raw.width == ViewGroup.LayoutParams.MATCH_PARENT) return false;
                int width = Math.max(view.getWidth(), view.getMeasuredWidth());
                int height = Math.max(view.getHeight(), view.getMeasuredHeight());
                if (width > 0 && width / density > 280f) return false;
                if (height > 0 && height / density > 200f) return false;
            } catch (Throwable ignored) {}
            return true;
        }

        private View phoneCallContainer() {
            return findId(
                    "call_button_container", "callButtonContainer",
                    "dialpad_call_button_container", "dialpadCallButtonContainer"
            );
        }

        private View findPhoneDialActionBySemantics(View root, View nav) {
            if (root == null) return null;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            View best = null;
            int bestScore = 0;
            int visited = 0;
            int screenWidth = Math.max(1, activity.getResources().getDisplayMetrics().widthPixels);
            int screenHeight = Math.max(1, activity.getResources().getDisplayMetrics().heightPixels);
            float density = Math.max(0.5f, activity.getResources().getDisplayMetrics().density);

            while (!queue.isEmpty() && visited++ < 4200) {
                View view = queue.removeFirst();
                if (view != nav && !isDescendantOf(view, nav)) {
                    String resourceName = resourceEntryName(view);
                    CharSequence description = view.getContentDescription();
                    String semantic = (resourceName + " "
                            + (description == null ? "" : description.toString()))
                            .toLowerCase(Locale.ROOT);
                    boolean dialSemantic = semantic.contains("拨号")
                            || semantic.contains("撥號")
                            || semantic.contains("呼叫")
                            || semantic.contains("dial")
                            || semantic.contains("call");
                    boolean excluded = semantic.contains("log")
                            || semantic.contains("history")
                            || semantic.contains("record")
                            || semantic.contains("tab")
                            || semantic.contains("contact")
                            || semantic.contains("backspace")
                            || semantic.contains("delete")
                            || semantic.contains("keypad_toggle");
                    if (dialSemantic && !excluded && view.isShown()
                            && view.getWidth() > 0 && view.getHeight() > 0) {
                        int[] location = new int[2];
                        try { view.getLocationOnScreen(location); } catch (Throwable ignored) {}
                        float centerX = location[0] + view.getWidth() * 0.5f;
                        float centerY = location[1] + view.getHeight() * 0.5f;
                        float widthDp = view.getWidth() / density;
                        float heightDp = view.getHeight() / density;
                        boolean bottomHalf = centerY > screenHeight * 0.52f;
                        boolean centered = Math.abs(centerX - screenWidth * 0.5f)
                                < screenWidth * 0.30f;
                        boolean actionSize = widthDp >= 36f && heightDp >= 36f
                                && widthDp <= 220f && heightDp <= 180f;
                        if (bottomHalf && centered && actionSize) {
                            int score = 60;
                            if (resourceName.contains("button") || resourceName.contains("btn")
                                    || resourceName.contains("fab")) score += 24;
                            if (resourceName.contains("container")) score += 8;
                            if (view.isClickable() || view.hasOnClickListeners()) score += 12;
                            if (Math.abs(widthDp - heightDp) < 32f) score += 6;
                            if (score > bestScore) {
                                bestScore = score;
                                best = view;
                            }
                        }
                    }
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.addLast(group.getChildAt(i));
                    }
                }
            }
            return bestScore >= 72 ? best : null;
        }

        private String resourceEntryName(View view) {
            if (view == null || view.getId() == View.NO_ID) return "";
            try {
                return view.getResources().getResourceEntryName(view.getId())
                        .toLowerCase(Locale.ROOT);
            } catch (Throwable ignored) {
                return "";
            }
        }

        private static boolean isDescendantOf(View view, View ancestor) {
            if (view == null || ancestor == null) return false;
            View current = view;
            for (int depth = 0; depth < 12 && current != null; depth++) {
                if (current == ancestor) return true;
                current = current.getParent() instanceof View
                        ? (View) current.getParent() : null;
            }
            return false;
        }

        @Override public boolean select(int index) {
            int sourceIndex = sourceIndexForLogical(index);
            if (sourceIndex < 0) return false;
            if (app == TargetApp.MI_GALLERY && selectGalleryNavigation(sourceIndex, index)) {
                return true;
            }
            View nav = navigation();
            if (nav == null) return false;

            int id = declaredMenuId(sourceIndex);
            if (!activeMenuContains(id)) {
                id = itemId(menuItemAt(sourceIndex));
            }
            if (id == 0) return false;
            if (callExists(nav, "setSelectedItemId", id)) return true;

            try {
                View itemView = activity.findViewById(id);
                if (dispatchClick(itemView)) return true;
            } catch (Throwable ignored) {}
            Object activeMenu = menu();
            return activeMenu != null
                    && callExists(activeMenu, "performIdentifierAction", id, 0);
        }

        private boolean selectGalleryNavigation(int sourceIndex, int logicalIndex) {
            Object navigator = galleryNavigator();
            if (navigator == null) return false;
            int navigationId = nativeForLogical(logicalIndex);

            // home_bottom_nav_menu.xml contains duplicate Android item ids, while its
            // miuixNavigationId values are unique. Resolve the actual BottomTab/NavigatorInfo by
            // navigation id and let Gallery's Navigator perform the page transaction.
            Object tab = call(navigator, "findTab", navigationId);
            Object info = call(tab, "getNavigatorInfo");
            if (info == null) {
                Object provider = getField(navigator, "mInfoProvider");
                info = call(provider, "onCreateNavigatorInfo", navigationId);
            }
            if (info == null || !callExists(navigator, "navigate", info)) return false;
            // Keep the hidden native presenter checked so host callbacks and restoration agree.
            callExists(navigator, "selectTab", sourceIndex);
            return true;
        }

        @Override public int detectSelectedIndex() {
            if (app == TargetApp.MI_GALLERY) {
                // Gallery's menu currently contains legacy duplicate Android item ids. Its
                // NavigatorInfo navigationId (1000 Photos / 1001 Collections) is unambiguous.
                Object currentInfo = call(galleryNavigator(), "getCurrentInfo");
                int navigationId = intValue(call(currentInfo, "getNavigationId"), -1);
                int logical = logicalForNative(navigationId);
                if (logical >= 0) return logical;

                // During Navigator transitions getCurrentInfo() can briefly be null. Read the
                // checked MenuItem's unique miuix bottom-tab id from its Intent instead of its
                // duplicated Android item id.
                Object galleryMenu = menu();
                int gallerySize = intValue(call(galleryMenu, "size"), 0);
                for (int source = 0; source < gallerySize; source++) {
                    Object item = call(galleryMenu, "getItem", source);
                    if (!Boolean.TRUE.equals(call(item, "isChecked"))) continue;
                    Object intent = call(item, "getIntent");
                    int checkedNavigationId = intValue(call(
                            intent,
                            "getIntExtra",
                            "miuix.miracle:bottomTabId",
                            -1
                    ), -1);
                    logical = logicalForNative(checkedNavigationId);
                    if (logical >= 0) return logical;
                }
            }

            View nav = navigation();
            int selectedId = intValue(call(nav, "getSelectedItemId"), 0);
            if (selectedId == 0) return -1;

            for (int source = 0; source < menuIdNames.length; source++) {
                if (declaredMenuId(source) == selectedId) {
                    return logicalForSourceIndex(source);
                }
            }

            Object activeMenu = menu();
            int size = intValue(call(activeMenu, "size"), 0);
            for (int source = 0; source < size && source < app.nativeIds.length; source++) {
                if (itemId(call(activeMenu, "getItem", source)) == selectedId) {
                    return logicalForSourceIndex(source);
                }
            }
            return -1;
        }

        @Override public boolean post() { return false; }
    }

    /** Exact bridge for Calendar 17.10.8.16's custom full-screen BottomNavigatorView overlay. */
    private static final class MiCalendarAdapter extends UniversalHostAdapter {
        private final View cachedNavigation;

        MiCalendarAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
            View byId = findId("navigator_bottom");
            cachedNavigation = byId != null ? byId
                    : findExactClass("com.android.calendar.homepage.BottomNavigatorView");
        }

        private View navigation() {
            if (cachedNavigation != null) return cachedNavigation;
            View byId = findId("navigator_bottom");
            return byId != null ? byId
                    : findExactClass("com.android.calendar.homepage.BottomNavigatorView");
        }

        private Object[] items() {
            Object value = call(navigation(), "getNavigatorItemView");
            return value instanceof Object[] ? (Object[]) value : new Object[0];
        }

        /**
         * Reverse inspection of the supplied MIUICalendar.apk confirms that BottomNavigatorView
         * itself is the page-level LinearLayout wrapper. Its private field `b` is the actual bottom
         * interaction row, and dispatchTouchEvent() only consumes ACTION_DOWN inside that row's
         * global rectangle. Suppressing the wrapper therefore hides LiquidGlass through
         * isNavigationVisible() and can also break Calendar's gesture coordinator.
         */
        private View interactionRow() {
            View nav = navigation();
            Object exact = getField(nav, "b");
            if (exact instanceof View && exact != nav) return (View) exact;
            Object[] itemViews = items();
            if (itemViews.length > 0 && itemViews[0] instanceof View) {
                Object parent = ((View) itemViews[0]).getParent();
                if (parent instanceof View && parent != nav) return (View) parent;
            }
            return null;
        }

        @Override public View getBottomBarRoot() { return navigation(); }

        @Override public boolean isNavigationVisible() {
            // The visual child row is intentionally translated below the screen. The controller
            // wrapper remains attached and is the reliable signal that Calendar is on its home UI.
            View nav = navigation();
            return nav != null && nav.isAttachedToWindow();
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            // Keep the page-level BottomNavigatorView fully measured and visible to Calendar's
            // coordinator. Move only its private interaction row below the screen. This preserves
            // vertical scrolling, leaves the item listeners callable through performClick(), and
            // makes the wrapper's dispatchTouchEvent() return false for real page gestures.
            View row = interactionRow();
            if (row != null) return new SuppressionPlan().offscreen(row);

            // Version-safe fallback: translation does not alter measurement, unlike the old
            // height-zero path. isNavigationVisible() above intentionally ignores translation.
            return new SuppressionPlan().offscreen(navigation());
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            Object[] items = items();
            if (nativeIndex < 0 || nativeIndex >= items.length
                    || !(items[nativeIndex] instanceof View)) return false;
            return dispatchClick((View) items[nativeIndex]);
        }

        @Override public int detectSelectedIndex() {
            // AllInOneActivity updates this committed view type from its k.r EventBus event.
            int viewType = intValue(getField(activity, "a", "f7856a"), -1);
            int nativeIndex;
            switch (viewType) {
                case 5: nativeIndex = 3; break; // Year
                case 4: nativeIndex = 0; break; // Month
                case 3: nativeIndex = 1; break; // Week
                case 7: nativeIndex = 2; break; // Day
                default: return -1;
            }
            return logicalForNative(nativeIndex);
        }

        @Override public int getDefaultSelectedIndex() {
            // Calendar opens on Month before its EventBus-backed view type is readable. The glass
            // order is Year/Month/Week/Day, so Month is logical index 1.
            return logicalForNative(0);
        }

        @Override public boolean post() { return false; }
    }

    /** Exact bridge for Compass 17.1.0.1's two-item miuix.bottomnavigation.BottomNavigator. */
    private static final class MiCompassAdapter extends UniversalHostAdapter {
        private static final String[] ICON_RESOURCE_NAMES = {
                "ic_bottom_nav_compass_selecd",
                "ic_bottom_nav_level_seleced"
        };
        private final View cachedNavigation;

        MiCompassAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
            View byId = findId("bottom_navigator");
            cachedNavigation = byId != null ? byId
                    : findExactClass("miuix.bottomnavigation.BottomNavigator");
        }

        private View navigation() {
            if (cachedNavigation != null) return cachedNavigation;
            View byId = findId("bottom_navigator");
            return byId != null ? byId
                    : findExactClass("miuix.bottomnavigation.BottomNavigator");
        }

        @Override public View getBottomBarRoot() { return navigation(); }

        @Override public boolean isNavigationVisible() {
            // LiquidGlass parks the visual navigator below the display. Attachment remains the
            // reliable signal that CompassActivity is still showing its two root destinations.
            View nav = navigation();
            return nav != null && nav.isAttachedToWindow();
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            // Keep BottomNavigator measured so its real item Views, selected-state controller and
            // CompassScreenView callback remain callable; only its pixels leave the viewport.
            return new SuppressionPlan().offscreen(navigation());
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            View nav = navigation();
            if (nav == null || nativeIndex < 0) return false;

            // getItemContainer() is public in the supplied Miuix widget. Calling its actual child
            // reproduces BottomNavigator.b0(): update selected state, notify CompassActivity, then
            // animate the hidden native overlay. This avoids bypassing the host's page callback.
            Object container = call(nav, "getItemContainer");
            if (container instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) container;
                if (nativeIndex < group.getChildCount()
                        && dispatchClick(group.getChildAt(nativeIndex))) return true;
            }

            // Version fallback: the verified CompassActivity callback is H2(int), while
            // setSelectedItem(int) keeps the native controller synchronized for swipe changes.
            boolean pageChanged = callExists(activity, "H2", nativeIndex);
            boolean selectionChanged = callExists(nav, "setSelectedItem", nativeIndex);
            return pageChanged || selectionChanged;
        }

        @Override public int detectSelectedIndex() {
            View nav = navigation();
            int selected = intValue(call(nav, "getSelectedItem"), -1);
            int logical = logicalForNative(selected);
            if (logical >= 0) return logical;

            // Miuix variants have shipped getSelectedItem() as either an index-like value or an
            // item object. Fall back to the real item container's checked/selected state so host
            // swipes, restoration and delayed callbacks cannot leave the glass indicator stale.
            Object container = call(nav, "getItemContainer");
            if (container instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) container;
                int count = Math.min(group.getChildCount(), 2);
                for (int nativeIndex = 0; nativeIndex < count; nativeIndex++) {
                    View child = group.getChildAt(nativeIndex);
                    if (compassItemSelected(child, 0)) {
                        logical = logicalForNative(nativeIndex);
                        if (logical >= 0) return logical;
                    }
                }
            }
            return -1;
        }

        private boolean compassItemSelected(View view, int depth) {
            if (view == null || depth > 5) return false;
            try {
                if (view.isSelected() || view.isActivated()) return true;
                Object checked = call(view, "isChecked");
                if (Boolean.TRUE.equals(checked)) return true;
                CharSequence state = view.getStateDescription();
                if (state != null) {
                    String text = state.toString();
                    if (text.contains("选中") || text.toLowerCase(Locale.ROOT).contains("selected")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (compassItemSelected(group.getChildAt(i), depth + 1)) return true;
                }
            }
            return false;
        }

        @Override public int[] getNavigationIconResourceIds() {
            int[] ids = new int[ICON_RESOURCE_NAMES.length];
            for (int index = 0; index < ICON_RESOURCE_NAMES.length; index++) {
                try {
                    ids[index] = activity.getResources().getIdentifier(
                            ICON_RESOURCE_NAMES[index], "drawable", app.packageName
                    );
                } catch (Throwable ignored) {
                    ids[index] = 0;
                }
            }
            return ids;
        }

        @Override public View getBackdropSourceView() {
            // Keep the verified Compass activity root as the ordinary Bitmap source. CameraView2
            // lives inside this page hierarchy; hidden SurfaceView descendants are filtered by
            // isShown() in CoolapkLegacyBitmapBackdropManager before any PixelCopy is requested.
            return findId("compass_root");
        }

        @Override public boolean preservePhysicalBackdropCoordinates() { return true; }

        @Override public boolean captureHardwareBackdrops() { return true; }

        @Override public int hardwareBackdropCaptureModeForSelection(int logicalIndex) {
            // 26.56: camera can be launched while either Compass or Level is active. Keep Surface
            // compositing eligible on both destinations; the manager itself stays capture-idle
            // until a real visible SurfaceView exists in the Activity window.
            int nativeIndex = nativeForLogical(logicalIndex);
            return nativeIndex == 0 || nativeIndex == 1 ? 1 : -1;
        }

        @Override public boolean preserveLastBackdropOnBlankFrame() { return true; }
        @Override public boolean post() { return false; }
    }

    /**
     * GitHub 1.273.0 keeps the page content in nav_host_fragment and renders its four top-level
     * destinations in the separate navigation_bar ComposeView. Collapsing only that exact view
     * lets the weighted fragment container reclaim the row without touching page Compose roots.
     *
     * The native row becomes accessibility-hidden after suppression, so routing and selection
     * also use the verified MainActivity.v0/z0/t0 methods and u singleton destination objects.
     */
    private static final class GithubAdapter extends UniversalHostAdapter {
        private static final String[][] TAB_ALIASES = {
                {"主页", "首页", "Home"},
                {"Inbox", "收件箱", "Notifications"},
                {"探索", "Explore"},
                {"Copilot"}
        };
        private static final String[] ITEM_CLASS_NAMES = {
                "com.github.android.main.u$c",
                "com.github.android.main.u$d",
                "com.github.android.main.u$b",
                "com.github.android.main.u$a"
        };

        GithubAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
        }

        private View navigationBar() {
            return findId("navigation_bar");
        }

        private AccessibilityNodeProvider provider() {
            View bar = navigationBar();
            try { return bar == null ? null : bar.getAccessibilityNodeProvider(); }
            catch (Throwable ignored) { return null; }
        }

        private Object navigationItem(int nativeIndex) {
            if (nativeIndex < 0 || nativeIndex >= ITEM_CLASS_NAMES.length) return null;
            try {
                Class<?> type = Class.forName(
                        ITEM_CLASS_NAMES[nativeIndex], false, activity.getClassLoader()
                );
                Field singleton = type.getDeclaredField("f");
                singleton.setAccessible(true);
                return singleton.get(null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private AccessibilityNodeInfo semanticNodeForNative(int nativeIndex) {
            if (nativeIndex < 0 || nativeIndex >= TAB_ALIASES.length) return null;
            AccessibilityNodeProvider provider = provider();
            if (provider == null) return null;
            try {
                AccessibilityNodeInfo root = provider.createAccessibilityNodeInfo(-1);
                if (root == null) root = provider.createAccessibilityNodeInfo(0);
                if (root == null) return null;
                ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
                queue.add(root);
                int visited = 0;
                while (!queue.isEmpty() && visited++ < 200) {
                    AccessibilityNodeInfo node = queue.removeFirst();
                    if (matchesExactSemanticValue(node.getText(), TAB_ALIASES[nativeIndex])
                            || matchesExactSemanticValue(
                                    node.getContentDescription(), TAB_ALIASES[nativeIndex])) {
                        while (!queue.isEmpty()) {
                            try { queue.removeFirst().recycle(); } catch (Throwable ignored) {}
                        }
                        return node;
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.addLast(child);
                    }
                    try { node.recycle(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private boolean matchesExactSemanticValue(CharSequence value, String... aliases) {
            if (value == null || aliases == null) return false;
            String normalized = value.toString().trim();
            for (String alias : aliases) {
                if (alias == null) continue;
                if (normalized.equalsIgnoreCase(alias)
                        || normalized.startsWith(alias + ",")
                        || normalized.startsWith(alias + "，")
                        || normalized.startsWith(alias + " ")) return true;
            }
            return false;
        }

        private Object currentDestination() {
            Object navController = getField(activity, "z0");
            Object controller = getField(navController, "b");
            return call(controller, "j");
        }

        private boolean isCurrentDestination(Object item, Object destination) {
            return item != null && destination != null
                    && Boolean.TRUE.equals(call(activity, "t0", item, destination));
        }

        @Override public View getBottomBarRoot() {
            return navigationBar();
        }

        @Override public View getBackdropSourceView() {
            return findId("nav_host_fragment");
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            return new SuppressionPlan().collapse(navigationBar());
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0 || nativeIndex >= TAB_ALIASES.length) return false;

            AccessibilityNodeInfo node = semanticNodeForNative(nativeIndex);
            if (node != null) {
                try {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                } finally {
                    try { node.recycle(); } catch (Throwable ignored) {}
                }
            }

            Object item = navigationItem(nativeIndex);
            Object destination = currentDestination();
            if (isCurrentDestination(item, destination)
                    && callExists(activity, "z0", item)) return true;
            if (callExists(activity, "v0", item)) return true;

            View bar = navigationBar();
            if (bar == null || bar.getWidth() <= 0 || bar.getHeight() <= 0) return false;
            float x = bar.getWidth() * ((nativeIndex * 2f + 1f) / 8f);
            return dispatchTapAt(bar, x, bar.getHeight() * 0.5f);
        }

        @Override public boolean post() { return false; }

        @Override public int detectSelectedIndex() {
            Object destination = currentDestination();
            if (destination != null) {
                for (int logical = 0; logical < visibleNativeIds.length; logical++) {
                    int nativeIndex = visibleNativeIds[logical];
                    if (isCurrentDestination(navigationItem(nativeIndex), destination)) {
                        return logical;
                    }
                }
            }

            for (int logical = 0; logical < visibleNativeIds.length; logical++) {
                AccessibilityNodeInfo node = semanticNodeForNative(visibleNativeIds[logical]);
                if (node == null) continue;
                try {
                    if (isSemanticSelected(node)) return logical;
                } finally {
                    try { node.recycle(); } catch (Throwable ignored) {}
                }
            }
            return -1;
        }

        @Override public long backdropTransitionDelayMs() { return 96L; }
    }

    /**
     * UU Remote ships two home implementations behind LaunchActivity:
     * - ComposeMainActivity: one AndroidComposeView owns both page content and the four-item bar.
     * - MainActivity: a legacy TabLayout + CustomViewPager fallback.
     *
     * UU Remote 4.38.0 changed its enum to Device(0), CloudPc(1), Assist(2), Mine(3). Its Compose
     * page also creates content only after the first WindowInsets dispatch, so accessibility
     * semantics are an optional click bridge rather than a prerequisite for installing the bar.
     */
    private static final class UuRemoteAdapter extends UniversalHostAdapter {
        private static final String COMPOSE_PAGE_FRAGMENT =
                "com.remote.app.fragment.ComposePageFragment";
        private static final String COMPOSE_PAGE_ARGUMENT = "compose_page_arg_page";
        private static final String COMPOSE_HOME_PAGE = "Home";
        private static final int[] COMPOSE_FOUR_NATIVE_IDS = {0, 1, 2, 3};
        private static final int[] COMPOSE_THREE_NATIVE_IDS = {0, 2, 3};
        private static final Map<Activity, int[]> COMPOSE_NATIVE_IDS_BY_ACTIVITY =
                Collections.synchronizedMap(new WeakHashMap<Activity, int[]>());
        private static final String[][] TAB_ALIASES = {
                {"我的设备", "全部设备", "Device"},
                {"云设备", "云电脑", "CloudPc", "Cloud PC"},
                {"远程协助", "Assist"},
                {"我的", "Mine", "Profile"}
        };
        private float cachedComposeBarHeightDp = 72f;
        private long cachedComposeBarHeightAt = 0L;

        UuRemoteAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
        }

        private View legacyBindingField(String fieldName) {
            Object binding = call(activity, "O");
            Object value = getField(binding, fieldName);
            return value instanceof View ? (View) value : null;
        }

        private View legacyTabLayout() {
            View value = legacyBindingField("b");
            if (value != null) return value;
            return findExactClass("com.google.android.material.tabs.TabLayout");
        }

        private View legacyPager() {
            View value = legacyBindingField("c");
            if (value != null) return value;
            return findExactClass("com.remote.widget.view.CustomViewPager");
        }

        private View legacySplitLine() {
            return findVerifiedSiblingDivider(
                    legacyBindingField("a"), legacyTabLayout(), "split_line"
            );
        }

        private Object supportFragmentManager() {
            Object manager = call(activity, "getSupportFragmentManager");
            if (manager == null) {
                // AppCompatActivity#getSupportFragmentManager is R8-renamed to D in UU 4.38.0.
                manager = call(activity, "D");
            }
            return manager;
        }

        private List<?> supportFragments(Object manager) {
            Object fragments = call(manager, "getFragments");
            if (fragments instanceof List) return (List<?>) fragments;

            // AndroidX FragmentManager is bundled and obfuscated in UU 4.38.0. Its FragmentStore
            // field/method are f9689c/f; keep standard names first for nearby unshrunk builds.
            // UU Remote 4.38.0 ships the AndroidX FragmentManager with its FragmentStore
            // field obfuscated to `c` (the app's own t8.d.a() reads d0.c.f()). Without this
            // exact field the ComposePageFragment list is always null and the injected bar is
            // never installed on ComposeMainActivity.
            Object store = getField(manager, "mFragmentStore", "f9689c", "c");
            fragments = call(store, "getFragments");
            if (!(fragments instanceof List)) fragments = call(store, "f");
            return fragments instanceof List ? (List<?>) fragments : null;
        }

        private Object currentComposePageFragment() {
            Object manager = supportFragmentManager();
            List<?> fragments = supportFragments(manager);
            if (manager == null || fragments == null) return null;
            for (int index = fragments.size() - 1; index >= 0; index--) {
                Object fragment = fragments.get(index);
                if (fragment == null
                        || !COMPOSE_PAGE_FRAGMENT.equals(fragment.getClass().getName())
                        || !Boolean.TRUE.equals(call(fragment, "isAdded"))
                        || Boolean.TRUE.equals(call(fragment, "isHidden"))
                        || intValue(call(fragment, "getId"), View.NO_ID) != android.R.id.content) {
                    continue;
                }
                Object fragmentView = call(fragment, "getView");
                if (!(fragmentView instanceof View)) continue;
                Object owner = call(fragment, "getFragmentManager");
                if (owner == null || owner == manager) return fragment;
            }
            return null;
        }

        private boolean isComposeHomePage() {
            Object fragment = currentComposePageFragment();
            Object arguments = call(fragment, "getArguments");
            Object page = call(arguments, "getString", COMPOSE_PAGE_ARGUMENT);
            return COMPOSE_HOME_PAGE.equals(page);
        }

        private View composeRoot() {
            Object fragment = currentComposePageFragment();
            Object fragmentView = call(fragment, "getView");
            if (!(fragmentView instanceof View)) return null;
            View pageRoot = (View) fragmentView;
            View root = findViewByExactClass(
                    pageRoot,
                    "androidx.compose.ui.platform.AndroidComposeView"
            );
            if (root != null) return root;
            // Keep this fallback inside the active ComposePageFragment. The Activity decor may
            // contain unrelated Compose roots (dialogs/overlays) that must never be overscanned.
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(pageRoot);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 800) {
                View view = queue.removeFirst();
                Class<?> type = view.getClass();
                if (hasMethod(type, "getAccessibilityNodeProvider")
                        && hasMethod(type, "getViewTreeOwners")
                        && hasMethod(type, "getSemanticsOwner")
                        && type.getName().contains("compose")) {
                    return view;
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
                }
            }
            return null;
        }

        private View composeHostView() {
            Object fragment = currentComposePageFragment();
            Object fragmentView = call(fragment, "getView");
            return fragmentView instanceof View ? (View) fragmentView : null;
        }

        private int[] composeNativeIds() {
            // The collapsed native root is not placed, so semantics cannot reveal its slots.
            // c() and UU's own tab switching both read this presenter list (cloud is optional).
            int[] presenterIds = uuComposeNativeIds(cachedUuComposeState());
            if (presenterIds != null) {
                COMPOSE_NATIVE_IDS_BY_ACTIVITY.put(activity, presenterIds);
                return presenterIds;
            }
            boolean[] present = new boolean[TAB_ALIASES.length];
            for (int nativeIndex = 0; nativeIndex < TAB_ALIASES.length; nativeIndex++) {
                AccessibilityNodeInfo node = semanticNodeForNative(nativeIndex);
                if (node == null) continue;
                present[nativeIndex] = true;
                try { node.recycle(); } catch (Throwable ignored) {}
            }
            // Device, Assist and Mine are the stable minimum. CloudPc is feature-gated remotely.
            if (present[0] && present[2] && present[3]) {
                int[] resolved = present[1]
                        ? COMPOSE_FOUR_NATIVE_IDS : COMPOSE_THREE_NATIVE_IDS;
                COMPOSE_NATIVE_IDS_BY_ACTIVITY.put(activity, resolved);
                return resolved;
            }
            int[] cached = COMPOSE_NATIVE_IDS_BY_ACTIVITY.get(activity);
            return cached != null ? cached : COMPOSE_FOUR_NATIVE_IDS;
        }

        @Override public String[] getVisibleLabels() {
            if (legacyTabLayout() != null) return visibleLabels;
            int[] nativeIds = composeNativeIds();
            if (nativeIds.length == visibleLabels.length) return visibleLabels;
            String[] labels = new String[nativeIds.length];
            for (int logical = 0; logical < nativeIds.length; logical++) {
                int nativeIndex = nativeIds[logical];
                labels[logical] = nativeIndex >= 0 && nativeIndex < visibleLabels.length
                        ? visibleLabels[nativeIndex] : "";
            }
            return labels;
        }

        @Override protected int nativeForLogical(int logical) {
            if (legacyTabLayout() != null) return super.nativeForLogical(logical);
            int[] nativeIds = composeNativeIds();
            return logical >= 0 && logical < nativeIds.length ? nativeIds[logical] : -1;
        }

        @Override protected int logicalForNative(int nativeValue) {
            if (legacyTabLayout() != null) return super.logicalForNative(nativeValue);
            int[] nativeIds = composeNativeIds();
            for (int logical = 0; logical < nativeIds.length; logical++) {
                if (nativeIds[logical] == nativeValue) return logical;
            }
            return -1;
        }

        private AccessibilityNodeProvider provider() {
            View root = composeRoot();
            try { return root == null ? null : root.getAccessibilityNodeProvider(); }
            catch (Throwable ignored) { return null; }
        }

        private AccessibilityNodeInfo semanticNodeForNative(int nativeIndex) {
            if (nativeIndex < 0 || nativeIndex >= TAB_ALIASES.length) return null;
            AccessibilityNodeProvider provider = provider();
            AccessibilityNodeInfo exact = findExactSemanticNode(provider, TAB_ALIASES[nativeIndex]);
            if (exact != null) return exact;
            // Never use contains("我的") here: it would resolve 我的设备 instead of the Mine tab.
            if (nativeIndex == 3) return findSemanticNode(provider, "Mine", "Profile");
            return findSemanticNode(provider, TAB_ALIASES[nativeIndex]);
        }

        private AccessibilityNodeInfo findExactSemanticNode(
                AccessibilityNodeProvider provider,
                String... aliases
        ) {
            if (provider == null || aliases == null) return null;
            try {
                AccessibilityNodeInfo root = provider.createAccessibilityNodeInfo(-1);
                if (root == null) root = provider.createAccessibilityNodeInfo(0);
                if (root == null) return null;
                ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
                queue.add(root);
                int visited = 0;
                while (!queue.isEmpty() && visited++ < 600) {
                    AccessibilityNodeInfo node = queue.removeFirst();
                    if (matchesExactSemanticValue(node.getText(), aliases)
                            || matchesExactSemanticValue(node.getContentDescription(), aliases)) {
                        AccessibilityNodeInfo clickable = clickableSemanticNode(node);
                        if (clickable != null) {
                            if (clickable != node) {
                                try { node.recycle(); } catch (Throwable ignored) {}
                            }
                            while (!queue.isEmpty()) {
                                try { queue.removeFirst().recycle(); } catch (Throwable ignored) {}
                            }
                            return clickable;
                        }
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.addLast(child);
                    }
                    try { node.recycle(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private boolean matchesExactSemanticValue(CharSequence value, String... aliases) {
            if (value == null || aliases == null) return false;
            String normalized = value.toString().trim();
            for (String alias : aliases) {
                if (alias == null) continue;
                if (normalized.equalsIgnoreCase(alias)) return true;
                if (normalized.startsWith(alias + ",")
                        || normalized.startsWith(alias + "，")
                        || normalized.startsWith(alias + " ")) return true;
            }
            return false;
        }

        private int legacyPositionForNative(int nativeIndex, int logicalFallback) {
            Object listValue = getField(activity, "L");
            if (listValue instanceof List) {
                List<?> tabs = (List<?>) listValue;
                for (int i = 0; i < tabs.size(); i++) {
                    if (intValue(call(tabs.get(i), "ordinal"), -1) == nativeIndex) return i;
                }
            }
            // The verified cloud-enabled list is Device, CloudPc, Assist, Mine, which is also the
            // LiquidGlass order. Use logical position only while the list is unavailable.
            return logicalFallback;
        }

        private int legacyNativeForPosition(int position) {
            Object listValue = getField(activity, "L");
            if (listValue instanceof List) {
                List<?> tabs = (List<?>) listValue;
                if (position >= 0 && position < tabs.size()) {
                    int nativeIndex = intValue(call(tabs.get(position), "ordinal"), -1);
                    if (nativeIndex >= 0) return nativeIndex;
                }
            }
            return nativeForLogical(position);
        }

        private Object legacyCurrentTab() {
            int position = intValue(call(legacyPager(), "getCurrentItem"), -1);
            Object listValue = getField(activity, "L");
            if (listValue instanceof List) {
                List<?> tabs = (List<?>) listValue;
                if (position >= 0 && position < tabs.size()) return tabs.get(position);
            }
            // Verified in UU Remote 4.29.0: K is the committed current home-tab enum.
            return getField(activity, "K");
        }

        private View legacyCurrentFragmentView() {
            // Verified in UU Remote 4.29.0: M maps each home-tab enum to its Fragment instance.
            return BackdropSourceResolver.fragmentViewFromMap(
                    getField(activity, "M"), legacyCurrentTab()
            );
        }

        private int nativeForSemanticValue(CharSequence value) {
            if (value == null) return -1;
            String normalized = value.toString().trim();
            for (int nativeIndex = 0; nativeIndex < TAB_ALIASES.length; nativeIndex++) {
                for (String alias : TAB_ALIASES[nativeIndex]) {
                    if (alias == null) continue;
                    if (normalized.equalsIgnoreCase(alias)
                            || normalized.startsWith(alias + ",")
                            || normalized.startsWith(alias + "，")
                            || normalized.startsWith(alias + " ")) {
                        return nativeIndex;
                    }
                }
            }
            return -1;
        }

        @Override public View getBottomBarRoot() {
            View legacy = legacyTabLayout();
            return legacy != null ? legacy : composeRoot();
        }

        @Override public View getBackdropSourceView() {
            View legacy = legacyPager();
            if (legacy != null) {
                View fragmentView = legacyCurrentFragmentView();
                if (fragmentView != null) return fragmentView;
                View page = BackdropSourceResolver.currentPagerPage(
                        legacy, intValue(call(legacy, "getCurrentItem"), -1)
                );
                return page != null ? page : legacy;
            }
            // ComposeMainActivity's outer ComposeView is the stable page host. The inner
            // AndroidComposeView can be recreated by Compose and is too short-lived for exact
            // backdrop capture.
            View host = composeHostView();
            return host != null ? host : composeRoot();
        }

        /**
         * Resolve the real Compose navigation paint height from semantic item bounds. Hard-coding
         * 88dp sampled a visibly different page strip on devices with another density/inset setup,
         * which made the liquid background look displaced. The cached result is shared by clipping
         * and direct capture so both operations always use the same physical region.
         */
        private float composeNavigationHeightDp() {
            long now = SystemClock.uptimeMillis();
            if (now - cachedComposeBarHeightAt < 800L) return cachedComposeBarHeightDp;
            cachedComposeBarHeightAt = now;

            View root = composeRoot();
            if (root == null || root.getHeight() <= 0) return cachedComposeBarHeightDp;
            float density = root.getResources().getDisplayMetrics().density;
            if (density <= 0f) density = 1f;

            int[] location = new int[2];
            try { root.getLocationOnScreen(location); } catch (Throwable ignored) {}
            int bottomInset = 0;
            try {
                if (root.getRootWindowInsets() != null) {
                    bottomInset = root.getRootWindowInsets()
                            .getInsets(WindowInsets.Type.navigationBars()).bottom;
                }
            } catch (Throwable ignored) {}
            int contentBottom = location[1] + root.getHeight() - Math.max(0, bottomInset);
            int semanticTop = Integer.MAX_VALUE;
            Rect bounds = new Rect();
            for (int nativeIndex = 0; nativeIndex < TAB_ALIASES.length; nativeIndex++) {
                AccessibilityNodeInfo node = semanticNodeForNative(nativeIndex);
                if (node == null) continue;
                try {
                    node.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) semanticTop = Math.min(semanticTop, bounds.top);
                } catch (Throwable ignored) {
                } finally {
                    try { node.recycle(); } catch (Throwable ignored) {}
                }
            }
            if (semanticTop != Integer.MAX_VALUE && contentBottom > semanticTop) {
                // Include the visual padding above the icon/text semantics but do not clip page
                // content unnecessarily. This typically resolves to 64-76dp instead of 88dp.
                int paddedTop = semanticTop - Math.round(12f * density);
                float measured = (contentBottom - paddedTop) / density;
                float quantized = Math.round(measured / 2f) * 2f;
                cachedComposeBarHeightDp = Math.max(56f, Math.min(84f, quantized));
            }
            return cachedComposeBarHeightDp;
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            View legacyBar = legacyTabLayout();
            if (legacyBar != null) {
                // Collapsing the exact legacy TabLayout lets its normal constraints reclaim the
                // space. ActivityMain keeps split_line as a separate sibling, so clear only that
                // verified <=4dp leaf while preserving its constraint geometry.
                return new SuppressionPlan()
                        .collapse(legacyBar)
                        .clear(legacySplitLine());
            }
            if (!isComposeHomePage()) return new SuppressionPlan();
            // Compose owns both the page and NavigationBar in one AndroidComposeView. The exact
            // native NavigationBar root layout is collapsed in UniversalHookEntry, so resizing
            // this inner view is unnecessary and is overwritten by AbstractComposeView anyway.
            return new SuppressionPlan();
        }

        @Override public boolean isNavigationVisible() {
            View legacy = legacyTabLayout();
            if (legacy != null) {
                return legacy.isAttachedToWindow() && legacy.getVisibility() == View.VISIBLE;
            }
            if (!isComposeHomePage()) return false;
            View root = composeRoot();
            return root != null && root.isAttachedToWindow() && root.isShown()
                    && root.getWidth() > 0 && root.getHeight() > 0;
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0 || nativeIndex >= TAB_ALIASES.length) return false;

            View pager = legacyPager();
            if (pager != null) {
                int position = legacyPositionForNative(nativeIndex, index);
                if (position < 0) return false;
                if (callExists(pager, "setCurrentItem", position, false)) return true;
                if (callExists(pager, "setCurrentItem", position)) return true;
            }

            if (!isComposeHomePage()) return false;

            // The native root is not placed, so route through UU's presenter instead of relying
            // on accessibility nodes. Its composable and state subscriptions still run normally.
            Object composeState = cachedUuComposeState();
            Object composeTab = composeTabValue(composeState, nativeIndex);
            if (composeTab != null && callExists(composeState, "n", composeTab)) return true;

            AccessibilityNodeInfo node = semanticNodeForNative(nativeIndex);
            if (node != null) {
                try {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                } finally {
                    try { node.recycle(); } catch (Throwable ignored) {}
                }
            }

            // Accessibility can be unavailable during the first composition. Dispatch to the
            // actual visible slot as a short-lived fallback; CloudPc may be absent by remote flag.
            View root = composeRoot();
            if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) return false;
            int[] nativeIds = composeNativeIds();
            int position = -1;
            for (int candidate = 0; candidate < nativeIds.length; candidate++) {
                if (nativeIds[candidate] == nativeIndex) {
                    position = candidate;
                    break;
                }
            }
            if (position < 0 || nativeIds.length == 0) return false;
            int bottomInset = 0;
            try {
                if (root.getRootWindowInsets() != null) {
                    bottomInset = root.getRootWindowInsets()
                            .getInsets(WindowInsets.Type.navigationBars()).bottom;
                }
            } catch (Throwable ignored) {}
            float density = root.getResources().getDisplayMetrics().density;
            float x = root.getWidth() * ((position + 0.5f) / nativeIds.length);
            float y = Math.max(1f, root.getHeight() - bottomInset - 33f * density);
            if (!isComposeHomePage() || composeRoot() != root) return false;
            if (dispatchTapAt(root, x, y)) return true;
            return dispatchTapAt(
                    root, x, Math.max(1f, root.getHeight() - bottomInset - 45f * density)
            );
        }

        @Override public boolean post() { return false; }

        @Override public int detectSelectedIndex() {
            View pager = legacyPager();
            if (pager != null) {
                int position = intValue(call(pager, "getCurrentItem"), -1);
                int nativeIndex = legacyNativeForPosition(position);
                int logical = logicalForNative(nativeIndex);
                if (logical >= 0) return logical;
            }
            Object composeState = cachedUuComposeState();
            int composeNative = composeStateSelectedNative(composeState);
            if (composeNative >= 0) {
                int logical = logicalForNative(composeNative);
                if (logical >= 0) return logical;
            }
            AccessibilityNodeProvider provider = provider();
            if (provider == null) return -1;
            try {
                AccessibilityNodeInfo root = provider.createAccessibilityNodeInfo(-1);
                if (root == null) root = provider.createAccessibilityNodeInfo(0);
                if (root == null) return -1;
                ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
                queue.add(root);
                int visited = 0;
                while (!queue.isEmpty() && visited++ < 600) {
                    AccessibilityNodeInfo node = queue.removeFirst();
                    int nativeIndex = nativeForSemanticValue(node.getText());
                    if (nativeIndex < 0) nativeIndex = nativeForSemanticValue(node.getContentDescription());
                    if (nativeIndex >= 0 && isSemanticSelected(node)) {
                        int logical = logicalForNative(nativeIndex);
                        try { node.recycle(); } catch (Throwable ignored) {}
                        while (!queue.isEmpty()) {
                            try { queue.removeFirst().recycle(); } catch (Throwable ignored) {}
                        }
                        return logical;
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.addLast(child);
                    }
                    try { node.recycle(); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            return -1;
        }

        private Object composeTabValue(Object composeState, int nativeIndex) {
            if (composeState == null || nativeIndex < 0 || nativeIndex >= TAB_ALIASES.length) {
                return null;
            }
            try {
                ClassLoader loader = composeState.getClass().getClassLoader();
                Class<?> enumType = Class.forName("O8.E4", false, loader);
                Object[] values = enumType.getEnumConstants();
                return values != null && nativeIndex < values.length ? values[nativeIndex] : null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private int composeStateSelectedNative(Object composeState) {
            if (composeState == null) return -1;
            try {
                Object stateHolder = getField(composeState, "w", "f7072w");
                Object selected = call(stateHolder, "getValue");
                return selected instanceof Enum ? ((Enum<?>) selected).ordinal() : -1;
            } catch (Throwable ignored) {
                return -1;
            }
        }

        @Override public long backdropTransitionDelayMs() { return 120L; }
        @Override public long backdropMovingCaptureIntervalMs() { return 16L; }
        @Override public long backdropSettleCaptureDelayMs() { return 72L; }
        @Override public boolean captureHardwareBackdrops() { return false; }
    }

    /**
     * StarDesk 1.4.2 is the international build of the same remote-control product family, but its
     * legacy MainActivity is separately obfuscated.  Keep it on the normal UniversalGlass path:
     * one native TabLayout bridge, one pager source, and the same liquid renderer as every other
     * supported application.  The host enum remains Device(0), Assist(1), CloudPc(2), Mine(3),
     * while this build exposes only Device, Assist and Mine.
     */
    private static final class StarDeskAdapter extends UniversalHostAdapter {
        StarDeskAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
        }

        private Object binding() {
            // Verified in StarDesk 1.4.2: MainActivity.J() returns c7.c.
            return call(activity, "J");
        }

        private View tabLayout() {
            Object value = getField(binding(), "b");
            if (value instanceof View) return (View) value;
            return findExactClass("com.google.android.material.tabs.TabLayout");
        }

        private View pager() {
            Object value = getField(binding(), "c");
            if (value instanceof View) return (View) value;
            return findExactClass("com.remote.widget.view.CustomViewPager");
        }

        private View splitLine() {
            Object root = getField(binding(), "a");
            return findVerifiedSiblingDivider(
                    root instanceof View ? (View) root : null, tabLayout(), "split_line"
            );
        }

        private List<?> tabs() {
            Object value = getField(activity, "w0");
            return value instanceof List ? (List<?>) value : Collections.emptyList();
        }

        private int positionForNative(int nativeIndex, int fallback) {
            List<?> list = tabs();
            for (int i = 0; i < list.size(); i++) {
                if (intValue(call(list.get(i), "ordinal"), -1) == nativeIndex) return i;
            }
            return fallback;
        }

        private int nativeForPosition(int position) {
            List<?> list = tabs();
            if (position >= 0 && position < list.size()) {
                int nativeIndex = intValue(call(list.get(position), "ordinal"), -1);
                if (nativeIndex >= 0) return nativeIndex;
            }
            return nativeForLogical(position);
        }

        private Object currentTab() {
            int position = intValue(call(pager(), "getCurrentItem"), -1);
            List<?> list = tabs();
            if (position >= 0 && position < list.size()) return list.get(position);
            return getField(activity, "v0");
        }

        private View currentFragmentView() {
            // Verified in StarDesk 1.4.2: x0 maps the home-tab enum directly to the four Fragment
            // instances. Only three are exposed in this build, while ViewPager keeps all pages
            // attached with offscreenPageLimit=4, so this map is the authoritative current page.
            return BackdropSourceResolver.fragmentViewFromMap(
                    getField(activity, "x0"), currentTab()
            );
        }

        @Override public View getBottomBarRoot() { return tabLayout(); }
        @Override public View getBackdropSourceView() {
            View viewPager = pager();
            if (viewPager == null) return null;
            View fragmentView = currentFragmentView();
            if (fragmentView != null) return fragmentView;
            View page = BackdropSourceResolver.currentPagerPage(
                    viewPager, intValue(call(viewPager, "getCurrentItem"), -1)
            );
            return page != null ? page : viewPager;
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            // This is the same normal View-system path used by other legacy hosts: collapse the
            // exact native row and clear ActivityMain's separately declared split_line sibling.
            return new SuppressionPlan()
                    .collapse(tabLayout())
                    .clear(splitLine());
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0) return false;
            int position = positionForNative(nativeIndex, index);
            Object target = enumForNative(nativeIndex);
            // onJumpTab is StarDesk's own public routing entry. It updates the committed enum and
            // lets the native TabLayout/ViewPager listeners move together.
            if (target != null && callExists(activity, "onJumpTab", target)) return true;
            View viewPager = pager();
            if (viewPager != null) {
                if (callExists(viewPager, "setCurrentItem", position, false)) return true;
                if (callExists(viewPager, "setCurrentItem", position)) return true;
            }

            Object tabs = tabLayout();
            Object tab = call(tabs, "g", position);
            if (tab != null && callExists(tabs, "k", tab, true)) return true;
            return false;
        }

        private Object enumForNative(int nativeIndex) {
            List<?> list = tabs();
            for (Object value : list) {
                if (intValue(call(value, "ordinal"), -1) == nativeIndex) return value;
            }
            return null;
        }

        @Override public boolean post() { return false; }

        @Override public int detectSelectedIndex() {
            View viewPager = pager();
            int position = intValue(call(viewPager, "getCurrentItem"), -1);
            if (position >= 0) {
                int logical = logicalForNative(nativeForPosition(position));
                if (logical >= 0) return logical;
            }
            Object current = getField(activity, "v0");
            return logicalForNative(intValue(call(current, "ordinal"), -1));
        }

        @Override public long backdropTransitionDelayMs() { return 104L; }
        @Override public long backdropMovingCaptureIntervalMs() { return 16L; }
        @Override public long backdropSettleCaptureDelayMs() { return 72L; }
        @Override public boolean captureHardwareBackdrops() { return false; }
    }

    /** Exact bridge for the uploaded Douyin build (com.ss.android.ugc.aweme). */
    private static final class AwemeAdapter extends UniversalHostAdapter {
        private static final String BOTTOM_CONTAINER =
                "com.ss.android.ugc.aweme.homepage.tab.bottom.ui.tabbar.container.MainBottomTabContainer";

        AwemeAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
        }

        private static final class Binding {
            final Object tab;
            final View view;
            final String tabId;
            final String title;
            final int centerX;
            int resolvedNative = -1;

            Binding(Object tab, View view, String tabId, String title, int centerX) {
                this.tab = tab;
                this.view = view;
                this.tabId = tabId == null ? "" : tabId;
                this.title = title == null ? "" : title;
                this.centerX = centerX;
            }
        }

        @Override public View getBottomBarRoot() {
            return findExactClass(BOTTOM_CONTAINER);
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            View root = getBottomBarRoot();
            // Move the native row off-screen instead of collapsing it: the row keeps its real
            // dimensions, so a real touch dispatch at the original slot coordinates still reaches
            // Douyin's own tab listeners (works even when BottomTab#getView hooks do not fire).
            // The feed content is extended through the vacated slot by the minimum needed so the
            // video still fills the screen without extra framing shift; every bottom overlay is
            // then lifted by the (slightly larger) requested amount so everything stays above the
            // glass bar.
            SuppressionPlan plan = new SuppressionPlan().offscreen(root);
            float overscanDp = measuredHeightDp(root, 64f) + settings.bottomPaddingDp;
            View content = activity.findViewById(android.R.id.content);
            if (content != null && overscanDp > 0f) plan.overscanBottom(content, overscanDp);
            // All music-disc-area components (圆盘 / 合集 / 拍同款 / 听抖音) align to just below
            // the 分享 button. Fall back to the glass-pill alignment when the share button is
            // not found on this build.
            if (!alignAwemeMusicCluster(activity)) {
                View collection = findAwemeCollectionBlock();
                if (collection != null) plan.alignAboveGlassByTranslation(collection, 6f);
            }
            View chatInput = refreshAwemeChatInput(activity);
            if (chatInput != null) plan.alignAboveGlassByTranslation(chatInput, 41f);
            liftAwemeOverlays(reservedBottomDp());
            return plan;
        }

        /** Bottom reservation released by LiquidGlass: native bar + padding + nav inset. */
        private float reservedBottomDp() {
            float navInsetDp = 0f;
            try {
                android.view.WindowInsets insets = activity.getWindow() == null ? null
                        : activity.getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    android.graphics.Insets nav = insets.getInsetsIgnoringVisibility(
                            android.view.WindowInsets.Type.navigationBars()
                    );
                    navInsetDp = nav.bottom
                            / activity.getResources().getDisplayMetrics().density;
                }
            } catch (Throwable ignored) {
            }
            return measuredHeightDp(getBottomBarRoot(), 64f)
                    + settings.bottomPaddingDp
                    + navInsetDp
                    + 14f; // 4dp base margin + 5dp (round 1) + 5dp (round 2)
        }

        private void liftAwemeOverlays(float reservedDp) {
            try {
                if (reservedDp <= 0f) return;
                float density = activity.getResources().getDisplayMetrics().density;
                int liftPx = Math.round(reservedDp * density);
                if (liftPx <= 0) return;

                ArrayList<View> liftedContainers = new ArrayList<>();

                // Lift captured overlay containers: right rail, bottom-left info, music disc,
                // 合集 block, 拍同款 block.
                View rail = AWEME_RIGHT_RAIL == null ? null : AWEME_RIGHT_RAIL.get();
                View left = AWEME_LEFT_INFO == null ? null : AWEME_LEFT_INFO.get();
                View music = AWEME_MUSIC_BLOCK == null ? null : AWEME_MUSIC_BLOCK.get();
                View mix = AWEME_MIX_BLOCK == null ? null : AWEME_MIX_BLOCK.get();
                View cutSame = AWEME_CUT_SAME_BLOCK == null ? null : AWEME_CUT_SAME_BLOCK.get();
                // Containers that may share a bigger bottom block (music disc, 拍同款, 合集)
                // use the ancestor-aware lift so their inner decorations move together.
                // 合集 is handled by alignAboveGlassByTranslation in the suppression plan; keep it
                // out of the manual lift to avoid two competing translations.
                // 音乐圆盘 / 拍同款 are aligned to the share button by alignAwemeMusicCluster;
                // keep them out of the manual lift so the two translations cannot fight.
                View[] walkUp = {rail};
                for (View view : walkUp) {
                    if (view != null && view.isAttachedToWindow()
                            && ActivityFinder.from(view.getContext()) == activity) {
                        liftCapturedWithAncestors(view, liftPx, liftedContainers);
                    }
                }
                // The bottom-left author/作品/简介 block is lifted directly (26.35 behaviour):
                // with a 合集 present, walking up over-lifted it.
                if (left != null && left.isAttachedToWindow()
                        && ActivityFinder.from(left.getContext()) == activity
                        && !insideAnyLifted(left, liftedContainers)) {
                    applyAwemeRightRailLift(left, liftPx);
                    liftedContainers.add(left);
                }

                // 拍同款 / 听抖音 text lives in its own mask when the music disc is absent.
                // Locate it by text (version-independent) and lift its enclosing container so the
                // two masks stay aligned with the normal music-disc position.
                liftAwemeTextMarkedOverlays(liftPx, liftedContainers);

                // The icon ImageViews (comment / collect / share) can live outside the captured
                // container. Lift them with the same offset, unless they are already inside a
                // container lifted above (avoids double translation). Only topmost candidates
                // move so nested widgets are not translated twice.
                View content = activity.findViewById(android.R.id.content);
                if (content == null) return;
                ArrayList<View> candidates = new ArrayList<>();
                ArrayDeque<View> queue = new ArrayDeque<>();
                queue.add(content);
                int visited = 0;
                while (!queue.isEmpty() && visited++ < 1200) {
                    View view = queue.removeFirst();
                    if (isAwemeRightRailClass(view.getClass().getName())) candidates.add(view);
                    // The icon ImageViews sometimes render blank after layout churn. Force them
                    // visible and invalidate so the drawable re-draws (the like icon already
                    // renders; comment / collect / share need this kick).
                    if (view.getClass().getName().contains("feed.widget.right")) {
                        try {
                            if (view.getVisibility() != View.VISIBLE) view.setVisibility(View.VISIBLE);
                            if (view.getAlpha() < 1f) view.setAlpha(1f);
                            view.invalidate();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (view instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) view;
                        for (int i = 0; i < group.getChildCount(); i++) {
                            queue.addLast(group.getChildAt(i));
                        }
                    }
                }
                for (View candidate : candidates) {
                    if (insideAnyLifted(candidate, liftedContainers)) continue;
                    View parent = candidate.getParent() instanceof View
                            ? (View) candidate.getParent() : null;
                    if (parent == null || !candidates.contains(parent)) {
                        applyAwemeRightRailLift(candidate, liftPx);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        private boolean insideAnyLifted(View view, List<View> liftedContainers) {
            if (view == null || liftedContainers == null) return false;
            for (View container : liftedContainers) {
                if (container != null && isDescendant(view, container)) return true;
            }
            return false;
        }

        private boolean shouldLiftByPosition(View view, int screenH) {
            if (view == null || screenH <= 0) return true;
            try {
                int[] loc = new int[2];
                view.getLocationOnScreen(loc);
                // Skip only views entirely inside the top band (top tabs / location tags).
                return !(loc[1] < screenH * 0.18f
                        && loc[1] + view.getHeight() < screenH * 0.45f);
            } catch (Throwable ignored) {
                return true;
            }
        }

        /**
         * Lift a captured overlay and, when it is only a leaf of a bigger bottom block (for
         * example the music disc), also lift its nearest enclosing container so sibling
         * decorations (听抖音 / 拍同款 / music name) move together to the same position.
         */
        private void liftCapturedWithAncestors(
                View view,
                int liftPx,
                ArrayList<View> liftedContainers
        ) {
            try {
                if (view == null || liftPx <= 0) return;
                if (insideAnyLifted(view, liftedContainers)) return;
                View target = view;
                View current = view;
                int screenH = activity.getResources().getDisplayMetrics().heightPixels;
                for (int depth = 0; depth < 5 && current != null; depth++) {
                    View parent = current.getParent() instanceof View
                            ? (View) current.getParent() : null;
                    if (parent == null) break;
                    String parentName = parent.getClass().getName();
                    boolean scrollHost = parentName.contains("RecyclerView")
                            || parentName.contains("ViewPager");
                    boolean fullScreenShell = parent.getHeight() > 0
                            && parent.getHeight() >= screenH * 0.9f;
                    if (scrollHost || fullScreenShell) break;
                    target = parent;
                    current = parent;
                }
                if (insideAnyLifted(target, liftedContainers)) return;
                applyAwemeRightRailLift(target, liftPx);
                liftedContainers.add(target);
            } catch (Throwable ignored) {
            }
        }

        /** Lift containers that carry the 拍同款 / 听抖音 text, found by text not class name. */
        private void liftAwemeTextMarkedOverlays(int liftPx, ArrayList<View> liftedContainers) {
            try {
                if (liftPx <= 0) return;
                View content = activity.findViewById(android.R.id.content);
                if (content == null) return;
                String[] markers = {"直播"};
                int downPx = Math.round(5f * activity.getResources().getDisplayMetrics().density);
                int screenH = activity.getResources().getDisplayMetrics().heightPixels;
                ArrayDeque<View> queue = new ArrayDeque<>();
                queue.add(content);
                int visited = 0;
                while (!queue.isEmpty() && visited++ < 1500) {
                    View view = queue.removeFirst();
                    String text = view instanceof TextView
                            ? String.valueOf(((TextView) view).getText()) : "";
                    CharSequence desc = view.getContentDescription();
                    String key = text + " " + (desc == null ? "" : desc.toString());
                    if (key.contains("全屏观看")) {
                        // The fullscreen-watch button was lifted a bit too high; drop it 5dp.
                        applyAwemeDownShift(view, downPx);
                    }
                    for (String marker : markers) {
                        if (key.contains(marker)) {
                            // Only lift text in the lower part of the screen; the top tab bar
                            // also contains the word "直播" and must never move.
                            if (!shouldLiftByPosition(view, screenH)) break;
                            liftCapturedWithAncestors(view, liftPx, liftedContainers);
                            break;
                        }
                    }
                    if (view instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) view;
                        for (int i = 0; i < group.getChildCount(); i++) {
                            queue.addLast(group.getChildAt(i));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        /** Locate the 合集 (collection/series) block by captured module or by its text. */
        private View findAwemeCollectionBlock() {
            return refreshAwemeCollection(activity);
        }

        private static void applyAwemeRightRailLift(View view, int liftPx) {
            try {
                if (view == null || liftPx <= 0) return;
                float target = -liftPx;
                if (Math.abs(view.getTranslationY() - target) > 0.5f) {
                    view.setTranslationY(target);
                }
            } catch (Throwable ignored) {
            }
        }

        private static void applyAwemeDownShift(View view, int shiftPx) {
            try {
                if (view == null || shiftPx <= 0) return;
                if (Math.abs(view.getTranslationY() - shiftPx) > 0.5f) {
                    view.setTranslationY(shiftPx);
                }
            } catch (Throwable ignored) {
            }
        }

        private boolean isHiddenNative(int nativeIndex) {
            return nativeIndex >= 0 && nativeIndex < app.labels.length
                    && (settings.hiddenMask & (1 << nativeIndex)) != 0;
        }

        @Override public String[] getVisibleLabels() {
            // Douyin must keep its five physical positions stable. Hidden entries become empty
            // slots instead of making 首页/消息/我的 slide sideways.
            String[] fixed = app.labels.clone();
            for (int i = 0; i < fixed.length; i++) {
                if (isHiddenNative(i)) fixed[i] = "";
            }
            return fixed;
        }

        @Override public int centerActionTabIndex() {
            return isHiddenNative(2) ? -1 : 2;
        }

        @Override public boolean separateCirclePlus() { return true; }

        @Override public boolean isNavigationVisible() {
            // 26.43 behaviour: the injected bar is always shown once installed. It must not
            // follow the native row's transient visibility (fullscreen / panels / popups) or a
            // missing container, otherwise scrolling videos makes it disappear.
            return true;
        }

        @Override protected int nativeForLogical(int logical) {
            if (logical < 0 || logical >= 5 || isHiddenNative(logical)) return -1;
            return logical;
        }

        @Override protected int logicalForNative(int nativeValue) {
            if (nativeValue < 0 || nativeValue >= 5 || isHiddenNative(nativeValue)) return -1;
            return nativeValue;
        }

        @Override public boolean usesNativeCenterActionView() { return true; }

        @Override public View getNativeCenterActionView() {
            if (isHiddenNative(2)) return null;
            // The separate-circle style keeps Douyin's real PublishButton in its original
            // container so Douyin's own init can attach the click listener normally. The glass
            // circle is only a visual; clicks are dispatched to the original button instead of
            // re-parenting it (which could break id-based listener lookup inside the container).
            if (separateCirclePlus()) return null;
            View root = getBottomBarRoot();
            View registered = AWEME_PUBLISH_BUTTON == null ? null : AWEME_PUBLISH_BUTTON.get();
            if (registered != null) {
                Activity owner = ActivityFinder.from(registered.getContext());
                boolean alreadyInjected = registered.getParent() instanceof MiuixGlassNavView;
                if (owner == activity && (alreadyInjected || isDescendant(registered, root))) {
                    return registered;
                }
            }
            // Exact fallback verified in the uploaded APK. The class itself is the native publish
            // control, so moving this View preserves Douyin's drawable, state animation and click.
            View fallback = findViewByExactClass(root,
                    "com.ss.android.ugc.aweme.tools.external.hometab.bottom.PublishButton");
            if (fallback != null) registerAwemePublishButton(fallback);
            return fallback;
        }

        private static boolean isDescendant(View child, View root) {
            if (child == null || root == null) return false;
            View current = child;
            for (int depth = 0; depth < 18 && current != null; depth++) {
                if (current == root) return true;
                if (!(current.getParent() instanceof View)) break;
                current = (View) current.getParent();
            }
            return false;
        }

        private static String textOf(Object value) {
            if (value instanceof TextView) {
                CharSequence text = ((TextView) value).getText();
                return text == null ? "" : text.toString();
            }
            return value == null ? "" : String.valueOf(value);
        }

        private List<Binding> bindings() {
            View root = getBottomBarRoot();
            if (root == null) return Collections.emptyList();
            ArrayList<Binding> result = new ArrayList<>();
            synchronized (AWEME_BOTTOM_TAB_VIEWS) {
                for (Map.Entry<Object, WeakReference<View>> entry : AWEME_BOTTOM_TAB_VIEWS.entrySet()) {
                    Object tab = entry.getKey();
                    View view = entry.getValue() == null ? null : entry.getValue().get();
                    if (tab == null || view == null || !view.isAttachedToWindow()
                            || !isDescendant(view, root)) continue;
                    String tabId = textOf(call(tab, "getCurrentTabId"));
                    String title = textOf(call(tab, "getTabTitle"));
                    if (title.isEmpty()) title = textOf(call(tab, "getTabDesc"));
                    if (title.isEmpty()) {
                        CharSequence description = view.getContentDescription();
                        if (description != null) title = description.toString();
                    }
                    int[] location = new int[2];
                    try { view.getLocationOnScreen(location); } catch (Throwable ignored) {}
                    int width = Math.max(view.getWidth(), view.getMeasuredWidth());
                    result.add(new Binding(tab, view, tabId, title, location[0] + width / 2));
                }
            }
            Collections.sort(result, (a, b) -> Integer.compare(a.centerX, b.centerX));

            // Prefer Douyin's stable tab ids/titles, then use the physical five-slot order as a
            // fallback for experiments where a tab id is supplied only after first selection.
            for (Binding binding : result) {
                binding.resolvedNative = nativeFromIdentity(binding.tabId, binding.title);
            }
            if (result.size() >= 5) {
                for (int i = 0; i < result.size() && i < 5; i++) {
                    if (result.get(i).resolvedNative < 0) result.get(i).resolvedNative = i;
                }
            } else if (result.size() == 4) {
                // Some builds model the center shoot action outside BottomTab. The four ordinary
                // tabs stay at physical slots 0/1/3/4.
                int[] fallback = {0, 1, 3, 4};
                for (int i = 0; i < 4; i++) {
                    if (result.get(i).resolvedNative < 0) result.get(i).resolvedNative = fallback[i];
                }
            }
            return result;
        }

        private static int nativeFromIdentity(String tabId, String title) {
            String id = tabId == null ? "" : tabId.toLowerCase(Locale.ROOT);
            String name = title == null ? "" : title.toLowerCase(Locale.ROOT);
            String key = id + " " + name;
            if (key.contains("homepage_profile") || key.contains("profile")
                    || key.contains("我的") || "我".equals(name.trim())) return 4;
            if (key.contains("homepage_notification") || key.contains("homepage_message")
                    || key.contains("notification") || key.contains("message")
                    || key.contains("消息")) return 3;
            if (key.contains("publish") || key.contains("shoot") || key.contains("camera")
                    || key.contains("发视频") || key.contains("拍摄")) return 2;
            if (key.contains("homepage_familiar") || key.contains("homepage_friends")
                    || key.contains("familiar") || key.contains("friend")
                    || key.contains("朋友")) return 1;
            if (key.contains("homepage_home") || key.contains("homepage_hot")
                    || key.equals("home") || key.contains("首页")) return 0;
            return -1;
        }

        private Binding bindingForNative(int nativeIndex) {
            List<Binding> list = bindings();
            for (Binding binding : list) {
                if (binding.resolvedNative == nativeIndex) return binding;
            }
            return null;
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0) return false;
            if (nativeIndex == 2) return post();
            Binding binding = bindingForNative(nativeIndex);
            if (binding != null) {
                View clickTarget = nearestClickableAncestor(binding.view, getBottomBarRoot());
                if (dispatchClick(clickTarget)) return true;
            }

            // Exact visual container fallback for A/B builds that defer BottomTab#getView().
            View root = getBottomBarRoot();
            if (root != null && root.getWidth() > 0 && root.getHeight() > 0) {
                float x = root.getWidth() * ((nativeIndex + 0.5f) / 5f);
                if (dispatchTapAt(root, x, root.getHeight() * 0.5f)) return true;
            }

            // Generic fallback: locate a clickable view labelled with the tab title. This works
            // even when BottomTab#getView hooks did not fire on the installed Douyin build.
            String wanted = nativeIndex < app.labels.length ? app.labels[nativeIndex] : null;
            if (wanted != null && !wanted.isEmpty()) {
                View labelled = findClickableByText(decor(), wanted);
                if (labelled != null && dispatchClick(labelled)) return true;
            }
            return false;
        }

        private View findClickableByText(View root, String wanted) {
            if (root == null || wanted == null || wanted.isEmpty()) return null;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 1500) {
                View view = queue.removeFirst();
                try {
                    CharSequence desc = view.getContentDescription();
                    String text = view instanceof TextView
                            ? String.valueOf(((TextView) view).getText()) : "";
                    String key = (desc == null ? "" : desc.toString()) + " " + text;
                    if (key.contains(wanted)) {
                        // Walk up from the labelled view to the nearest clickable ancestor; the
                        // text view itself is often not the one holding the click listener.
                        View current = view;
                        for (int depth = 0; current != null && depth < 12; depth++) {
                            if (current.isClickable() || current.hasOnClickListeners()) {
                                return current;
                            }
                            if (!(current.getParent() instanceof View)) break;
                            current = (View) current.getParent();
                        }
                        return view;
                    }
                } catch (Throwable ignored) {
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.addLast(group.getChildAt(i));
                    }
                }
            }
            return null;
        }

        @Override public boolean post() {
            View nativePublish = getNativeCenterActionView();
            if (nativePublish == null) {
                // The real button stays inside the (collapsed) native container. Find it in the
                // whole window so id-based listener attachment is never broken by re-parenting.
                View decorRoot = decor();
                View found = decorRoot == null ? null : findViewByExactClass(
                        decorRoot,
                        "com.ss.android.ugc.aweme.tools.external.hometab.bottom.PublishButton"
                );
                if (found != null) {
                    registerAwemePublishButton(found);
                    nativePublish = found;
                }
            }
            if (nativePublish != null) {
                // Prefer Douyin's exact OnClickListener captured at hook time.
                View.OnClickListener listener = AWEME_PUBLISH_LISTENER == null
                        ? null : AWEME_PUBLISH_LISTENER.get();
                if (listener != null) {
                    try {
                        listener.onClick(nativePublish);
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
                if (dispatchClick(nativePublish)) return true;
                // Last resort: click the button's clickable ancestor chain directly.
                View ancestor = nearestClickableAncestor(nativePublish, decor());
                if (ancestor != null && ancestor != nativePublish && dispatchClick(ancestor)) return true;
            }
            Binding binding = bindingForNative(2);
            if (binding != null) {
                View clickTarget = nearestClickableAncestor(binding.view, getBottomBarRoot());
                if (dispatchClick(clickTarget)) return true;
            }
            View root = getBottomBarRoot();
            if (root == null || root.getWidth() <= 0) return false;
            // The native container is collapsed to zero height; dispatch at the horizontal
            // center with a one-pixel vertical offset so the event still reaches the slot.
            return dispatchTapAt(root, root.getWidth() * 0.5f, Math.max(1f, root.getHeight() * 0.5f));
        }

        @Override public int detectSelectedIndex() {
            View root = getBottomBarRoot();
            for (Binding binding : bindings()) {
                int nativeIndex = binding.resolvedNative;
                if (nativeIndex < 0 || nativeIndex == 2) continue;
                boolean selected = Boolean.TRUE.equals(call(binding.tab, "isSelected"));
                if (!selected) selected = isSelectedOrParent(binding.view, root);
                if (selected) {
                    int logical = logicalForNative(nativeIndex);
                    if (logical >= 0) return logical;
                }
            }
            return -1;
        }

        @Override public long backdropTransitionDelayMs() { return 72L; }
        @Override public long backdropMovingCaptureIntervalMs() { return 24L; }
        @Override public long backdropSettleCaptureDelayMs() { return 84L; }
    }

    private static final class XhsAdapter extends UniversalHostAdapter {
        private static final String MAIN_BAR = "com.xingin.xhs.homepage.tabbar.TabBarView";
        private static final String MALL_BAR = "com.xingin.commercial.shop.malltabbar.TabBarView";
        private static final String[] SLOT_GETTERS = {
                "getIndexHome", "getIndexStore", "getIndexMessage", "getIndexMe"
        };
        private static final String[] SLOT_IDS = {
                "index_home", "index_store", "index_message", "index_me"
        };
        private static final Map<Activity, WeakReference<View>> ACTIVE_BARS =
                Collections.synchronizedMap(new WeakHashMap<Activity, WeakReference<View>>());

        XhsAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
        }

        static void invalidate(Activity activity) {
            if (activity != null) ACTIVE_BARS.remove(activity);
        }

        /** Resolve only the currently attached, measured native bottom row. */
        private View resolveActiveBar() {
            WeakReference<View> activeRef = ACTIVE_BARS.get(activity);
            View active = activeRef == null ? null : activeRef.get();
            if (active != null && active.isAttachedToWindow()
                    && active.getVisibility() == View.VISIBLE) return active;

            View root = decor();
            if (root == null) return null;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            View bestOnscreen = null;
            View bestAny = null;
            long bestOnscreenScore = Long.MIN_VALUE;
            long bestAnyScore = Long.MIN_VALUE;
            int[] location = new int[2];
            int decorHeight = root.getHeight();
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 3500) {
                View view = queue.removeFirst();
                String name = view.getClass().getName();
                if ((MAIN_BAR.equals(name) || MALL_BAR.equals(name))
                        && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE
                        && view.isShown() && view.getWidth() > 0 && view.getHeight() > 0) {
                    try {
                        view.getLocationOnScreen(location);
                        long score = (long) location[1] * 10000L
                                + (long) view.getWidth() * view.getHeight();
                        if (score > bestAnyScore) {
                            bestAnyScore = score;
                            bestAny = view;
                        }
                        boolean notMovedByModule = Math.abs(view.getTranslationY()) < 1f;
                        boolean intersectsScreen = decorHeight <= 0
                                || location[1] < decorHeight + view.getHeight();
                        if (notMovedByModule && intersectsScreen && score > bestOnscreenScore) {
                            bestOnscreenScore = score;
                            bestOnscreen = view;
                        }
                    } catch (Throwable ignored) {}
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
                }
            }
            // A newly attached on-screen A/B bar always wins over the previously moved row.
            if (bestOnscreen != null) {
                ACTIVE_BARS.put(activity, new WeakReference<>(bestOnscreen));
                return bestOnscreen;
            }
            if (bestAny != null) {
                ACTIVE_BARS.put(activity, new WeakReference<>(bestAny));
                return bestAny;
            }

            // Stable resource fallback for builds where the A/B TabBarView class is wrapped.
            View home = findId("index_home");
            View store = findId("index_store");
            View message = findId("index_message");
            View me = findId("index_me");
            if (home != null && store != null && message != null && me != null) {
                View current = home;
                for (int depth = 0; current != null && depth < 10; depth++) {
                    if (current instanceof ViewGroup
                            && isDescendant((ViewGroup) current, store)
                            && isDescendant((ViewGroup) current, message)
                            && isDescendant((ViewGroup) current, me)) {
                        ACTIVE_BARS.put(activity, new WeakReference<>(current));
                        return current;
                    }
                    current = current.getParent() instanceof View ? (View) current.getParent() : null;
                }
            }
            return null;
        }

        /**
         * XHS keeps both the home TabBarView (in content_container) and the shop mall TabBarView
         * (inside the shop fragment) attached at the same time. Suppressing only the active one
         * lets the other row become visible after a tab switch until the next poll. Return every
         * attached instance so the plan can park them all.
         */
        private List<View> allAttachedBars() {
            List<View> bars = new ArrayList<>();
            View root = decor();
            if (root == null) return bars;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 5000) {
                View view = queue.removeFirst();
                String name = view.getClass().getName();
                if ((MAIN_BAR.equals(name) || MALL_BAR.equals(name))
                        && view.isAttachedToWindow()
                        && view.getWidth() > 0 && view.getHeight() > 0
                        && !bars.contains(view)) {
                    bars.add(view);
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.addLast(group.getChildAt(i));
                    }
                }
            }
            return bars;
        }

        private boolean isDescendant(ViewGroup parent, View child) {
            View current = child;
            for (int depth = 0; current != null && depth < 12; depth++) {
                if (current == parent) return true;
                current = current.getParent() instanceof View ? (View) current.getParent() : null;
            }
            return false;
        }

        private View resolveSlot(View bar, int nativeIndex) {
            if (bar == null || nativeIndex < 0 || nativeIndex >= SLOT_GETTERS.length) return null;
            Object value = call(bar, SLOT_GETTERS[nativeIndex]);
            if (value instanceof View) return (View) value;
            try {
                int id = activity.getResources().getIdentifier(
                        SLOT_IDS[nativeIndex], "id", app.packageName);
                return id == 0 ? null : bar.findViewById(id);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private View resolvePost(View bar) {
            if (bar == null) return null;
            Object value = call(bar, "getIndexPost");
            if (value instanceof View) return (View) value;
            try {
                int id = activity.getResources().getIdentifier("index_post", "id", app.packageName);
                return id == 0 ? null : bar.findViewById(id);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private View bar() { return resolveActiveBar(); }

        /**
         * Send the synthetic tap through TabBarView rather than directly to a child slot. XHS
         * keeps gesture/Rx bookkeeping on the parent; bypassing it can leave the parent event
         * list empty while the child callback is already running.
         */
        private boolean dispatchNativeSlot(View bar, View slot) {
            if (bar == null || slot == null || !bar.isAttachedToWindow()
                    || !slot.isAttachedToWindow() || bar.getWidth() <= 0 || bar.getHeight() <= 0
                    || slot.getWidth() <= 0 || slot.getHeight() <= 0) return false;
            try {
                int[] barLocation = new int[2];
                int[] slotLocation = new int[2];
                bar.getLocationOnScreen(barLocation);
                slot.getLocationOnScreen(slotLocation);
                float x = slotLocation[0] - barLocation[0] + slot.getWidth() * 0.5f;
                float y = slotLocation[1] - barLocation[1] + slot.getHeight() * 0.5f;
                if (x >= 0f && y >= 0f && x < bar.getWidth() && y < bar.getHeight()
                        && dispatchTapAt(bar, x, y)) return true;
            } catch (Throwable ignored) {}
            try { return slot.performClick(); } catch (Throwable ignored) { return false; }
        }

        @Override public View getBottomBarRoot() { return bar(); }

        @Override public boolean isNavigationVisible() {
            View content = activity.findViewById(android.R.id.content);
            View current = bar();
            return content != null && content.isAttachedToWindow() && content.isShown()
                    && current != null && current.isAttachedToWindow()
                    && current.getVisibility() == View.VISIBLE;
        }

        @Override public View getBackdropSourceView() {
            View pager = findId("indexViewPager");
            return pager != null ? pager : activity.findViewById(android.R.id.content);
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            View current = bar();
            View pager = findId("indexViewPager");
            SuppressionPlan plan = new SuppressionPlan()
                    .zeroBottomMargin(pager)
                    .zeroBottomPadding(pager);
            // Keep every native TabBarView in its original parent with its original size.
            // Translation does not detach Presenter/Rx subscriptions; LiquidGlass maps each click
            // back to the exact off-screen slot View.
            if (current != null && !plan.offscreenRoots.contains(current)) {
                plan.offscreen(current);
            }
            for (View bar : allAttachedBars()) {
                if (!plan.offscreenRoots.contains(bar)) plan.offscreen(bar);
            }
            return plan;
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0 || nativeIndex > 3) return false;
            View current = bar();
            return dispatchNativeSlot(current, resolveSlot(current, nativeIndex));
        }

        @Override public boolean post() {
            View current = bar();
            return dispatchNativeSlot(current, resolvePost(current));
        }

        // Observe only this Activity's capture root through ViewTreeObserver. The old 180 ms cap
        // was retained when full-size capture was expensive, but the current 0.30 bottom-strip
        // pipeline now runs continuously while the page is visible. XHS uses a fixed 21 ms floor so the
        // background keeps following after the finger and fling stop without dropping back to an
        // event-only final frame.
        @Override public long backdropMovingCaptureIntervalMs() { return 21L; }
        @Override public long backdropSettleCaptureDelayMs() { return 104L; }
        @Override public boolean captureHardwareBackdrops() { return false; }

        @Override public int detectSelectedIndex() {
            View current = bar();
            if (current == null) return -1;
            for (int i = 0; i < visibleNativeIds.length; i++) {
                View view = resolveSlot(current, visibleNativeIds[i]);
                if (isSelectedOrParent(view, current)) return i;
            }
            return -1;
        }
    }


    /**
     * JD 15.9.50 (versionCode 102518) navigation bridge.
     *
     * JD's visual order is index/find/messagenew/cart/home, but the controller ids are sparse:
     * 0/2/11/3/4. NavigationGroup remains the authority for click routing and selected state in
     * both the classic row and AiNavigationGroup experiment. Its direct RelativeLayout parent is
     * the fragment-owned plate (background/gradient/shadow plus row), so that complete plate is
     * moved off screen while every original button/listener stays attached.
     */
    private static final class JdAdapter extends UniversalHostAdapter {
        private static final String GROUP_CLASS =
                "com.jingdong.common.unification.navigationbar.newbar.NavigationGroup";
        private static final String AI_GROUP_CLASS =
                "com.jingdong.app.mall.navigationbar.refactor.view.AiNavigationGroup";
        private static final String BUTTON_CLASS =
                "com.jingdong.common.unification.navigationbar.newbar.NavigationButton";
        private static final String AI_BOTTOM_BLUR_CLASS =
                "com.jingdong.app.mall.navigationbar.refactor.view.BottomBgBlur";
        private static final String AI_GRADIENT_CLASS =
                "com.jingdong.app.mall.navigationbar.refactor.view.BottomGradientMask";
        private static final String AI_BOX_CLASS =
                "com.jingdong.app.mall.navigationbar.refactor.view.NavBoxContainer";
        private WeakReference<View> cachedGroup = new WeakReference<>(null);
        private WeakReference<View> cachedShell = new WeakReference<>(null);

        JdAdapter(Activity a, TargetApp app, TargetAppSettings s) { super(a, app, s); }

        private View group() {
            View current = cachedGroup.get();
            if (isNavigationGroup(current) && current.isAttachedToWindow()) return current;
            current = findExactClass(AI_GROUP_CLASS, GROUP_CLASS);
            if (current != null) cachedGroup = new WeakReference<>(current);
            return current;
        }

        private static boolean isNavigationGroup(View view) {
            if (view == null) return false;
            for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
                if (GROUP_CLASS.equals(type.getName())) return true;
            }
            return false;
        }

        private View shell() {
            View navigation = group();
            if (navigation == null) return null;
            View current = cachedShell.get();
            if (current != null && current.isAttachedToWindow()
                    && isAncestorOf(current, navigation)) return current;

            // AI navigation wraps the fragment row in NavBoxContainer and places two compositor
            // blur/gradient siblings in a 128dp RelativeLayout. Choose that first common ancestor
            // so no native halo remains behind the LiquidGlass pill.
            View ancestor = navigation;
            for (int depth = 0; ancestor != null && depth < 7; depth++) {
                boolean hasBottomBlur = findViewByExactClass(
                        ancestor, AI_BOTTOM_BLUR_CLASS) != null;
                boolean hasGradient = findViewByExactClass(
                        ancestor, AI_GRADIENT_CLASS) != null;
                boolean hasBox = findViewByExactClass(ancestor, AI_BOX_CLASS) != null;
                if (hasBottomBlur && hasGradient && hasBox) {
                    cachedShell = new WeakReference<>(ancestor);
                    return ancestor;
                }
                ancestor = ancestor.getParent() instanceof View
                        ? (View) ancestor.getParent() : null;
            }

            // refactor.view.h/a both return a RelativeLayout containing the native background
            // plate and NavigationGroup. The hosting fragment container sits one level above it.
            Object parent = navigation.getParent();
            if (parent instanceof View
                    && "android.widget.RelativeLayout".equals(parent.getClass().getName())) {
                current = (View) parent;
                cachedShell = new WeakReference<>(current);
                return current;
            }
            return navigation;
        }

        private static boolean isAncestorOf(View ancestor, View child) {
            if (ancestor == null || child == null) return false;
            View current = child;
            for (int depth = 0; current != null && depth < 9; depth++) {
                if (current == ancestor) return true;
                current = current.getParent() instanceof View
                        ? (View) current.getParent() : null;
            }
            return false;
        }

        @Override public View getBottomBarRoot() { return shell(); }

        @Override public View getBackdropSourceView() {
            // JDTabFragment owns the active destination viewport while the separate navigation
            // Fragment is mounted into the bottom holder. Sampling this wrapper avoids capturing
            // the holder itself and survives index/find/message/cart/home Fragment swaps.
            Object tabFragment = call(activity, "getTabFragment");
            Object tabView = call(tabFragment, "getView");
            if (tabView instanceof View && ((View) tabView).isAttachedToWindow()) {
                return (View) tabView;
            }
            Object currentFragment = call(activity, "getCurrentFragment");
            if (currentFragment != null && !currentFragment.getClass().getName().contains(
                    "JDNavigationFragment")) {
                Object currentView = call(currentFragment, "getView");
                if (currentView instanceof View && ((View) currentView).isAttachedToWindow()) {
                    return (View) currentView;
                }
            }
            return activity.findViewById(android.R.id.content);
        }

        private View buttonForNativeId(int nativeId) {
            View root = group();
            if (root == null) return null;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 160) {
                View view = queue.removeFirst();
                if (isClassOrSubclass(view, BUTTON_CLASS)
                        && intValue(call(view, "getNavigationId"), Integer.MIN_VALUE)
                        == nativeId) return view;
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        queue.addLast(group.getChildAt(i));
                    }
                }
            }
            return null;
        }

        private static boolean isClassOrSubclass(View view, String className) {
            if (view == null || className == null) return false;
            for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
                if (className.equals(type.getName())) return true;
            }
            return false;
        }

        @Override public Bitmap[] getNavigationIconBitmaps() {
            Bitmap[] icons = new Bitmap[visibleNativeIds.length];
            int found = 0;
            for (int i = 0; i < visibleNativeIds.length; i++) {
                View button = buttonForNativeId(visibleNativeIds[i]);
                Object iconObject = call(button, "getNaviIconView");
                Bitmap bitmap = iconObject instanceof View
                        ? selectedIconBitmap((View) iconObject) : null;
                if (bitmap != null && !bitmap.isRecycled()) {
                    icons[i] = bitmap;
                    found++;
                }
            }
            return found == 0 ? new Bitmap[0] : icons;
        }

        private static Bitmap selectedIconBitmap(View iconView) {
            if (iconView == null) return null;
            // NavigationIconView keeps the filled/selected asset in clickDrawable. Pull its
            // underlying host-owned Bitmap so every Compose destination uses the same filled
            // silhouette and lets LiquidGlass apply selected/unselected tint consistently.
            Object[] candidates = {
                    getField(iconView, "clickDrawable"),
                    getField(iconView, "joyRadioStateDrawable"),
                    getField(iconView, "radioStateDrawable"),
                    getField(iconView, "defaultDrawable"),
                    call(iconView, "getDrawable")
            };
            for (Object candidate : candidates) {
                Bitmap bitmap = bitmapOwnedByDrawable(candidate);
                if (bitmap != null) return bitmap;
            }
            return drawAndCropIcon(iconView);
        }

        private static Bitmap bitmapOwnedByDrawable(Object candidate) {
            if (candidate == null) return null;
            if (candidate instanceof Bitmap) {
                Bitmap bitmap = (Bitmap) candidate;
                return bitmap.isRecycled() ? null : bitmap;
            }
            if (candidate instanceof BitmapDrawable) {
                Bitmap bitmap = ((BitmapDrawable) candidate).getBitmap();
                return bitmap == null || bitmap.isRecycled() ? null : bitmap;
            }
            Object field = getField(candidate, "bitmap");
            if (field instanceof Bitmap && !((Bitmap) field).isRecycled()) {
                return (Bitmap) field;
            }
            return null;
        }

        /** Lottie/experimental icon fallback; result is module-owned and never recycled by host. */
        private static Bitmap drawAndCropIcon(View iconView) {
            try {
                int width = Math.max(iconView.getWidth(), iconView.getMeasuredWidth());
                int height = Math.max(iconView.getHeight(), iconView.getMeasuredHeight());
                if (width <= 0 || height <= 0 || width > 512 || height > 512) return null;
                Bitmap raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(raw);
                iconView.draw(canvas);

                int[] pixels = new int[width * height];
                raw.getPixels(pixels, 0, width, 0, 0, width, height);
                int left = width;
                int top = height;
                int right = -1;
                int bottom = -1;
                for (int y = 0; y < height; y++) {
                    int row = y * width;
                    for (int x = 0; x < width; x++) {
                        if ((pixels[row + x] >>> 24) <= 8) continue;
                        if (x < left) left = x;
                        if (x > right) right = x;
                        if (y < top) top = y;
                        if (y > bottom) bottom = y;
                    }
                }
                if (right < left || bottom < top) {
                    raw.recycle();
                    return null;
                }
                if (left == 0 && top == 0 && right == width - 1 && bottom == height - 1) {
                    return raw;
                }
                Bitmap cropped = Bitmap.createBitmap(
                        raw, left, top, right - left + 1, bottom - top + 1
                );
                raw.recycle();
                return cropped;
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            View root = shell();
            return new SuppressionPlan().offscreen(root != null ? root : group());
        }

        @Override public boolean isNavigationVisible() {
            View navigation = group();
            // Off-screen suppression changes alpha/translation only; isShown() therefore remains
            // the host's own visibility signal when JD hides navigation for an inner page.
            return navigation != null && navigation.isAttachedToWindow() && navigation.isShown();
        }

        @Override public boolean select(int index) {
            int nativeId = nativeForLogical(index);
            if (nativeId < 0) return false;
            View button = buttonForNativeId(nativeId);
            Object touch = call(button, "getTouchView");
            if (touch instanceof View && dispatchClick((View) touch)) return true;
            if (dispatchClick(button)) return true;
            // Mirrors NavigationGroup.onClick(): setCheck(id, true) runs the original action,
            // reporting and selection listener without assuming ids are child positions.
            return callExists(group(), "setCheck", nativeId, true);
        }

        @Override public boolean post() { return false; }

        @Override public long backdropTransitionDelayMs() { return 112L; }
        @Override public long backdropMovingCaptureIntervalMs() { return 32L; }
        @Override public long backdropSettleCaptureDelayMs() { return 112L; }

        @Override public int detectSelectedIndex() {
            int nativeId = intValue(call(group(), "getCheckId"), -1);
            if (nativeId < 0) {
                for (int id : visibleNativeIds) {
                    View button = buttonForNativeId(id);
                    Object selected = call(button, "currentTabState");
                    if (Boolean.TRUE.equals(selected)) {
                        nativeId = id;
                        break;
                    }
                }
            }
            return logicalForNative(nativeId);
        }

        @Override public boolean preserveLastBackdropOnBlankFrame() { return true; }
    }


    /**
     * Magisk v30.7 uses com.topjohnwu.magisk.widget.ConcealableBottomNavigationView,
     * a Material BottomNavigationView.  Keep Magisk's own four-item Menu as the source of truth
     * instead of baking obfuscated resource ids into LiquidGlass.  The uploaded APK order is
     * Home / Superuser / Logs / Modules, rendered here as 主页 / 超级用户 / 日志 / 模块.
     */
    private static final class MagiskAdapter extends UniversalHostAdapter {
        private static final String NAV_CLASS =
                "com.topjohnwu.magisk.widget.ConcealableBottomNavigationView";
        private WeakReference<View> cachedNav = new WeakReference<>(null);
        private Bitmap[] cachedIcons;

        MagiskAdapter(Activity a, TargetApp app, TargetAppSettings s) {
            super(a, app, s);
        }

        private View nav() {
            View current = cachedNav.get();
            if (current != null && current.isAttachedToWindow()
                    && NAV_CLASS.equals(current.getClass().getName())) return current;
            current = findExactClass(NAV_CLASS);
            if (current != null) cachedNav = new WeakReference<>(current);
            return current;
        }

        private Object menu() {
            return call(nav(), "getMenu");
        }

        private int menuSize() {
            return intValue(call(menu(), "size"), 0);
        }

        private Object menuItemAt(int index) {
            Object m = menu();
            if (m == null || index < 0 || index >= menuSize()) return null;
            return call(m, "getItem", index);
        }

        private int menuItemIdAt(int index) {
            return intValue(call(menuItemAt(index), "getItemId"), 0);
        }

        private int logicalForItemId(int itemId) {
            if (itemId == 0) return -1;
            int count = Math.min(4, menuSize());
            for (int i = 0; i < count; i++) {
                if (menuItemIdAt(i) == itemId) return i;
            }
            return -1;
        }

        @Override public View getBottomBarRoot() { return nav(); }

        @Override public SuppressionPlan getSuppressionPlan() {
            // Magisk's navigation participates in the Activity layout. Translating only the exact
            // row preserves the original bottom inset/page height and its internal navigation state.
            return new SuppressionPlan().offscreen(nav());
        }

        @Override public boolean isNavigationVisible() {
            View root = nav();
            if (root == null || !root.isAttachedToWindow()
                    || root.getVisibility() != View.VISIBLE) return false;
            // ConcealableBottomNavigationView animates itself offscreen with setHidden(true).
            // Mirror that host state instead of forcing our injected bar to remain visible.
            return !Boolean.TRUE.equals(call(root, "isHidden"));
        }

        @Override public boolean select(int index) {
            int id = menuItemIdAt(index);
            if (id == 0) return false;
            View root = nav();
            if (root != null && callExists(root, "setSelectedItemId", id)) return true;
            Object m = menu();
            if (m != null && callExists(m, "performIdentifierAction", id, 0)) return true;
            try {
                View item = activity.findViewById(id);
                return item != null && item.performClick();
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override public int detectSelectedIndex() {
            int selectedId = intValue(call(nav(), "getSelectedItemId"), 0);
            int logical = logicalForItemId(selectedId);
            if (logical >= 0) return logical;
            int count = Math.min(4, menuSize());
            for (int i = 0; i < count; i++) {
                int id = menuItemIdAt(i);
                if (id == 0) continue;
                try {
                    View item = activity.findViewById(id);
                    if (item != null && (item.isSelected() || item.isActivated())) return i;
                } catch (Throwable ignored) {}
            }
            return -1;
        }

        @Override public Bitmap[] getNavigationIconBitmaps() {
            if (cachedIcons != null && cachedIcons.length == 4) {
                boolean valid = true;
                for (Bitmap b : cachedIcons) {
                    if (b == null || b.isRecycled()) { valid = false; break; }
                }
                if (valid) return cachedIcons;
            }
            ArrayList<ImageView> images = new ArrayList<>();
            collectMagiskIconViews(nav(), images, 0);
            Bitmap[] result = new Bitmap[4];
            int out = 0;
            for (ImageView image : images) {
                if (out >= result.length) break;
                Bitmap bitmap = drawableToBitmap(image.getDrawable());
                if (bitmap != null && !bitmap.isRecycled()) result[out++] = bitmap;
            }
            if (out == result.length) cachedIcons = result;
            return out == 0 ? new Bitmap[0] : result;
        }

        private static void collectMagiskIconViews(View view, List<ImageView> out, int depth) {
            if (view == null || depth > 10 || out.size() >= 8) return;
            if (view instanceof ImageView && ((ImageView) view).getDrawable() != null) {
                out.add((ImageView) view);
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    collectMagiskIconViews(group.getChildAt(i), out, depth + 1);
                }
            }
        }

        private static Bitmap drawableToBitmap(Drawable original) {
            if (original == null) return null;
            try {
                if (original instanceof BitmapDrawable) {
                    Bitmap bitmap = ((BitmapDrawable) original).getBitmap();
                    if (bitmap != null && !bitmap.isRecycled()) return bitmap;
                }
                Drawable d = original.getConstantState() == null
                        ? original.mutate()
                        : original.getConstantState().newDrawable().mutate();
                int width = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 96;
                int height = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 96;
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                d.setBounds(0, 0, width, height);
                d.draw(canvas);
                return bitmap;
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override public View getBackdropSourceView() {
            return activity.findViewById(android.R.id.content);
        }

        @Override public boolean preserveLastBackdropOnBlankFrame() { return true; }
        @Override public boolean post() { return false; }
    }

    /**
     * WeChat 8.0.72 (versionCode 3084) keeps the four home destinations in
     * MainTabUI$TabsAdapter and renders the original row with
     * LauncherUIBottomTabView. This bridge follows the same pager route as the uploaded WAA
     * reference while keeping the original row intact for WeChat's own state machine.
     */
    private static final class WeChatAdapter extends UniversalHostAdapter {
        private static final String BOTTOM_BAR_CLASS =
                "com.tencent.mm.ui.LauncherUIBottomTabView";
        private static final String WX_PAGER_CLASS =
                "com.tencent.mm.ui.mogic.WxViewPager";
        private static final Map<View, Bitmap[]> NATIVE_ICON_CACHE =
                Collections.synchronizedMap(new WeakHashMap<View, Bitmap[]>());
        private WeakReference<View> cachedBar = new WeakReference<>(null);
        private WeakReference<View> cachedPager = new WeakReference<>(null);

        WeChatAdapter(Activity a, TargetApp app, TargetAppSettings s) { super(a, app, s); }

        private View bar() {
            View current = cachedBar.get();
            if (current != null && current.isAttachedToWindow()
                    && BOTTOM_BAR_CLASS.equals(current.getClass().getName())) return current;
            View byId = findId("kao");
            current = byId != null && BOTTOM_BAR_CLASS.equals(byId.getClass().getName())
                    ? byId : findExactClass(BOTTOM_BAR_CLASS);
            if (current != null) cachedBar = new WeakReference<>(current);
            return current;
        }

        private View pageRoot() {
            return activity.findViewById(android.R.id.content);
        }

        private Object mainTabUi() {
            // 8.0.72 exposes L7():MainTabUI. Keep the reference build name as a narrow fallback.
            Object main = call(activity, "L7");
            if (main == null) main = call(activity, "getMainTabUI");
            if (main == null) {
                main = fieldValueByType(activity, "com.tencent.mm.ui.MainTabUI");
            }
            return main;
        }

        private View pager() {
            View current = cachedPager.get();
            if (current != null && current.isAttachedToWindow()) return current;

            View byId = findId("nqd");
            current = byId != null ? byId
                    : findExactClass(WX_PAGER_CLASS, "androidx.viewpager.widget.ViewPager");
            if (current == null) {
                Object main = mainTabUi();
                Object resolved = call(main, "getViewPager");
                if (!(resolved instanceof View)) {
                    resolved = fieldValueByType(main, WX_PAGER_CLASS,
                            "androidx.viewpager.widget.ViewPager");
                }
                if (resolved instanceof View) current = (View) resolved;
            }
            if (current != null) cachedPager = new WeakReference<>(current);
            return current;
        }

        private static Object fieldValueByType(Object owner, String... typeNames) {
            if (owner == null || typeNames == null) return null;
            for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    String fieldType = field.getType().getName();
                    boolean wanted = false;
                    for (String name : typeNames) {
                        if (name != null && (name.equals(fieldType)
                                || (name.endsWith("ViewPager") && fieldType.contains("ViewPager")))) {
                            wanted = true;
                            break;
                        }
                    }
                    if (!wanted) continue;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(owner);
                        if (value != null) return value;
                    } catch (Throwable ignored) {}
                }
            }
            return null;
        }

        @Override public View getBottomBarRoot() { return bar(); }
        @Override public View getBackdropSourceView() { return pageRoot(); }

        @Override public Bitmap[] getNavigationIconBitmaps() {
            View nativeBar = bar();
            if (nativeBar == null) return new Bitmap[0];
            Bitmap[] all = NATIVE_ICON_CACHE.get(nativeBar);
            if (all == null) {
                all = extractNativeIcons(nativeBar);
                int found = 0;
                for (Bitmap bitmap : all) {
                    if (bitmap != null && !bitmap.isRecycled()) found++;
                }
                // Inflation can call the installer before TabIconView creates d/e/f. Retry on the
                // next exact lifecycle/layout signal instead of caching an empty early result.
                if (found == 0) return new Bitmap[0];
                NATIVE_ICON_CACHE.put(nativeBar, all);
            }

            Bitmap[] visible = new Bitmap[visibleNativeIds.length];
            int found = 0;
            for (int i = 0; i < visibleNativeIds.length; i++) {
                int nativeIndex = visibleNativeIds[i];
                if (nativeIndex >= 0 && nativeIndex < all.length) {
                    Bitmap bitmap = all[nativeIndex];
                    if (bitmap != null && !bitmap.isRecycled()) {
                        visible[i] = bitmap;
                        found++;
                    }
                }
            }
            return found == 0 ? new Bitmap[0] : visible;
        }

        private Bitmap[] extractNativeIcons(View root) {
            Bitmap[] result = new Bitmap[4];
            ArrayList<View> tabIcons = new ArrayList<>();
            collectTabIconViews(root, tabIcons, 0);
            int count = Math.min(result.length, tabIcons.size());
            for (int i = 0; i < count; i++) {
                result[i] = firstBitmapField(tabIcons.get(i));
            }

            boolean any = false;
            for (Bitmap bitmap : result) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                ArrayList<ImageView> imageViews = new ArrayList<>();
                collectImageViews(root, imageViews, 0);
                count = Math.min(result.length, imageViews.size());
                for (int i = 0; i < count; i++) {
                    result[i] = bitmapFromDrawable(imageViews.get(i).getDrawable());
                }
            }
            return result;
        }

        private static void collectTabIconViews(View view, List<View> out, int depth) {
            if (view == null || depth > 12 || out.size() >= 4) return;
            for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
                if ("com.tencent.mm.ui.TabIconView".equals(type.getName())) {
                    out.add(view);
                    break;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount() && out.size() < 4; i++) {
                    collectTabIconViews(group.getChildAt(i), out, depth + 1);
                }
            }
        }

        private static Bitmap firstBitmapField(Object owner) {
            if (owner == null) return null;
            for (Class<?> type = owner.getClass(); type != null && type != View.class;
                 type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType() != Bitmap.class) continue;
                    try {
                        field.setAccessible(true);
                        Bitmap bitmap = (Bitmap) field.get(owner);
                        if (bitmap != null && !bitmap.isRecycled()) return bitmap;
                    } catch (Throwable ignored) {}
                }
            }
            return null;
        }

        private static void collectImageViews(View view, List<ImageView> out, int depth) {
            if (view == null || depth > 12 || out.size() >= 4) return;
            if (view instanceof ImageView) out.add((ImageView) view);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount() && out.size() < 4; i++) {
                    collectImageViews(group.getChildAt(i), out, depth + 1);
                }
            }
        }

        private static Bitmap bitmapFromDrawable(Drawable original) {
            if (original == null) return null;
            try {
                if (original instanceof BitmapDrawable) {
                    Bitmap bitmap = ((BitmapDrawable) original).getBitmap();
                    if (bitmap != null && !bitmap.isRecycled()) return bitmap;
                }
                Drawable drawable = original.getConstantState() == null
                        ? original.mutate()
                        : original.getConstantState().newDrawable().mutate();
                int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 96;
                int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 96;
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, width, height);
                drawable.draw(canvas);
                return bitmap;
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            // MainTabUI adds this row as a bottom-gravity FrameLayout overlay on the pager. It
            // reserves no page height, so translating only the exact row preserves chat/insets.
            return new SuppressionPlan().offscreen(bar());
        }

        @Override public boolean preserveLastBackdropOnBlankFrame() { return true; }
        @Override public boolean captureHardwareBackdrops() { return false; }

        @Override public boolean isNavigationVisible() {
            View nativeBar = bar();
            // The replacement lives in DecorView, outside WeChat's home pager container. During
            // an embedded chat the native row can remain attached while that container is GONE.
            // Follow the row's ancestors as well as q(0)/q(8); the layout guard restores the
            // glass row as soon as WeChat shows the home container after q(0)'s animation.
            return nativeBar != null && nativeBar.isShown();
        }

        @Override public boolean shouldShowInjectedNavigation() {
            // LauncherUI#getCurrentFragmet() remains the outgoing ChattingUIFragment during the
            // close animation and may stay cached after NewChattingTabUI.q(0) restores the home
            // container. q(0)/q(8)'s hide reason and the native row's ancestor visibility are
            // checked independently instead of using that potentially cached fragment.
            return true;
        }

        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0) return false;
            View nativeBar = bar();
            View slot = findTaggedSlot(nativeBar, nativeIndex, 0);
            if (dispatchClick(nearestClickableAncestor(slot, nativeBar))) return true;

            View viewPager = pager();
            if (callExists(viewPager, "setCurrentItem", nativeIndex, false)) return true;
            if (callExists(viewPager, "setCurrentItem", nativeIndex)) return true;
            if (nativeBar instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) nativeBar;
                if (nativeIndex < group.getChildCount()) {
                    View direct = group.getChildAt(nativeIndex);
                    if (dispatchClick(nearestClickableAncestor(direct, nativeBar))) return true;
                }
            }
            return false;
        }

        private View findTaggedSlot(View view, int nativeIndex, int depth) {
            if (view == null || depth > 10) return null;
            try {
                Object tag = view.getTag();
                if (tag instanceof Number && ((Number) tag).intValue() == nativeIndex) return view;
            } catch (Throwable ignored) {}
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View found = findTaggedSlot(group.getChildAt(i), nativeIndex, depth + 1);
                    if (found != null) return found;
                }
            }
            return null;
        }

        @Override public boolean post() { return false; }

        @Override public int detectSelectedIndex() {
            int nativeIndex = intValue(call(bar(), "getCurIdx"), -1);
            if (nativeIndex < 0) nativeIndex = intValue(call(pager(), "getCurrentItem"), -1);
            if (nativeIndex < 0) nativeIndex = intValue(call(mainTabUi(), "i"), -1);
            return logicalForNative(nativeIndex);
        }
    }

    /**
     * TIM 4.1.0 adapter rebuilt from the uploaded com.tencent.tim APK.
     *
     * The APK uses com.tencent.mobileqq.activity.SplashActivity and MainFragment. Its resource
     * table exposes the home navigation anchors tabs, tabView and tabhost, while the actual
     * user-facing order is exactly 消息 / 联系人 / 我的. This adapter resolves only that exact
     * three-label row and never reuses QQ's 动态/频道 assumptions.
     */
    private static final class TimAdapter extends UniversalHostAdapter {
        private static final String[] ROOT_IDS = {
                "tabs", "tabView", "tabhost", "container_secondary_tab"
        };
        private View cachedBar;

        TimAdapter(Activity a, TargetApp app, TargetAppSettings s) { super(a, app, s); }

        private View bar() {
            View current = cachedBar;
            if (isUsableBar(current)) return current;

            for (String id : ROOT_IDS) {
                View found = findId(id);
                View normalized = nearestLabeledGroup(found);
                if (isUsableBar(normalized)) {
                    cachedBar = normalized;
                    return normalized;
                }
            }

            View fallback = findBottomLabeledGroup(decor());
            if (isUsableBar(fallback)) cachedBar = fallback;
            return cachedBar;
        }

        private View nearestLabeledGroup(View seed) {
            View current = seed;
            for (int depth = 0; current != null && depth < 8; depth++) {
                if (current instanceof ViewGroup && containsAllLabels(current)) return current;
                if (!(current.getParent() instanceof View)) break;
                current = (View) current.getParent();
            }
            return null;
        }

        private View findBottomLabeledGroup(View root) {
            if (root == null) return null;
            int screenHeight = Math.max(root.getHeight(), root.getResources().getDisplayMetrics().heightPixels);
            int minTop = Math.max(0, (int) (screenHeight * 0.52f));
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            View best = null;
            int bestTop = Integer.MIN_VALUE;
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 3200) {
                View view = queue.removeFirst();
                if (view instanceof ViewGroup && view.getVisibility() == View.VISIBLE
                        && view.isAttachedToWindow() && view.getWidth() >= root.getWidth() * 0.55f
                        && view.getHeight() > 0 && view.getHeight() <= dp(view, 150f)) {
                    int[] location = new int[2];
                    try { view.getLocationOnScreen(location); } catch (Throwable ignored) {}
                    if (location[1] >= minTop && containsAllLabels(view) && location[1] > bestTop) {
                        best = view;
                        bestTop = location[1];
                    }
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
                }
            }
            return best;
        }

        private boolean isUsableBar(View view) {
            return view instanceof ViewGroup && view.isAttachedToWindow()
                    && view.getWidth() > 0 && view.getHeight() > 0 && containsAllLabels(view);
        }

        private boolean containsAllLabels(View root) {
            for (String label : visibleLabels) {
                if (findLabelView(root, label) == null) return false;
            }
            return visibleLabels.length == 3;
        }

        private View findLabelView(View root, String wanted) {
            if (root == null || wanted == null) return null;
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 500) {
                View view = queue.removeFirst();
                String text = viewText(view);
                if (wanted.equals(text)) return view;
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
                }
            }
            return null;
        }

        private String viewText(View view) {
            StringBuilder value = new StringBuilder();
            try {
                if (view instanceof TextView) value.append(((TextView) view).getText());
            } catch (Throwable ignored) {}
            try {
                CharSequence description = view.getContentDescription();
                if (description != null && description.length() > 0) {
                    if (value.length() > 0) value.append(' ');
                    value.append(description);
                }
            } catch (Throwable ignored) {}
            String result = value.toString().replace(" ", "").replace("\n", "").trim();
            for (String label : visibleLabels) {
                if (result.equals(label) || result.equals(label + "已选中")
                        || result.equals(label + "选中")) return label;
            }
            return result;
        }

        private List<View> slots() {
            View root = bar();
            if (!(root instanceof ViewGroup)) return Collections.emptyList();
            ArrayList<View> result = new ArrayList<>();
            for (String label : visibleLabels) {
                View labelView = findLabelView(root, label);
                View slot = nearestSlot(labelView, root);
                if (slot == null || result.contains(slot)) return fallbackSlots((ViewGroup) root);
                result.add(slot);
            }
            return result;
        }

        private View nearestSlot(View label, View root) {
            if (label == null || root == null) return null;
            View current = label;
            View clickable = null;
            for (int depth = 0; current != null && depth < 10; depth++) {
                try {
                    if (current.isClickable() || current.hasOnClickListeners()) clickable = current;
                } catch (Throwable ignored) {}
                if (current.getParent() == root) return clickable != null ? clickable : current;
                if (!(current.getParent() instanceof View)) break;
                current = (View) current.getParent();
            }
            return clickable;
        }

        private List<View> fallbackSlots(ViewGroup root) {
            ArrayList<View> direct = new ArrayList<>();
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (child != null && child.getVisibility() == View.VISIBLE
                        && child.getWidth() > 0 && child.getHeight() > 0) direct.add(child);
            }
            Collections.sort(direct, (left, right) -> {
                int[] l = new int[2];
                int[] r = new int[2];
                try { left.getLocationOnScreen(l); } catch (Throwable ignored) {}
                try { right.getLocationOnScreen(r); } catch (Throwable ignored) {}
                return Integer.compare(l[0], r[0]);
            });
            if (direct.size() == 3) return direct;

            ArrayList<View> clickable = new ArrayList<>();
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 400) {
                View view = queue.removeFirst();
                if (view != root && view.getVisibility() == View.VISIBLE
                        && view.getWidth() >= root.getWidth() / 5
                        && view.getHeight() >= root.getHeight() / 2
                        && (view.isClickable() || view.hasOnClickListeners())) {
                    boolean nested = false;
                    for (View existing : clickable) {
                        if (isDescendant(view, existing) || isDescendant(existing, view)) {
                            nested = true;
                            break;
                        }
                    }
                    if (!nested) clickable.add(view);
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) queue.addLast(group.getChildAt(i));
                }
            }
            Collections.sort(clickable, (left, right) -> {
                int[] l = new int[2];
                int[] r = new int[2];
                try { left.getLocationOnScreen(l); } catch (Throwable ignored) {}
                try { right.getLocationOnScreen(r); } catch (Throwable ignored) {}
                return Integer.compare(l[0], r[0]);
            });
            return clickable.size() == 3 ? clickable : Collections.emptyList();
        }

        private boolean isDescendant(View child, View ancestor) {
            if (child == null || ancestor == null) return false;
            View current = child;
            for (int depth = 0; current != null && depth < 12; depth++) {
                if (current == ancestor) return true;
                if (!(current.getParent() instanceof View)) break;
                current = (View) current.getParent();
            }
            return false;
        }

        private int dp(View view, float value) {
            try { return Math.round(value * view.getResources().getDisplayMetrics().density); }
            catch (Throwable ignored) { return Math.round(value); }
        }

        @Override public boolean shouldShowInjectedNavigation() {
            return !hasActiveConversationFragment();
        }

        private boolean hasActiveConversationFragment() {
            Object supportManager = call(activity, "getSupportFragmentManager");
            if (fragmentManagerContainsConversation(supportManager, 0)) return true;
            Object platformManager = call(activity, "getFragmentManager");
            return fragmentManagerContainsConversation(platformManager, 0);
        }

        private boolean fragmentManagerContainsConversation(Object manager, int depth) {
            if (manager == null || depth > 5) return false;
            Object fragments = call(manager, "getFragments");
            if (!(fragments instanceof Iterable)) return false;
            for (Object fragment : (Iterable<?>) fragments) {
                if (fragment == null) continue;
                boolean active = fragmentIsActive(fragment);
                String name = fragment.getClass().getName();
                if (active && isConversationFragmentClass(name)) return true;
                if (active) {
                    Object childManager = call(fragment, "getChildFragmentManager");
                    if (fragmentManagerContainsConversation(childManager, depth + 1)) return true;
                }
            }
            return false;
        }

        private boolean fragmentIsActive(Object fragment) {
            if (Boolean.TRUE.equals(call(fragment, "isHidden"))) return false;
            if (Boolean.TRUE.equals(call(fragment, "isResumed"))) return true;
            if (Boolean.TRUE.equals(call(fragment, "isVisible"))) return true;
            Object view = call(fragment, "getView");
            return view instanceof View && ((View) view).isShown();
        }

        private boolean isConversationFragmentClass(String name) {
            if (name == null) return false;
            if ("com.tencent.mobileqq.activity.ChatFragment".equals(name)) return true;
            return name.endsWith("AIOFragment")
                    || (name.contains(".aio.") && name.endsWith("Fragment"));
        }

        private View pageRoot() {
            View content = activity.findViewById(android.R.id.content);
            return content != null ? content : decor();
        }

        @Override public View getBottomBarRoot() { return bar(); }
        @Override public View getBackdropSourceView() { return pageRoot(); }

        @Override public SuppressionPlan getSuppressionPlan() {
            View nativeBar = bar();
            return new SuppressionPlan()
                    .overscanBottom(pageRoot(), measuredHeightDp(nativeBar, 58f))
                    .offscreen(nativeBar);
        }

        @Override public boolean select(int index) {
            List<View> items = slots();
            if (index < 0 || index >= items.size()) return false;
            View slot = items.get(index);
            if (dispatchClick(slot)) return true;

            View root = bar();
            if (root == null || slot == null) return false;
            int[] rootLocation = new int[2];
            int[] slotLocation = new int[2];
            try {
                root.getLocationOnScreen(rootLocation);
                slot.getLocationOnScreen(slotLocation);
                return dispatchTapAt(
                        root,
                        slotLocation[0] - rootLocation[0] + slot.getWidth() * 0.5f,
                        slotLocation[1] - rootLocation[1] + slot.getHeight() * 0.5f
                );
            } catch (Throwable ignored) {
                return false;
            }
        }

        @Override public boolean post() { return false; }

        @Override public int detectSelectedIndex() {
            List<View> items = slots();
            for (int i = 0; i < items.size(); i++) {
                if (selectedInTree(items.get(i), 0)) return i;
            }
            return -1;
        }

        private boolean selectedInTree(View view, int depth) {
            if (view == null || depth > 7) return false;
            try {
                if (view.isSelected() || view.isActivated()) return true;
                CharSequence state = view.getStateDescription();
                if (state != null && (state.toString().contains("选中")
                        || state.toString().toLowerCase(Locale.ROOT).contains("selected"))) return true;
            } catch (Throwable ignored) {}
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    if (selectedInTree(group.getChildAt(i), depth + 1)) return true;
                }
            }
            return false;
        }
    }

    private static final class PddAdapter extends UniversalHostAdapter {
        private static final String TAB_CLASS =
                "com.xunmeng.pinduoduo.ui_home_activity.widget.tab.PddTabView";

        PddAdapter(Activity a, TargetApp app, TargetAppSettings s) { super(a, app, s); }

        private View exactTabView() { return findExactClass(TAB_CLASS); }

        private boolean objectOwnsView(Object owner, View wanted) {
            if (owner == null || wanted == null) return false;
            int inspected = 0;
            for (Class<?> type = owner.getClass(); type != null && inspected < 96; type = type.getSuperclass()) {
                Field[] fields;
                try { fields = type.getDeclaredFields(); } catch (Throwable ignored) { continue; }
                for (Field field : fields) {
                    if (inspected++ >= 96) break;
                    try {
                        field.setAccessible(true);
                        if (field.get(owner) == wanted) return true;
                    } catch (Throwable ignored) {}
                }
            }
            return false;
        }

        private Object controller() {
            View bar = exactTabView();
            Object legacy = getField(activity, "b1");
            if (legacy != null && (bar == null || objectOwnsView(legacy, bar))) return legacy;
            if (bar == null) return legacy;

            int inspected = 0;
            for (Class<?> type = activity.getClass(); type != null && inspected < 96; type = type.getSuperclass()) {
                Field[] fields;
                try { fields = type.getDeclaredFields(); } catch (Throwable ignored) { continue; }
                for (Field field : fields) {
                    if (inspected++ >= 96) break;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(activity);
                        if (value != null && objectOwnsView(value, bar)) return value;
                    } catch (Throwable ignored) {}
                }
            }
            return legacy;
        }

        private View tabView() {
            View exact = exactTabView();
            if (exact != null) return exact;
            Object c = controller();
            if (c == null) return null;
            int inspected = 0;
            for (Class<?> type = c.getClass(); type != null && inspected < 96; type = type.getSuperclass()) {
                Field[] fields;
                try { fields = type.getDeclaredFields(); } catch (Throwable ignored) { continue; }
                for (Field field : fields) {
                    if (inspected++ >= 96) break;
                    try {
                        field.setAccessible(true);
                        Object value = field.get(c);
                        if (value instanceof View && TAB_CLASS.equals(value.getClass().getName())) {
                            return (View) value;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            return null;
        }

        private View pageRoot() {
            Object c = controller();
            Object value = getField(c, "y");
            if (value instanceof View) return (View) value;
            View content = findId("home_root", "home_container", "fragment_container", "content");
            return content != null ? content : activity.findViewById(android.R.id.content);
        }

        private boolean looksLikeTabList(List<?> values) {
            if (values == null || values.size() < 3 || values.size() > 12) return false;
            int matches = 0;
            for (Object value : values) {
                if (value == null) continue;
                Object title = getField(value, "title");
                Object link = getField(value, "link");
                if (title != null || link != null) matches++;
            }
            return matches >= Math.min(3, values.size());
        }

        private List<?> tabs() {
            Object c = controller();
            Object listObject = getField(c, "f");
            Object legacy = getField(listObject, "bottom_tabs");
            if (legacy instanceof List && looksLikeTabList((List<?>) legacy)) return (List<?>) legacy;
            Object direct = getField(c, "bottom_tabs");
            if (direct instanceof List && looksLikeTabList((List<?>) direct)) return (List<?>) direct;

            if (c != null) {
                int inspected = 0;
                for (Class<?> type = c.getClass(); type != null && inspected < 128; type = type.getSuperclass()) {
                    Field[] fields;
                    try { fields = type.getDeclaredFields(); } catch (Throwable ignored) { continue; }
                    for (Field field : fields) {
                        if (inspected++ >= 128) break;
                        try {
                            field.setAccessible(true);
                            Object value = field.get(c);
                            if (value instanceof List && looksLikeTabList((List<?>) value)) {
                                return (List<?>) value;
                            }
                            Object nested = getField(value, "bottom_tabs");
                            if (nested instanceof List && looksLikeTabList((List<?>) nested)) {
                                return (List<?>) nested;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            return Collections.emptyList();
        }

        private boolean matchesTab(Object tab, String wanted) {
            if (tab == null || wanted == null) return false;
            String title = String.valueOf(getField(tab, "title"));
            String link = String.valueOf(getField(tab, "link")).toLowerCase(Locale.ROOT);
            if (wanted.equals("首页")) return title.contains("首页") || link.contains("index.html");
            if (wanted.equals("直播")) return title.contains("直播") || title.contains("视频")
                    || link.contains("live") || link.contains("video");
            if (wanted.equals("领券")) return title.contains("券") || title.contains("百亿补贴")
                    || link.contains("coupon") || link.contains("classification.html");
            if (wanted.equals("消息")) return title.contains("消息") || title.contains("聊天")
                    || link.contains("chat_list") || link.contains("message");
            if (wanted.equals("我的")) return title.contains("我的") || title.contains("个人")
                    || link.contains("personal.html");
            return title.contains(wanted);
        }

        private int runtimeIndexForLogical(int logical) {
            if (logical < 0 || logical >= visibleLabels.length) return -1;
            String wanted = visibleLabels[logical];
            List<?> values = tabs();
            for (int i = 0; i < values.size(); i++) {
                if (matchesTab(values.get(i), wanted)) return i;
            }
            int fallback = nativeForLogical(logical);
            int runtimeCount = tabSlots().size();
            if (fallback >= 0 && (runtimeCount == 0 || fallback < runtimeCount)) return fallback;
            return logical;
        }

        private int logicalForRuntime(int runtime) {
            for (int i = 0; i < visibleLabels.length; i++) {
                if (runtimeIndexForLogical(i) == runtime) return i;
            }
            return -1;
        }

        private List<View> tabSlots() {
            View bar = tabView();
            if (!(bar instanceof ViewGroup)) return Collections.emptyList();
            ViewGroup group = (ViewGroup) bar;
            List<View> direct = new ArrayList<>();
            int minWidth = Math.max(1, bar.getWidth() / 12);
            int minHeight = Math.max(1, bar.getHeight() / 3);
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != null && child.getVisibility() == View.VISIBLE
                        && child.getWidth() >= minWidth && child.getHeight() >= minHeight) {
                    direct.add(child);
                }
            }
            if (direct.size() >= 4 && direct.size() <= 8) return sortByScreenX(direct);

            List<View> clickable = new ArrayList<>();
            ArrayDeque<View> queue = new ArrayDeque<>();
            queue.add(bar);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 180) {
                View candidate = queue.removeFirst();
                if (candidate != bar && candidate.getVisibility() == View.VISIBLE
                        && candidate.getWidth() >= minWidth && candidate.getHeight() >= minHeight
                        && (candidate.hasOnClickListeners() || candidate.isClickable())) {
                    clickable.add(candidate);
                }
                if (candidate instanceof ViewGroup) {
                    ViewGroup parent = (ViewGroup) candidate;
                    for (int i = 0; i < parent.getChildCount(); i++) queue.addLast(parent.getChildAt(i));
                }
            }
            return sortByScreenX(clickable);
        }

        private List<View> sortByScreenX(List<View> views) {
            Collections.sort(views, (left, right) -> {
                int[] l = new int[2];
                int[] r = new int[2];
                try { left.getLocationOnScreen(l); } catch (Throwable ignored) {}
                try { right.getLocationOnScreen(r); } catch (Throwable ignored) {}
                return Integer.compare(l[0], r[0]);
            });
            return views;
        }

        private View slotForRuntime(int runtime) {
            List<View> slots = tabSlots();
            return runtime >= 0 && runtime < slots.size() ? slots.get(runtime) : null;
        }

        private boolean dispatchOriginalSlot(View slot) {
            if (slot == null || !slot.isEnabled()) return false;
            try { if (slot.performClick()) return true; } catch (Throwable ignored) {}
            try { if (slot.callOnClick()) return true; } catch (Throwable ignored) {}
            return false;
        }

        @Override public View getBottomBarRoot() { return tabView(); }
        @Override public View getBackdropSourceView() { return pageRoot(); }

        @Override public boolean isNavigationVisible() {
            View bar = tabView();
            // PDD often keeps the tab object VISIBLE while hiding one of its parent containers.
            // isShown() follows that parent chain; alpha/translation are intentionally ignored so
            // our own off-screen suppression does not make the glass row disappear.
            return bar != null && bar.isAttachedToWindow() && bar.isShown();
        }

        @Override public SuppressionPlan getSuppressionPlan() {
            View bar = tabView();
            float height = measuredHeightDp(bar, 58f);
            // PddTabView owns version-specific listeners and remote-tab state. Keep it in the
            // original parent like XHS instead of reparenting it into the 1x1 parking lot.
            return new SuppressionPlan().overscanBottom(pageRoot(), height).offscreen(bar);
        }

        @Override public boolean select(int index) {
            int runtime = runtimeIndexForLogical(index);
            if (runtime < 0) return false;
            if (dispatchOriginalSlot(slotForRuntime(runtime))) return true;
            Object c = controller();
            callExists(c, "onTabTouched", runtime);
            if (callExists(c, "onTabSelected", runtime)) return true;
            return callExists(c, "v0", runtime);
        }

        @Override public boolean post() { return false; }

        @Override public int detectSelectedIndex() {
            List<View> slots = tabSlots();
            View bar = tabView();
            for (int i = 0; i < slots.size(); i++) {
                if (isSelectedOrParent(slots.get(i), bar)) return logicalForRuntime(i);
            }
            Object c = controller();
            int runtime = intValue(call(c, "b1"), -1);
            if (runtime < 0) runtime = intValue(getField(c, "b1", "currentIndex", "selectedIndex"), -1);
            return logicalForRuntime(runtime);
        }
    }

    private static final class FlutterAdapter extends UniversalHostAdapter {
        private static final String[][] LABELS = {{"产品信息", "产品"}, {"设置"}, {"我的", "Mine"}};
        FlutterAdapter(Activity a, TargetApp app, TargetAppSettings s) { super(a, app, s); }
        private View flutter() { return findFlutterView(decor()); }
        @Override public View getBottomBarRoot() { return null; }
        @Override public SuppressionPlan getSuppressionPlan() {
            // Flutter draws the Scaffold and its native BottomNavigationBar into one texture. Give
            // the Flutter viewport a bottom overscan so that its own bar is laid out below the
            // physical window while the page body continues behind LiquidGlass.
            return new SuppressionPlan().overscanBottom(flutter(), 88f);
        }
        @Override public View getBackdropSourceView() { return flutter(); }
        @Override public boolean isNavigationVisible() {
            View view = flutter();
            return view != null && view.isAttachedToWindow() && view.isShown()
                    && view.getWidth() > 0 && view.getHeight() > 0;
        }
        private AccessibilityNodeProvider provider() {
            try { return flutter() == null ? null : flutter().getAccessibilityNodeProvider(); }
            catch (Throwable ignored) { return null; }
        }
        @Override public boolean select(int index) {
            int nativeIndex = nativeForLogical(index);
            if (nativeIndex < 0 || nativeIndex >= LABELS.length) return false;
            AccessibilityNodeInfo node = findSemanticNode(provider(), LABELS[nativeIndex]);
            if (node != null) {
                try { if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; }
                finally { try { node.recycle(); } catch (Throwable ignored) {} }
            }
            View view = flutter();
            if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
            int bottomInset = 0;
            try {
                if (view.getRootWindowInsets() != null) {
                    bottomInset = view.getRootWindowInsets()
                            .getInsets(WindowInsets.Type.navigationBars()).bottom;
                }
            } catch (Throwable ignored) {}
            float density = view.getResources().getDisplayMetrics().density;
            float x = view.getWidth() * ((nativeIndex * 2f + 1f) / 6f);
            float y = Math.max(1f, view.getHeight() - bottomInset - 42f * density);
            if (dispatchTapAt(view, x, y)) return true;
            return dispatchTapAt(view, x, Math.max(1f, view.getHeight() - bottomInset - 58f * density));
        }
        @Override public boolean post() { return false; }
        @Override public int detectSelectedIndex() {
            AccessibilityNodeProvider provider = provider();
            if (provider == null) return -1;
            for (int logical = 0; logical < visibleNativeIds.length; logical++) {
                int nativeIndex = visibleNativeIds[logical];
                AccessibilityNodeInfo node = findSemanticNode(provider, LABELS[nativeIndex]);
                if (node == null) continue;
                try {
                    if (isSemanticSelected(node)) return logical;
                } finally {
                    try { node.recycle(); } catch (Throwable ignored) {}
                }
            }
            return -1;
        }
        @Override public int getDefaultSelectedIndex() {
            // This MOONDROP build opens IndexPage on the Settings tab. If 产品信息 is hidden,
            // logicalForNative(1) correctly becomes the first visible glass item.
            int logical = logicalForNative(1);
            return logical >= 0 ? logical : 0;
        }
    }

    private static boolean isSemanticSelected(AccessibilityNodeInfo node) {
        if (node == null) return false;
        try {
            if (node.isSelected() || node.isChecked()) return true;
        } catch (Throwable ignored) {}
        AccessibilityNodeInfo parent = null;
        try {
            parent = node.getParent();
            for (int depth = 0; parent != null && depth < 5; depth++) {
                if (parent.isSelected() || parent.isChecked()) return true;
                AccessibilityNodeInfo next = parent.getParent();
                try { parent.recycle(); } catch (Throwable ignored) {}
                parent = next;
            }
        } catch (Throwable ignored) {
        } finally {
            if (parent != null) {
                try { parent.recycle(); } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo clickableSemanticNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (isSemanticClickable(node)) return node;

        AccessibilityNodeInfo parent = null;
        try {
            parent = node.getParent();
            for (int depth = 0; parent != null && depth < 5; depth++) {
                if (isSemanticClickable(parent)) {
                    AccessibilityNodeInfo clickable = parent;
                    parent = null;
                    return clickable;
                }
                AccessibilityNodeInfo next = parent.getParent();
                try { parent.recycle(); } catch (Throwable ignored) {}
                parent = next;
            }
        } catch (Throwable ignored) {
        } finally {
            if (parent != null) {
                try { parent.recycle(); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static boolean isSemanticClickable(AccessibilityNodeInfo node) {
        if (node == null) return false;
        try {
            if (node.isClickable()) return true;
        } catch (Throwable ignored) {}
        try {
            return (node.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static AccessibilityNodeInfo findSemanticNode(
            AccessibilityNodeProvider provider,
            String... aliases
    ) {
        if (provider == null || aliases == null) return null;
        try {
            AccessibilityNodeInfo root = provider.createAccessibilityNodeInfo(-1);
            if (root == null) root = provider.createAccessibilityNodeInfo(0);
            if (root == null) return null;
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            int visited = 0;
            while (!queue.isEmpty() && visited++ < 600) {
                AccessibilityNodeInfo node = queue.removeFirst();
                CharSequence text = node.getText();
                CharSequence description = node.getContentDescription();
                boolean match = false;
                for (String alias : aliases) {
                    if (contains(text, alias) || contains(description, alias)) {
                        match = true;
                        break;
                    }
                }
                if (match) {
                    AccessibilityNodeInfo clickable = clickableSemanticNode(node);
                    if (clickable != null) {
                        if (clickable != node) {
                            try { node.recycle(); } catch (Throwable ignored) {}
                        }
                        while (!queue.isEmpty()) {
                            try { queue.removeFirst().recycle(); } catch (Throwable ignored) {}
                        }
                        return clickable;
                    }
                }
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.addLast(child);
                }
                try { node.recycle(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean contains(CharSequence value, String alias) {
        return value != null && alias != null && value.toString().contains(alias);
    }
}
