#include <jni.h>
#include <android/bitmap.h>
#include <math.h>
#include <stdint.h>

/* Managed Android/Xposed callbacks stay in DEX; only allocation-free calculations live here. */
#define PROFILE_VIEW_NORMAL 0
#define PROFILE_VIEW_LOW_LATENCY 1
#define PROFILE_COOLAPK 2
#define PROFILE_PIXEL_COPY 3

static jfloat clamp_float(JNIEnv *env, jclass clazz, jfloat value, jfloat min, jfloat max) {
    (void) env;
    (void) clazz;
    if (value < min) return min;
    if (value > max) return max;
    return value;
}

static jint clamp_int(JNIEnv *env, jclass clazz, jint value, jint min, jint max) {
    (void) env;
    (void) clazz;
    if (value < min) return min;
    if (value > max) return max;
    return value;
}

static jfloat update_ema(JNIEnv *env, jclass clazz, jfloat previous, jfloat sample) {
    (void) env;
    (void) clazz;
    if (!isfinite(sample) || sample <= 0.0f) return previous;
    if (!isfinite(previous) || previous <= 0.0f) return sample;
    return previous * 0.82f + sample * 0.18f;
}

static int64_t budget_for_profile(int profile, float ema) {
    switch (profile) {
        case PROFILE_VIEW_LOW_LATENCY:
            if (ema <= 4.5f) return 12;
            if (ema <= 7.0f) return 16;
            if (ema <= 10.0f) return 20;
            if (ema <= 14.0f) return 24;
            if (ema <= 19.0f) return 32;
            if (ema <= 26.0f) return 42;
            return 56;
        case PROFILE_COOLAPK:
            if (ema <= 5.5f) return 24;
            if (ema <= 8.5f) return 28;
            if (ema <= 12.5f) return 36;
            if (ema <= 17.0f) return 50;
            if (ema <= 24.0f) return 66;
            return 84;
        case PROFILE_PIXEL_COPY:
            if (ema <= 9.0f) return 24;
            if (ema <= 15.0f) return 28;
            if (ema <= 23.0f) return 36;
            if (ema <= 33.0f) return 50;
            if (ema <= 46.0f) return 66;
            return 84;
        case PROFILE_VIEW_NORMAL:
        default:
            if (ema <= 5.5f) return 21;
            if (ema <= 8.5f) return 28;
            if (ema <= 12.5f) return 36;
            if (ema <= 17.0f) return 50;
            if (ema <= 24.0f) return 66;
            return 84;
    }
}

static jlong adaptive_interval(
        JNIEnv *env,
        jclass clazz,
        jint profile,
        jfloat ema,
        jlong moving_interval,
        jlong current_adaptive
) {
    (void) env;
    (void) clazz;
    int64_t budget = budget_for_profile(profile, ema);
    int64_t target = moving_interval > budget ? moving_interval : budget;
    int64_t next;
    if (target > current_adaptive) {
        next = target;
    } else {
        int64_t eased = current_adaptive - 4;
        next = target > eased ? target : eased;
    }
    return (jlong) (next < 1 ? 1 : next);
}

typedef struct {
    AndroidBitmapInfo info;
    void *pixels;
} locked_bitmap;

static int lock_rgba_bitmap(JNIEnv *env, jobject bitmap, locked_bitmap *locked) {
    if (bitmap == NULL || locked == NULL) return 0;
    locked->pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &locked->info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return 0;
    }
    if (locked->info.width == 0 || locked->info.height == 0
            || locked->info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return 0;
    }
    if (AndroidBitmap_lockPixels(env, bitmap, &locked->pixels) != ANDROID_BITMAP_RESULT_SUCCESS
            || locked->pixels == NULL) {
        locked->pixels = NULL;
        return 0;
    }
    return 1;
}

