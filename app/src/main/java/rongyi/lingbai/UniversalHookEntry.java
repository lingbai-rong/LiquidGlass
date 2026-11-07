package rongyi.lingbai;

import android.app.Activity;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Exact lifecycle and host-class hooks for the uploaded APK versions. */
public final class UniversalHookEntry {
    private static final Map<Activity, ContentObserver> OBSERVERS =
            Collections.synchronizedMap(new WeakHashMap<Activity, ContentObserver>());
    private static final Map<Activity, Long> LAST_EXACT_RETRY =
            Collections.synchronizedMap(new WeakHashMap<Activity, Long>());
    private static final Map<Object, WeakReference<Activity>> AWEME_COMMENT_OWNERS =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<Activity>>());
    private static final Map<Object, WeakReference<Activity>> AWEME_CHAT_FRAGMENT_OWNERS =
            Collections.synchronizedMap(new WeakHashMap<Object, WeakReference<Activity>>());
    private static final Map<Activity, Integer> WECHAT_TASKBAR_GENERATIONS =
            Collections.synchronizedMap(new WeakHashMap<Activity, Integer>());
    private static final Map<Activity, Integer> JD_NAV_VISIBILITY_GENERATIONS =
            Collections.synchronizedMap(new WeakHashMap<Activity, Integer>());
    private static WeakReference<Activity> LAST_AWEME_MAIN_ACTIVITY = new WeakReference<>(null);
    private static WeakReference<Activity> LAST_WECHAT_LAUNCHER = new WeakReference<>(null);
    /**
     * One stable session reason for WeChat's embedded NewChattingTabUI. 26.69 used each fragment and the
     * NewChattingTabUI instance as independent weak-map keys. A fragment can stay resumed while
     * NewChattingTabUI.q(0) has already restored the launcher, leaving one stale reason behind and
     * keeping LiquidGlass GONE. q(0)/q(8) is the host's authoritative container-visibility signal,
     * so every committed chat callback now drives this single token. A separate short transition
     * token covers the interval before q() and is released if opening the chat is cancelled.
     */
    private static final Object WECHAT_CHAT_VISIBILITY_REASON = new Object();
    private static final Object WECHAT_CHAT_TRANSITION_REASON = new Object();
    private static final Object WECHAT_TASKBAR_PULL_REASON = new Object();
    private static final Object WECHAT_TASKBAR_VISIBILITY_REASON = new Object();
    private static final Object JD_NAV_VISIBILITY_REASON = new Object();
    // One token per Douyin surface, rather than one token per Fragment. Comment screens often
    // create two or three cooperating fragments and some of them stay in FragmentManager after
    // the sheet is dismissed. Per-fragment hide reasons could therefore leave one stale token and
    // keep the glass bar GONE forever.
    private static final Object AWEME_COMMENT_VISIBILITY_REASON = new Object();
    private static final Object AWEME_EMBEDDED_CHAT_VISIBILITY_REASON = new Object();

    private UniversalHookEntry() {}

    public static void install(final LoadPackageParam lpparam, final TargetApp app) {
        XposedBridge.log("[LiquidGlass][26.72] loaded in " + app.packageName);

        // Install for every supported host. The hook is deliberately narrow: it only
        // neutralizes Resources.NotFoundException while Android/Compose is constructing an
        // accessibility virtual node. This protects Bilibili and any future host that races the
        // injected Compose tree without swallowing ordinary resource failures.
        installInjectedComposeAccessibilityResourceGuard(app);

        // Do not hook high-frequency framework mutators such as View.setVisibility,
        // setLayoutParams, setAlpha or setTranslationY for every View in the host process. Exact
        // host hooks plus per-root layout guards keep suppression local to the verified rows.

        installActivityHooks(app);
        installExactHostHooks(lpparam, app);
        if (app == TargetApp.MOONDROP) installMoondropRenderModeHook(lpparam, app);
        // XHS is handled here as well. Navigation is mapped through the original attached
        // TabBarView slots, so it no longer depends on a version-specific obfuscated controller.
    }


    /**
     * Some HyperOS accessibility/screenshot services aggressively prefetch Compose virtual nodes.
     * During the attach-time race, an injected node can resolve this module's string id through the
     * host APK Resources table. Bilibili 9.3/9.4, Pinduoduo and NetEase have shown this same
     * main-thread Resources$NotFoundException pattern. The injected glass intentionally has no
     * accessibility contract, so only missing strings requested from an accessibility-node stack
     * are neutralized.
     */
    private static void installInjectedComposeAccessibilityResourceGuard(TargetApp app) {
        InjectedResourceResolverGuard.install(app == null ? "host" : app.key);
    }

    private static void installActivityHooks(final TargetApp app) {
        XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (app == TargetApp.WECHAT && matches(activity, app)) {
                    // MainTabUI.d() creates the row from inside LauncherUI.onCreate(). Make the
                    // owner available before that nested exact callback runs.
                    LAST_WECHAT_LAUNCHER = new WeakReference<>(activity);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!matches(activity, app)) return;
                GlobalRenderSettingsReader.invalidate();
                registerObserver(activity, app);
                installLater(activity, app, 0L, 48L, 140L, 360L, 900L, 1800L, 3600L);
                if (app == TargetApp.DOUYIN) {
                    LAST_AWEME_MAIN_ACTIVITY = new WeakReference<>(activity);
                }
                if (app == TargetApp.WECHAT) {
                    LAST_WECHAT_LAUNCHER = new WeakReference<>(activity);
                }
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onContentChanged", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!matches(activity, app)) return;
                installLater(activity, app, 0L, 48L, 180L, 600L);
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onStart", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!matches(activity, app)) return;
                registerObserver(activity, app);
                installLater(activity, app, 0L, 100L, 420L);
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!matches(activity, app)) return;
                GlobalRenderSettingsReader.invalidate();
                registerObserver(activity, app);
                UniversalGlassInstaller.resume(activity, app);
                if (app == TargetApp.WECHAT) {
                    LAST_WECHAT_LAUNCHER = new WeakReference<>(activity);
                    WeChatRoundAvatars.reloadSettings();
                }
                if (app != TargetApp.XHS) {
                    installLater(activity, app, 0L, 80L, 260L, 800L, 2200L);
                }
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onPostResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!matches(activity, app)) return;
                installLater(activity, app, 0L, 160L, 720L);
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onWindowFocusChanged", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0 || !Boolean.TRUE.equals(param.args[0])) return;
                Activity activity = (Activity) param.thisObject;
                if (!matches(activity, app)) return;
                installLater(activity, app, 0L, 72L, 240L);
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (matches(activity, app)) UniversalGlassInstaller.pause(activity);
            }
        });

        // XHS must not run an Xposed callback for every MotionEvent in its feed. Its capture root
        // owns one ordinary ViewTreeObserver listener instead of any process-wide scroll hook.
        if (app != TargetApp.XHS) {
            XposedBridge.hookAllMethods(Activity.class, "dispatchTouchEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!matches(activity, app) || param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof MotionEvent)) return;
                    int action = ((MotionEvent) param.args[0]).getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                        UniversalGlassInstaller.markBackdropMoving(activity);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (!matches(activity, app) || param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof MotionEvent)) return;
                    int action = ((MotionEvent) param.args[0]).getActionMasked();
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        // ACTION_UP only starts quiet-window tracking. The final capture waits until
                        // RecyclerView/Compose flings stop emitting scroll/draw signals.
                        UniversalGlassInstaller.markBackdropIdle(activity);
                    }
                }
            });
        }

        XposedBridge.hookAllMethods(Activity.class, "onConfigurationChanged", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (matches(activity, app)) UniversalGlassInstaller.recreate(activity, app);
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!matches(activity, app)) return;
                unregisterObserver(activity);
                LAST_EXACT_RETRY.remove(activity);
                if (app == TargetApp.WECHAT && LAST_WECHAT_LAUNCHER.get() == activity) {
                    LAST_WECHAT_LAUNCHER = new WeakReference<>(null);
                }
                if (app == TargetApp.WECHAT) {
                    WECHAT_TASKBAR_GENERATIONS.remove(activity);
                }
                if (app == TargetApp.JD) {
                    JD_NAV_VISIBILITY_GENERATIONS.remove(activity);
                }
                if (LiquidTabNavigationRules.supports(app)) LiquidTabHostAdapter.forget(activity);
                UniversalGlassInstaller.remove(activity);
            }
        });
    }

    private static void installExactHostHooks(LoadPackageParam lpparam, TargetApp app) {
        if (LiquidTabPortAdapter.supports(app)) { LiquidTabPortAdapter.installHooks(lpparam, app); return; }
        if (LiquidTabNavigationRules.supports(app)) {
            installLiquidTabHostHooks(lpparam, app);
            return;
        }
        switch (app.key) {
            case "wechat":
                hookViewClass(lpparam, app,
                        "com.tencent.mm.ui.LauncherUIBottomTabView",
                        "onAttachedToWindow", "onFinishInflate", "onLayout");
                hookWeChatFrostedContent(lpparam, app);
                hookWeChatSelectionSignals(lpparam, app);
                hookWeChatChatVisibility(lpparam, app);
                hookWeChatTaskBarVisibility(lpparam, app);
                // The panel is optional. Install every original LiquidGlass hook first, then add
                // the WeKit feature behind a hard failure boundary. A missing WeChat symbol,
                // Compose owner or DexKit library must never prevent the original bottom bar.
                installWeChatSidePanelSafely(lpparam);
                installWeChatRoundAvatarsSafely(lpparam);
                break;
            case "jd":
                // JD 15.9.50 builds both the classic and AI rows from NavigationGroup. The
                // controller keeps sparse navigation ids (0/2/11/3/4), so listen to its own
                // population and selection commits rather than child positions.
                hookViewClass(lpparam, app,
                        "com.jingdong.common.unification.navigationbar.newbar.NavigationGroup",
                        "onAttachedToWindow", "onLayout", "onVisibilityChanged",
                        "setNavigationButton");
                hookViewClass(lpparam, app,
                        "com.jingdong.common.unification.navigationbar.newbar.NavigationButton",
                        "buildBtnByLoca", "buildBtnByRemote", "refreshBtnTheme");
                hookPageTransitionClass(lpparam, app,
                        "com.jingdong.common.unification.navigationbar.newbar.NavigationGroup",
                        "setCheck", "setOnlyCheckState", "checkButton", "onClick");
                hookFragmentClass(lpparam, app,
                        "com.jingdong.app.mall.navigationbar.JDNavigationFragment",
                        "onCreateViews", "onResume", "s", "w");
                hookJdNavigationVisibility(lpparam, app);
                hookActivityClass(lpparam, app,
                        "com.jingdong.app.mall.MainFrameActivity",
                        "onAttachFragment", "onResume", "onWindowFocusChanged",
                        "setNavigationVisibility");
                break;
            case "tim":
                hookFragmentClass(lpparam, app,
                        "com.tencent.mobileqq.activity.home.MainFragment",
                        "onViewCreated", "onTabChanged", "b9", "onResume");
                hookTimFragmentMode(lpparam,
                        "com.tencent.mobileqq.activity.home.MainFragment", false,
                        "onCreateView", "onViewCreated", "onResume", "onStart");
                hookTimFragmentMode(lpparam,
                        "com.tencent.mobileqq.activity.ChatFragment", true,
                        "onCreateView", "onViewCreated", "onResume", "onStart");
                hookActivityClass(lpparam, app,
                        "com.tencent.mobileqq.activity.SplashActivity",
                        "openMainFragment", "updateMain", "onWindowFocusChanged");
                hookTimActivityHomeMethods(lpparam,
                        "com.tencent.mobileqq.activity.SplashActivity",
                        "openMainFragment");
                break;
            case "moondrop":
                hookViewClass(lpparam, app, "qs1", "onAttachedToWindow");
                break;
            case "bilibili":
            case "bilibili_hd":
                hookViewClass(lpparam, app,
                        "com.bilibili.lib.homepage.widget.TabHost",
                        "onAttachedToWindow", "setTabs", "setCurrentItem");
                break;
            case "midea":
                hookActivityClass(lpparam, app,
                        "com.midea.meiju.main.MainActivity",
                        "showOrHideAppMainBottomBar", "updateTab");
                break;
            case "meituan":
                hookViewClass(lpparam, app,
                        "com.sankuai.waimai.business.page.homepage.view.TitleIndicator",
                        "onAttachedToWindow", "setActivity", "setCurrentTab", "k");
                break;
            case "cainiao":
                hookViewClass(lpparam, app,
                        "com.cainiao.commonlibrary.navigation.NavigationView",
                        "onAttachedToWindow", "init", "setViewAdapter", "setNavigationIndex",
                        "updateSelectState", "refreshNavigations", "refreshNavigationsNoClick");
                hookViewClass(lpparam, app,
                        "com.cainiao.commonlibrary.navigation.NavigationTabView",
                        "onAttachedToWindow", "setIsSelect", "onNavigationViewClick");
                hookActivityClass(lpparam, app,
                        "com.cainiao.wireless.homepage.view.activity.HomePageActivity",
                        "onCreate", "initView", "onResume", "showNavigation", "hideNavigation",
                        "switchFragment");
                break;
            case "meituan_main":
                hookViewClass(lpparam, app,
                        "com.meituan.android.pt.homepage.tab.TabBlockV2",
                        "onAttachedToWindow", "C", "A", "t");
                hookActivityClass(lpparam, app,
                        "com.meituan.android.pt.homepage.activity.MainActivity",
                        "T5");
                break;
            case "mihome":
                hookViewClass(lpparam, app,
                        "com.xiaomi.smarthome.newui.buttomtab.TabPageIndicatorNew",
                        "onAttachedToWindow", "setViewPager", "setCurrentItem");
                break;
            case "mi_fitness":
                // Xiaomi Fitness 3.58.0 creates the verified Material TabLayout dynamically in
                // MainActivity.bindView(). Observe both its population/selection and the owner
                // lifecycle so the glass is installed after the four custom tab views exist.
                hookViewClass(lpparam, app,
                        "com.google.android.material.tabs.TabLayout",
                        "onAttachedToWindow", "addTab", "selectTab", "setScrollPosition");
                hookActivityClass(lpparam, app,
                        "com.xiaomi.fitness.main.MainActivity",
                        "onCreate", "bindView", "onResume", "onWindowFocusChanged");
                break;
            case "netease":
                hookViewClass(lpparam, app,
                        "com.netease.cloudmusic.theme.ui.NavigationTabLayout",
                        "onAttachedToWindow");
                hookPageTransitionClass(lpparam, app,
                        "com.netease.cloudmusic.theme.ui.NavigationTabLayout", "t");
                hookNeteaseDrawerState(lpparam);
                break;
            case "uuremote":
                hookUuComposeNavigation(lpparam);
                hookViewClass(lpparam, app,
                        "androidx.compose.ui.platform.AndroidComposeView",
                        "onAttachedToWindow");
                hookActivityClass(lpparam, app,
                        "com.remote.app.ui.activity.ComposeMainActivity",
                        "onCreate", "onResume");
                hookActivityClass(lpparam, app,
                        "com.remote.app.ui.activity.MainActivity",
                        "N", "Q", "onResume");
                break;
            case "stardesk":
                hookActivityClass(lpparam, app,
                        "com.remote.app.ui.activity.MainActivity",
                        "onCreate", "L", "onResume", "onJumpTab");
                break;
            case "qqmusic":
                hookViewClass(lpparam, app,
                        "com.tencent.qqmusic.ui.minibar.navigation.MainDeskNavigateContainer",
                        "onAttachedToWindow", "setSelectedItem", "m", "e");
                break;
            case "xhs":
                hookViewClass(lpparam, app,
                        "com.xingin.xhs.homepage.tabbar.TabBarView",
                        "onAttachedToWindow", "onFinishInflate");
                hookViewClass(lpparam, app,
                        "com.xingin.commercial.shop.malltabbar.TabBarView",
                        "onAttachedToWindow", "onFinishInflate");
                break;
            case "pdd":
                hookViewClass(lpparam, app,
                        "com.xunmeng.pinduoduo.ui_home_activity.widget.tab.PddTabView",
                        "onAttachedToWindow", "onDetachedFromWindow", "onLayout", "onSizeChanged",
                        "onVisibilityChanged", "onWindowVisibilityChanged",
                        "setTabs", "setOnTabChangeListener");
                hookActivityClass(lpparam, app,
                        "com.xunmeng.pinduoduo.ui.activity.MainFrameActivity",
                        "updateRootView", "backToHome");
                break;
            case "douyin":
                hookAwemeBottomTabs(lpparam, app);
                // 26.78: keep the complete 26.76 Douyin layout/capture path unchanged.
                // Only add explicit visibility hooks for comments and friend chat.
                hookAwemeCommentPanels(lpparam);
                hookAwemeChatRooms(lpparam);
                hookActivityClass(lpparam, app,
                        "com.ss.android.ugc.aweme.main.MainActivity",
                        "onCreate", "onResume", "onWindowFocusChanged");
                break;
            case "mi_file_manager":
                hookFileManagerSelectionMode(lpparam, app);
                hookFileManagerPageVisibility(lpparam, app);
                // Fall through to the shared Miuix navigator hooks.
            case "mi_phone":
            case "mi_messages":
            case "mi_gallery":
            case "mi_notes":
                hookViewClass(lpparam, app,
                        "miuix.navigator.bottomnavigation.BottomNavigationView",
                        "onAttachedToWindow");
                hookViewClass(lpparam, app,
                        "miuix.navigator.navigation.NavigationBarView",
                        "onAttachedToWindow", "inflateMenu", "show", "hide");
                // Messages 17.0.3.2 obfuscates NavigationBarView to Ra.i while retaining the
                // public BottomNavigationView and setSelectedItemId API. Resolve the declaring
                // superclass from that public class instead of depending on a library class name.
                hookPageTransitionHierarchy(lpparam, app,
                        "miuix.navigator.bottomnavigation.BottomNavigationView",
                        "setSelectedItemId");
                hookPageTransitionClass(lpparam, app,
                        "miuix.navigator.navigation.NavigationBarItemView",
                        "setChecked");
                if (app == TargetApp.MI_GALLERY) {
                    // Gallery installs its LayoutInflater factory before NavigatorActivity creates
                    // MiuixNavigatorImpl. Hook the two guaranteed creation/attachment stages rather
                    // than hoping an early generic Activity retry sees a non-null bottom tab.
                    hookActivityClass(lpparam, app,
                            "com.miui.gallery.activity.HomeNavigatorActivity",
                            "onCreate", "onCreatePrimaryNavigation");
                    hookGalleryNavigatorLifecycle(lpparam, app);
                }
                if (app == TargetApp.MI_PHONE) {
                    // Contacts 17.20.10.48 declares TwelveKeyDialer as a manifest alias; the real
                    // screen is PeopleActivity and dynamically inflates these concrete dialer
                    // Views. Their callbacks are the reliable point to bind/repair the composite
                    // dialer root as it appears and lays out.
                    hookViewClass(lpparam, app,
                            "com.android.contacts.dialer.view.DialpadLayout",
                            "onFinishInflate", "onLayout");
                    hookViewClass(lpparam, app,
                            "com.android.contacts.dialer.view.SingleCallLayout",
                            "onFinishInflate");
                    hookViewClass(lpparam, app,
                            "com.android.contacts.dialer.view.DoubleCallButtonContainer",
                            "onFinishInflate");

                    // Keep older Contacts aliases as a compatibility fallback. Missing classes or
                    // methods are ignored by hookActivityClass without touching unrelated Views.
                    hookActivityClass(lpparam, app,
                            "com.android.contacts.activities.TwelveKeyDialer",
                            "setDialpadVisibility", "showDialpad", "hideDialpad",
                            "updateDialpad", "onDialpadShown");
                }
                break;
            case "mi_calendar":
                hookViewClass(lpparam, app,
                        "com.android.calendar.homepage.BottomNavigatorView",
                        "onAttachedToWindow", "setVisibility");
                hookPageTransitionClass(lpparam, app,
                        "com.android.calendar.homepage.BottomNavigatorView",
                        "g");
                break;
            case "mi_compass":
                hookViewClass(lpparam, app,
                        "miuix.bottomnavigation.BottomNavigator",
                        "onAttachedToWindow");
                // Compass exposes the target index directly in both callbacks. Read that argument
                // synchronously instead of relying only on a delayed getSelectedItem() poll.
                hookCompassSelectionSignals(lpparam, app);
                hookCompassCameraSignals(lpparam, app);
                break;
            default:
                break;
        }
    }

    private static void installLiquidTabHostHooks(LoadPackageParam lpparam, TargetApp app) {
        if (app == TargetApp.MI_MARKET) {
            hookViewClass(lpparam, app, LiquidTabNavigationRules.MARKET_ROW,
                    "onAttachedToWindow", "onDetachedFromWindow", "show");
            hookViewClass(lpparam, app, LiquidTabNavigationRules.MARKET_ITEM,
                    "onAttachedToWindow");
            Class<?> proxy = findClass(
                    "com.xiaomi.market.ui.DoubleTabProxyActivityWrapper", lpparam.classLoader);
            if (proxy != null) {
                for (String method : new String[]{"setTabContainer", "onBottomTabBlurSwitch", "onResume"}) {
                    try {
                        XposedBridge.hookAllMethods(proxy, method, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object row = UniversalHostAdapter.getField(param.thisObject, "bottomTabLayout");
                            Object owner = UniversalHostAdapter.getField(param.thisObject, "mActivity");
                            Activity activity = row instanceof View
                                    ? ActivityFinder.from(((View) row).getContext())
                                    : owner instanceof Activity ? (Activity) owner : null;
                            LiquidTabHostAdapter.invalidate(activity);
                            scheduleExactRetry(activity, app);
                        }
                        });
                    } catch (Throwable ignored) {}
                }
            }
        } else if (app == TargetApp.MI_COMMUNITY) {
            hookViewClass(lpparam, app, LiquidTabNavigationRules.COMMUNITY_ROW,
                    "onAttachedToWindow", "onDetachedFromWindow", "setCurrentItem");
            hookViewClass(lpparam, app, LiquidTabNavigationRules.COMMUNITY_ITEM,
                    "onAttachedToWindow");
        } else if (app == TargetApp.BILIBILI_IN) {
            // ComposeView inherits attachment from AbstractComposeView in this host generation.
            hookViewClass(lpparam, app, "androidx.compose.ui.platform.AbstractComposeView",
                    "onAttachedToWindow", "onDetachedFromWindow");
        } else if (app == TargetApp.WEIBO || app == TargetApp.TAOBAO) {
            // TabHost remains the host's actual controller; do not replace its tab listener.
            hookViewClass(lpparam, app, "android.widget.TabHost",
                    "onAttachedToWindow", "setCurrentTab", "setCurrentTabByTag");
            hookViewClass(lpparam, app, "android.widget.TabWidget",
                    "onAttachedToWindow", "onDetachedFromWindow", "setCurrentTab");
        } else if (app == TargetApp.TIEBA) {
            for (String row : LiquidTabNavigationRules.TIEBA_ROWS) {
                hookViewClass(lpparam, app, row,
                        "onAttachedToWindow", "onDetachedFromWindow",
                        "setCurrentTab", "setNavigationBarBg");
            }
        }
    }

    /**
     * UU's current Compose home draws its native navigation row from one obfuscated composable.
     * The row is not an Android child view, so changing the AndroidComposeView height cannot hide
     * it.  Hook the exact runtime signature from the supplied UU APK and leave the page content
     * composition untouched.
     */
    private static void hookUuComposeNavigation(LoadPackageParam lpparam) {
        Class<?> navigationComposable = findClass("t8.g", lpparam.classLoader);
        if (navigationComposable == null) {
            XposedBridge.log("[LiquidGlass][UU] native Compose navigation class not found");
            return;
        }
        try {
            UuComposeNavigationLayout layout = UuComposeNavigationLayout.create(lpparam.classLoader);
            if (layout == null) {
                XposedBridge.log("[LiquidGlass][UU] native navigation layout contract unavailable; keeping native bar");
                return;
            }
            Object emptyModifier = Class.forName("H0.l", false, lpparam.classLoader)
                    .getField("b").get(null);
            UuComposeNavigationLayout.Scope scope = new UuComposeNavigationLayout.Scope();
            boolean enabled = TargetAppHookSettingsReader.load(null, TargetApp.UUREMOTE).enabled;
            XposedHelpers.findAndHookMethod(
                    Class.forName("H0.a", false, lpparam.classLoader),
                    "d", "H0.o", "u0.o", new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!scope.claimRoot(param.args[1], param.args[0], emptyModifier)
                                    || param.getThrowable() != null) return;
                            Object collapsed = layout.append(param.getResult());
                            if (collapsed != null) param.setResult(collapsed);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    navigationComposable,
                    "c",
                    "H0.o",
                    "L8.L6",
                    "u0.o",
                    int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            UniversalHostAdapter.cacheUuComposeState(param.args[1]);
                            // c() applies its parameter only to the inner Row. Its outer Column
                            // and divider use Modifier.Empty, so suppress that exact root instead.
                            // Never return early from c(): preserve all Composer groups and flows.
                            if (enabled) scope.enter(param.args[2]);
                        }

                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (enabled) scope.exit();
                        }
                    }
            );
            XposedBridge.log("[LiquidGlass][UU] native Compose navigation root collapse hook installed");
        } catch (Throwable error) {
            XposedBridge.log("[LiquidGlass][UU] native Compose navigation hook unavailable: "
                    + error);
        }
    }

    private static void installWeChatSidePanelSafely(LoadPackageParam lpparam) {
        try {
            WeChatSidePanel.install(lpparam);
        } catch (Throwable error) {
            XposedBridge.log("[LiquidGlass][WeChatSidePanel] optional hook installation failed; "
                    + "original WeChat hooks remain active: " + error);
            XposedBridge.log(error);
        }
    }

    private static void installWeChatRoundAvatarsSafely(LoadPackageParam lpparam) {
        try {
            WeChatRoundAvatars.install(lpparam);
        } catch (Throwable error) {
            XposedBridge.log("[LiquidGlass][WeChatRoundAvatars] optional installation failed: " + error);
            XposedBridge.log(error);
        }
    }

    /** Mirror JD's two native visibility routes without guessing the currently shown Fragment. */
    private static void hookJdNavigationVisibility(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        // Both classic and AI holders inherit B(boolean,long). It moves the holder returned by
        // m(): the classic 65dp frame or the AI 128dp blur plate.
        Class<?> baseHolder = findClass("yb0.e", lpparam.classLoader);
        if (baseHolder != null) {
            try {
                XposedBridge.hookAllMethods(baseHolder, "B", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 2
                                || !(param.args[0] instanceof Boolean)) return;
                        Activity activity = jdHolderActivity(param.thisObject);
                        if (!matches(activity, app)) return;
                        boolean visible = ((Boolean) param.args[0]).booleanValue();
                        long duration = param.args[1] instanceof Number
                                ? ((Number) param.args[1]).longValue() : 0L;
                        updateJdNavigationVisibility(
                                activity, app, visible,
                                visible ? Math.max(0L, Math.min(duration, 2000L)) + 32L : 0L
                        );
                    }
                });
            } catch (Throwable ignored) {}
        }

        // The classic holder additionally implements D(boolean,caller) as an immediate
        // VISIBLE/GONE route. AI intentionally leaves D() empty, so hook only yb0.j here.
        Class<?> classicHolder = findClass("yb0.j", lpparam.classLoader);
        if (classicHolder != null) {
            try {
                XposedBridge.hookAllMethods(classicHolder, "D", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Boolean)) return;
                        Activity activity = jdHolderActivity(param.thisObject);
                        if (!matches(activity, app)) return;
                        boolean visible = ((Boolean) param.args[0]).booleanValue();
                        updateJdNavigationVisibility(activity, app, visible, visible ? 64L : 0L);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static Activity jdHolderActivity(Object holder) {
        Object resolved = UniversalHostAdapter.call(holder, "g");
        return resolved instanceof Activity ? (Activity) resolved : null;
    }

    private static void updateJdNavigationVisibility(
            final Activity activity,
            final TargetApp app,
            boolean visible,
            long releaseDelayMs
    ) {
        if (!matches(activity, app)) return;
        final int generation;
        synchronized (JD_NAV_VISIBILITY_GENERATIONS) {
            Integer previous = JD_NAV_VISIBILITY_GENERATIONS.get(activity);
            generation = previous == null ? 1 : previous + 1;
            JD_NAV_VISIBILITY_GENERATIONS.put(activity, generation);
        }
        if (!visible) {
            UniversalGlassInstaller.setNavigationHidden(
                    activity, JD_NAV_VISIBILITY_REASON, true);
            scheduleExactRetry(activity, app);
            return;
        }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor == null) return;
        decor.postDelayed(() -> {
            synchronized (JD_NAV_VISIBILITY_GENERATIONS) {
                Integer current = JD_NAV_VISIBILITY_GENERATIONS.get(activity);
                if (current == null || current.intValue() != generation) return;
            }
            UniversalGlassInstaller.setNavigationHidden(
                    activity, JD_NAV_VISIBILITY_REASON, false);
            scheduleExactRetry(activity, app);
        }, Math.max(0L, releaseDelayMs));
    }

    /**
     * Douyin 35.x keeps stable, non-obfuscated BottomTab controller methods even though most of
     * the surrounding homepage implementation is R8-renamed. Capture those exact controller/view
     * pairs and refresh the injected row when Douyin commits a native tab transition.
     */
    /**
     * Auto-hide the glass bottom bar while Douyin's comment panel is open, and restore it when
     * the panel closes. Hooks the shared Fragment lifecycle so inherited onResume/onPause still
     * fire for every comment panel variant.
     */
    private static void hookAwemeCommentPanels(LoadPackageParam lpparam) {
        String[] fragmentClasses = {
                "androidx.fragment.app.Fragment",
                "android.app.Fragment",
        };
        for (String fragmentName : fragmentClasses) {
            Class<?> fragment = findClass(fragmentName, lpparam.classLoader);
            if (fragment == null) continue;
            try {
                XposedBridge.hookAllMethods(fragment, "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object panel = param.thisObject;
                        if (isAwemeCommentPanel(panel)) {
                            registerAwemePanel(panel, false);
                        } else if (isAwemeEmbeddedChatPanel(panel)) {
                            registerAwemePanel(panel, true);
                        }
                    }
                });
                XC_MethodHook releaseHook = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object panel = param.thisObject;
                        if (isAwemeCommentPanel(panel)) {
                            releaseAwemePanel(panel, false);
                        } else if (isAwemeEmbeddedChatPanel(panel)) {
                            releaseAwemePanel(panel, true);
                        }
                    }
                };
                XposedBridge.hookAllMethods(fragment, "onPause", releaseHook);
                XposedBridge.hookAllMethods(fragment, "onStop", releaseHook);
                XposedBridge.hookAllMethods(fragment, "onDestroyView", releaseHook);
            } catch (Throwable ignored) {
            }
        }

        // A few Douyin comment implementations dismiss by directly replacing the fragment view,
        // which can make the ordinary Fragment callback arrive late. Re-check the tracked exact
        // panels whenever MainActivity becomes interactive again. This is only a verifier; if an
        // actual comment/chat panel is still visible the stable reason remains set.
        Class<?> mainActivity = findClass(
                "com.ss.android.ugc.aweme.main.MainActivity", lpparam.classLoader
        );
        if (mainActivity != null) {
            try {
                XposedBridge.hookAllMethods(mainActivity, "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity main = (Activity) param.thisObject;
                        LAST_AWEME_MAIN_ACTIVITY = new WeakReference<>(main);
                        scheduleAwemePanelVerification(main, 48L, 180L, 480L);
                    }
                });
            } catch (Throwable ignored) {}
            try {
                XposedBridge.hookAllMethods(mainActivity, "onWindowFocusChanged", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)
                                || param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Boolean)
                                || !((Boolean) param.args[0])) return;
                        Activity main = (Activity) param.thisObject;
                        LAST_AWEME_MAIN_ACTIVITY = new WeakReference<>(main);
                        scheduleAwemePanelVerification(main, 32L, 140L, 360L);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static Object reflectGetActivity(Object fragment) {
        try {
            java.lang.reflect.Method getActivity = fragment.getClass().getMethod("getActivity");
            return getActivity.invoke(fragment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object reflectGetView(Object fragment) {
        try {
            java.lang.reflect.Method getView = fragment.getClass().getMethod("getView");
            return getView.invoke(fragment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Activity resolveAwemeMainActivity(Activity owner) {
        Activity main = LAST_AWEME_MAIN_ACTIVITY == null ? null : LAST_AWEME_MAIN_ACTIVITY.get();
        if (main != null && !main.isFinishing() && !main.isDestroyed()) return main;
        if (owner != null && owner.getClass().getName().equals(
                "com.ss.android.ugc.aweme.main.MainActivity")) {
            LAST_AWEME_MAIN_ACTIVITY = new WeakReference<>(owner);
            return owner;
        }
        return null;
    }

    private static void registerAwemePanel(Object fragment, boolean embeddedChat) {
        if (fragment == null || !isFragmentActuallyVisible(fragment)) return;
        Object owner = reflectGetActivity(fragment);
        if (!(owner instanceof Activity)) return;
        Activity main = resolveAwemeMainActivity((Activity) owner);
        if (main == null) return;
        Map<Object, WeakReference<Activity>> owners = embeddedChat
                ? AWEME_CHAT_FRAGMENT_OWNERS : AWEME_COMMENT_OWNERS;
        owners.put(fragment, new WeakReference<>(main));
        UniversalGlassInstaller.setNavigationHidden(
                main, embeddedChat ? AWEME_EMBEDDED_CHAT_VISIBILITY_REASON
                        : AWEME_COMMENT_VISIBILITY_REASON, true
        );
        if (embeddedChat) {
            scheduleAwemeChatComposerRefresh(main, 0L, 64L, 180L, 420L);
        }
        scheduleAwemePanelVerification(main, 1000L);
    }

    private static void releaseAwemePanel(Object fragment, boolean embeddedChat) {
        Map<Object, WeakReference<Activity>> owners = embeddedChat
                ? AWEME_CHAT_FRAGMENT_OWNERS : AWEME_COMMENT_OWNERS;
        WeakReference<Activity> reference = owners.remove(fragment);
        Activity owner = reference == null ? null : reference.get();
        if (owner == null) {
            Object reflected = reflectGetActivity(fragment);
            if (reflected instanceof Activity) owner = resolveAwemeMainActivity((Activity) reflected);
        }
        if (owner == null) owner = resolveAwemeMainActivity(null);
        if (owner == null) return;
        // Do not directly clear a per-fragment reason. A single stable reason represents the
        // whole surface; after a short frame delay we verify whether any cooperating fragment is
        // still truly on screen and only then restore the bar.
        scheduleAwemePanelVerification(owner, 32L, 120L, 280L);
    }

    private static void scheduleAwemePanelVerification(
            Activity activity, long... delays
    ) {
        if (activity == null || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (decor == null) return;
        for (long delay : delays) {
            decor.postDelayed(() -> verifyAwemePanelVisibility(activity), Math.max(0L, delay));
        }
    }

    private static void verifyAwemePanelVisibility(Activity main) {
        if (main == null || main.isFinishing() || main.isDestroyed()) return;
        boolean commentVisible = pruneAndCheckAwemePanels(AWEME_COMMENT_OWNERS, main);
        boolean chatVisible = pruneAndCheckAwemePanels(AWEME_CHAT_FRAGMENT_OWNERS, main);
        UniversalGlassInstaller.setNavigationHidden(
                main, AWEME_COMMENT_VISIBILITY_REASON, commentVisible
        );
        UniversalGlassInstaller.setNavigationHidden(
                main, AWEME_EMBEDDED_CHAT_VISIBILITY_REASON, chatVisible
        );
        if (chatVisible) {
            UniversalGlassInstaller.refreshDouyinChatComposerPosition(main);
        }
    }

    private static boolean pruneAndCheckAwemePanels(
            Map<Object, WeakReference<Activity>> owners, Activity main
    ) {
        boolean visible = false;
        synchronized (owners) {
            java.util.ArrayList<Object> stale = new java.util.ArrayList<>();
            for (Map.Entry<Object, WeakReference<Activity>> entry : owners.entrySet()) {
                Object fragment = entry.getKey();
                Activity owner = entry.getValue() == null ? null : entry.getValue().get();
                if (fragment == null || owner == null || owner != main
                        || !isFragmentActuallyVisible(fragment)) {
                    stale.add(fragment);
                    continue;
                }
                visible = true;
            }
            for (Object fragment : stale) owners.remove(fragment);
        }
        return visible;
    }

    private static boolean isFragmentActuallyVisible(Object fragment) {
        if (fragment == null) return false;
        try {
            Class<?> type = fragment.getClass();
            java.lang.reflect.Method isAdded = type.getMethod("isAdded");
            java.lang.reflect.Method isResumed = type.getMethod("isResumed");
            if (!Boolean.TRUE.equals(isAdded.invoke(fragment))
                    || !Boolean.TRUE.equals(isResumed.invoke(fragment))) return false;

            Object resolved = reflectGetView(fragment);
            if (resolved instanceof View) {
                View view = (View) resolved;
                if (!view.isAttachedToWindow() || !view.isShown() || view.getAlpha() <= 0.01f
                        || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
                Rect rect = new Rect();
                return view.getGlobalVisibleRect(rect) && rect.width() > 8 && rect.height() > 8;
            }

            // DialogFragment can temporarily have no root returned by getView(); use its own
            // visibility bit only as a fallback while it is still added+resumed.
            try {
                java.lang.reflect.Method isVisible = type.getMethod("isVisible");
                return Boolean.TRUE.equals(isVisible.invoke(fragment));
            } catch (Throwable ignored) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAwemeCommentPanel(Object fragment) {
        if (fragment == null) return false;
        String name = fragment.getClass().getName();
        return name.equals("com.ss.android.ugc.aweme.comment.ui.CommentListFragment")
                || name.equals("com.ss.android.ugc.aweme.comment.ui.CommentListPageFragment")
                || name.equals("com.ss.android.ugc.aweme.comment.ui.CommentFeedDialogFragment")
                || name.equals("com.ss.android.ugc.aweme.comment.ui.CommentListPageDialogFragment")
                || name.equals("com.ss.android.ugc.aweme.comment.commentlist.kmp.KmpCommentListFragment")
                || name.equals("com.ss.android.ugc.aweme.comment.ui.PadSplitCommentListPageFragment")
                || name.equals("com.ss.android.ugc.aweme.comment.ui.CommentInputFragment")
                || name.equals("com.ss.android.ugc.aweme.comment.ui.CommentInputDialogFragment");
    }

    private static boolean isAwemeEmbeddedChatPanel(Object fragment) {
        if (fragment == null) return false;
        String name = fragment.getClass().getName();
        return name.equals("com.ss.android.ugc.aweme.im.business.chat.root.RipsChatRoomFragment")
                || name.equals("com.ss.android.ugc.aweme.im.business.chat.root.RipsChatRoomFullScreenFragment")
                || name.equals("com.ss.android.ugc.aweme.im.business.chat.root.RipsHalfChatRoomFragment");
    }

    private static void scheduleAwemeChatComposerRefresh(Activity main, long... delays) {
        if (main == null || main.getWindow() == null) return;
        View decor = main.getWindow().getDecorView();
        if (decor == null) return;
        for (long delay : delays) {
            decor.postDelayed(
                    () -> UniversalGlassInstaller.refreshDouyinChatComposerPosition(main),
                    Math.max(0L, delay)
            );
        }
    }

    /**
     * Auto-hide the MainActivity glass bar while a friend chat room is in the foreground.
     * ChatRoomActivity is a separate activity; the bar belongs to the main activity behind it.
     */
    private static void hookAwemeChatRooms(LoadPackageParam lpparam) {
        String[] rooms = {
                "com.ss.android.ugc.aweme.im.business.chat.root.BaseChatRoomActivity",
                "com.ss.android.ugc.aweme.im.business.chat.root.ChatRoomActivity",
                "com.ss.android.ugc.aweme.im.business.chat.root.HalfChatRoomActivity",
                "com.ss.android.ugc.aweme.im.business.chat.root.ChatRoomActivityTrans",
                "com.ss.android.ugc.aweme.im.sdk.module.gagas.GagasMatchSessionActivity",
                "com.ss.android.ugc.aweme.im.sdk.module.shanhai.activity.DSChatroomSessionActivity",
                "com.ss.android.ugc.aweme.im.sdk.module.shanhai.activity.ShanHaiSessionActivity",
        };
        for (String room : rooms) {
            Class<?> clazz = findClass(room, lpparam.classLoader);
            if (clazz == null) continue;
            try {
                XposedBridge.hookAllMethods(clazz, "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity chatActivity = (Activity) param.thisObject;
                        setMainAwemeNavHidden(chatActivity, true);
                    }
                });
                XposedBridge.hookAllMethods(clazz, "onStop", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity chatActivity = (Activity) param.thisObject;
                        setMainAwemeNavHidden(chatActivity, false);
                    }
                });
                XposedBridge.hookAllMethods(clazz, "onDestroy", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        setMainAwemeNavHidden(param.thisObject, false);
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private static void setMainAwemeNavHidden(Object reason, boolean hidden) {
        Activity main = LAST_AWEME_MAIN_ACTIVITY == null ? null : LAST_AWEME_MAIN_ACTIVITY.get();
        if (main != null && !main.isFinishing() && !main.isDestroyed()) {
            UniversalGlassInstaller.setNavigationHidden(main, reason, hidden);
        }
    }

    private static void hookAwemeBottomTabs(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        Class<?> bottomTab = findClass(
                "com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab",
                lpparam.classLoader
        );
        if (bottomTab != null) {
            try {
                XposedHelpers.findAndHookMethod(bottomTab, "getView", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.getResult() instanceof View)) return;
                        View view = (View) param.getResult();
                        boolean changed = UniversalHostAdapter.registerAwemeBottomTab(
                                param.thisObject, view
                        );
                        if (!changed) return;
                        Activity activity = ActivityFinder.from(view.getContext());
                        if (matches(activity, app)) scheduleExactRetry(activity, app);
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[LiquidGlass][26.31] Douyin BottomTab#getView hook unavailable: " + t);
            }

            // Remote configuration can rebuild a slot's title/identity without replacing the
            // BottomTab object. Re-register the same exact view after those public mutations.
            for (String methodName : new String[]{"setTabText", "setTabDesc"}) {
                try {
                    XposedBridge.hookAllMethods(bottomTab, methodName, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object resolved = UniversalHostAdapter.call(param.thisObject, "getView");
                            if (!(resolved instanceof View)) return;
                            View view = (View) resolved;
                            UniversalHostAdapter.registerAwemeBottomTab(param.thisObject, view);
                            Activity activity = ActivityFinder.from(view.getContext());
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }

        // The uploaded Douyin APK exposes the real center plus control as this concrete View.
        // Capture the actual instance so LiquidGlass can re-parent it unchanged into the glass row
        // instead of drawing a fake "发视频" label/icon.
        Class<?> publishButton = findClass(
                "com.ss.android.ugc.aweme.tools.external.hometab.bottom.PublishButton",
                lpparam.classLoader
        );
        if (publishButton != null && View.class.isAssignableFrom(publishButton)) {
            try {
                XposedBridge.hookAllConstructors(publishButton, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;
                        View view = (View) param.thisObject;
                        UniversalHostAdapter.registerAwemePublishButton(view);
                        view.post(() -> {
                            Activity activity = ActivityFinder.from(view.getContext());
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        });
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[LiquidGlass][26.31] Douyin PublishButton constructor hook unavailable: " + t);
            }
            try {
                XposedBridge.hookAllMethods(publishButton, "onAttachedToWindow", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;
                        View view = (View) param.thisObject;
                        UniversalHostAdapter.registerAwemePublishButton(view);
                        Activity activity = ActivityFinder.from(view.getContext());
                        if (matches(activity, app)) scheduleExactRetry(activity, app);
                    }
                });
            } catch (Throwable ignored) {}

            // Capture Douyin's exact publish OnClickListener so the injected circular "+" can
            // invoke it directly even when the native button is re-parented or hidden.
            try {
                XposedBridge.hookAllMethods(publishButton, "setOnClickListener", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof View.OnClickListener) {
                            UniversalHostAdapter.registerAwemePublishListener(
                                    (View.OnClickListener) param.args[0]
                            );
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        // The feed item's right-side interaction rail (avatar / 点赞 / 评论 / 收藏 / 分享) is
        // passed into FeedItemRightModule as its first constructor argument. Capture the exact
        // container so LiquidGlass can lift it back above the glass bar after overscanning the
        // feed content to make the video fill the screen.
        Class<?> rightModule = findClass(
                "com.ss.android.ugc.aweme.feed.quick.uimodule.FeedItemRightModule",
                lpparam.classLoader
        );
        if (rightModule != null) {
            try {
                XposedBridge.hookAllConstructors(rightModule, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof View)) return;
                        final View rail = (View) param.args[0];
                        UniversalHostAdapter.registerAwemeRightRail(rail);
                        rail.post(() -> {
                            Activity activity = ActivityFinder.from(rail.getContext());
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        });
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[LiquidGlass][26.31] Douyin FeedItemRightModule hook unavailable: " + t);
            }
        }

        // Bottom-left author/作品/简介/合集 block. Same constructor pattern as the right rail.
        Class<?> leftModule = findClass(
                "com.ss.android.ugc.aweme.feed.quick.uimodule.FeedItemLeftModule",
                lpparam.classLoader
        );
        if (leftModule != null) {
            try {
                XposedBridge.hookAllConstructors(leftModule, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof View)) return;
                        final View info = (View) param.args[0];
                        UniversalHostAdapter.registerAwemeLeftInfo(info);
                        info.post(() -> {
                            Activity activity = ActivityFinder.from(info.getContext());
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        });
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[LiquidGlass][26.31] Douyin FeedItemLeftModule hook unavailable: " + t);
            }
        }

        // Bottom-left music disc block. Same constructor pattern.
        Class<?> musicModule = findClass(
                "com.ss.android.ugc.aweme.feed.quick.uimodule.FeedVideoMusicBlockModule",
                lpparam.classLoader
        );
        if (musicModule != null) {
            try {
                XposedBridge.hookAllConstructors(musicModule, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof View)) return;
                        final View music = (View) param.args[0];
                        UniversalHostAdapter.registerAwemeMusicBlock(music);
                        music.post(() -> {
                            Activity activity = ActivityFinder.from(music.getContext());
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        });
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[LiquidGlass][26.31] Douyin FeedVideoMusicBlockModule hook unavailable: " + t);
            }
        }

        // 合集 (collection/series) block at the bottom. Same constructor pattern.
        Class<?> mixModule = findClass(
                "com.ss.android.ugc.aweme.feed.quick.uimodule.FeedBottomMixModule",
                lpparam.classLoader
        );
        if (mixModule != null) {
            try {
                XposedBridge.hookAllConstructors(mixModule, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof View)) return;
                        final View mix = (View) param.args[0];
                        UniversalHostAdapter.registerAwemeMixBlock(mix);
                        mix.post(() -> {
                            Activity activity = ActivityFinder.from(mix.getContext());
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        });
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[LiquidGlass][26.31] Douyin FeedBottomMixModule hook unavailable: " + t);
            }
        }

        // 拍同款 (make-same-video) block, shown when the music disc is absent. Same pattern.
        Class<?> cutSameModule = findClass(
                "com.ss.android.ugc.aweme.feed.quick.uimodule.FeedBottomVideoCutSameModule",
                lpparam.classLoader
        );
        if (cutSameModule != null) {
            try {
                XposedBridge.hookAllConstructors(cutSameModule, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof View)) return;
                        final View cutSame = (View) param.args[0];
                        UniversalHostAdapter.registerAwemeCutSameBlock(cutSame);
                        cutSame.post(() -> {
                            Activity activity = ActivityFinder.from(cutSame.getContext());
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        });
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log("[LiquidGlass][26.31] Douyin FeedBottomVideoCutSameModule hook unavailable: " + t);
            }
        }

        Class<?> component = findClass(
                "com.ss.android.ugc.aweme.homepage.tab.bottom.MPFBottomTabComponent",
                lpparam.classLoader
        );
        if (component != null) {
            for (String methodName : new String[]{"onNavigateToTab", "P3"}) {
                try {
                    XposedBridge.hookAllMethods(component, methodName, new XC_MethodHook() {
                        private Activity ownerActivity(Object owner) {
                            Object resolved = UniversalHostAdapter.call(owner, "getContainerView");
                            return resolved instanceof View
                                    ? ActivityFinder.from(((View) resolved).getContext()) : null;
                        }

                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = ownerActivity(param.thisObject);
                            if (matches(activity, app)) {
                                UniversalGlassInstaller.notifyHostPageTransition(activity, app);
                            }
                        }

                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = ownerActivity(param.thisObject);
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * PhoneMainFragment owns all three root tabs. File Explorer hides that one Fragment whenever
     * it pushes a folder, search or other secondary destination, including destinations that
     * deliberately reuse a root navigation id. Refresh from this exact visibility callback so the
     * injected row does not wait for the one-second safety poll.
     */
    private static void hookFileManagerPageVisibility(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        Class<?> main = findClass(
                "com.android.fileexplorer.fragment.PhoneMainFragment",
                lpparam.classLoader
        );
        if (main == null) return;
        try {
            XposedBridge.hookAllMethods(main, "onVisibilityChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = fileManagerCallbackActivity(param.thisObject);
                    if (!matches(activity, app)) return;
                    UniversalGlassInstaller.refreshHostVisibility(activity);
                    View decor = activity.getWindow() == null
                            ? null : activity.getWindow().getDecorView();
                    if (decor != null) {
                        decor.postOnAnimation(() ->
                                UniversalGlassInstaller.refreshHostVisibility(activity));
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void hookFileManagerSelectionMode(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        final String[] callbackClasses = new String[]{
                "com.android.fileexplorer.adapter.recycle.modecallback.BaseMultiChoiceCallback",
                "com.android.fileexplorer.adapter.recycle.modecallback.MultiChoiceCallback",
                "com.android.fileexplorer.adapter.recycle.modecallback.FileMultiChoiceCallback",
                "com.android.fileexplorer.adapter.recycle.modecallback.GroupMultiChoiceCallback",
                "com.android.fileexplorer.adapter.recycle.modecallback.SearchMultiChoiceCallback",
                "com.android.fileexplorer.adapter.recycle.modecallback.CategoryMultiChoiceCallback",
                "com.android.fileexplorer.adapter.recycle.modecallback.FavoriteMultiCallback",
                "com.android.fileexplorer.adapter.recycle.modecallback.NasMultiChoiceCallback",
                "com.android.fileexplorer.adapter.recycle.modecallback.PrivateMultiChoiceCallback",
                "com.android.fileexplorer.fragment.file.home.HomeFileMultiChoiceCallback",
                "com.android.cloud.recyclerview.CloudFileMultiChoiceCallback",
                // Recent/secondary/category pages use the older checkable-adapter listener path.
                "com.android.fileexplorer.listener.choice.AbsMultiChoiceListener",
                "com.android.fileexplorer.listener.choice.FileOpMultiChoiceListener",
                "com.android.cloud.listener.choice.CloudOpMultiChoiceListener"
        };

        XC_MethodHook modeReady = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = fileManagerCallbackActivity(param.thisObject);
                if (!matches(activity, app)) return;
                Object mode = param.args != null && param.args.length > 0
                        ? param.args[0] : null;
                if (!isFileManagerOperationActionMode(activity, mode)) return;
                UniversalGlassInstaller.setFileManagerSelectionMode(
                        activity, true, param.thisObject, mode);
            }
        };

        XC_MethodHook modeUpdated = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = fileManagerCallbackActivity(param.thisObject);
                if (!matches(activity, app)) return;
                Object mode = param.args != null && param.args.length > 0
                        ? param.args[0] : null;
                if (!isFileManagerOperationActionMode(activity, mode)) return;
                UniversalGlassInstaller.refreshFileManagerSelectionMode(
                        activity, param.thisObject, mode);
            }
        };

        XC_MethodHook leave = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = fileManagerCallbackActivity(param.thisObject);
                if (!matches(activity, app)) return;
                Object mode = param.args != null && param.args.length > 0
                        ? param.args[0] : null;
                UniversalGlassInstaller.finishFileManagerSelectionMode(
                        activity, param.thisObject, mode);
                View decor = activity.getWindow() == null
                        ? null : activity.getWindow().getDecorView();
                if (decor != null) {
                    decor.postDelayed(() ->
                            UniversalGlassInstaller.refreshHostVisibility(activity), 48L);
                    decor.postDelayed(() ->
                            UniversalGlassInstaller.refreshHostVisibility(activity), 180L);
                }
            }
        };

        boolean hookedExact = hookFileManagerMiuixActionMode(lpparam, app);
        boolean hookedAny = false;
        if (!hookedExact) {
            // Compatibility only. Hooking both every callback superclass/subclass and the exact
            // Miuix ActionMode caused the same long-press to traverse the whole Decor repeatedly.
            for (String className : callbackClasses) {
                try {
                    Class<?> callback = findClass(className, lpparam.classLoader);
                    if (callback == null) continue;
                    hookedAny = true;

                    // Enter only after the real menu exists and has been validated below. A pending
                    // startActionMode without a Menu also occurs for Private/Favorite action sets
                    // and must not be replaced by the ordinary Send/Move/Delete row.
                    XposedBridge.hookAllMethods(callback, "onCreateActionMode", modeReady);
                    XposedBridge.hookAllMethods(callback, "onPrepareActionMode", modeUpdated);
                    XposedBridge.hookAllMethods(callback, "onItemCheckedStateChanged", modeUpdated);
                    XposedBridge.hookAllMethods(callback, "onAllItemCheckedStateChanged", modeUpdated);
                    XposedBridge.hookAllMethods(callback, "onDestroyActionMode", leave);
                    XposedBridge.hookAllMethods(callback, "exitActionMode", leave);
                    XposedBridge.hookAllMethods(callback, "exitActionModeNotTimeInterval", leave);
                } catch (Throwable ignored) {
                    // APK variants may omit individual callback subclasses.
                }
            }
        }
        if (!hookedAny && !hookedExact) {
            XposedBridge.log("[LiquidGlass][3.38] File Manager selection hooks not found");
        }
    }

    private static boolean hookFileManagerMiuixActionMode(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        boolean hooked = false;
        try {
            Class<?> actionModeImpl = findClass(
                    "miuix.appcompat.internal.view.ActionModeImpl", lpparam.classLoader);
            if (actionModeImpl != null) {
                hooked = true;
                // Do not enter from dispatchOnCreate(): ActionBarContextView has not created its
                // ResponsiveActionMenuView yet. initForMode below is the first exact, scan-free
                // point at which the native action row can be captured and suppressed.
                XposedBridge.hookAllMethods(actionModeImpl, "invalidate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = fileManagerCallbackActivity(param.thisObject);
                        if (!matches(activity, app)
                                || !isFileManagerOperationActionMode(activity, param.thisObject)) {
                            return;
                        }
                        UniversalGlassInstaller.refreshFileManagerSelectionMode(
                                activity, null, param.thisObject);
                    }
                });
                XposedBridge.hookAllMethods(actionModeImpl, "finish", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = fileManagerCallbackActivity(param.thisObject);
                        if (!matches(activity, app)
                                || !isFileManagerOperationActionMode(activity, param.thisObject)) {
                            return;
                        }
                        UniversalGlassInstaller.finishFileManagerSelectionMode(
                                activity, null, param.thisObject);
                    }
                });
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> contextView = findClass(
                    "miuix.appcompat.internal.app.widget.ActionBarContextView",
                    lpparam.classLoader);
            if (contextView != null) {
                hooked = true;
                XposedBridge.hookAllMethods(contextView, "initForMode", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object mode = param.args != null && param.args.length > 0
                                ? param.args[0] : null;
                        Activity activity = fileManagerCallbackActivity(mode);
                        if (activity == null && param.thisObject instanceof View) {
                            activity = ActivityFinder.from(((View) param.thisObject).getContext());
                        }
                        if (!matches(activity, app)) return;
                        if (isFileManagerOperationActionMode(activity, mode)) {
                            UniversalGlassInstaller.bindFileManagerActionModeView(
                                    activity,
                                    mode,
                                    param.thisObject instanceof View
                                            ? (View) param.thisObject : null
                            );
                        } else {
                            // Private/Favorite action sets keep their native contextual menu, but
                            // the ordinary glass navigation must disappear immediately underneath.
                            UniversalGlassInstaller.refreshHostVisibility(activity);
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}
        return hooked;
    }

    private static boolean isFileManagerOperationActionMode(Activity activity, Object mode) {
        if (activity == null || !(mode instanceof android.view.ActionMode)) return false;
        android.view.Menu menu;
        try { menu = ((android.view.ActionMode) mode).getMenu(); }
        catch (Throwable ignored) { return false; }
        if (menu == null) return false;
        try {
            int send = activity.getResources().getIdentifier(
                    "action_send", "id", TargetApp.MI_FILE_MANAGER.packageName);
            int decrypt = activity.getResources().getIdentifier(
                    "action_decrypt", "id", TargetApp.MI_FILE_MANAGER.packageName);
            int move = activity.getResources().getIdentifier(
                    "action_move", "id", TargetApp.MI_FILE_MANAGER.packageName);
            int delete = activity.getResources().getIdentifier(
                    "action_delete", "id", TargetApp.MI_FILE_MANAGER.packageName);
            boolean primary = (send != 0 && menu.findItem(send) != null)
                    || (decrypt != 0 && menu.findItem(decrypt) != null);
            return primary && move != 0 && menu.findItem(move) != null
                    && delete != 0 && menu.findItem(delete) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Activity fileManagerCallbackActivity(Object callback) {
        return activityFromFileManagerObject(callback, 0);
    }

    private static Activity activityFromFileManagerObject(Object value, int depth) {
        if (value == null || depth > 2) return null;
        if (value instanceof Activity) return (Activity) value;
        if (value instanceof android.content.Context) {
            Activity activity = ActivityFinder.from((android.content.Context) value);
            if (activity != null) return activity;
        }
        if (value instanceof View) {
            Activity activity = ActivityFinder.from(((View) value).getContext());
            if (activity != null) return activity;
        }

        for (String methodName : new String[]{
                "getActivity", "requireActivity", "getContext", "requireContext"
        }) {
            try {
                java.lang.reflect.Method method = value.getClass().getMethod(methodName);
                method.setAccessible(true);
                Object nested = method.invoke(value);
                Activity activity = activityFromFileManagerObject(nested, depth + 1);
                if (activity != null) return activity;
            } catch (Throwable ignored) {}
        }

        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                String name = field.getName();
                if (!("mActivity".equals(name)
                        || "activity".equalsIgnoreCase(name)
                        || "mFragment".equals(name)
                        || "fragment".equalsIgnoreCase(name)
                        || "mContext".equals(name)
                        || "context".equalsIgnoreCase(name))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Activity activity = activityFromFileManagerObject(
                            field.get(value), depth + 1);
                    if (activity != null) return activity;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }


    private static void hookGalleryNavigatorLifecycle(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        // DEX audit of the supplied Gallery APK shows that the visual row is managed across both
        // MiuixNavigatorImpl and its NavigatorImpl base. Hook every concrete create/add/setup/
        // visibility stage so the exact native row is made transparent before its next draw.
        String[] classNames = {
                "miuix.navigator.app.MiuixNavigatorImpl",
                "miuix.navigator.NavigatorImpl"
        };
        String[] methods = {
                "setBottomTabMenu", "ensureBottomNavigationExist", "initExtraViews", "selectTab",
                "addBottomNavigation", "removeBottomNavigation", "setBottomNavigation",
                "setupBottomNavigation", "onBottomNavigationChanged",
                "onBottomNavigationPrepared", "onBottomNavigationVisibilityChanged"
        };
        for (String className : classNames) {
            Class<?> type = findClass(className, lpparam.classLoader);
            if (type == null) continue;
            for (String methodName : methods) {
                try {
                    XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            View bottomTab = resolveGalleryBottomNavigation(param.thisObject);
                            if (bottomTab == null) return;
                            // This is synchronous and does not wait for the scheduled installer.
                            UniversalGlassInstaller.makeGalleryNativeNavigationTransparent(bottomTab);
                            Activity activity = ActivityFinder.from(bottomTab.getContext());
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        }
                    });
                } catch (Throwable ignored) {}
            }
        }
    }

    private static View resolveGalleryBottomNavigation(Object navigator) {
        if (navigator == null) return null;
        Object bottomTab = UniversalHostAdapter.call(navigator, "getBottomTabView");
        if (!(bottomTab instanceof View)) {
            bottomTab = UniversalHostAdapter.getField(
                    navigator, "mBottomNavigationView", "mBottomNavigation"
            );
        }
        if (!(bottomTab instanceof View)) {
            Object navigationView = UniversalHostAdapter.call(navigator, "getNavigationView");
            bottomTab = UniversalHostAdapter.call(navigationView, "getBottomNavigation");
            if (!(bottomTab instanceof View)) {
                bottomTab = UniversalHostAdapter.getField(
                        navigationView, "mBottomNavigationView", "mBottomNavigation"
                );
            }
        }
        return bottomTab instanceof View ? (View) bottomTab : null;
    }


    /** Exact 8.0.72 page commits plus WAA-style early chat hiding without global View hooks. */
    private static void hookWeChatSelectionSignals(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        Class<?> bottomBar = findClass(
                "com.tencent.mm.ui.LauncherUIBottomTabView", lpparam.classLoader);
        if (bottomBar != null) {
            try {
                XposedBridge.hookAllMethods(bottomBar, "setTo", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;
                        Activity activity = ActivityFinder.from(
                                ((View) param.thisObject).getContext());
                        if (matches(activity, app)) {
                            UniversalGlassInstaller.notifyHostPageTransition(activity, app);
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View) || param.args == null
                                || param.args.length == 0 || !(param.args[0] instanceof Number)) return;
                        Activity activity = ActivityFinder.from(
                                ((View) param.thisObject).getContext());
                        if (!matches(activity, app)) return;
                        UniversalGlassInstaller.syncWeChatSelection(
                                activity, ((Number) param.args[0]).intValue());
                        scheduleExactRetry(activity, app);
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> launcher = findClass("com.tencent.mm.ui.LauncherUI", lpparam.classLoader);
        if (launcher != null) {
            try {
                XposedBridge.hookAllMethods(launcher, "startChatting", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity activity = (Activity) param.thisObject;
                        if (!matches(activity, app)) return;
                        UniversalGlassInstaller.setNavigationHidden(
                                activity, WECHAT_CHAT_TRANSITION_REASON, true);
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity activity = (Activity) param.thisObject;
                        if (!matches(activity, app) || activity.getWindow() == null) return;
                        View decor = activity.getWindow().getDecorView();
                        if (decor != null) decor.postDelayed(() -> {
                            // q(8) has already installed the stable session token when the chat
                            // committed. Clearing only this short transition reason also repairs a
                            // cancelled/failed startChatting call without exposing an open chat.
                            UniversalGlassInstaller.setNavigationHidden(
                                    activity, WECHAT_CHAT_TRANSITION_REASON, false);
                            UniversalGlassInstaller.refreshHostVisibility(activity);
                            scheduleExactRetry(activity, app);
                        }, 900L);
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> tabsAdapter = findClass(
                "com.tencent.mm.ui.MainTabUI$TabsAdapter", lpparam.classLoader);
        if (tabsAdapter != null) {
            try {
                XposedBridge.hookAllMethods(tabsAdapter, "onPageSelected", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Activity activity = LAST_WECHAT_LAUNCHER.get();
                        if (matches(activity, app)) {
                            UniversalGlassInstaller.notifyHostPageTransition(activity, app);
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        syncWeChatSelection(param, app);
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> mainTabUi = findClass("com.tencent.mm.ui.MainTabUI", lpparam.classLoader);
        if (mainTabUi != null) {
            try {
                // 8.0.72 MainTabUI.d() creates/attaches LauncherUIBottomTabView and then binds
                // TabsAdapter. Installing after this exact commit avoids a cold-start native flash.
                XposedBridge.hookAllMethods(mainTabUi, "d", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = LAST_WECHAT_LAUNCHER.get();
                        if (matches(activity, app)) scheduleExactRetry(activity, app);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void syncWeChatSelection(XC_MethodHook.MethodHookParam param, TargetApp app) {
        if (param == null || param.args == null || param.args.length == 0
                || !(param.args[0] instanceof Number)) return;
        Activity activity = param.thisObject instanceof Activity
                ? (Activity) param.thisObject : LAST_WECHAT_LAUNCHER.get();
        if (!matches(activity, app)) return;
        UniversalGlassInstaller.syncWeChatSelection(
                activity, ((Number) param.args[0]).intValue());
        scheduleExactRetry(activity, app);
    }

    private static void hookWeChatChatVisibility(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        Class<?> newChattingTab = findClass(
                "com.tencent.mm.ui.NewChattingTabUI", lpparam.classLoader);
        if (newChattingTab != null) {
            try {
                XposedBridge.hookAllMethods(newChattingTab, "r", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Activity activity = LAST_WECHAT_LAUNCHER.get();
                        if (!matches(activity, app)) return;
                        UniversalGlassInstaller.setNavigationHidden(
                                activity, WECHAT_CHAT_TRANSITION_REASON, true);
                    }
                });
            } catch (Throwable ignored) {}
            try {
                XposedBridge.hookAllMethods(newChattingTab, "q", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Number)) return;
                        Activity activity = LAST_WECHAT_LAUNCHER.get();
                        if (!matches(activity, app)) return;
                        int visibility = ((Number) param.args[0]).intValue();
                        UniversalGlassInstaller.setNavigationHidden(
                                activity, WECHAT_CHAT_VISIBILITY_REASON,
                                visibility != View.VISIBLE);
                        // q() is the commit boundary for both opening and closing. Whichever value
                        // it publishes, the early transition token has finished its job.
                        UniversalGlassInstaller.setNavigationHidden(
                                activity, WECHAT_CHAT_TRANSITION_REASON, false);
                        scheduleExactRetry(activity, app);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    /**
     * WeChat 8.0.72 keeps the mini-program pull-down desktop inside LauncherUI, so Activity and
     * chat callbacks do not change while the full-screen "Recent" task bar is open. TaskBarView's
     * q implementation exposes two exact commit callbacks: l() logs onOpenHeader, while o(reason,
     * duration) logs onCloseHeader (the drag-close w(reason) path delegates to o(reason, 0)). Keep
     * this as an independent hide reason so it composes with the embedded-chat reason.
     */
    private static void hookWeChatTaskBarVisibility(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        Class<?> taskBarContainer = findClass(
                "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer", lpparam.classLoader);
        if (taskBarContainer != null) {
            try {
                XposedBridge.hookAllMethods(taskBarContainer, "g", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Boolean)) return;
                        Activity activity = weChatLauncherForView(param.thisObject, app);
                        if (activity == null) return;
                        // z.onScroll calls g(true) when the pulled header crosses Q/2 and g(false)
                        // when it falls below that threshold. This separate transition reason
                        // removes the injected row during the visible drag while allowing a
                        // cancelled pull to restore immediately. Once l() commits the open page,
                        // its stable reason keeps the row hidden even after g(false).
                        UniversalGlassInstaller.setNavigationHidden(
                                activity, WECHAT_TASKBAR_PULL_REASON,
                                Boolean.TRUE.equals(param.args[0]));
                        scheduleExactRetry(activity, app);
                    }
                });
            } catch (Throwable ignored) {}
            try {
                XposedBridge.hookAllMethods(
                        taskBarContainer, "onDetachedFromWindow", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = weChatLauncherForView(param.thisObject, app);
                        if (activity == null) return;
                        // A recreated/destroyed ConversationListView can remove its header without
                        // another settled-close callback. Invalidate any delayed release and drop
                        // only the two taskbar tokens; an overlapping chat token remains intact.
                        advanceWeChatTaskBarGeneration(activity);
                        UniversalGlassInstaller.setNavigationHidden(
                                activity, WECHAT_TASKBAR_PULL_REASON, false);
                        UniversalGlassInstaller.setNavigationHidden(
                                activity, WECHAT_TASKBAR_VISIBILITY_REASON, false);
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> taskBar = findClass(
                "com.tencent.mm.plugin.taskbar.ui.TaskBarView", lpparam.classLoader);
        if (taskBar == null) return;

        try {
            XposedBridge.hookAllMethods(taskBar, "l", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = weChatLauncherForView(param.thisObject, app);
                    if (activity == null) return;
                    advanceWeChatTaskBarGeneration(activity);
                    UniversalGlassInstaller.setNavigationHidden(
                            activity, WECHAT_TASKBAR_VISIBILITY_REASON, true);
                    // Transfer ownership from the drag token to the committed-open token. This
                    // also prevents a missed g(false) from leaving the temporary token behind.
                    UniversalGlassInstaller.setNavigationHidden(
                            activity, WECHAT_TASKBAR_PULL_REASON, false);
                    scheduleExactRetry(activity, app);
                }
            });
        } catch (Throwable ignored) {}

        try {
            XposedBridge.hookAllMethods(taskBar, "o", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    final View taskBarView = (View) param.thisObject;
                    final Activity activity = weChatLauncherForView(taskBarView, app);
                    if (activity == null) return;
                    int hostDuration = 0;
                    if (param.args != null && param.args.length > 1
                            && param.args[1] instanceof Number) {
                        hostDuration = ((Number) param.args[1]).intValue();
                    }
                    final int generation = advanceWeChatTaskBarGeneration(activity);
                    // o() is emitted when the close animation is scheduled. Keep the injected row
                    // hidden through that host-supplied duration, then restore on the next frame.
                    long restoreDelay = Math.min(1000L, Math.max(0L, hostDuration)) + 32L;
                    taskBarView.postDelayed(() -> {
                        if (!isCurrentWeChatTaskBarGeneration(activity, generation)) return;
                        WECHAT_TASKBAR_GENERATIONS.remove(activity);
                        UniversalGlassInstaller.setNavigationHidden(
                                activity, WECHAT_TASKBAR_VISIBILITY_REASON, false);
                        UniversalGlassInstaller.refreshHostVisibility(activity);
                        scheduleExactRetry(activity, app);
                    }, restoreDelay);
                }
            });
        } catch (Throwable ignored) {}
    }

    private static Activity weChatLauncherForView(Object owner, TargetApp app) {
        Activity activity = owner instanceof View
                ? ActivityFinder.from(((View) owner).getContext()) : null;
        if (!matches(activity, app)) activity = LAST_WECHAT_LAUNCHER.get();
        return matches(activity, app) ? activity : null;
    }

    private static int advanceWeChatTaskBarGeneration(Activity activity) {
        synchronized (WECHAT_TASKBAR_GENERATIONS) {
            Integer previous = WECHAT_TASKBAR_GENERATIONS.get(activity);
            int next = previous == null || previous == Integer.MAX_VALUE ? 1 : previous + 1;
            WECHAT_TASKBAR_GENERATIONS.put(activity, next);
            return next;
        }
    }

    private static boolean isCurrentWeChatTaskBarGeneration(Activity activity, int generation) {
        synchronized (WECHAT_TASKBAR_GENERATIONS) {
            Integer current = WECHAT_TASKBAR_GENERATIONS.get(activity);
            return current != null && current == generation;
        }
    }

    /**
     * WeChat 8.0.72 draws its own full-width bottom frosted strip in FrostedContentView, separate
     * from LauncherUIBottomTabView. Once the native row moves off-screen that strip would remain as
     * the rectangular white veil seen behind the injected pill. Keep only the exact bottom blur
     * area at zero; top frosted content and ordinary child drawing remain untouched.
     */
    private static void hookWeChatFrostedContent(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        Class<?> type = findClass("com.tencent.mm.ui.FrostedContentView", lpparam.classLoader);
        if (type == null) return;
        String[] methods = {
                "a", "setBottomBlurAreaHeight", "onAttachedToWindow", "onLayout", "dispatchDraw"
        };
        for (String methodName : methods) {
            final boolean dispatchDraw = "dispatchDraw".equals(methodName);
            try {
                XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!dispatchDraw) return;
                        suppress(param);
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (dispatchDraw) return;
                        suppress(param);
                    }

                    private void suppress(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;
                        View view = (View) param.thisObject;
                        Activity activity = ActivityFinder.from(view.getContext());
                        if (!matches(activity, app)) return;
                        UniversalGlassInstaller.onWeChatFrostedContentMutated(view);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void hookTimFragmentMode(
            LoadPackageParam lpparam,
            String className,
            final boolean conversationMode,
            String... methodNames
    ) {
        Class<?> type = findClass(className, lpparam.classLoader);
        if (type == null) return;
        for (String methodName : methodNames) {
            try {
                XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object owner = param.thisObject;
                        Object resolved = UniversalHostAdapter.call(owner, "getActivity");
                        if (!(resolved instanceof Activity)) {
                            resolved = UniversalHostAdapter.call(owner, "getBaseActivity");
                        }
                        if (resolved instanceof Activity
                                && matches((Activity) resolved, TargetApp.TIM)) {
                            UniversalGlassInstaller.setTimConversationMode(
                                    (Activity) resolved, conversationMode
                            );
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void hookTimActivityHomeMethods(
            LoadPackageParam lpparam,
            String className,
            String... methodNames
    ) {
        Class<?> type = findClass(className, lpparam.classLoader);
        if (type == null) return;
        for (String methodName : methodNames) {
            try {
                XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity
                                && matches((Activity) param.thisObject, TargetApp.TIM)) {
                            UniversalGlassInstaller.setTimConversationMode(
                                    (Activity) param.thisObject, false
                            );
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void hookFragmentClass(
            LoadPackageParam lpparam,
            final TargetApp app,
            String className,
            String... methodNames
    ) {
        Class<?> type = findClass(className, lpparam.classLoader);
        if (type == null) return;
        for (String methodName : methodNames) {
            try {
                XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object owner = param.thisObject;
                        Object resolved = UniversalHostAdapter.call(owner, "getActivity");
                        if (!(resolved instanceof Activity)) {
                            resolved = UniversalHostAdapter.call(owner, "getBaseActivity");
                        }
                        if (resolved instanceof Activity && matches((Activity) resolved, app)) {
                            scheduleExactRetry((Activity) resolved, app);
                            UniversalGlassInstaller.notifyHostPageTransition((Activity) resolved, app);
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    /**
     * NetEase 9.5.50 MainActivity is the concrete DrawerLayout.DrawerListener used by the left
     * hamburger menu. These public callback names are framework contracts and are not obfuscated.
     */
    private static void hookNeteaseDrawerState(LoadPackageParam lpparam) {
        Class<?> type = findClass(
                "com.netease.cloudmusic.activity.MainActivity", lpparam.classLoader
        );
        if (type == null) return;
        try {
            XposedBridge.hookAllMethods(type, "onDrawerOpened", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity) {
                        UniversalGlassInstaller.setNeteaseDrawerOpen(
                                (Activity) param.thisObject, true
                        );
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass][3.77] NetEase drawer-open hook unavailable: " + t);
        }
        try {
            XposedBridge.hookAllMethods(type, "onDrawerClosed", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity) {
                        UniversalGlassInstaller.setNeteaseDrawerOpen(
                                (Activity) param.thisObject, false
                        );
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass][3.77] NetEase drawer-close hook unavailable: " + t);
        }
    }

    private static void hookViewClass(
            LoadPackageParam lpparam,
            final TargetApp app,
            String className,
            String... methodNames
    ) {
        Class<?> type = findClass(className, lpparam.classLoader);
        if (type == null) return;
        for (String methodName : methodNames) {
            try {
                XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View)) return;
                        Activity activity = ActivityFinder.from(((View) param.thisObject).getContext());
                        if (LiquidTabNavigationRules.supports(app)
                                && ("onAttachedToWindow".equals(methodName)
                                || "onDetachedFromWindow".equals(methodName))) {
                            LiquidTabHostAdapter.invalidate(activity);
                        }
                        if (app == TargetApp.MI_GALLERY) {
                            // Gallery may restart its native show animator after a tab change.
                            // Re-apply alpha-zero synchronously so the exact attached row cannot
                            // flash above the independent LiquidGlass window.
                            UniversalGlassInstaller.onGalleryNativeNavigationMutated(
                                    (View) param.thisObject
                            );
                        }
                        if (app == TargetApp.XHS) {
                            // A/B experiments can attach a replacement TabBarView while the old
                            // controller-bound row is still alive off-screen. Make the next adapter
                            // resolution scan once and cache the newly attached row.
                            UniversalHostAdapter.invalidateCachedHostRoot(activity, app);
                        }
                        scheduleExactRetry(activity, app);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void hookCompassCameraSignals(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        // Compass 17.1.0.1 is obfuscated. DEX inspection of the uploaded APK confirms:
        //   CompassActivity.k2() = "close camera"
        //   CompassActivity.h2() = camera permission check + "startCamera"
        //   CameraView2.l()      = "openCamera"
        // Hook these real method names instead of the human-readable log strings.
        Class<?> compassActivity = findClass(
                "com.miui.compass.CompassActivity", lpparam.classLoader
        );
        if (compassActivity != null) {
            try {
                XposedBridge.hookAllMethods(compassActivity, "k2", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity activity = (Activity) param.thisObject;
                        if (!matches(activity, app)) return;
                        // Closing the camera process is a hard black boundary, but 26.60 no
                        // longer relies on process lifetime for the normal page state.
                        UniversalGlassInstaller.onCompassCameraClosed(activity);
                    }
                });
            } catch (Throwable ignored) {}
            try {
                XposedBridge.hookAllMethods(compassActivity, "h2", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity activity = (Activity) param.thisObject;
                        if (!matches(activity, app)) return;
                        // h2() starts/keeps CameraView2 alive. It does NOT mean the preview is
                        // currently exposed: MIUI intentionally keeps the camera running behind
                        // the black camera mask. Only the host e1/mask signal below may expose it.
                        UniversalGlassInstaller.onCompassCameraProcessStarted(activity);
                    }
                });
            } catch (Throwable ignored) {}

            // Compass 17.1.0.1's sensor listener does not stop CameraView2 when it returns to the
            // ordinary black Compass/Gradienter UI. Instead it animates camera_mask_in/out and
            // writes the private e1 flag through this synthetic accessor:
            //   I1(activity, true)  -> camera mask OUT, live preview is visually exposed
            //   I1(activity, false) -> camera mask IN, normal black page owns the screen
            // This is the authoritative state for LiquidGlass; Surface/process liveness is only
            // a resource state and may remain true indefinitely behind the normal page.
            try {
                XposedBridge.hookAllMethods(compassActivity, "I1", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 2
                                || !(param.args[0] instanceof Activity)
                                || !(param.args[1] instanceof Boolean)) return;
                        Activity activity = (Activity) param.args[0];
                        if (!matches(activity, app)) return;
                        UniversalGlassInstaller.onCompassCameraPreviewVisibility(
                                activity, ((Boolean) param.args[1]).booleanValue()
                        );
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> cameraView = findClass(
                "com.miui.compass.CameraView2", lpparam.classLoader
        );
        if (cameraView != null) {
            try {
                XposedBridge.hookAllMethods(cameraView, "l", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = compassOwnerActivity(param.thisObject);
                        if (!matches(activity, app)) return;
                        // CameraView2.l() may continue/re-fire while the normal black page is on
                        // top. Never let it unlock real backdrop capture by itself.
                        UniversalGlassInstaller.onCompassCameraProcessStarted(activity);
                    }
                });
            } catch (Throwable ignored) {}
        }

        // Final-state backup for the exact 17.1.0.1 camera-mask animations. The host's $k
        // listener finishes camera_mask_in by making v0 VISIBLE; $l finishes camera_mask_out by
        // making it INVISIBLE. I1 above switches at animation start, these hooks confirm the end.
        hookCompassCameraMaskAnimationEnd(lpparam, app,
                "com.miui.compass.CompassActivity$k", false);
        hookCompassCameraMaskAnimationEnd(lpparam, app,
                "com.miui.compass.CompassActivity$l", true);
    }

    private static void hookCompassCameraMaskAnimationEnd(
            LoadPackageParam lpparam,
            final TargetApp app,
            String className,
            final boolean previewVisible
    ) {
        Class<?> listener = findClass(className, lpparam.classLoader);
        if (listener == null) return;
        try {
            XposedBridge.hookAllMethods(listener, "onAnimationEnd", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = compassAnimationOwnerActivity(param.thisObject);
                    if (!matches(activity, app)) return;
                    UniversalGlassInstaller.onCompassCameraPreviewVisibility(
                            activity, previewVisible
                    );
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * Resolve the CompassActivity captured by MIUI Compass animation listener inner classes
     * without depending on a framework-specific field helper. Ordinary Java reflection keeps this
     * lookup independent from the API 102 hook adapter and compatible with obfuscated host fields.
     */
    private static Activity compassAnimationOwnerActivity(Object listener) {
        if (listener == null) return null;

        // 17.1.0.1 CompassActivity$k/$l capture the outer CompassActivity in field "a".
        // Search the class hierarchy because obfuscation/synthetic-field placement may vary.
        for (Class<?> type = listener.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("a");
                field.setAccessible(true);
                Activity activity = compassOwnerActivity(field.get(listener));
                if (activity != null) return activity;
            } catch (Throwable ignored) {}
        }

        // Defensive fallback: if a future minor build renames the captured field, inspect the
        // listener's instance fields and accept only a value that directly resolves to Activity.
        for (Class<?> type = listener.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Activity activity = compassOwnerActivity(field.get(listener));
                    if (activity != null) return activity;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Activity compassOwnerActivity(Object owner) {
        if (owner instanceof Activity) return (Activity) owner;
        if (owner instanceof View) {
            try { return ActivityFinder.from(((View) owner).getContext()); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    private static void hookCompassSelectionSignals(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        Class<?> navigator = findClass(
                "miuix.bottomnavigation.BottomNavigator", lpparam.classLoader
        );
        if (navigator != null) {
            try {
                XposedBridge.hookAllMethods(navigator, "setSelectedItem", new XC_MethodHook() {
                    private void sync(MethodHookParam param) {
                        if (!(param.thisObject instanceof View) || param.args == null
                                || param.args.length == 0 || !(param.args[0] instanceof Number)) return;
                        Activity activity = ActivityFinder.from(((View) param.thisObject).getContext());
                        if (!matches(activity, app)) return;
                        UniversalGlassInstaller.syncCompassSelection(
                                activity, ((Number) param.args[0]).intValue()
                        );
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        // Commit only after BottomNavigator has actually changed the host page.
                        // Applying the new capture policy in beforeHookedMethod() let an outgoing
                        // camera frame race into the incoming destination.
                        sync(param);
                        if (param.thisObject instanceof View) {
                            Activity activity = ActivityFinder.from(
                                    ((View) param.thisObject).getContext()
                            );
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> compassScreenView = findClass(
                "com.miui.compass.CompassScreenView", lpparam.classLoader
        );
        if (compassScreenView != null) {
            try {
                XposedBridge.hookAllMethods(compassScreenView, "A", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof View) || param.args == null
                                || param.args.length == 0 || !(param.args[0] instanceof Number)) return;
                        Activity activity = ActivityFinder.from(((View) param.thisObject).getContext());
                        if (!matches(activity, app)) return;
                        // 26.59: this is the actual normal-page commit. CameraView2 may still be
                        // alive behind it, so re-evaluate foreground ownership after A(int) returns.
                        UniversalGlassInstaller.syncCompassSelection(
                                activity, ((Number) param.args[0]).intValue()
                        );
                        scheduleExactRetry(activity, app);
                    }
                });
            } catch (Throwable ignored) {}
        }

        Class<?> compassActivity = findClass(
                "com.miui.compass.CompassActivity", lpparam.classLoader
        );
        if (compassActivity != null) {
            try {
                XposedBridge.hookAllMethods(compassActivity, "H2", new XC_MethodHook() {
                    private void sync(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity) || param.args == null
                                || param.args.length == 0 || !(param.args[0] instanceof Number)) return;
                        Activity activity = (Activity) param.thisObject;
                        if (!matches(activity, app)) return;
                        UniversalGlassInstaller.syncCompassSelection(
                                activity, ((Number) param.args[0]).intValue()
                        );
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        // H2(int) owns CompassScreenView.A(int). Wait until it completes so the
                        // bitmap reset/camera search sees the incoming page, not the outgoing one.
                        sync(param);
                        if (param.thisObject instanceof Activity) {
                            Activity activity = (Activity) param.thisObject;
                            if (matches(activity, app)) scheduleExactRetry(activity, app);
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void hookActivityClass(
            LoadPackageParam lpparam,
            final TargetApp app,
            String className,
            String... methodNames
    ) {
        Class<?> type = findClass(className, lpparam.classLoader);
        if (type == null) return;
        for (String methodName : methodNames) {
            try {
                XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity) {
                            scheduleExactRetry((Activity) param.thisObject, app);
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static void hookPageTransitionClass(
            LoadPackageParam lpparam,
            final TargetApp app,
            String className,
            String... methodNames
    ) {
        Class<?> type = findClass(className, lpparam.classLoader);
        if (type == null) return;
        for (String methodName : methodNames) {
            hookPageTransitionType(type, app, methodName);
        }
    }

    private static void hookPageTransitionHierarchy(
            LoadPackageParam lpparam,
            final TargetApp app,
            String publicViewClassName,
            String... methodNames
    ) {
        Class<?> type = findClass(publicViewClassName, lpparam.classLoader);
        if (type == null) return;
        for (String methodName : methodNames) {
            for (Class<?> current = type;
                 current != null && View.class.isAssignableFrom(current);
                 current = current.getSuperclass()) {
                hookPageTransitionType(current, app, methodName);
            }
        }
    }

    private static void hookPageTransitionType(
            Class<?> type,
            final TargetApp app,
            final String methodName
    ) {
        if (type == null || methodName == null) return;
        try {
            XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    if ("setChecked".equals(methodName) && param.args.length > 0
                            && Boolean.FALSE.equals(param.args[0])) return;
                    Activity activity = ActivityFinder.from(((View) param.thisObject).getContext());
                    if (matches(activity, app)) {
                        UniversalGlassInstaller.notifyHostPageTransition(activity, app);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    if ("setChecked".equals(methodName) && param.args.length > 0
                            && Boolean.FALSE.equals(param.args[0])) return;
                    Activity activity = ActivityFinder.from(((View) param.thisObject).getContext());
                    scheduleExactRetry(activity, app);
                }
            });
        } catch (Throwable ignored) {}
    }

    private static void installMoondropRenderModeHook(
            LoadPackageParam lpparam,
            final TargetApp app
    ) {
        try {
            Class<?> flutterBase = XposedHelpers.findClass("gq1", lpparam.classLoader);
            Class<?> renderMode = XposedHelpers.findClass("iy3", lpparam.classLoader);
            final Object texture = enumConstant(renderMode, "texture");
            if (texture == null) return;
            XposedBridge.hookAllMethods(flutterBase, "getRenderMode", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(texture);
                }
            });
            XposedBridge.log("[LiquidGlass][3.06] MOONDROP Flutter renderMode=texture");
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass][3.06] MOONDROP render-mode hook unavailable: " + t);
        }
    }

    private static Object enumConstant(Class<?> type, String wanted) {
        if (type == null || !type.isEnum()) return null;
        try {
            Object[] constants = type.getEnumConstants();
            if (constants == null) return null;
            for (Object constant : constants) {
                if (constant instanceof Enum
                        && wanted.equalsIgnoreCase(((Enum<?>) constant).name())) return constant;
                if (wanted.equalsIgnoreCase(String.valueOf(constant))) return constant;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Class<?> findClass(String name, ClassLoader loader) {
        try { return XposedHelpers.findClass(name, loader); }
        catch (Throwable ignored) { return null; }
    }

    private static void scheduleExactRetry(Activity activity, TargetApp app) {
        if (!matches(activity, app)) return;
        long now = SystemClock.uptimeMillis();
        Long last = LAST_EXACT_RETRY.get(activity);
        if (last != null && now - last < 90L) return;
        LAST_EXACT_RETRY.put(activity, now);
        if (app == TargetApp.XHS) {
            // isXhsReady() owns the one-second stable-row retry. Do not enqueue another seven
            // complete install passes for the same attachment callback.
            installLater(activity, app, 0L);
        } else if (app == TargetApp.NETEASE) {
            // NetEase keeps the outgoing Fragment attached after NavigationTabLayout.t() returns.
            // Re-resolve the actual viewport child through the end of that animation so the
            // backdrop source changes from the old page to the new page, not merely to the pager.
            installLater(activity, app, 0L, 72L, 180L, 340L, 520L, 760L);
        } else if (app == TargetApp.JD) {
            // JD swaps destination Fragments and can rebuild remote/Lottie navigation assets on
            // its own main-thread jobs. Follow that short commit window without broad View hooks.
            installLater(activity, app, 0L, 64L, 160L, 320L, 620L);
        } else {
            installLater(activity, app, 0L, 64L, 220L);
        }
    }

    private static boolean matches(Activity activity, TargetApp app) {
        if (LiquidTabPortAdapter.supports(app)) return activity != null && LiquidTabPortAdapter.matchesActivity(activity, app);
        if (activity == null || app == null) return false;
        if (app.matchesActivity(activity.getClass().getName())) return true;
        if (app == TargetApp.DOUYIN) {
            // Some Douyin builds wrap MainActivity in a package-local subclass.
            String name = activity.getClass().getName();
            if (name.startsWith("com.ss.android.ugc.aweme.") && name.contains("MainActivity")) {
                return true;
            }
        }
        if (app != TargetApp.MI_GALLERY) return false;
        // Gallery variants may wrap HomePageActivity in a product-specific subclass. Match the
        // verified Activity anywhere in the instance hierarchy without broad package heuristics.
        for (Class<?> type = activity.getClass().getSuperclass();
             type != null;
             type = type.getSuperclass()) {
            if (app.matchesActivity(type.getName())) return true;
        }
        return false;
    }

    private static void installLater(Activity activity, TargetApp app, long... delays) {
        if (!matches(activity, app) || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (decor == null) return;
        for (long delay : delays) {
            decor.postDelayed(() -> UniversalGlassInstaller.install(activity, app), delay);
        }
    }

    private static void registerObserver(final Activity activity, final TargetApp app) {
        if (activity == null || OBSERVERS.containsKey(activity)) return;
        try {
            final WeakReference<Activity> activityRef = new WeakReference<>(activity);
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange) {
                    GlobalRenderSettingsReader.invalidate();
                    if (app == TargetApp.WECHAT) WeChatRoundAvatars.reloadSettings();
                    // Settings and the global backdrop option are safe to apply to the existing
                    // injected view. Recreating the whole row here raced Bilibili/NetEase page
                    // layout and could leave them with no glass after a single toggle.
                    Activity current = activityRef.get();
                    if (matches(current, app)) {
                        UniversalGlassInstaller.reloadSettings(current, app);
                        if (app == TargetApp.WECHAT) {
                            try {
                                WeChatSidePanel.reloadSettings(current);
                            } catch (Throwable error) {
                                XposedBridge.log("[LiquidGlass][WeChatSidePanel] optional settings "
                                        + "reload failed; original glass remains active: " + error);
                                XposedBridge.log(error);
                            }
                        }
                    }
                }
            };
            activity.getContentResolver().registerContentObserver(
                    GlassSettings.CONTENT_URI, false, observer
            );
            OBSERVERS.put(activity, observer);
        } catch (Throwable ignored) {}
    }

    /** Applies a live API 102 Remote Preferences update to every active host Activity. */
    static void reloadRunningSettings(TargetApp app) {
        if (app == null) return;
        // The avatar option remains independent even when no navigation Activity is attached.
        if (app == TargetApp.WECHAT) WeChatRoundAvatars.reloadSettings();
        List<Activity> activities;
        synchronized (OBSERVERS) {
            activities = new ArrayList<>(OBSERVERS.keySet());
        }
        for (Activity activity : activities) {
            if (!matches(activity, app)) continue;
            try {
                UniversalGlassInstaller.reloadSettings(activity, app);
                if (app == TargetApp.WECHAT) WeChatSidePanel.reloadSettings(activity);
            } catch (Throwable error) {
                XposedBridge.log("[LiquidGlass][26.96] live settings refresh failed for "
                        + app.packageName + ": " + error);
            }
        }
    }

    private static void unregisterObserver(Activity activity) {
        ContentObserver observer = OBSERVERS.remove(activity);
        if (observer == null || activity == null) return;
        try { activity.getContentResolver().unregisterContentObserver(observer); }
        catch (Throwable ignored) {}
    }
}
