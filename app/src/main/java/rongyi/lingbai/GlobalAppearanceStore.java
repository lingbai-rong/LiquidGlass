package rongyi.lingbai;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One appearance profile shared by every LiquidGlass host.
 *
 * App-specific switches (enabled, theme, channel/post options) remain independent. Only the
 * physical bar dimensions, glass strength, host navigation style and vibration are synchronized globally.
 */
public final class GlobalAppearanceStore {
    public static final String PREF_NAME = "global_glass_appearance";
    private static final String KEY_PRESENT = "present";
    public static final String KEY_BACKDROP_CAPTURE_ENABLED = "backdrop_capture_enabled";
    /**
     * v2 stores the checkbox exactly as presented to the user: checked means capture disabled.
     * The older enabled key is read only as a migration fallback when v2 has never been written.
     */
    public static final String KEY_BACKDROP_CAPTURE_DISABLED = "backdrop_capture_disabled_v2";
    public static final boolean DEFAULT_BACKDROP_CAPTURE_ENABLED = true;
    public static final String KEY_NATIVE_BLUR_ENABLED = "native_blur_enabled";
    public static final boolean DEFAULT_NATIVE_BLUR_ENABLED = false;
    public static final String KEY_SELECTED_ACCENT_ENABLED = "selected_accent_enabled";
    public static final boolean DEFAULT_SELECTED_ACCENT_ENABLED = false;
    /** Render-only language for labels in every injected bottom bar. */
    public static final String KEY_BOTTOM_BAR_LANGUAGE = "bottom_bar_language";
    public static final int BOTTOM_BAR_LANGUAGE_SIMPLIFIED = 0;
    public static final int BOTTOM_BAR_LANGUAGE_TRADITIONAL = 1;
    public static final int DEFAULT_BOTTOM_BAR_LANGUAGE = BOTTOM_BAR_LANGUAGE_SIMPLIFIED;
    public static final String KEY_NAVIGATION_STYLE = "host_navigation_style";
    public static final String DEFAULT_NAVIGATION_STYLE = InjectedNavigationStyle.LIQUID_GLASS;
    public static final String KEY_NAVIGATION_HAPTICS_ENABLED = "host_navigation_haptics_enabled";
    public static final boolean DEFAULT_NAVIGATION_HAPTICS_ENABLED = true;
    public static final String KEY_RENDER_REVISION = "render_revision";

    private GlobalAppearanceStore() {}


    public static boolean isBackdropCaptureEnabled(Context context) {
        Context storage = GlassSettings.storageContext(context);
        if (storage == null) return DEFAULT_BACKDROP_CAPTURE_ENABLED;
        try {
            SharedPreferences preferences = HookPreferenceTransport.open(storage, PREF_NAME);
            if (preferences.contains(KEY_BACKDROP_CAPTURE_DISABLED)) {
                return !preferences.getBoolean(KEY_BACKDROP_CAPTURE_DISABLED, false);
            }
            if (preferences.contains(KEY_BACKDROP_CAPTURE_ENABLED)) {
                return preferences.getBoolean(
                        KEY_BACKDROP_CAPTURE_ENABLED, DEFAULT_BACKDROP_CAPTURE_ENABLED
                );
            }
            return DEFAULT_BACKDROP_CAPTURE_ENABLED;
        } catch (Throwable ignored) {
            return DEFAULT_BACKDROP_CAPTURE_ENABLED;
        }
    }