static void unlock_bitmap(JNIEnv *env, jobject bitmap, locked_bitmap *locked) {
    if (locked != NULL && locked->pixels != NULL) {
        AndroidBitmap_unlockPixels(env, bitmap);
        locked->pixels = NULL;
    }
}

static const uint8_t *rgba_at(const locked_bitmap *locked, uint64_t index) {
    uint64_t width = locked->info.width;
    uint64_t x = index % width;
    uint64_t y = index / width;
    const uint8_t *row = (const uint8_t *) locked->pixels + y * locked->info.stride;
    return row + x * 4U;
}

static jboolean native_is_view_draw_blank(
        JNIEnv *env,
        jclass clazz,
        jobject bitmap,
        jboolean check_opaque_black
) {
    (void) clazz;
    locked_bitmap locked;
    if (!lock_rgba_bitmap(env, bitmap, &locked)) return JNI_FALSE;

    uint64_t total = (uint64_t) locked.info.width * locked.info.height;
    uint64_t transparent_stride = total / 384U;
    if (transparent_stride < 1U) transparent_stride = 1U;
    uint64_t transparent = 0U;
    uint64_t sampled = 0U;
    for (uint64_t index = 0U; index < total; index += transparent_stride) {
        const uint8_t *rgba = rgba_at(&locked, index);
        if (rgba[3] <= 4U) transparent++;
        sampled++;
    }
    if (sampled == 0U || transparent * 100U >= sampled * 96U) {
        unlock_bitmap(env, bitmap, &locked);
        return JNI_TRUE;
    }

    if (check_opaque_black) {
        uint64_t black_stride = total / 512U;
        if (black_stride < 1U) black_stride = 1U;
        uint64_t black = 0U;
        sampled = 0U;
        for (uint64_t index = 0U; index < total; index += black_stride) {
            const uint8_t *rgba = rgba_at(&locked, index);
            if (rgba[3] >= 250U && rgba[0] == 0U && rgba[1] == 0U && rgba[2] == 0U) {
                black++;
            }
            sampled++;
        }
        if (sampled == 0U || black * 1000U >= sampled * 985U) {
            unlock_bitmap(env, bitmap, &locked);
            return JNI_TRUE;
        }
    }

    unlock_bitmap(env, bitmap, &locked);
    return JNI_FALSE;
}

static jboolean native_is_view_draw_opaque_black(
        JNIEnv *env,
        jclass clazz,
        jobject bitmap
) {
    (void) clazz;
    locked_bitmap locked;
    if (!lock_rgba_bitmap(env, bitmap, &locked)) return JNI_FALSE;

    uint64_t total = (uint64_t) locked.info.width * locked.info.height;
    uint64_t stride = total / 512U;
    if (stride < 1U) stride = 1U;
    uint64_t black = 0U;
    uint64_t sampled = 0U;
    for (uint64_t index = 0U; index < total; index += stride) {
        const uint8_t *rgba = rgba_at(&locked, index);
        if (rgba[3] >= 250U && rgba[0] == 0U && rgba[1] == 0U && rgba[2] == 0U) {
            black++;
        }
        sampled++;
    }

    unlock_bitmap(env, bitmap, &locked);
    return sampled == 0U || black * 1000U >= sampled * 985U ? JNI_TRUE : JNI_FALSE;
}

