package rongyi.lingbai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Cross-process snapshot for global render switches. */
public final class GlobalRenderSettingsReader {
    // ContentObservers invalidate immediately and onResume uses force=true. A longer non-forced
    // cache keeps delayed install retries from repeating remote-preference I/O.
    private static final long CACHE_MAX_AGE_MS = 30_000L;
    private static final Object CACHE_LOCK = new Object();
    private static volatile Snapshot processCached;
    private static volatile long processCachedAtMs;

    private GlobalRenderSettingsReader() {}

    public static void apply(Context hostContext, MiuixGlassNavView nav) {
        apply(hostContext, nav, false);
    }

    /**
     * Applies only the global render switches to an existing injected row.
     *
     * This is deliberately lighter than reinstalling the host adapter. Callers use {@code force}
     * after an observer/onResume signal, or from a slow fallback poll when an OEM drops the
     * cross-package ContentObserver notification.
     *
     * @return the durable global revision that was applied, or {@code 0} for untouched defaults.
     */
    public static long apply(
            Context hostContext,
            MiuixGlassNavView nav,
            boolean force
    ) {
        if (nav == null) return 0L;
        Snapshot value = load(hostContext, force);
        nav.setNativeBlurEnabled(value.nativeBlurEnabled);
        nav.setBackdropCaptureEnabled(value.backdropCaptureEnabled);
        nav.setSelectedAccentEnabled(value.selectedAccentEnabled);
        nav.setBottomBarTraditionalChinese(value.bottomBarTraditionalChinese);
        nav.setNavigationHapticsEnabled(value.navigationHapticsEnabled);
        nav.setNavigationStyle(value.navigationStyle);
        return value.revision;
    }

    public static void apply(Context hostContext, CoolapkLegacyGlassNavView nav) {
        apply(hostContext, nav, false);
    }

    public static long apply(
            Context hostContext,
            CoolapkLegacyGlassNavView nav,
            boolean force
    ) {
        if (nav == null) return 0L;
        Snapshot value = load(hostContext, force);
        nav.setNativeBlurEnabled(value.nativeBlurEnabled);
        nav.setBackdropCaptureEnabled(value.backdropCaptureEnabled);
        nav.setSelectedAccentEnabled(value.selectedAccentEnabled);
        nav.setBottomBarTraditionalChinese(value.bottomBarTraditionalChinese);
        nav.setNavigationHapticsEnabled(value.navigationHapticsEnabled);
        nav.setNavigationStyle(value.navigationStyle);
        return value.revision;
    }

    public static void invalidate() {
        synchronized (CACHE_LOCK) {
            // Force a fresh module read while retaining a memory-only fallback if it fails.
            processCachedAtMs = 0L;
        }
    }

    private static Snapshot load(Context hostContext, boolean force) {
        synchronized (CACHE_LOCK) {
            long now = SystemClock.elapsedRealtime();
            Snapshot cached = processCached;
            if (!force && cached != null && processCachedAtMs > 0L
                    && now - processCachedAtMs <= CACHE_MAX_AGE_MS) {
                return cached;
            }

            Snapshot xposed = readXposed();
            Snapshot value = xposed != null ? xposed : cached;
            if (value == null) value = Snapshot.defaults();

            processCached = value;
            processCachedAtMs = SystemClock.elapsedRealtime();
            return value;
        }
    }

