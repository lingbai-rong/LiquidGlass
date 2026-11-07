package rongyi.lingbai;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** API 102 bridge for the narrow legacy-style callback surface used by this project. */
public final class XposedBridge {
    private static final String TAG = "LiquidGlass";
    private static volatile XposedModule module;

    private XposedBridge() {}

    static void attach(XposedModule value) {
        if (value == null) throw new IllegalArgumentException("XposedModule is null");
        module = value;
        ModuleLog.attachRemote();
    }

    public static SharedPreferences getRemotePreferences(String group) {
        return requireModule().getRemotePreferences(group);
    }

    public static ApplicationInfo getModuleApplicationInfo() {
        return requireModule().getModuleApplicationInfo();
    }

    public static XposedInterface.HookHandle hookMethod(
            Member member,
            XC_MethodHook callback
    ) {
        if (!(member instanceof Executable)) {
            throw new IllegalArgumentException("Hook member is not executable: " + member);
        }
        return hookExecutable((Executable) member, callback);
    }

    public static Set<XposedInterface.HookHandle> hookAllMethods(
            Class<?> type,
            String methodName,
            XC_MethodHook callback
    ) {
        if (type == null) throw new IllegalArgumentException("Hook class is null");
        if (methodName == null || methodName.isEmpty()) {
            throw new IllegalArgumentException("Hook method name is empty");
        }
        if (callback == null) throw new IllegalArgumentException("Hook callback is null");

        Set<XposedInterface.HookHandle> handles = new LinkedHashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (!methodName.equals(method.getName())) continue;
            try {
                handles.add(hookExecutable(method, callback));
            } catch (Throwable error) {
                log("[API102] unable to hook " + type.getName() + "#" + methodName
                        + ": " + error);
            }
        }
        return Collections.unmodifiableSet(handles);
    }

    public static Set<XposedInterface.HookHandle> hookAllConstructors(
            Class<?> type,
            XC_MethodHook callback
    ) {
        if (type == null) throw new IllegalArgumentException("Hook class is null");
        if (callback == null) throw new IllegalArgumentException("Hook callback is null");

        Set<XposedInterface.HookHandle> handles = new LinkedHashSet<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            try {
                handles.add(hookExecutable(constructor, callback));
            } catch (Throwable error) {
                log("[API102] unable to hook constructor " + constructor.toGenericString()
                        + ": " + error);
            }
        }
        return Collections.unmodifiableSet(handles);
    }

    private static XposedInterface.HookHandle hookExecutable(
            Executable executable,
            XC_MethodHook callback
    ) {
        if (executable == null) throw new IllegalArgumentException("Hook executable is null");
        if (callback == null) throw new IllegalArgumentException("Hook callback is null");
        return requireModule().hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> intercept(chain, callback));
    }

    private static Object intercept(
            XposedInterface.Chain chain,
            XC_MethodHook callback
    ) throws Throwable {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.thisObject = chain.getThisObject();
        param.args = chain.getArgs().toArray(new Object[0]);

        try {
            callback.callBefore(param);
        } catch (Throwable callbackError) {
            logCallbackError("before", chain, callbackError);
            param.setOutcome(null, null, false);
        }

        if (!param.returnEarly) {
            try {
                Object[] args = param.args != null ? param.args : new Object[0];
                param.setOutcome(chain.proceed(args), null, false);
            } catch (Throwable originalError) {
                param.setOutcome(null, originalError, false);
            }
        }

        Object resultBeforeAfter = param.getResult();
        Throwable throwableBeforeAfter = param.getThrowable();
        try {
            callback.callAfter(param);
        } catch (Throwable callbackError) {
            logCallbackError("after", chain, callbackError);
            param.setOutcome(resultBeforeAfter, throwableBeforeAfter, param.returnEarly);
        }

        Throwable throwable = param.getThrowable();
        if (throwable != null) throw throwable;
        return param.getResult();
    }

    private static void logCallbackError(
            String phase,
            XposedInterface.Chain chain,
            Throwable error
    ) {
        if (!ModuleLog.isEnabled()) return;
        XposedModule value = module;
        String message = "[API102] " + phase + " callback failed for "
                + chain.getExecutable().toGenericString();
        if (value != null) value.log(Log.ERROR, TAG, message, error);
        else Log.e(TAG, message, error);
    }

    public static void log(String message) {
        if (!ModuleLog.isEnabled()) return;
        XposedModule value = module;
        if (value != null) value.log(Log.INFO, TAG, String.valueOf(message));
        else Log.i(TAG, String.valueOf(message));
    }

    public static void log(Throwable error) {
        if (!ModuleLog.isEnabled()) return;
        XposedModule value = module;
        if (value != null) value.log(Log.ERROR, TAG, "Module error", error);
        else Log.e(TAG, "Module error", error);
    }

    private static XposedModule requireModule() {
        XposedModule value = module;
        if (value == null) throw new IllegalStateException("API 102 module is not attached");
        return value;
    }
}