static jboolean native_is_legacy_draw_blank(
        JNIEnv *env,
        jclass clazz,
        jobject bitmap,
        jboolean check_dark
) {
    (void) clazz;
    locked_bitmap locked;
    if (!lock_rgba_bitmap(env, bitmap, &locked)) return JNI_FALSE;

    uint32_t width = locked.info.width;
    uint32_t height = locked.info.height;
    uint32_t step_x = width / 12U;
    uint32_t step_y = height / 6U;
    if (step_x < 1U) step_x = 1U;
    if (step_y < 1U) step_y = 1U;

    uint64_t transparent = 0U;
    uint64_t sampled = 0U;
    uint64_t dark = 0U;
    uint64_t visible_sampled = 0U;
    for (uint32_t y = 0U; y < height; y += step_y) {
        const uint8_t *row = (const uint8_t *) locked.pixels + (uint64_t) y * locked.info.stride;
        for (uint32_t x = 0U; x < width; x += step_x) {
            const uint8_t *rgba = row + (uint64_t) x * 4U;
            uint8_t alpha = rgba[3];
            if (alpha < 16U) transparent++;
            if (check_dark && alpha >= 16U) {
                visible_sampled++;
                if ((uint32_t) rgba[0] + rgba[1] + rgba[2] < 30U) dark++;
            }
            sampled++;
        }
    }

    unlock_bitmap(env, bitmap, &locked);
    if (sampled == 0U || transparent * 100U >= sampled * 92U) return JNI_TRUE;
    if (check_dark && visible_sampled > 0U && dark * 100U >= visible_sampled * 96U) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean native_is_window_pixel_copy_blank(
        JNIEnv *env,
        jclass clazz,
        jobject bitmap
) {
    (void) clazz;
    locked_bitmap locked;
    if (!lock_rgba_bitmap(env, bitmap, &locked)) return JNI_FALSE;

    uint64_t total = (uint64_t) locked.info.width * locked.info.height;
    uint64_t stride = total / 512U;
    if (stride < 1U) stride = 1U;
    uint64_t transparent = 0U;
    uint64_t black = 0U;
    uint64_t sampled = 0U;
    for (uint64_t index = 0U; index < total; index += stride) {
        const uint8_t *rgba = rgba_at(&locked, index);
        uint8_t alpha = rgba[3];
        if (alpha <= 4U) transparent++;
        if (alpha >= 250U && rgba[0] == 0U && rgba[1] == 0U && rgba[2] == 0U) black++;
        sampled++;
    }

    unlock_bitmap(env, bitmap, &locked);
    return sampled == 0U
            || transparent * 100U >= sampled * 96U
            || black * 1000U >= sampled * 985U
            ? JNI_TRUE : JNI_FALSE;
}

/*
 * Some video decoder / compositor transitions return PixelCopy.SUCCESS while the copied
 * SurfaceView still contains a yellow/neutral tiled placeholder. It is neither transparent nor
 * black, so the ordinary blank-frame guard accepts it and the liquid-glass layer can cache it.
 *
 * Detect the decoder artifact on the raw Surface bitmap, before host UI is composited. The guard
 * deliberately requires all three signals seen in the bad frame: yellow coverage, neutral-tile
 * coverage, and repeated yellow<->neutral runs on most horizontal probes. A normal colorful or
 * naturally yellow video therefore does not match on color alone.
 */
static jboolean native_is_surface_pixel_copy_placeholder(
        JNIEnv *env,
        jclass clazz,
        jobject bitmap
) {
    (void) clazz;
    locked_bitmap locked;
    if (!lock_rgba_bitmap(env, bitmap, &locked)) return JNI_FALSE;

    const uint32_t width = locked.info.width;
    const uint32_t height = locked.info.height;
    const uint32_t step_x = width > 192U ? width / 192U : 1U;
    const uint32_t step_y = height > 48U ? height / 48U : 1U;
    uint64_t sampled = 0U;
    uint64_t yellow = 0U;
    uint64_t neutral = 0U;
    uint64_t chromatic = 0U;

    for (uint32_t y = 0U; y < height; y += step_y) {
        const uint8_t *row = (const uint8_t *) locked.pixels
                + (uint64_t) y * locked.info.stride;
        for (uint32_t x = 0U; x < width; x += step_x) {
            const uint8_t *rgba = row + (uint64_t) x * 4U;
            const uint32_t r = rgba[0];
            const uint32_t g = rgba[1];
            const uint32_t b = rgba[2];
            const uint32_t a = rgba[3];
            if (a < 200U) continue;
            uint32_t max = r > g ? r : g;
            if (b > max) max = b;
            uint32_t min = r < g ? r : g;
            if (b < min) min = b;
            const uint32_t spread = max - min;
            const uint32_t rg_delta = r > g ? r - g : g - r;
            if (r >= 135U && g >= 125U && b + 35U <= (r < g ? r : g)
                    && rg_delta <= 110U) {
                yellow++;
            }
            if (spread <= 42U && r + g + b >= 120U) neutral++;
            if (spread >= 40U) chromatic++;
            sampled++;
        }
    }

    if (sampled < 64U
            || yellow * 100U < sampled * 12U
            || neutral * 100U < sampled * 10U
            || (yellow + neutral) * 100U < sampled * 50U
            || (chromatic > 0U && yellow * 100U < chromatic * 60U)) {
        unlock_bitmap(env, bitmap, &locked);
        return JNI_FALSE;
    }

    uint32_t patterned_rows = 0U;
    uint32_t total_transitions = 0U;
    for (uint32_t probe = 1U; probe < 8U; probe++) {
        uint32_t y = (uint32_t) (((uint64_t) height * probe) / 8U);
        if (y >= height) y = height - 1U;
        const uint8_t *row = (const uint8_t *) locked.pixels
                + (uint64_t) y * locked.info.stride;
        uint32_t previous_category = 0U;
        uint32_t transitions = 0U;
        for (uint32_t x = 0U; x < width; x += step_x) {
            const uint8_t *rgba = row + (uint64_t) x * 4U;
            if (rgba[3] < 200U) continue;
            const uint32_t r = rgba[0];
            const uint32_t g = rgba[1];
            const uint32_t b = rgba[2];
            uint32_t max = r > g ? r : g;
            if (b > max) max = b;
            uint32_t min = r < g ? r : g;
            if (b < min) min = b;
            const uint32_t rg_delta = r > g ? r - g : g - r;
            uint32_t category = 0U;
            if (r >= 135U && g >= 125U && b + 35U <= (r < g ? r : g)
                    && rg_delta <= 110U) {
                category = 1U;
            } else if (max - min <= 42U && r + g + b >= 120U) {
                category = 2U;
            }
            if (category != 0U) {
                if (previous_category != 0U && category != previous_category) transitions++;
                previous_category = category;
            }
        }
        if (transitions >= 5U) patterned_rows++;
        total_transitions += transitions;
    }

    unlock_bitmap(env, bitmap, &locked);
    return patterned_rows >= 4U && total_transitions >= 35U ? JNI_TRUE : JNI_FALSE;
}

static const JNINativeMethod METHODS[] = {
        {"clampFloat", "(FFF)F", (void *) clamp_float},
        {"clampInt", "(III)I", (void *) clamp_int},
        {"updateEma", "(FF)F", (void *) update_ema},
        {"adaptiveInterval", "(IFJJ)J", (void *) adaptive_interval},
        {"isViewDrawBlank", "(Landroid/graphics/Bitmap;Z)Z", (void *) native_is_view_draw_blank},
        {"isViewDrawOpaqueBlack", "(Landroid/graphics/Bitmap;)Z", (void *) native_is_view_draw_opaque_black},
        {"isLegacyDrawBlank", "(Landroid/graphics/Bitmap;Z)Z", (void *) native_is_legacy_draw_blank},
        {"isWindowPixelCopyBlank", "(Landroid/graphics/Bitmap;)Z", (void *) native_is_window_pixel_copy_blank},
        {"isSurfacePixelCopyPlaceholder", "(Landroid/graphics/Bitmap;)Z", (void *) native_is_surface_pixel_copy_placeholder},
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }
    jclass clazz = (*env)->FindClass(env, "rongyi/lingbai/NativeCore");
    if (clazz == NULL) return JNI_ERR;
    if ((*env)->RegisterNatives(
            env,
            clazz,
            METHODS,
            (jint) (sizeof(METHODS) / sizeof(METHODS[0]))
    ) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