    private static Snapshot readXposed() {
        try {
            SharedPreferences preferences = XposedBridge.getRemotePreferences(
                    GlobalAppearanceStore.PREF_NAME
            );

            long revision = preferences.getLong(GlobalAppearanceStore.KEY_RENDER_REVISION, 0L);
            if (revision <= 0L) return null;

            boolean backdropCaptureEnabled;
            if (preferences.contains(GlobalAppearanceStore.KEY_BACKDROP_CAPTURE_DISABLED)) {
                backdropCaptureEnabled = !preferences.getBoolean(
                        GlobalAppearanceStore.KEY_BACKDROP_CAPTURE_DISABLED,
                        false
                );
            } else if (preferences.contains(GlobalAppearanceStore.KEY_BACKDROP_CAPTURE_ENABLED)) {
                backdropCaptureEnabled = preferences.getBoolean(
                        GlobalAppearanceStore.KEY_BACKDROP_CAPTURE_ENABLED,
                        GlobalAppearanceStore.DEFAULT_BACKDROP_CAPTURE_ENABLED
                );
            } else {
                backdropCaptureEnabled = GlobalAppearanceStore.DEFAULT_BACKDROP_CAPTURE_ENABLED;
            }

            boolean nativeBlurEnabled = preferences.getBoolean(
                    GlobalAppearanceStore.KEY_NATIVE_BLUR_ENABLED,
                    GlobalAppearanceStore.DEFAULT_NATIVE_BLUR_ENABLED
            );
            boolean selectedAccentEnabled = preferences.getBoolean(
                    GlobalAppearanceStore.KEY_SELECTED_ACCENT_ENABLED,
                    GlobalAppearanceStore.DEFAULT_SELECTED_ACCENT_ENABLED
            );
            boolean bottomBarTraditionalChinese = preferences.getInt(
                    GlobalAppearanceStore.KEY_BOTTOM_BAR_LANGUAGE,
                    GlobalAppearanceStore.DEFAULT_BOTTOM_BAR_LANGUAGE
            ) == GlobalAppearanceStore.BOTTOM_BAR_LANGUAGE_TRADITIONAL;
            String navigationStyle;
            try {
                navigationStyle = InjectedNavigationStyle.normalize(preferences.getString(
                        GlobalAppearanceStore.KEY_NAVIGATION_STYLE,
                        GlobalAppearanceStore.DEFAULT_NAVIGATION_STYLE
                ));
            } catch (ClassCastException malformedStyle) {
                // A corrupt new key must not prevent existing render switches from updating.
                navigationStyle = GlobalAppearanceStore.DEFAULT_NAVIGATION_STYLE;
            }
            boolean navigationHapticsEnabled;
            try {
                navigationHapticsEnabled = preferences.getBoolean(
                        GlobalAppearanceStore.KEY_NAVIGATION_HAPTICS_ENABLED,
                        GlobalAppearanceStore.DEFAULT_NAVIGATION_HAPTICS_ENABLED);
            } catch (ClassCastException malformedHaptics) {
                navigationHapticsEnabled = GlobalAppearanceStore.DEFAULT_NAVIGATION_HAPTICS_ENABLED;
            }
            return new Snapshot(
                    backdropCaptureEnabled,
                    nativeBlurEnabled,
                    selectedAccentEnabled,
                    bottomBarTraditionalChinese,
                    navigationStyle,
                    navigationHapticsEnabled,
                    revision
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class Snapshot {
        final boolean backdropCaptureEnabled;
        final boolean nativeBlurEnabled;
        final boolean selectedAccentEnabled;
        final boolean bottomBarTraditionalChinese;
        final String navigationStyle;
        final boolean navigationHapticsEnabled;
        final long revision;

        Snapshot(
                boolean backdropCaptureEnabled,
                boolean nativeBlurEnabled,
                boolean selectedAccentEnabled,
                boolean bottomBarTraditionalChinese,
                String navigationStyle,
                boolean navigationHapticsEnabled,
                long revision
        ) {
            this.backdropCaptureEnabled = backdropCaptureEnabled;
            this.nativeBlurEnabled = nativeBlurEnabled;
            this.selectedAccentEnabled = selectedAccentEnabled;
            this.bottomBarTraditionalChinese = bottomBarTraditionalChinese;
            this.navigationStyle = InjectedNavigationStyle.normalize(navigationStyle);
            this.navigationHapticsEnabled = navigationHapticsEnabled;
            this.revision = Math.max(0L, revision);
        }

        static Snapshot defaults() {
            return new Snapshot(
                    GlobalAppearanceStore.DEFAULT_BACKDROP_CAPTURE_ENABLED,
                    GlobalAppearanceStore.DEFAULT_NATIVE_BLUR_ENABLED,
                    GlobalAppearanceStore.DEFAULT_SELECTED_ACCENT_ENABLED,
                    GlobalAppearanceStore.DEFAULT_BOTTOM_BAR_LANGUAGE
                            == GlobalAppearanceStore.BOTTOM_BAR_LANGUAGE_TRADITIONAL,
                    GlobalAppearanceStore.DEFAULT_NAVIGATION_STYLE,
                    GlobalAppearanceStore.DEFAULT_NAVIGATION_HAPTICS_ENABLED,
                    0L
            );
        }
    }
}
