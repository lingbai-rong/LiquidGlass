package rongyi.lingbai;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.AtomicFile;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Shared settings used by both the launcher app and the LSPosed hook process. */
public final class GlassSettings {
    public static final String MODULE_PACKAGE = "rongyi.lingbai";
    public static final String PREF_NAME = "glass_settings";
    // Notification-only URI. No ContentProvider is registered for this authority.
    public static final String AUTHORITY = MODULE_PACKAGE + ".settings";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/config");

    private static final String SNAPSHOT_FILE = "glass_settings_snapshot.bin";
    private static final int SNAPSHOT_MAGIC = 0x434D474E; // CMGN
    private static final int SNAPSHOT_VERSION = 5;

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    public static final String KEY_REVISION = "revision";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_THEME_MODE = "theme_mode";
    public static final String KEY_TAB_WIDTH = "tab_width_dp";
    public static final String KEY_BOTTOM_PADDING = "bottom_padding_dp";
    public static final String KEY_BLUR_RADIUS = "blur_radius_tenths";
    public static final String KEY_LIGHT_ALPHA = "light_alpha_percent";
    public static final String KEY_DARK_ALPHA = "dark_alpha_percent";
    public static final String KEY_ICON_SCALE = "icon_scale_percent";
    public static final String KEY_TEXT_SIZE = "text_size_tenths";
    public static final String KEY_ICON_ONLY = "icon_only";
    public static final String KEY_SHOW_POST_BUTTON = "show_post_button";
    public static final String KEY_CUSTOM_COLOR_ENABLED = "custom_color_enabled";
    public static final String KEY_CUSTOM_POST_COLOR = "custom_post_color";
    public static final String KEY_SHOW_CHANNEL = "show_channel";

    public static final boolean DEFAULT_ENABLED = true;
    public static final int DEFAULT_THEME_MODE = THEME_SYSTEM;
    public static final int DEFAULT_TAB_WIDTH = 76;
    public static final int DEFAULT_BOTTOM_PADDING = 12;
    public static final int DEFAULT_BLUR_RADIUS = 40; // 4.0 dp
    public static final int DEFAULT_LIGHT_ALPHA = 40;
    public static final int DEFAULT_DARK_ALPHA = 58;
    public static final int DEFAULT_ICON_SCALE = 100;
    public static final int DEFAULT_TEXT_SIZE = 110; // 11.0 sp
    public static final boolean DEFAULT_ICON_ONLY = false;
    public static final boolean DEFAULT_SHOW_POST_BUTTON = true;
    public static final boolean DEFAULT_CUSTOM_COLOR_ENABLED = false;
    public static final int DEFAULT_CUSTOM_POST_COLOR = 0xFFFF8A00; // Orange
    public static final boolean DEFAULT_SHOW_CHANNEL = false;

    public final long revision;
    public final boolean enabled;
    public final int themeMode;
    public final float tabWidthDp;
    public final float bottomPaddingDp;
    public final float blurRadiusDp;
    public final float lightAlpha;
    public final float darkAlpha;
    public final float iconScale;
    public final float textSizeSp;
    public final boolean iconOnly;
    public final boolean showPostButton;
    public final boolean customColorEnabled;
    public final int customPostColor;
    public final boolean showChannel;

    /** Backward-compatible constructor used by legacy call sites. */
    public GlassSettings(
            long revision,
            boolean enabled,
            int themeMode,
            float tabWidthDp,
            float bottomPaddingDp,
            float blurRadiusDp,
            float lightAlpha,
            float darkAlpha,
            float iconScale,
            float textSizeSp,
            boolean iconOnly,
            boolean showPostButton,
            boolean customColorEnabled,
            int customPostColor
    ) {
        this(
                revision, enabled, themeMode, tabWidthDp, bottomPaddingDp, blurRadiusDp,
                lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly, showPostButton,
                customColorEnabled, customPostColor, DEFAULT_SHOW_CHANNEL
        );
    }

