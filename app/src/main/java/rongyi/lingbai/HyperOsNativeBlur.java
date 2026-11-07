package rongyi.lingbai;

import android.graphics.Color;
import android.os.Build;
import android.view.View;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Best-effort bridge to Xiaomi/HyperOS RenderThread background blur.
 *
 * The Xiaomi framework adds hidden methods directly to android.view.View. They blur the pixels
 * behind a View in SurfaceFlinger/RenderThread, so no Bitmap, Canvas.draw(), PixelCopy or polling is
 * involved. The result is compositor output only: these methods never return readable pixels and
 * cannot be used as the ImageBitmap input of the refraction shader. All calls are guarded and the
 * caller must keep the existing bitmap path as the separate full-refraction mode.
 */
final class HyperOsNativeBlur {
    private static volatile boolean resolved;
    private static volatile boolean available;
    private static Method setBackgroundBlurMode;
    private static Method setBackgroundBlurRadius;
    private static Method setViewBlurMode;
    private static Method clearBlendColors;
    private static Method addBlendColor;

    private HyperOsNativeBlur() {}

    static boolean isDeviceCandidate() {
        String manufacturer = String.valueOf(Build.MANUFACTURER).toLowerCase(Locale.ROOT);
        String brand = String.valueOf(Build.BRAND).toLowerCase(Locale.ROOT);
        return manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
                brand.contains("redmi") || brand.contains("poco");
    }

    static boolean isAvailable() {
        resolve();
        return available;
    }

    static boolean enable(View view, int radiusPx, int tintColor) {
        if (view == null || radiusPx <= 0 || !isDeviceCandidate()) return false;
        resolve();
        if (!available) return false;
        try {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            setBackgroundBlurMode.invoke(view, 1);
            setBackgroundBlurRadius.invoke(view, Math.max(1, Math.min(radiusPx, 150)));
            setViewBlurMode.invoke(view, 1);
            if (clearBlendColors != null) clearBlendColors.invoke(view);
            if (addBlendColor != null && Color.alpha(tintColor) > 0) {
                // 103 is Xiaomi's SRC_OVER-style background blend used by Miuix blur surfaces.
                addBlendColor.invoke(view, tintColor, 103);
            }
            view.invalidate();
            return true;
        } catch (Throwable ignored) {
            disable(view);
            return false;
        }
    }

    static void disable(View view) {
        if (view == null) return;
        resolve();
        try { if (clearBlendColors != null) clearBlendColors.invoke(view); } catch (Throwable ignored) {}
        try { if (setBackgroundBlurRadius != null) setBackgroundBlurRadius.invoke(view, 0); } catch (Throwable ignored) {}
        try { if (setViewBlurMode != null) setViewBlurMode.invoke(view, 0); } catch (Throwable ignored) {}
        try { if (setBackgroundBlurMode != null) setBackgroundBlurMode.invoke(view, 0); } catch (Throwable ignored) {}
        try { view.invalidate(); } catch (Throwable ignored) {}
    }

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        if (!isDeviceCandidate()) return;
        try {
            Class<?> clazz = View.class;
            setBackgroundBlurMode = find(clazz, "setMiBackgroundBlurMode", int.class);
            setBackgroundBlurRadius = find(clazz, "setMiBackgroundBlurRadius", int.class);
            setViewBlurMode = find(clazz, "setMiViewBlurMode", int.class);
            clearBlendColors = findOptional(clazz, "clearMiBackgroundBlendColor");
            addBlendColor = findOptional(clazz, "addMiBackgroundBlendColor", int.class, int.class);
            available = setBackgroundBlurMode != null && setBackgroundBlurRadius != null &&
                    setViewBlurMode != null;
        } catch (Throwable ignored) {
            available = false;
        }
    }

    private static Method find(Class<?> clazz, String name, Class<?>... parameterTypes) throws Exception {
        Method method;
        try {
            method = clazz.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            method = clazz.getDeclaredMethod(name, parameterTypes);
        }
        method.setAccessible(true);
        return method;
    }

    private static Method findOptional(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            return find(clazz, name, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
