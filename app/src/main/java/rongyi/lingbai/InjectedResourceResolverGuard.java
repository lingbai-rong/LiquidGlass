package rongyi.lingbai;

import android.content.Context;
import android.content.res.Resources;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps resource ids emitted by the injected Compose/Miuix tree inside the module resource table.
 *
 * Every Android APK normally owns package id 0x7f. When a ComposeView is created with a host
 * context, a module id such as 0x7f0a00a3 can therefore be routed to the host Resources object.
 * If that host has no entry at the same numeric id, Resources.NotFoundException terminates the
 * host process. The issue is not specific to Bilibili or TIM and must not be maintained as an app
 * allow-list.
 *
 * The primary fix is ModuleResourceContext in MiuixGlassNavView. This hook is a narrow fallback for
 * attach/recomposition races and ROM services that retain the host Resources object: it only
 * substitutes a value when the same id can actually be resolved from LiquidGlass resources.
 */
public final class InjectedResourceResolverGuard {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final ThreadLocal<Boolean> RESOLVING_MODULE_VALUE = new ThreadLocal<>();
    private static volatile Resources moduleResources;
    private static volatile String modulePackageName = "rongyi.lingbai";

    private InjectedResourceResolverGuard() {}

    public static void install(String hostLabel) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookAllMethods(Resources.class, "getText", createResourceHook("getText"));
            XposedBridge.hookAllMethods(Resources.class, "getString", createResourceHook("getString"));
            XposedBridge.log("[LiquidGlass][3.68] module resource resolver guard installed for "
                    + (hostLabel == null ? "host" : hostLabel));
        } catch (Throwable throwable) {
            INSTALLED.set(false);
            XposedBridge.log("[LiquidGlass][3.68] module resource resolver guard unavailable: "
                    + throwable);
        }
    }


    private static XC_MethodHook createResourceHook(final String methodName) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Throwable throwable = param.getThrowable();
                if (!(throwable instanceof Resources.NotFoundException)) return;
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof Integer)) return;
                if (Boolean.TRUE.equals(RESOLVING_MODULE_VALUE.get())) return;

                int resourceId = (Integer) param.args[0];
                boolean accessibilityRequest = isAccessibilityNodeStack();
                if (!accessibilityRequest) {
                    // MIUI builds the floating copy/paste toolbar through Resources.getText(). A
                    // host resource miss must never be redirected into this module's 0x7f table:
                    // both APKs use the same package id, and retrying getText() from inside this
                    // hook recursively invokes the hook until the main thread ANRs.
                    if (isTextSelectionActionModeStack()) {
                        param.setThrowable(null);
                        param.setResult(defaultText(param.args));
                    }
                    return;
                }

                Resources resources = moduleResources;
                if (resources == null || param.thisObject == resources) {
                    param.setThrowable(null);
                    param.setResult(defaultText(param.args));
                    return;
                }

                Object replacement;
                RESOLVING_MODULE_VALUE.set(Boolean.TRUE);
                try {
                    replacement = resolveModuleValue(methodName, resourceId, param.args);
                } finally {
                    RESOLVING_MODULE_VALUE.remove();
                }
                if (replacement != null) {
                    param.setThrowable(null);
                    param.setResult(replacement);
                    return;
                }

                // The injected navigation has no accessibility contract. An unresolved virtual
                // node label is therefore safe to expose as empty/default text.
                param.setThrowable(null);
                param.setResult(defaultText(param.args));
            }
        };
    }

    /** Register the verified module context before Compose starts its first composition. */
    public static void registerModuleContext(Context context) {
        if (context == null) return;
        try {
            Context application = context.getApplicationContext();
            Context candidate = application != null ? application : context;
            Resources resources = candidate.getResources();
            if (resources == null) return;
            moduleResources = resources;
            modulePackageName = candidate.getPackageName();
        } catch (Throwable ignored) {
        }
    }

    public static String getRegisteredModulePackageName() {
        return modulePackageName;
    }

    private static Object resolveModuleValue(String methodName, int resourceId, Object[] args) {
        Resources resources = moduleResources;
        if (resources == null || resourceId == 0) return null;
        try {
            if ("getString".equals(methodName)) {
                if (args != null && args.length > 1 && args[1] instanceof Object[]) {
                    return resources.getString(resourceId, (Object[]) args[1]);
                }
                return resources.getString(resourceId);
            }
            if ("getText".equals(methodName)) {
                if (args != null && args.length > 1 && args[1] instanceof CharSequence) {
                    return resources.getText(resourceId, (CharSequence) args[1]);
                }
                return resources.getText(resourceId);
            }
        } catch (Resources.NotFoundException ignored) {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private static CharSequence defaultText(Object[] args) {
        if (args != null && args.length > 1 && args[1] instanceof CharSequence) {
            return (CharSequence) args[1];
        }
        return "";
    }

    private static boolean isAccessibilityNodeStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        if (stack == null) return false;
        for (StackTraceElement element : stack) {
            if (element == null) continue;
            String className = element.getClassName();
            String methodName = element.getMethodName();
            if (className == null) continue;
            if (className.contains("AccessibilityInteractionController")
                    || className.contains("AndroidComposeViewAccessibilityDelegateCompat")
                    || className.contains("ComposeAccessibilityNodeProvider")
                    || "createAccessibilityNodeInfo".equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTextSelectionActionModeStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        if (stack == null) return false;
        for (StackTraceElement element : stack) {
            if (element == null) continue;
            String className = element.getClassName();
            if (className == null) continue;
            if (className.startsWith("miuix.textaction.")
                    || className.startsWith("miuix.toolbar.FloatingActionMode")
                    || className.contains("SelectionActionModeHelper")
                    || className.contains("TextActionModeCallback")) {
                return true;
            }
        }
        return false;
    }
}