    public GlassSettings(
            long revision,
            boolean enabled,
            int themeMode,
            float tabWidthDp,
            float bottomPaddingDp,
            float blurRadiusDp,
            float lightAlpha,
            float darkAlpha,
            float iconScale,
            float textSizeSp,
            boolean iconOnly,
            boolean showPostButton,
            boolean customColorEnabled,
            int customPostColor,
            boolean showChannel
    ) {
        this.revision = Math.max(0L, revision);
        this.enabled = enabled;
        this.themeMode = clamp(themeMode, THEME_SYSTEM, THEME_DARK);
        this.tabWidthDp = clamp(tabWidthDp, 64f, 92f);
        this.bottomPaddingDp = clamp(bottomPaddingDp, 4f, 28f);
        this.blurRadiusDp = clamp(blurRadiusDp, 0f, 8f);
        this.lightAlpha = clamp(lightAlpha, 0.10f, 0.75f);
        this.darkAlpha = clamp(darkAlpha, 0.30f, 0.92f);
        this.iconScale = clamp(iconScale, 0.80f, 1.20f);
        this.textSizeSp = clamp(textSizeSp, 9f, 14f);
        this.iconOnly = iconOnly;
        this.showPostButton = showPostButton;
        this.customColorEnabled = customColorEnabled;
        this.customPostColor = 0xFF000000 | (customPostColor & 0x00FFFFFF);
        this.showChannel = showChannel;
    }

    public static GlassSettings defaults() {
        return new GlassSettings(
                0L,
                DEFAULT_ENABLED,
                DEFAULT_THEME_MODE,
                DEFAULT_TAB_WIDTH,
                DEFAULT_BOTTOM_PADDING,
                DEFAULT_BLUR_RADIUS / 10f,
                DEFAULT_LIGHT_ALPHA / 100f,
                DEFAULT_DARK_ALPHA / 100f,
                DEFAULT_ICON_SCALE / 100f,
                DEFAULT_TEXT_SIZE / 10f,
                DEFAULT_ICON_ONLY,
                DEFAULT_SHOW_POST_BUTTON,
                DEFAULT_CUSTOM_COLOR_ENABLED,
                DEFAULT_CUSTOM_POST_COLOR,
                DEFAULT_SHOW_CHANNEL
        );
    }

    public GlassSettings withRevision(long newRevision) {
        return new GlassSettings(
                newRevision,
                enabled,
                themeMode,
                tabWidthDp,
                bottomPaddingDp,
                blurRadiusDp,
                lightAlpha,
                darkAlpha,
                iconScale,
                textSizeSp,
                iconOnly,
                showPostButton,
                customColorEnabled,
                customPostColor,
                showChannel
        );
    }

    public static Context storageContext(Context context) {
        if (context == null) return null;
        try {
            Context storage = context.createDeviceProtectedStorageContext();
            return storage == null ? context : storage;
        } catch (Throwable ignored) {
            return context;
        }
    }

    public static SharedPreferences localPreferences(Context context) {
        Context storage = storageContext(context);
        if (storage == null) throw new IllegalStateException("Context is null");
        return HookPreferenceTransport.open(storage, PREF_NAME);
    }

    /**
     * Reads every supported storage copy and selects the newest complete snapshot.
     * The AtomicFile copy avoids stale SharedPreferences caches in the module process.
     */
    public static GlassSettings loadLocal(Context context) {
        GlassSettings best = null;
        if (context == null) return defaults();

        Context deviceContext = storageContext(context);
        best = newer(best, readPreferences(deviceContext));
        best = newer(best, readSnapshot(deviceContext));

        try {
            if (!context.isDeviceProtectedStorage()) {
                best = newer(best, readPreferences(context));
                best = newer(best, readSnapshot(context));
            }
        } catch (Throwable ignored) {
            best = newer(best, readPreferences(context));
            best = newer(best, readSnapshot(context));
        }

        return best == null ? defaults() : best;
    }

