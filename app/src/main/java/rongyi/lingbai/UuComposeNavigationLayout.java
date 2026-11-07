package rongyi.lingbai;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;

/** Target-side layout contract; never loads the module's Compose types into UU. */
final class UuComposeNavigationLayout {
    private final Class<?> modifierType;
    private final Method layout;
    private final Object measure;

    private UuComposeNavigationLayout(Class<?> modifierType, Method layout, Object measure) {
        this.modifierType = modifierType;
        this.layout = layout;
        this.measure = measure;
    }

    static UuComposeNavigationLayout create(ClassLoader loader) {
        if (loader == null) return null;
        try {
            return create(Class.forName("H0.o", false, loader),
                    Class.forName("androidx.compose.ui.layout.a", false, loader),
                    Class.forName("Nd.g", false, loader),
                    Class.forName("d1.M", false, loader),
                    Class.forName("Nd.d", false, loader),
                    Class.forName("zd.A", false, loader).getField("a").get(null));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    // Injectable target contracts let JVM tests exercise the actual proxy callbacks.
    static UuComposeNavigationLayout create(Class<?> modifierType, Class<?> layoutType,
            Class<?> measureType, Class<?> scopeType, Class<?> placementType, Object unit) {
        try {
            if (!modifierType.isInterface() || !measureType.isInterface()
                    || !placementType.isInterface() || unit == null) return null;
            Method layout = layoutType.getDeclaredMethod("b", modifierType, measureType);
            Method measureCall = measureType.getMethod("b", Object.class, Object.class, Object.class);
            Method placementCall = placementType.getMethod("invoke", Object.class);
            Method result = scopeType.getMethod("v0", int.class, int.class, Map.class, placementType);
            if (!Modifier.isStatic(layout.getModifiers()) || layout.getReturnType() != modifierType
                    || measureCall.getReturnType() != Object.class
                    || placementCall.getReturnType() != Object.class
                    || result.getReturnType().isPrimitive()) return null;
            layout.setAccessible(true);
            result.setAccessible(true);
            Object placement = Proxy.newProxyInstance(placementType.getClassLoader(),
                    new Class<?>[]{placementType}, (proxy, method, args) -> {
                        if (method.equals(placementCall)) return unit;
                        return objectMethod(proxy, method, args);
                    });
            Object measure = Proxy.newProxyInstance(measureType.getClassLoader(),
                    new Class<?>[]{measureType}, (proxy, method, args) -> {
                        if (method.equals(measureCall)) {
                            // Keep composition alive, but never measure or place the native subtree.
                            return result.invoke(args[0], 0, 0, Collections.emptyMap(), placement);
                        }
                        return objectMethod(proxy, method, args);
                    });
            return new UuComposeNavigationLayout(modifierType, layout, measure);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    Object append(Object modifier) {
        if (!modifierType.isInstance(modifier)) return null;
        try {
            Object hidden = layout.invoke(null, modifier, measure);
            return modifierType.isInstance(hidden) ? hidden : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "equals": return proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "LiquidGlassUuCollapsedNavigation";
                default: break;
            }
        }
        throw new UnsupportedOperationException(method.toString());
    }

    /** Bounds the shared materialize hook to the exact navigation composable's outer layout. */
    static final class Scope {
        private final ThreadLocal<ArrayDeque<Frame>> frames = new ThreadLocal<>();

        void enter(Object composer) {
            ArrayDeque<Frame> stack = frames.get();
            if (stack == null) {
                stack = new ArrayDeque<>();
                frames.set(stack);
            }
            stack.push(new Frame(composer));
        }

        void exit() {
            ArrayDeque<Frame> stack = frames.get();
            if (stack == null) return;
            stack.pop();
            if (stack.isEmpty()) frames.remove();
        }

        boolean claimRoot(Object composer, Object modifier, Object emptyModifier) {
            ArrayDeque<Frame> stack = frames.get();
            if (stack == null || stack.isEmpty()) return false;
            Frame frame = stack.peek();
            if (composer == null || frame.composer != composer || frame.consumed) return false;
            frame.consumed = true;
            return emptyModifier != null && modifier == emptyModifier;
        }

        private static final class Frame {
            final Object composer;
            boolean consumed;
            Frame(Object composer) { this.composer = composer; }
        }
    }
}
