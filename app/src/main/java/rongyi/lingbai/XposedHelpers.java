package rongyi.lingbai;

import java.lang.reflect.Method;

/** Reflection helpers retained locally so no legacy Xposed API is referenced by the module. */
public final class XposedHelpers {
    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("Class not found: " + className, error);
        }
    }

    public static Object callMethod(Object receiver, String name, Object... arguments) {
        if (receiver == null) throw new IllegalArgumentException("Null receiver for " + name);
        for (Class<?> type = receiver.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                Class<?>[] parameters = method.getParameterTypes();
                boolean compatible = true;
                for (int i = 0; i < parameters.length; i++) {
                    Class<?> parameter = parameters[i];
                    if (parameter.isPrimitive()) {
                        if (parameter == int.class) parameter = Integer.class;
                        else if (parameter == boolean.class) parameter = Boolean.class;
                        else if (parameter == long.class) parameter = Long.class;
                        else if (parameter == float.class) parameter = Float.class;
                        else if (parameter == double.class) parameter = Double.class;
                        else if (parameter == byte.class) parameter = Byte.class;
                        else if (parameter == short.class) parameter = Short.class;
                        else if (parameter == char.class) parameter = Character.class;
                    }
                    if (arguments[i] != null && !parameter.isInstance(arguments[i])) { compatible = false; break; }
                }
                if (!compatible) continue;
                try { method.setAccessible(true); return method.invoke(receiver, arguments); }
                catch (Throwable error) { throw new IllegalStateException("Cannot invoke " + name, error); }
            }
        }
        throw new IllegalStateException("Method not found: " + receiver.getClass().getName() + "#" + name);
    }

    public static Object findAndHookMethod(
            Class<?> type,
            String methodName,
            Object... parameterTypesAndCallback
    ) {
        if (type == null) throw new IllegalArgumentException("Hook class is null");
        if (parameterTypesAndCallback == null || parameterTypesAndCallback.length == 0) {
            throw new IllegalArgumentException("Missing hook callback");
        }
        Object callbackValue = parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        if (!(callbackValue instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("Last argument is not an XC_MethodHook");
        }

        Class<?>[] parameterTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object value = parameterTypesAndCallback[i];
            if (value instanceof Class<?>) {
                parameterTypes[i] = (Class<?>) value;
            } else if (value instanceof String) {
                parameterTypes[i] = findClass((String) value, type.getClassLoader());
            } else {
                throw new IllegalArgumentException("Invalid parameter type: " + value);
            }
        }

        Method method = findMethod(type, methodName, parameterTypes);
        return XposedBridge.hookMethod(method, (XC_MethodHook) callbackValue);
    }

    private static Method findMethod(Class<?> type, String methodName, Class<?>[] parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException(
                "Method not found: " + type.getName() + "#" + methodName,
                new NoSuchMethodException(methodName)
        );
    }
}