    /**
     * Persists one authoritative revision to both SharedPreferences and AtomicFile.
     * Device-protected storage is always written so hooked processes can read settings
     * before credential unlock. Credential-protected storage is kept as a fallback.
     */
    public static boolean saveLocal(Context context, GlassSettings settings) {
        if (context == null || settings == null) return false;
        GlassSettings current = loadLocal(context);
        long currentRevision = current == null ? 0L : current.revision;
        long requestedRevision = Math.max(settings.revision, currentRevision);
        long nextRevision = Math.max(
                System.currentTimeMillis(),
                requestedRevision == Long.MAX_VALUE ? Long.MAX_VALUE : requestedRevision + 1L
        );
        GlassSettings snapshot = settings.withRevision(nextRevision);

        Context deviceContext = storageContext(context);
        boolean devicePrefs = writePreferences(deviceContext, snapshot);
        boolean deviceFile = writeSnapshot(deviceContext, snapshot);
        boolean canonicalSaved = devicePrefs || deviceFile;

        try {
            if (!context.isDeviceProtectedStorage()) {
                // Best-effort compatibility copy for older versions that used normal storage.
                writePreferences(context, snapshot);
                writeSnapshot(context, snapshot);
            }
        } catch (Throwable ignored) {
            // Device-protected storage remains the canonical copy.
        }

        notifyChanged(context);
        return canonicalSaved;
    }

    private static GlassSettings newer(GlassSettings current, GlassSettings candidate) {
        if (candidate == null) return current;
        if (current == null || candidate.revision >= current.revision) return candidate;
        return current;
    }