    public static long getRenderRevision(Context context) {
        Context storage = GlassSettings.storageContext(context);
        if (storage == null) return 0L;
        try {
            return HookPreferenceTransport.open(storage, PREF_NAME)
                    .getLong(KEY_RENDER_REVISION, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static void setBackdropCaptureEnabled(Context context, boolean enabled) {
        updateGlobalOptions(context, enabled, null, null, null, null, null);
    }

    public static boolean isNativeBlurEnabled(Context context) {
        Context storage = GlassSettings.storageContext(context);
        if (storage == null) return DEFAULT_NATIVE_BLUR_ENABLED;
        try {
            return HookPreferenceTransport.open(storage, PREF_NAME)
                    .getBoolean(KEY_NATIVE_BLUR_ENABLED, DEFAULT_NATIVE_BLUR_ENABLED);
        } catch (Throwable ignored) {
            return DEFAULT_NATIVE_BLUR_ENABLED;
        }
    }

    public static void setNativeBlurEnabled(Context context, boolean enabled) {
        updateGlobalOptions(context, null, enabled, null, null, null, null);
    }

    public static boolean isSelectedAccentEnabled(Context context) {
        Context storage = GlassSettings.storageContext(context);
        if (storage == null) return DEFAULT_SELECTED_ACCENT_ENABLED;
        try {
            return HookPreferenceTransport.open(storage, PREF_NAME)
                    .getBoolean(KEY_SELECTED_ACCENT_ENABLED, DEFAULT_SELECTED_ACCENT_ENABLED);
        } catch (Throwable ignored) {
            return DEFAULT_SELECTED_ACCENT_ENABLED;
        }
    }

    public static void setSelectedAccentEnabled(Context context, boolean enabled) {
        updateGlobalOptions(context, null, null, enabled, null, null, null);
    }

    public static int getBottomBarLanguage(Context context) {
        Context storage = GlassSettings.storageContext(context);
        if (storage == null) return DEFAULT_BOTTOM_BAR_LANGUAGE;
        try {
            return normalizeBottomBarLanguage(
                    HookPreferenceTransport.open(storage, PREF_NAME)
                            .getInt(KEY_BOTTOM_BAR_LANGUAGE, DEFAULT_BOTTOM_BAR_LANGUAGE)
            );
        } catch (Throwable ignored) {
            return DEFAULT_BOTTOM_BAR_LANGUAGE;
        }
    }

    public static boolean isBottomBarTraditionalChinese(Context context) {
        return getBottomBarLanguage(context) == BOTTOM_BAR_LANGUAGE_TRADITIONAL;
    }

    public static void setBottomBarLanguage(Context context, int language) {
        updateGlobalOptions(context, null, null, null, normalizeBottomBarLanguage(language), null, null);
    }

    public static String getNavigationStyle(Context context) {
        Context storage = GlassSettings.storageContext(context);
        if (storage == null) return DEFAULT_NAVIGATION_STYLE;
        try {
            return InjectedNavigationStyle.normalize(HookPreferenceTransport.open(storage, PREF_NAME)
                    .getString(KEY_NAVIGATION_STYLE, DEFAULT_NAVIGATION_STYLE));
        } catch (Throwable ignored) {
            return DEFAULT_NAVIGATION_STYLE;
        }
    }

    public static void setNavigationStyle(Context context, String style) {
        updateGlobalOptions(context, null, null, null, null, InjectedNavigationStyle.normalize(style), null);
    }

    public static boolean isNavigationHapticsEnabled(Context context) {
        Context storage = GlassSettings.storageContext(context);
        if (storage == null) return DEFAULT_NAVIGATION_HAPTICS_ENABLED;
        try {
            return HookPreferenceTransport.open(storage, PREF_NAME).getBoolean(
                    KEY_NAVIGATION_HAPTICS_ENABLED, DEFAULT_NAVIGATION_HAPTICS_ENABLED);
        } catch (Throwable ignored) {
            return DEFAULT_NAVIGATION_HAPTICS_ENABLED;
        }
    }

    public static void setNavigationHapticsEnabled(Context context, boolean enabled) {
        updateGlobalOptions(context, null, null, null, null, null, enabled);
    }

    public static void setBottomBarTraditionalChinese(Context context, boolean enabled) {
        setBottomBarLanguage(
                context,
                enabled ? BOTTOM_BAR_LANGUAGE_TRADITIONAL : BOTTOM_BAR_LANGUAGE_SIMPLIFIED
        );
    }

    private static void updateGlobalOptions(
            Context context,
            Boolean backdropCaptureEnabled,
            Boolean nativeBlurEnabled,
            Boolean selectedAccentEnabled,
            Integer bottomBarLanguage,
            String navigationStyle,
            Boolean navigationHapticsEnabled
    ) {
        if (context == null) return;
        long revision = Math.max(System.currentTimeMillis(), getRenderRevision(context) + 1L);
        Context storage = GlassSettings.storageContext(context);
        writeGlobalOptions(
                storage,
                backdropCaptureEnabled,
                nativeBlurEnabled,
                selectedAccentEnabled,
                bottomBarLanguage,
                navigationStyle,
                navigationHapticsEnabled,
                revision
        );
        try {
            if (!context.isDeviceProtectedStorage()) {
                writeGlobalOptions(
                        context,
                        backdropCaptureEnabled,
                        nativeBlurEnabled,
                        selectedAccentEnabled,
                        bottomBarLanguage,
                        navigationStyle,
                        navigationHapticsEnabled,
                        revision
                );
            }
        } catch (Throwable ignored) {
        }
        try {
            context.getContentResolver().notifyChange(GlassSettings.CONTENT_URI, null);
        } catch (Throwable ignored) {
        }
    }

    private static void writeGlobalOptions(
            Context context,
            Boolean backdropCaptureEnabled,
            Boolean nativeBlurEnabled,
            Boolean selectedAccentEnabled,
            Integer bottomBarLanguage,
            String navigationStyle,
            Boolean navigationHapticsEnabled,
            long revision
    ) {
        if (context == null) return;
        try {
            SharedPreferences.Editor editor = HookPreferenceTransport.open(context, PREF_NAME)
                    .edit();
            if (backdropCaptureEnabled != null) {
                editor.putBoolean(KEY_BACKDROP_CAPTURE_DISABLED, !backdropCaptureEnabled);
                editor.putBoolean(KEY_BACKDROP_CAPTURE_ENABLED, backdropCaptureEnabled);
            }
            if (nativeBlurEnabled != null) {
                editor.putBoolean(KEY_NATIVE_BLUR_ENABLED, nativeBlurEnabled);
            }
            if (selectedAccentEnabled != null) {
                editor.putBoolean(KEY_SELECTED_ACCENT_ENABLED, selectedAccentEnabled);
            }
            if (bottomBarLanguage != null) {
                editor.putInt(KEY_BOTTOM_BAR_LANGUAGE, normalizeBottomBarLanguage(bottomBarLanguage));
            }
            if (navigationStyle != null) {
                editor.putString(KEY_NAVIGATION_STYLE, InjectedNavigationStyle.normalize(navigationStyle));
            }
            if (navigationHapticsEnabled != null) {
                editor.putBoolean(KEY_NAVIGATION_HAPTICS_ENABLED, navigationHapticsEnabled);
            }
            editor.putLong(KEY_RENDER_REVISION, revision);
            // Hooks are notified immediately after this method. Commit before notifyChange so
            // Remote Preferences readers and their memory-only caches observe the same value.
            editor.commit();
        } catch (Throwable ignored) {
        }
    }

    public static GlassSettings applyTo(Context context, GlassSettings profile) {
        if (profile == null) profile = GlassSettings.defaults();
        Context storage = GlassSettings.storageContext(context);
        if (storage == null) return profile;
        try {
            SharedPreferences p = HookPreferenceTransport.open(storage, PREF_NAME);
            if (!p.getBoolean(KEY_PRESENT, false)) return profile;
            return withAppearance(
                    profile,
                    p.getInt(GlassSettings.KEY_TAB_WIDTH, Math.round(profile.tabWidthDp)),
                    p.getInt(GlassSettings.KEY_BOTTOM_PADDING, Math.round(profile.bottomPaddingDp)),
                    p.getInt(GlassSettings.KEY_BLUR_RADIUS, Math.round(profile.blurRadiusDp * 10f)) / 10f,
                    p.getInt(GlassSettings.KEY_LIGHT_ALPHA, Math.round(profile.lightAlpha * 100f)) / 100f,
                    p.getInt(GlassSettings.KEY_DARK_ALPHA, Math.round(profile.darkAlpha * 100f)) / 100f,
                    p.getInt(GlassSettings.KEY_ICON_SCALE, Math.round(profile.iconScale * 100f)) / 100f,
                    p.getInt(GlassSettings.KEY_TEXT_SIZE, Math.round(profile.textSizeSp * 10f)) / 10f
            );
        } catch (Throwable ignored) {
            return profile;
        }
    }

    public static void saveAndSyncAll(Context context, GlassSettings source) {
        if (context == null || source == null) return;
        Context storage = GlassSettings.storageContext(context);
        writeAppearanceProfile(storage, source);
        try {
            if (!context.isDeviceProtectedStorage()) writeAppearanceProfile(context, source);
        } catch (Throwable ignored) {
        }
        long revision = Math.max(System.currentTimeMillis(), source.revision + 1L);
        try {
            GlassSettings coolapk = withAppearance(GlassSettings.loadLocal(context), source).withRevision(revision);
            GlassSettings.saveLocal(context, coolapk);
        } catch (Throwable ignored) {
        }
        // Previous builds claimed this profile was synchronized to every app, but only the legacy stores above were updated. Copy only appearance fields into every ordinary target
        // profile while retaining each app's enabled/theme/tab-hiding/post-button choices.
        long targetRevision = revision + 2L;
        for (TargetApp app : TargetApp.all()) {
            try {
                TargetAppSettings current = TargetAppSettings.loadLocal(context, app);
                TargetAppSettings updated = current.withAppearance(
                        current.themeMode,
                        source.tabWidthDp,
                        source.bottomPaddingDp,
                        source.blurRadiusDp,
                        source.lightAlpha,
                        source.darkAlpha,
                        source.iconScale,
                        source.textSizeSp,
                        current.iconOnly
                ).withRevision(targetRevision++);
                TargetAppSettings.saveLocal(context, app, updated, false);
            } catch (Throwable ignored) {
            }
        }
        GlassSettings.notifyChanged(context);
    }

    private static void writeAppearanceProfile(Context context, GlassSettings source) {
        if (context == null || source == null) return;
        try {
            HookPreferenceTransport.open(context, PREF_NAME).edit()
                    .putBoolean(KEY_PRESENT, true)
                    .putInt(GlassSettings.KEY_TAB_WIDTH, Math.round(source.tabWidthDp))
                    .putInt(GlassSettings.KEY_BOTTOM_PADDING, Math.round(source.bottomPaddingDp))
                    .putInt(GlassSettings.KEY_BLUR_RADIUS, Math.round(source.blurRadiusDp * 10f))
                    .putInt(GlassSettings.KEY_LIGHT_ALPHA, Math.round(source.lightAlpha * 100f))
                    .putInt(GlassSettings.KEY_DARK_ALPHA, Math.round(source.darkAlpha * 100f))
                    .putInt(GlassSettings.KEY_ICON_SCALE, Math.round(source.iconScale * 100f))
                    .putInt(GlassSettings.KEY_TEXT_SIZE, Math.round(source.textSizeSp * 10f))
                    .commit();
        } catch (Throwable ignored) {
        }
    }

    public static GlassSettings resetAppearanceOnly(GlassSettings current) {
        if (current == null) current = GlassSettings.defaults();
        GlassSettings defaults = GlassSettings.defaults();
        return withAppearance(current, defaults);
    }

    public static GlassSettings withAppearance(GlassSettings profile, GlassSettings appearance) {
        return withAppearance(
                profile,
                appearance.tabWidthDp,
                appearance.bottomPaddingDp,
                appearance.blurRadiusDp,
                appearance.lightAlpha,
                appearance.darkAlpha,
                appearance.iconScale,
                appearance.textSizeSp
        );
    }

    private static GlassSettings withAppearance(
            GlassSettings profile,
            float tabWidth,
            float bottomPadding,
            float blur,
            float lightAlpha,
            float darkAlpha,
            float iconScale,
            float textSize
    ) {
        return new GlassSettings(
                profile.revision,
                profile.enabled,
                profile.themeMode,
                tabWidth,
                bottomPadding,
                blur,
                lightAlpha,
                darkAlpha,
                iconScale,
                textSize,
                profile.iconOnly,
                profile.showPostButton,
                profile.customColorEnabled,
                profile.customPostColor,
                profile.showChannel
        );
    }

    private static int normalizeBottomBarLanguage(int language) {
        return language == BOTTOM_BAR_LANGUAGE_TRADITIONAL
                ? BOTTOM_BAR_LANGUAGE_TRADITIONAL
                : BOTTOM_BAR_LANGUAGE_SIMPLIFIED;
    }
}
