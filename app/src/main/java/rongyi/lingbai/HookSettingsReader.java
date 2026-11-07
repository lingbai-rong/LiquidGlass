package rongyi.lingbai;

import android.content.Context;
import android.content.SharedPreferences;

/** Loaded only inside the LSPosed target process. */
public final class HookSettingsReader {
    /** Last verified value for this process only; never persisted into the hooked application. */
    private static volatile GlassSettings processCached;

    private HookSettingsReader() {}

    public static GlassSettings load(Context hostContext) {
        synchronized (HookSettingsReader.class) {
            GlassSettings xposedSnapshot = readXposedSnapshot();
            if (xposedSnapshot != null) {
                processCached = xposedSnapshot;
                return xposedSnapshot;
            }

            // Remote Preferences are a live framework-owned snapshot. Keep only a memory fallback
            // for transient read failures; never let it short-circuit a later settings reload.
            GlassSettings fallback = processCached;
            return fallback != null ? fallback : GlassSettings.defaults();
        }
    }

    private static GlassSettings readXposedSnapshot() {
        try {
            SharedPreferences prefs = XposedBridge.getRemotePreferences(GlassSettings.PREF_NAME);
            long revision = prefs.getLong(GlassSettings.KEY_REVISION, -1L);
            if (revision < 0L) return null;
            return new GlassSettings(
                    revision,
                    prefs.getBoolean(GlassSettings.KEY_ENABLED, GlassSettings.DEFAULT_ENABLED),
                    prefs.getInt(GlassSettings.KEY_THEME_MODE, GlassSettings.DEFAULT_THEME_MODE),
                    prefs.getInt(GlassSettings.KEY_TAB_WIDTH, GlassSettings.DEFAULT_TAB_WIDTH),
                    prefs.getInt(GlassSettings.KEY_BOTTOM_PADDING, GlassSettings.DEFAULT_BOTTOM_PADDING),
                    prefs.getInt(GlassSettings.KEY_BLUR_RADIUS, GlassSettings.DEFAULT_BLUR_RADIUS) / 10f,
                    prefs.getInt(GlassSettings.KEY_LIGHT_ALPHA, GlassSettings.DEFAULT_LIGHT_ALPHA) / 100f,
                    prefs.getInt(GlassSettings.KEY_DARK_ALPHA, GlassSettings.DEFAULT_DARK_ALPHA) / 100f,
                    prefs.getInt(GlassSettings.KEY_ICON_SCALE, GlassSettings.DEFAULT_ICON_SCALE) / 100f,
                    prefs.getInt(GlassSettings.KEY_TEXT_SIZE, GlassSettings.DEFAULT_TEXT_SIZE) / 10f,
                    prefs.getBoolean(GlassSettings.KEY_ICON_ONLY, GlassSettings.DEFAULT_ICON_ONLY),
                    prefs.getBoolean(GlassSettings.KEY_SHOW_POST_BUTTON, GlassSettings.DEFAULT_SHOW_POST_BUTTON),
                    prefs.getBoolean(GlassSettings.KEY_CUSTOM_COLOR_ENABLED, GlassSettings.DEFAULT_CUSTOM_COLOR_ENABLED),
                    prefs.getInt(GlassSettings.KEY_CUSTOM_POST_COLOR, GlassSettings.DEFAULT_CUSTOM_POST_COLOR)
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

}
