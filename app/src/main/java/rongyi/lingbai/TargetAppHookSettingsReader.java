package rongyi.lingbai;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Reads per-host settings without writing anything into the LSPosed target's storage. */
public final class TargetAppHookSettingsReader {
    private static final ConcurrentMap<String, TargetAppSettings> LAST_KNOWN =
            new ConcurrentHashMap<>();

    private TargetAppHookSettingsReader() {}

    public static TargetAppSettings load(Context hostContext, TargetApp app) {
        if (app == null) return null;
        // Always reload the module-owned snapshot. The old process-wide cache returned the first value
        // for the lifetime of the target process, so a correctly delivered ContentObserver event
        // still reapplied stale settings until the host was force-stopped.
        TargetAppSettings latest = readXposed(app);
        if (latest != null) {
            LAST_KNOWN.put(app.key, latest);
            return latest;
        }

        // A transient LSPosed/shared-preference read failure must not reset a running host to
        // defaults. Keep only a fallback snapshot; unlike the old cache it never short-circuits a
        // future reload.
        TargetAppSettings fallback = LAST_KNOWN.get(app.key);
        return fallback != null ? fallback : TargetAppSettings.defaults(app);
    }

    private static TargetAppSettings readXposed(TargetApp app) {
        try {
            SharedPreferences p = XposedBridge.getRemotePreferences(TargetAppSettings.PREF_NAME);
            TargetAppSettings d = TargetAppSettings.defaults(app);
            String prefix = app.key;
            long revision = p.getLong(prefix + "_revision", p.getLong("revision", -1L));
            if (revision < 0L) return null;
            return new TargetAppSettings(
                    Math.max(0L, revision),
                    p.getBoolean(prefix + "_enabled", d.enabled),
                    p.getInt(prefix + "_hidden_mask", d.hiddenMask),
                    p.getBoolean(prefix + "_show_post", d.showPost),
                    p.getInt(prefix + "_theme_mode", d.themeMode),
                    p.getInt(prefix + "_tab_width_tenths", Math.round(d.tabWidthDp * 10f)) / 10f,
                    p.getInt(prefix + "_bottom_padding_tenths", Math.round(d.bottomPaddingDp * 10f)) / 10f,
                    p.getInt(prefix + "_blur_tenths", Math.round(d.blurRadiusDp * 10f)) / 10f,
                    p.getInt(prefix + "_light_alpha_thousandths", Math.round(d.lightAlpha * 1000f)) / 1000f,
                    p.getInt(prefix + "_dark_alpha_thousandths", Math.round(d.darkAlpha * 1000f)) / 1000f,
                    p.getInt(prefix + "_icon_scale_thousandths", Math.round(d.iconScale * 1000f)) / 1000f,
                    p.getInt(prefix + "_text_size_tenths", Math.round(d.textSizeSp * 10f)) / 10f,
                    p.getBoolean(prefix + "_icon_only", d.iconOnly),
                    p.getBoolean(prefix + "_custom_post_color_enabled", d.customPostColorEnabled),
                    p.getInt(prefix + "_custom_post_color", d.customPostColor),
                    p.getBoolean(prefix + "_force_black_backdrop", d.forceBlackBackdrop),
                    p.getBoolean(prefix + "_press_only_transparent_backdrop", d.pressOnlyTransparentBackdrop),
                    p.getString(
                            prefix + "_wechat_first_tab_label",
                            p.getBoolean(prefix + "_wechat_first_tab_english", d.wechatFirstTabEnglish)
                                    ? TargetAppSettings.WECHAT_FIRST_TAB_LABEL_ENGLISH
                                    : TargetAppSettings.WECHAT_FIRST_TAB_LABEL_CHINESE
                    )
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

}
