package rongyi.lingbai;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class HookEntry extends XposedModule {
    private static final String TARGET_PACKAGE = "com.coolapk.market";
    private static final String MAIN_ACTIVITY = "com.coolapk.market.view.main.MainActivity";
    private volatile String processName = "";
    private final AtomicBoolean installationStarted = new AtomicBoolean(false);
    private final Map<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>
            remoteSettingsListeners = Collections.synchronizedMap(new IdentityHashMap<>());
    private final AtomicBoolean settingsRefreshPosted = new AtomicBoolean(false);
    private static final Map<Activity, OnBackInvokedCallback> BACK_OBSERVERS =
            Collections.synchronizedMap(new WeakHashMap<Activity, OnBackInvokedCallback>());
    private static final Map<Activity, ContentObserver> SETTINGS_OBSERVERS =
            Collections.synchronizedMap(new WeakHashMap<Activity, ContentObserver>());
    private static WeakReference<Activity> resumedMainActivity = new WeakReference<>(null);
    private static final Map<Activity, EdgeBackTracker> EDGE_BACK_TRACKERS =
            Collections.synchronizedMap(new WeakHashMap<Activity, EdgeBackTracker>());
    private static final Set<String> HOOKED_BACK_CALLBACK_METHODS =
            Collections.synchronizedSet(new HashSet<String>());
    private static final ThreadLocal<Boolean> RETURN_HOME_BEFORE_BACK = new ThreadLocal<>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        XposedBridge.attach(this);
        long properties = getFrameworkProperties();
        XposedBridge.log("[LiquidGlass][26.96][API" + getApiVersion()
                + "] module loaded in " + processName
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + ", remote=" + ((properties & PROP_CAP_REMOTE) != 0L));
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (param == null || !isSupportedPackage(param.getPackageName())) return;
        ModuleLog.attachRemote();
        XposedBridge.log("[LiquidGlass][26.96] package ready: " + param.getPackageName()
                + " / " + processName + ", first=" + param.isFirstPackage());
        LoadPackageParam load = new LoadPackageParam(
                param.getPackageName(),
                processName,
                param.getClassLoader(),
                param.getApplicationInfo(),
                param.isFirstPackage()
        );
        installHooks(load, "package-ready");
    }

    private static boolean isSupportedPackage(String packageName) {
        return TARGET_PACKAGE.equals(packageName) || TargetApp.fromPackage(packageName) != null;
    }

    private void installHooks(LoadPackageParam load, String trigger) {
        if (load == null) return;
        try {
            if (!runInstallationOnce(installationStarted, () -> {
                handleLoadPackage(load);
                registerRemoteSettingsListeners(load.packageName);
            })) return;
            XposedBridge.log("[LiquidGlass][26.96] hooks installed for " + load.packageName
                    + " via " + trigger);
        } catch (Throwable error) {
            XposedBridge.log("[LiquidGlass][26.96] hook installation failed for "
                    + load.packageName + " via " + trigger + ": " + error);
            XposedBridge.log(error);
        }
    }

    static boolean runInstallationOnce(AtomicBoolean started, Runnable install) {
        if (!started.compareAndSet(false, true)) return false;
        boolean completed = false;
        try {
            install.run();
            completed = true;
            return true;
        } finally {
            if (!completed) started.set(false);
        }
    }

    private void registerRemoteSettingsListeners(String packageName) {
        TargetApp targetApp = TargetApp.fromPackage(packageName);
        String[] groups = targetApp == null
                ? new String[]{GlassSettings.PREF_NAME, GlobalAppearanceStore.PREF_NAME}
                : new String[]{TargetAppSettings.PREF_NAME, GlobalAppearanceStore.PREF_NAME};
        for (String group : groups) {
            try {
                SharedPreferences preferences = XposedBridge.getRemotePreferences(group);
                synchronized (remoteSettingsListeners) {
                    if (remoteSettingsListeners.containsKey(preferences)) continue;
                    SharedPreferences.OnSharedPreferenceChangeListener listener =
                            (changedPreferences, changedKey) -> scheduleSettingsRefresh(targetApp);
                    remoteSettingsListeners.put(preferences, listener);
                    preferences.registerOnSharedPreferenceChangeListener(listener);
                }
            } catch (Throwable error) {
                XposedBridge.log("[LiquidGlass][26.96] remote settings listener unavailable for "
                        + group + ": " + error);
            }
        }
    }

    private void scheduleSettingsRefresh(TargetApp targetApp) {
        if (!settingsRefreshPosted.compareAndSet(false, true)) return;
        Runnable refresh = () -> {
            settingsRefreshPosted.set(false);
            GlobalRenderSettingsReader.invalidate();
            if (targetApp != null) {
                UniversalHookEntry.reloadRunningSettings(targetApp);
                return;
            }
            Activity current = resumedMainActivity.get();
            if (isUsableMainActivity(current)) GlassNavInstaller.reloadSettings(current);
        };
        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper == null || Looper.myLooper() == mainLooper) {
            refresh.run();
        } else {
            new Handler(mainLooper).postDelayed(refresh, 40L);
        }
    }

    /** Package-private diagnostic seam used by local JVM tests. */
    static boolean isSupportedPackageForTest(String packageName) {
        return isSupportedPackage(packageName);
    }

    private void handleLoadPackage(final LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;

        TargetApp targetApp = TargetApp.fromPackage(lpparam.packageName);
        if (targetApp != null) {
            if (LiquidTabPortAdapter.supports(targetApp) && !LiquidTabPortAdapter.acceptsProcess(targetApp, lpparam.processName)) return;
            if ((targetApp == TargetApp.WECHAT || targetApp == TargetApp.JD
                    || targetApp == TargetApp.XHS || targetApp == TargetApp.PDD
                    || targetApp == TargetApp.DOUYIN)
                    && lpparam.processName != null
                    && !lpparam.packageName.equals(lpparam.processName)) {
                if (targetApp == TargetApp.WECHAT
                        && (lpparam.packageName + ":push").equals(lpparam.processName)) {
                    // CoreService runs in :push. Install only the optional message-notification
                    // avatar hook there; never install navigation, panel, or Activity hooks.
                    try {
                        WeChatRoundAvatars.install(lpparam);
                    } catch (Throwable error) {
                        XposedBridge.log("[LiquidGlass][WeChatRoundAvatars] push hook failed: "
                                + error);
                    }
                }
                return;
            }
            // Some Alibaba/Flutter builds can move their visible main Activity into a named
            // application process. The installed hooks still act only on the verified Activity
            // classes and bottom rows, so do not discard those real UI processes here.
            TargetAppHookSettingsReader.load(null, targetApp);
            UniversalHookEntry.install(lpparam, targetApp);
            return;
        }
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;
        HookSettingsReader.load(null);
        installCoolapkHooks(lpparam);
    }

    private static void installCoolapkHooks(final LoadPackageParam lpparam) {

        XposedBridge.log("[LiquidGlass][3.68] loaded in " + lpparam.packageName);
        InjectedResourceResolverGuard.install("coolapk");

        // MIUI/HyperOS screenshot, OCR and some accessibility services may query
        // Compose virtual accessibility nodes inside the injected glass bar. On CoolAPK
        // this can make Compose try to resolve this module's string resource id through
        // CoolAPK Resources, causing Resources$NotFoundException on the main thread.
        // Guard both sides: return a harmless empty node if the Compose provider throws,
        // and neutralize missing string resources only when the call originates from
        // Compose's accessibility delegate. This keeps screenshot stable without changing
        // the visible KSU/miuix glass effect.
        installComposeAccessibilityCrashGuard(lpparam);
        installAndroidXBackDispatcherHook(lpparam);
        installFrameworkBackCallbackHook();

        // Earlier than onResume. This reduces the one-frame flash of CoolAPK's own
        // bottom bar on cold start. The real bottom_bar_container may not exist yet,
        // so we post several very early attempts to the decor queue.
        XposedBridge.hookAllMethods(Activity.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                registerSystemBackObserver(activity);
                registerSettingsObserver(activity);
                installLater(activity, 0L);
                installLater(activity, 16L);
                installLater(activity, 48L);
                installLater(activity, 96L);
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onStart", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                registerSystemBackObserver(activity);
                registerSettingsObserver(activity);
                installLater(activity, 0L);
                installLater(activity, 32L);
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (MAIN_ACTIVITY.equals(activity.getClass().getName())) {
                    // Re-read the global snapshot after returning from the module settings app,
                    // even on ROMs that drop cross-package ContentObserver notifications.
                    GlobalRenderSettingsReader.invalidate();
                    resumedMainActivity = new WeakReference<>(activity);
                    registerSystemBackObserver(activity);
                    registerSettingsObserver(activity);
                }
                if (MAIN_ACTIVITY.equals(activity.getClass().getName())) {
                    GlassNavInstaller.resumeBackdrop(activity);
                }
                installLater(activity, 0L);
                installLater(activity, 40L);
                installLater(activity, 180L);
                installLater(activity, 650L);
                syncSelectionLater(activity, 90L);
                syncSelectionLater(activity, 260L);
                syncSelectionLater(activity, 700L);
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
                Activity current = resumedMainActivity.get();
                if (current == activity) resumedMainActivity = new WeakReference<>(null);
                GlassNavInstaller.pauseBackdrop(activity);
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onWindowFocusChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                    Activity activity = (Activity) param.thisObject;
                    if (MAIN_ACTIVITY.equals(activity.getClass().getName())) {
                        resumedMainActivity = new WeakReference<>(activity);
                        registerSystemBackObserver(activity);
                    }
                    installLater(activity, 0L);
                    installLater(activity, 48L);
                    syncSelectionLater(activity, 96L);
                    syncSelectionLater(activity, 280L);
                }
            }
        });

        // CoolAPK may change day/night mode inside the app. Reusing the same injected
        // ComposeView across that transition can leave miuix backdrop/theme objects tied
        // to the old Configuration. Replace the whole module view, then let normal install
        // hooks create a fresh instance. No host Lifecycle/ViewTree owner is modified.
        XposedBridge.hookAllMethods(Activity.class, "onConfigurationChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
                GlassNavInstaller.recreateForThemeChange(activity);
                installLater(activity, 32L);
                installLater(activity, 120L);
                installLater(activity, 320L);
            }
        });

        // Backdrop movement is now detected from the real content-root draw burst inside
        // CoolapkLegacyBitmapBackdropManager. Avoid global View/AbsListView scroll hooks: every
        // RecyclerView child used to cross Xposed while scrolling even though only one bottom strip
        // capture was needed.

        // Keep only the edge-back detector here. Background capture is never driven by
        // generic touch MOVE events; the local content-root draw listener coalesces real host
        // changes and applies the adaptive capture limiter.
        XposedBridge.hookAllMethods(Activity.class, "dispatchTouchEvent", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof MotionEvent)) return;
                MotionEvent event = (MotionEvent) param.args[0];
                trackEdgeBackGesture(activity, event);
            }
        });

        // Do not hook View.onAttachedToWindow process-wide. Lifecycle/focus/configuration
        // retries already cover the two CoolAPK bottom-bar views, while a global attach hook runs
        // for every RecyclerView row and is a permanent scrolling cost.

        // Back/deep-link navigation can change the current root page without touching
        // the visible glass bar. Re-check CoolAPK's original selected semantics afterwards.
        XposedBridge.hookAllMethods(Activity.class, "onBackPressed", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
                RETURN_HOME_BEFORE_BACK.set(GlassNavInstaller.shouldReturnHomeOnBack(activity));
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
                Boolean shouldReturnHome = RETURN_HOME_BEFORE_BACK.get();
                RETURN_HOME_BEFORE_BACK.remove();
                GlassNavInstaller.onBackCallbackFinished(activity, Boolean.TRUE.equals(shouldReturnHome));
            }
        });

        // Legacy/compat gesture navigation can be delivered as KEYCODE_BACK without
        // calling Activity.onBackPressed. Capture that path separately.
        XposedBridge.hookAllMethods(Activity.class, "dispatchKeyEvent", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof KeyEvent)) return;
                KeyEvent event = (KeyEvent) param.args[0];
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                    RETURN_HOME_BEFORE_BACK.set(GlassNavInstaller.shouldReturnHomeOnBack(activity));
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof KeyEvent)) return;
                KeyEvent event = (KeyEvent) param.args[0];
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    Boolean shouldReturnHome = RETURN_HOME_BEFORE_BACK.get();
                    RETURN_HOME_BEFORE_BACK.remove();
                    GlassNavInstaller.onBackCallbackFinished(activity, Boolean.TRUE.equals(shouldReturnHome));
                }
            }
        });
        XposedBridge.hookAllMethods(Activity.class, "onNewIntent", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
                installLater(activity, 32L);
                syncSelectionLater(activity, 120L);
                syncSelectionLater(activity, 360L);
            }
        });

        XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
                unregisterSystemBackObserver(activity);
                unregisterSettingsObserver(activity);
                EDGE_BACK_TRACKERS.remove(activity);
            }
        });
    }


    /**
     * CoolAPK uses AndroidX OnBackPressedDispatcher for predictive back on some builds.
     * Observe the dispatcher itself so a completed back gesture always converges the glass
     * indicator to 首页, even when Activity.onBackPressed() is never called.
     */
    private static void installAndroidXBackDispatcherHook(final LoadPackageParam lpparam) {
        try {
            final Class<?> callbackBase = XposedHelpers.findClass(
                    "androidx.activity.OnBackPressedCallback", lpparam.classLoader
            );
            Class<?> dispatcher = XposedHelpers.findClass("androidx.activity.OnBackPressedDispatcher", lpparam.classLoader);

            XposedBridge.hookAllMethods(dispatcher, "addCallback", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null) return;
                    for (Object arg : param.args) {
                        if (arg != null && callbackBase.isInstance(arg)) {
                            hookConcreteBackCallback(arg, "handleOnBackPressed");
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(dispatcher, "onBackPressed", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Activity activity = resumedMainActivity.get();
                    if (isUsableMainActivity(activity)) {
                        RETURN_HOME_BEFORE_BACK.set(GlassNavInstaller.shouldReturnHomeOnBack(activity));
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = resumedMainActivity.get();
                    if (!isUsableMainActivity(activity)) return;
                    Boolean shouldReturnHome = RETURN_HOME_BEFORE_BACK.get();
                    RETURN_HOME_BEFORE_BACK.remove();
                    GlassNavInstaller.onBackCallbackFinished(activity, Boolean.TRUE.equals(shouldReturnHome));
                }
            });
            XposedBridge.log("[LiquidGlass] AndroidX back callback interception installed");
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass] AndroidX back callback interception unavailable: " + t);
        }
    }

    /**
     * Hook callbacks registered directly with the framework dispatcher. This catches
     * Compose/predictive-back implementations which never call Activity.onBackPressed.
     */
    private static void installFrameworkBackCallbackHook() {
        String[] dispatcherNames = new String[]{
                "android.window.WindowOnBackInvokedDispatcher",
                "android.window.ProxyOnBackInvokedDispatcher"
        };
        for (String className : dispatcherNames) {
            try {
                Class<?> dispatcher = XposedHelpers.findClass(className, null);
                XC_MethodHook registrationHook = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args == null) return;
                        for (Object arg : param.args) {
                            if (arg instanceof OnBackInvokedCallback) {
                                hookConcreteBackCallback(arg, "onBackInvoked");
                            }
                        }
                    }
                };
                XposedBridge.hookAllMethods(dispatcher, "registerOnBackInvokedCallback", registrationHook);
                XposedBridge.hookAllMethods(dispatcher, "registerOnBackInvokedCallbackUnchecked", registrationHook);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void hookConcreteBackCallback(Object callback, String methodName) {
        if (callback == null) return;
        Class<?> callbackClass = callback.getClass();
        // Do not hook callbacks created by this module itself. Use the defining
        // ClassLoader rather than the source package name so this remains correct
        // after R8 repackages and obfuscates module classes in release builds.
        ClassLoader callbackLoader = callbackClass.getClassLoader();
        ClassLoader moduleLoader = HookEntry.class.getClassLoader();
        if (callbackLoader != null && callbackLoader == moduleLoader) return;
        final String hookKey = callbackClass.getName() + "#" + methodName;
        synchronized (HOOKED_BACK_CALLBACK_METHODS) {
            if (!HOOKED_BACK_CALLBACK_METHODS.add(hookKey)) return;
        }

        try {
            Class<?> current = callbackClass;
            boolean hooked = false;
            while (current != null && current != Object.class) {
                if (!XposedBridge.hookAllMethods(current, methodName, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Activity activity = resumedMainActivity.get();
                        if (isUsableMainActivity(activity)) {
                            RETURN_HOME_BEFORE_BACK.set(GlassNavInstaller.shouldReturnHomeOnBack(activity));
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = resumedMainActivity.get();
                        if (!isUsableMainActivity(activity)) return;
                        Boolean shouldReturnHome = RETURN_HOME_BEFORE_BACK.get();
                        RETURN_HOME_BEFORE_BACK.remove();
                        GlassNavInstaller.onBackCallbackFinished(activity, Boolean.TRUE.equals(shouldReturnHome));
                    }
                }).isEmpty()) {
                    hooked = true;
                    break;
                }
                current = current.getSuperclass();
            }
            if (!hooked) {
                synchronized (HOOKED_BACK_CALLBACK_METHODS) {
                    HOOKED_BACK_CALLBACK_METHODS.remove(hookKey);
                }
            }
        } catch (Throwable ignored) {
            synchronized (HOOKED_BACK_CALLBACK_METHODS) {
                HOOKED_BACK_CALLBACK_METHODS.remove(hookKey);
            }
        }
    }

    private static boolean isUsableMainActivity(Activity activity) {
        return activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && MAIN_ACTIVITY.equals(activity.getClass().getName());
    }

    private static void registerSettingsObserver(final Activity activity) {
        try {
            if (activity == null || !MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
            synchronized (SETTINGS_OBSERVERS) {
                if (SETTINGS_OBSERVERS.containsKey(activity)) return;
                final WeakReference<Activity> activityRef = new WeakReference<>(activity);
                ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                    @Override public void onChange(boolean selfChange) {
                        GlobalRenderSettingsReader.invalidate();
                        Activity current = activityRef.get();
                        if (!isUsableMainActivity(current)) return;
                        GlassNavInstaller.reloadSettings(current);
                    }
                };
                activity.getContentResolver().registerContentObserver(
                        GlassSettings.CONTENT_URI,
                        true,
                        observer
                );
                SETTINGS_OBSERVERS.put(activity, observer);
            }
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass] settings observer unavailable: " + t);
        }
    }

    private static void unregisterSettingsObserver(Activity activity) {
        try {
            if (activity == null) return;
            ContentObserver observer;
            synchronized (SETTINGS_OBSERVERS) {
                observer = SETTINGS_OBSERVERS.remove(activity);
            }
            if (observer != null) {
                activity.getContentResolver().unregisterContentObserver(observer);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Android 16 predictive-back no longer has to call Activity.onBackPressed().
     * Register a non-consuming system-navigation observer so the module is notified
     * after a real back action without replacing CoolAPK's own callback.
     */
    private static void registerSystemBackObserver(final Activity activity) {
        try {
            if (activity == null || !MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
            synchronized (BACK_OBSERVERS) {
                if (BACK_OBSERVERS.containsKey(activity)) return;
                final WeakReference<Activity> activityRef = new WeakReference<>(activity);
                final OnBackInvokedCallback callback = new OnBackInvokedCallback() {
                    @Override public void onBackInvoked() {
                        Activity current = activityRef.get();
                        if (isUsableMainActivity(current)) {
                            GlassNavInstaller.onSystemBackInvoked(current);
                        }
                    }
                };
                // PRIORITY_SYSTEM_NAVIGATION_OBSERVER was added after API 35.
                // Resolve it reflectively so the project still compiles against Android 15,
                // and simply skip this optional observer on Android 15 devices.
                final int observerPriority;
                try {
                    observerPriority = OnBackInvokedDispatcher.class
                            .getField("PRIORITY_SYSTEM_NAVIGATION_OBSERVER")
                            .getInt(null);
                } catch (Throwable unavailableOnAndroid15) {
                    return;
                }
                activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        observerPriority, callback
                );
                BACK_OBSERVERS.put(activity, callback);
                XposedBridge.log("[LiquidGlass] system-navigation back observer registered");
            }
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass] back observer unavailable: " + t);
        }
    }

    private static void unregisterSystemBackObserver(Activity activity) {
        try {
            if (activity == null) return;
            OnBackInvokedCallback callback;
            synchronized (BACK_OBSERVERS) {
                callback = BACK_OBSERVERS.remove(activity);
            }
            if (callback != null) {
                activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
            }
        } catch (Throwable ignored) {
        }
    }


    private static void trackEdgeBackGesture(Activity activity, MotionEvent event) {
        try {
            if (activity == null || event == null || activity.getWindow() == null) return;
            int width = activity.getWindow().getDecorView().getWidth();
            if (width <= 0) return;
            float density = activity.getResources().getDisplayMetrics().density;
            float edge = 34f * density;
            float trigger = 64f * density;
            float maxVertical = 150f * density;
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                boolean left = event.getX() <= edge;
                boolean right = event.getX() >= width - edge;
                if (!left && !right) {
                    EDGE_BACK_TRACKERS.remove(activity);
                    return;
                }
                EdgeBackTracker tracker = new EdgeBackTracker();
                tracker.downX = event.getX();
                tracker.downY = event.getY();
                tracker.downAt = SystemClock.uptimeMillis();
                tracker.fromLeft = left;
                tracker.shouldReturnHomeAtDown = GlassNavInstaller.shouldReturnHomeOnBack(activity);
                EDGE_BACK_TRACKERS.put(activity, tracker);
                return;
            }

            EdgeBackTracker tracker = EDGE_BACK_TRACKERS.get(activity);
            if (tracker == null) return;
            float inward = tracker.fromLeft ? event.getX() - tracker.downX : tracker.downX - event.getX();
            float vertical = Math.abs(event.getY() - tracker.downY);
            if (inward >= trigger && vertical <= maxVertical) tracker.passedThreshold = true;

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                EDGE_BACK_TRACKERS.remove(activity);
                long duration = SystemClock.uptimeMillis() - tracker.downAt;
                if (tracker.passedThreshold && duration <= 1800L) {
                    GlassNavInstaller.onBackCallbackFinished(activity, tracker.shouldReturnHomeAtDown);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class EdgeBackTracker {
        float downX;
        float downY;
        long downAt;
        boolean fromLeft;
        boolean passedThreshold;
        boolean shouldReturnHomeAtDown;
    }

    private static void installComposeAccessibilityCrashGuard(final LoadPackageParam lpparam) {
        hookComposeProviderWithClassLoader(HookEntry.class.getClassLoader());
        if (lpparam != null && lpparam.classLoader != null && lpparam.classLoader != HookEntry.class.getClassLoader()) {
            hookComposeProviderWithClassLoader(lpparam.classLoader);
        }
        // Resources.getText/getString are guarded once by InjectedResourceResolverGuard. Installing
        // another process-wide pair here makes every host resource lookup cross two Xposed
        // callbacks and can re-enter fallback resolution during MIUI text selection.
    }

    private static void hookComposeProviderWithClassLoader(ClassLoader classLoader) {
        if (classLoader == null) return;
        try {
            Class<?> providerClass = XposedHelpers.findClass(
                    "androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat$ComposeAccessibilityNodeProvider",
                    classLoader
            );
            XposedHelpers.findAndHookMethod(providerClass, "createAccessibilityNodeInfo", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Throwable t = param.getThrowable();
                    if (t instanceof Resources.NotFoundException || isComposeAccessibilityStack(t)) {
                        param.setThrowable(null);
                        param.setResult(createEmptyAccessibilityNode());
                    }
                }
            });
            XposedBridge.log("[LiquidGlass] Compose accessibility provider guard installed: " + classLoader);
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass] Compose provider guard skipped for " + classLoader + ": " + t);
        }
    }

    private static AccessibilityNodeInfo createEmptyAccessibilityNode() {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        try {
            info.setClassName(View.class.getName());
            info.setPackageName(TARGET_PACKAGE);
            info.setContentDescription(null);
            info.setText(null);
            info.setClickable(false);
            info.setFocusable(false);
            info.setEnabled(false);
            info.setVisibleToUser(false);
        } catch (Throwable ignored) {}
        return info;
    }

    private static boolean isComposeAccessibilityStack(Throwable throwable) {
        if (throwable == null) return false;
        StackTraceElement[] stack = throwable.getStackTrace();
        if (stack != null) {
            for (StackTraceElement e : stack) {
                if (e == null) continue;
                String c = e.getClassName();
                if (c == null) continue;
                if (c.contains("AndroidComposeViewAccessibilityDelegateCompat")
                        || c.contains("ComposeAccessibilityNodeProvider")
                        || c.contains("AccessibilityInteractionController")) {
                    return true;
                }
            }
        }
        stack = Thread.currentThread().getStackTrace();
        if (stack != null) {
            for (StackTraceElement e : stack) {
                if (e == null) continue;
                String c = e.getClassName();
                if (c == null) continue;
                if (c.contains("AndroidComposeViewAccessibilityDelegateCompat")
                        || c.contains("ComposeAccessibilityNodeProvider")
                        || c.contains("AccessibilityInteractionController")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void syncSelectionLater(final Activity activity, long delay) {
        try {
            if (activity == null || activity.isFinishing()) return;
            if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            decor.postDelayed(new Runnable() {
                @Override public void run() {
                    GlassNavInstaller.syncSelectionFromOriginal(activity);
                }
            }, Math.max(0L, delay));
        } catch (Throwable ignored) {
        }
    }

    private static void installLater(final Activity activity, long delay) {
        try {
            if (activity == null || activity.isFinishing()) return;
            if (!MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
            View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            if (decor == null) return;
            decor.postDelayed(new Runnable() {
                @Override
                public void run() {
                    GlassNavInstaller.install(activity);
                }
            }, Math.max(0L, delay));
        } catch (Throwable t) {
            XposedBridge.log("[LiquidGlass] installLater error: " + t);
        }
    }
}