    private static GlassSettings readPreferences(Context context) {
        if (context == null) return null;
        try {
            SharedPreferences prefs = HookPreferenceTransport.open(context, PREF_NAME);
            if (!hasStoredValues(prefs)) return null;
            return fromPreferences(prefs);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writePreferences(Context context, GlassSettings settings) {
        if (context == null || settings == null) return false;
        try {
            SharedPreferences prefs = HookPreferenceTransport.open(context, PREF_NAME);
            return prefs.edit()
                    .putLong(KEY_REVISION, settings.revision)
                    .putBoolean(KEY_ENABLED, settings.enabled)
                    .putInt(KEY_THEME_MODE, settings.themeMode)
                    .putInt(KEY_TAB_WIDTH, Math.round(settings.tabWidthDp))
                    .putInt(KEY_BOTTOM_PADDING, Math.round(settings.bottomPaddingDp))
                    .putInt(KEY_BLUR_RADIUS, Math.round(settings.blurRadiusDp * 10f))
                    .putInt(KEY_LIGHT_ALPHA, Math.round(settings.lightAlpha * 100f))
                    .putInt(KEY_DARK_ALPHA, Math.round(settings.darkAlpha * 100f))
                    .putInt(KEY_ICON_SCALE, Math.round(settings.iconScale * 100f))
                    .putInt(KEY_TEXT_SIZE, Math.round(settings.textSizeSp * 10f))
                    .putBoolean(KEY_ICON_ONLY, settings.iconOnly)
                    .putBoolean(KEY_SHOW_POST_BUTTON, settings.showPostButton)
                    .putBoolean(KEY_CUSTOM_COLOR_ENABLED, settings.customColorEnabled)
                    .putInt(KEY_CUSTOM_POST_COLOR, settings.customPostColor)
                    .putBoolean(KEY_SHOW_CHANNEL, settings.showChannel)
                    .commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasStoredValues(SharedPreferences prefs) {
        if (prefs == null) return false;
        return prefs.contains(KEY_REVISION)
                || prefs.contains(KEY_ENABLED)
                || prefs.contains(KEY_THEME_MODE)
                || prefs.contains(KEY_TAB_WIDTH)
                || prefs.contains(KEY_ICON_ONLY)
                || prefs.contains(KEY_SHOW_POST_BUTTON)
                || prefs.contains(KEY_CUSTOM_COLOR_ENABLED)
                || prefs.contains(KEY_CUSTOM_POST_COLOR)
                || prefs.contains(KEY_SHOW_CHANNEL);
    }

    public static GlassSettings fromPreferences(SharedPreferences prefs) {
        return new GlassSettings(
                prefs.getLong(KEY_REVISION, 0L),
                prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED),
                prefs.getInt(KEY_THEME_MODE, DEFAULT_THEME_MODE),
                prefs.getInt(KEY_TAB_WIDTH, DEFAULT_TAB_WIDTH),
                prefs.getInt(KEY_BOTTOM_PADDING, DEFAULT_BOTTOM_PADDING),
                prefs.getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS) / 10f,
                prefs.getInt(KEY_LIGHT_ALPHA, DEFAULT_LIGHT_ALPHA) / 100f,
                prefs.getInt(KEY_DARK_ALPHA, DEFAULT_DARK_ALPHA) / 100f,
                prefs.getInt(KEY_ICON_SCALE, DEFAULT_ICON_SCALE) / 100f,
                prefs.getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE) / 10f,
                prefs.getBoolean(KEY_ICON_ONLY, DEFAULT_ICON_ONLY),
                prefs.getBoolean(KEY_SHOW_POST_BUTTON, DEFAULT_SHOW_POST_BUTTON),
                prefs.getBoolean(KEY_CUSTOM_COLOR_ENABLED, DEFAULT_CUSTOM_COLOR_ENABLED),
                prefs.getInt(KEY_CUSTOM_POST_COLOR, DEFAULT_CUSTOM_POST_COLOR),
                prefs.getBoolean(KEY_SHOW_CHANNEL, DEFAULT_SHOW_CHANNEL)
        );
    }

    private static File snapshotFile(Context context) {
        if (context == null) return null;
        try {
            File dir = context.getFilesDir();
            if (dir == null) return null;
            return new File(dir, SNAPSHOT_FILE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writeSnapshot(Context context, GlassSettings settings) {
        File file = snapshotFile(context);
        if (file == null || settings == null) return false;

        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            stream = atomicFile.startWrite();
            DataOutputStream out = new DataOutputStream(stream);
            out.writeInt(SNAPSHOT_MAGIC);
            out.writeInt(SNAPSHOT_VERSION);
            out.writeLong(settings.revision);
            out.writeBoolean(settings.enabled);
            out.writeInt(settings.themeMode);
            out.writeFloat(settings.tabWidthDp);
            out.writeFloat(settings.bottomPaddingDp);
            out.writeFloat(settings.blurRadiusDp);
            out.writeFloat(settings.lightAlpha);
            out.writeFloat(settings.darkAlpha);
            out.writeFloat(settings.iconScale);
            out.writeFloat(settings.textSizeSp);
            out.writeBoolean(settings.iconOnly);
            out.writeBoolean(settings.showPostButton);
            out.writeBoolean(settings.customColorEnabled);
            out.writeInt(settings.customPostColor);
            out.writeBoolean(settings.showChannel);
            out.flush();
            try { stream.getFD().sync(); } catch (Throwable ignored) {}
            atomicFile.finishWrite(stream);
            return true;
        } catch (Throwable ignored) {
            if (stream != null) {
                try { atomicFile.failWrite(stream); } catch (Throwable ignoredAgain) {}
            }
            return false;
        }
    }

    private static GlassSettings readSnapshot(Context context) {
        File file = snapshotFile(context);
        if (file == null || !file.isFile()) return null;

        AtomicFile atomicFile = new AtomicFile(file);
        try (FileInputStream stream = atomicFile.openRead();
             DataInputStream in = new DataInputStream(stream)) {
            if (in.readInt() != SNAPSHOT_MAGIC) return null;
            int version = in.readInt();
            if (version < 1 || version > SNAPSHOT_VERSION) return null;

            long revision = in.readLong();
            boolean enabled = version >= 4 ? in.readBoolean() : DEFAULT_ENABLED;
            int themeMode = in.readInt();
            float tabWidth = in.readFloat();
            float bottomPadding = in.readFloat();
            float blurRadius = in.readFloat();
            float lightAlpha = in.readFloat();
            float darkAlpha = in.readFloat();
            float iconScale = in.readFloat();
            float textSize = in.readFloat();
            boolean iconOnly = in.readBoolean();
            boolean showPostButton = in.readBoolean();
            boolean customColorEnabled;
            int customPostColor;
            if (version >= 3) {
                customColorEnabled = in.readBoolean();
                customPostColor = in.readInt();
            } else if (version == 2) {
                // Version 2 stored a Monet switch. Consume it for binary compatibility,
                // but do not reinterpret it as the new custom-color switch. New behavior
                // must start disabled so the original CoolAPK green remains the default.
                in.readBoolean();
                customColorEnabled = DEFAULT_CUSTOM_COLOR_ENABLED;
                customPostColor = in.readInt();
            } else {
                customColorEnabled = DEFAULT_CUSTOM_COLOR_ENABLED;
                customPostColor = DEFAULT_CUSTOM_POST_COLOR;
            }
            boolean showChannel = version >= 5 ? in.readBoolean() : DEFAULT_SHOW_CHANNEL;

            return new GlassSettings(
                    revision,
                    enabled,
                    themeMode,
                    tabWidth,
                    bottomPadding,
                    blurRadius,
                    lightAlpha,
                    darkAlpha,
                    iconScale,
                    textSize,
                    iconOnly,
                    showPostButton,
                    customColorEnabled,
                    customPostColor,
                    showChannel
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putLong(KEY_REVISION, revision);
        bundle.putBoolean(KEY_ENABLED, enabled);
        bundle.putInt(KEY_THEME_MODE, themeMode);
        bundle.putFloat(KEY_TAB_WIDTH, tabWidthDp);
        bundle.putFloat(KEY_BOTTOM_PADDING, bottomPaddingDp);
        bundle.putFloat(KEY_BLUR_RADIUS, blurRadiusDp);
        bundle.putFloat(KEY_LIGHT_ALPHA, lightAlpha);
        bundle.putFloat(KEY_DARK_ALPHA, darkAlpha);
        bundle.putFloat(KEY_ICON_SCALE, iconScale);
        bundle.putFloat(KEY_TEXT_SIZE, textSizeSp);
        bundle.putBoolean(KEY_ICON_ONLY, iconOnly);
        bundle.putBoolean(KEY_SHOW_POST_BUTTON, showPostButton);
        bundle.putBoolean(KEY_CUSTOM_COLOR_ENABLED, customColorEnabled);
        bundle.putInt(KEY_CUSTOM_POST_COLOR, customPostColor);
        bundle.putBoolean(KEY_SHOW_CHANNEL, showChannel);
        return bundle;
    }

    public static GlassSettings fromBundle(Bundle bundle) {
        if (bundle == null) return defaults();
        GlassSettings defaults = defaults();
        return new GlassSettings(
                bundle.getLong(KEY_REVISION, defaults.revision),
                bundle.getBoolean(KEY_ENABLED, defaults.enabled),
                bundle.getInt(KEY_THEME_MODE, defaults.themeMode),
                bundle.getFloat(KEY_TAB_WIDTH, defaults.tabWidthDp),
                bundle.getFloat(KEY_BOTTOM_PADDING, defaults.bottomPaddingDp),
                bundle.getFloat(KEY_BLUR_RADIUS, defaults.blurRadiusDp),
                bundle.getFloat(KEY_LIGHT_ALPHA, defaults.lightAlpha),
                bundle.getFloat(KEY_DARK_ALPHA, defaults.darkAlpha),
                bundle.getFloat(KEY_ICON_SCALE, defaults.iconScale),
                bundle.getFloat(KEY_TEXT_SIZE, defaults.textSizeSp),
                bundle.getBoolean(KEY_ICON_ONLY, defaults.iconOnly),
                bundle.getBoolean(KEY_SHOW_POST_BUTTON, defaults.showPostButton),
                bundle.getBoolean(KEY_CUSTOM_COLOR_ENABLED, defaults.customColorEnabled),
                bundle.getInt(KEY_CUSTOM_POST_COLOR, defaults.customPostColor),
                bundle.getBoolean(KEY_SHOW_CHANNEL, defaults.showChannel)
        );
    }

    public static void notifyChanged(Context context) {
        if (context == null) return;
        try {
            context.getContentResolver().notifyChange(CONTENT_URI, null);
        } catch (Throwable ignored) {
        }
    }

    private static float clamp(float value, float min, float max) {
        return NativeCore.clampFloat(value, min, max);
    }

    private static int clamp(int value, int min, int max) {
        return NativeCore.clampInt(value, min, max);
    }
}
