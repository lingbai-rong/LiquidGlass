package rongyi.lingbai;

import android.graphics.Bitmap;

/** Direct JNI declarations for pure policies implemented in libliquidglass_core.so. */
public final class NativeCore {
    public static final int PROFILE_VIEW_NORMAL = 0;
    public static final int PROFILE_VIEW_LOW_LATENCY = 1;
    public static final int PROFILE_COOLAPK = 2;
    public static final int PROFILE_PIXEL_COPY = 3;

    static {
        System.loadLibrary("liquidglass_core");
    }

    private NativeCore() {}

    public static native float clampFloat(float value, float min, float max);
    public static native int clampInt(int value, int min, int max);
    public static native float updateEma(float previous, float sample);
    public static native long adaptiveInterval(
            int profile,
            float ema,
            long movingInterval,
            long currentAdaptive
    );
    public static native boolean isViewDrawBlank(Bitmap bitmap, boolean checkOpaqueBlack);
    public static native boolean isViewDrawOpaqueBlack(Bitmap bitmap);
    public static native boolean isLegacyDrawBlank(Bitmap bitmap, boolean checkDark);
    public static native boolean isWindowPixelCopyBlank(Bitmap bitmap);
    /** Detects the yellow/neutral tiled decoder placeholder occasionally returned as SUCCESS. */
    public static native boolean isSurfacePixelCopyPlaceholder(Bitmap bitmap);

}
