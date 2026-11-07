package rongyi.lingbai;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;


/** Independent switches and appearance values for every adapted host application. */
public final class TargetAppSettings {
    public static final String PREF_NAME = "target_app_settings";

    private static final String SUFFIX_REVISION = "_revision";
    private static final String SUFFIX_ENABLED = "_enabled";
    private static final String SUFFIX_HIDDEN = "_hidden_mask";
    private static final String SUFFIX_SHOW_POST = "_show_post";
    private static final String SUFFIX_THEME = "_theme_mode";
    private static final String SUFFIX_TAB_WIDTH = "_tab_width_tenths";
    private static final String SUFFIX_BOTTOM_PADDING = "_bottom_padding_tenths";
    private static final String SUFFIX_BLUR = "_blur_tenths";
    private static final String SUFFIX_LIGHT_ALPHA = "_light_alpha_thousandths";
    private static final String SUFFIX_DARK_ALPHA = "_dark_alpha_thousandths";
    private static final String SUFFIX_ICON_SCALE = "_icon_scale_thousandths";
    private static final String SUFFIX_TEXT_SIZE = "_text_size_tenths";
    private static final String SUFFIX_ICON_ONLY = "_icon_only";
    private static final String SUFFIX_CUSTOM_COLOR_ENABLED = "_custom_post_color_enabled";
    private static final String SUFFIX_CUSTOM_COLOR = "_custom_post_color";
    private static final String SUFFIX_FORCE_BLACK_BACKDROP = "_force_black_backdrop";
    private static final String SUFFIX_PRESS_ONLY_TRANSPARENT_BACKDROP = "_press_only_transparent_backdrop";
    private static final String SUFFIX_WECHAT_FIRST_TAB_LABEL = "_wechat_first_tab_label";
    /** Legacy 26.73-26.88 key retained for upgrade and downgrade compatibility. */
    private static final String SUFFIX_WECHAT_FIRST_TAB_ENGLISH = "_wechat_first_tab_english";

    public static final String WECHAT_FIRST_TAB_LABEL_CHINESE = "微信";
    public static final String WECHAT_FIRST_TAB_LABEL_ENGLISH = "WeChat";
    public static final int WECHAT_FIRST_TAB_LABEL_MAX_CODE_POINTS = 6;

    public final long revision;
    public final boolean enabled;
    public final int hiddenMask;
    public final boolean showPost;
    public final int themeMode;
    public final float tabWidthDp;
    public final float bottomPaddingDp;
    public final float blurRadiusDp;
    public final float lightAlpha;
    public final float darkAlpha;
    public final float iconScale;
    public final float textSizeSp;
    public final boolean iconOnly;
    public final boolean customPostColorEnabled;
    public final int customPostColor;
    public final boolean forceBlackBackdrop;
    public final boolean pressOnlyTransparentBackdrop;
    public final String wechatFirstTabLabel;
    /** Derived compatibility view for code and snapshots created before custom labels existed. */
    @Deprecated
    public final boolean wechatFirstTabEnglish;

    public TargetAppSettings(
            long revision,
            boolean enabled,
            int hiddenMask,
            boolean showPost,
            int themeMode,
            float tabWidthDp,
            float bottomPaddingDp,
            float blurRadiusDp,
            float lightAlpha,
            float darkAlpha,
            float iconScale,
            float textSizeSp,
            boolean iconOnly,
            boolean customPostColorEnabled,
            int customPostColor,
            boolean forceBlackBackdrop,
            boolean pressOnlyTransparentBackdrop,
            String wechatFirstTabLabel
    ) {
        this.revision = Math.max(0L, revision);
        this.enabled = enabled;
        this.hiddenMask = Math.max(0, hiddenMask);
        this.showPost = showPost;
        this.themeMode = clamp(themeMode, GlassSettings.THEME_SYSTEM, GlassSettings.THEME_DARK);
        this.tabWidthDp = clamp(tabWidthDp, 64f, 92f);
        this.bottomPaddingDp = clamp(bottomPaddingDp, 4f, 28f);
        this.blurRadiusDp = clamp(blurRadiusDp, 0f, 8f);
        this.lightAlpha = clamp(lightAlpha, 0.10f, 0.75f);
        this.darkAlpha = clamp(darkAlpha, 0.30f, 0.92f);
        this.iconScale = clamp(iconScale, 0.80f, 1.20f);
        this.textSizeSp = clamp(textSizeSp, 9f, 14f);
        this.iconOnly = iconOnly;
        this.customPostColorEnabled = customPostColorEnabled;
        this.customPostColor = 0xFF000000 | (customPostColor & 0x00FFFFFF);
        this.forceBlackBackdrop = forceBlackBackdrop;
        this.pressOnlyTransparentBackdrop = pressOnlyTransparentBackdrop;
        this.wechatFirstTabLabel = normalizeWeChatFirstTabLabel(wechatFirstTabLabel);
        this.wechatFirstTabEnglish = WECHAT_FIRST_TAB_LABEL_ENGLISH.equals(this.wechatFirstTabLabel);
    }

    /** Compatibility constructor for callers compiled against the old boolean setting. */
    @Deprecated
    public TargetAppSettings(
            long revision,
            boolean enabled,
            int hiddenMask,
            boolean showPost,
            int themeMode,
            float tabWidthDp,
            float bottomPaddingDp,
            float blurRadiusDp,
            float lightAlpha,
            float darkAlpha,
            float iconScale,
            float textSizeSp,
            boolean iconOnly,
            boolean customPostColorEnabled,
            int customPostColor,
            boolean forceBlackBackdrop,
            boolean pressOnlyTransparentBackdrop,
            boolean wechatFirstTabEnglish
    ) {
        this(
                revision, enabled, hiddenMask, showPost, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customPostColorEnabled, customPostColor, forceBlackBackdrop,
                pressOnlyTransparentBackdrop,
                wechatFirstTabEnglish
                        ? WECHAT_FIRST_TAB_LABEL_ENGLISH : WECHAT_FIRST_TAB_LABEL_CHINESE
        );
    }

    /** Compatibility constructor for 2.00-2.02 cache values. */
    public TargetAppSettings(long revision, boolean enabled, int hiddenMask, boolean showPost) {
        this(
                revision, enabled, hiddenMask, showPost,
                GlassSettings.THEME_SYSTEM,
                GlassSettings.DEFAULT_TAB_WIDTH,
                GlassSettings.DEFAULT_BOTTOM_PADDING,
                GlassSettings.DEFAULT_BLUR_RADIUS / 10f,
                GlassSettings.DEFAULT_LIGHT_ALPHA / 100f,
                GlassSettings.DEFAULT_DARK_ALPHA / 100f,
                GlassSettings.DEFAULT_ICON_SCALE / 100f,
                GlassSettings.DEFAULT_TEXT_SIZE / 10f,
                GlassSettings.DEFAULT_ICON_ONLY,
                false,
                GlassSettings.DEFAULT_CUSTOM_POST_COLOR,
                false,
                false,
                false
        );
    }

    public static TargetAppSettings defaults(TargetApp app) {
        GlassSettings d = GlassSettings.defaults();
        int defaultPostColor;
        if (app != null && "bilibili".equals(app.key)) {
            defaultPostColor = 0xFFFB7299; // Bilibili pink
        } else if (app != null && "xhs".equals(app.key)) {
            defaultPostColor = 0xFFFF2442; // Xiaohongshu red
        } else if (app != null && "meituan_main".equals(app.key)) {
            defaultPostColor = 0xFFFFD100; // Meituan yellow
        } else if (app != null && "midea".equals(app.key)) {
            defaultPostColor = 0xFF0092D8; // Midea blue
        } else {
            defaultPostColor = GlassSettings.DEFAULT_CUSTOM_POST_COLOR;
        }
        boolean defaultCustomColor = false;
        // HyperOS Gallery exposes two navigator destinations but renders its floating native
        // bar without text. Keep that visual contract by default; users can still turn labels on
        // from the ordinary per-app appearance page.
        boolean defaultIconOnly = app == TargetApp.MI_GALLERY || d.iconOnly;
        // Douyin's own bottom bar is dark by default; match it unless the user changes theme.
        int defaultTheme = app == TargetApp.DOUYIN
                ? GlassSettings.THEME_DARK : d.themeMode;
        return new TargetAppSettings(
                0L,
                true,
                0,
                app != null && app.hasPostAction,
                defaultTheme,
                d.tabWidthDp,
                d.bottomPaddingDp,
                d.blurRadiusDp,
                d.lightAlpha,
                d.darkAlpha,
                d.iconScale,
                d.textSizeSp,
                defaultIconOnly,
                defaultCustomColor,
                defaultPostColor,
                false,
                false,
                false
        );
    }

    public TargetAppSettings withEnabled(boolean value) {
        return copy(revision, value, hiddenMask, showPost, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customPostColorEnabled, customPostColor, forceBlackBackdrop, pressOnlyTransparentBackdrop, wechatFirstTabLabel);
    }

    public TargetAppSettings withHiddenMask(int value) {
        return copy(revision, enabled, value, showPost, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customPostColorEnabled, customPostColor, forceBlackBackdrop, pressOnlyTransparentBackdrop, wechatFirstTabLabel);
    }

    public TargetAppSettings withShowPost(boolean value) {
        return copy(revision, enabled, hiddenMask, value, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customPostColorEnabled, customPostColor, forceBlackBackdrop, pressOnlyTransparentBackdrop, wechatFirstTabLabel);
    }

    public TargetAppSettings withAppearance(
            int themeMode,
            float tabWidthDp,
            float bottomPaddingDp,
            float blurRadiusDp,
            float lightAlpha,
            float darkAlpha,
            float iconScale,
            float textSizeSp,
            boolean iconOnly
    ) {
        return copy(revision, enabled, hiddenMask, showPost, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customPostColorEnabled, customPostColor, forceBlackBackdrop, pressOnlyTransparentBackdrop, wechatFirstTabLabel);
    }

    public TargetAppSettings withPostColor(boolean customEnabled, int color) {
        return copy(revision, enabled, hiddenMask, showPost, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customEnabled, color, forceBlackBackdrop, pressOnlyTransparentBackdrop, wechatFirstTabLabel);
    }

    public TargetAppSettings withForceBlackBackdrop(boolean value) {
        return copy(revision, enabled, hiddenMask, showPost, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customPostColorEnabled, customPostColor, value, pressOnlyTransparentBackdrop, wechatFirstTabLabel);
    }

    public TargetAppSettings withPressOnlyTransparentBackdrop(boolean value) {
        return copy(revision, enabled, hiddenMask, showPost, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customPostColorEnabled, customPostColor, forceBlackBackdrop, value, wechatFirstTabLabel);
    }

    public TargetAppSettings withWeChatFirstTabEnglish(boolean value) {
        return withWeChatFirstTabLabel(value
                ? WECHAT_FIRST_TAB_LABEL_ENGLISH : WECHAT_FIRST_TAB_LABEL_CHINESE);
    }

    public TargetAppSettings withWeChatFirstTabLabel(String value) {
        return copy(revision, enabled, hiddenMask, showPost, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customPostColorEnabled, customPostColor, forceBlackBackdrop, pressOnlyTransparentBackdrop, value);
    }

    public TargetAppSettings withRevision(long value) {
        return copy(value, enabled, hiddenMask, showPost, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customPostColorEnabled, customPostColor, forceBlackBackdrop, pressOnlyTransparentBackdrop, wechatFirstTabLabel);
    }

    public GlassSettings toGlassSettings() {
        return new GlassSettings(
                revision,
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
                showPost,
                customPostColorEnabled,
                customPostColor,
                false
        );
    }

    private static TargetAppSettings copy(
            long revision,
            boolean enabled,
            int hiddenMask,
            boolean showPost,
            int themeMode,
            float tabWidthDp,
            float bottomPaddingDp,
            float blurRadiusDp,
            float lightAlpha,
            float darkAlpha,
            float iconScale,
            float textSizeSp,
            boolean iconOnly,
            boolean customPostColorEnabled,
            int customPostColor,
            boolean forceBlackBackdrop,
            boolean pressOnlyTransparentBackdrop,
            String wechatFirstTabLabel
    ) {
        return new TargetAppSettings(
                revision, enabled, hiddenMask, showPost, themeMode, tabWidthDp, bottomPaddingDp,
                blurRadiusDp, lightAlpha, darkAlpha, iconScale, textSizeSp, iconOnly,
                customPostColorEnabled, customPostColor, forceBlackBackdrop, pressOnlyTransparentBackdrop,
                wechatFirstTabLabel
        );
    }

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putLong("revision", revision);
        b.putBoolean("enabled", enabled);
        b.putInt("hiddenMask", hiddenMask);
        b.putBoolean("showPost", showPost);
        b.putInt("themeMode", themeMode);
        b.putFloat("tabWidthDp", tabWidthDp);
        b.putFloat("bottomPaddingDp", bottomPaddingDp);
        b.putFloat("blurRadiusDp", blurRadiusDp);
        b.putFloat("lightAlpha", lightAlpha);
        b.putFloat("darkAlpha", darkAlpha);
        b.putFloat("iconScale", iconScale);
        b.putFloat("textSizeSp", textSizeSp);
        b.putBoolean("iconOnly", iconOnly);
        b.putBoolean("customPostColorEnabled", customPostColorEnabled);
        b.putInt("customPostColor", customPostColor);
        b.putBoolean("forceBlackBackdrop", forceBlackBackdrop);
        b.putBoolean("pressOnlyTransparentBackdrop", pressOnlyTransparentBackdrop);
        b.putString("wechatFirstTabLabel", wechatFirstTabLabel);
        b.putBoolean("wechatFirstTabEnglish", wechatFirstTabEnglish);
        return b;
    }

    public static TargetAppSettings fromBundle(Bundle b, TargetApp app) {
        if (b == null) return null;
        TargetAppSettings d = defaults(app);
        return new TargetAppSettings(
                b.getLong("revision", d.revision),
                b.getBoolean("enabled", d.enabled),
                b.getInt("hiddenMask", d.hiddenMask),
                b.getBoolean("showPost", d.showPost),
                b.getInt("themeMode", d.themeMode),
                b.getFloat("tabWidthDp", d.tabWidthDp),
                b.getFloat("bottomPaddingDp", d.bottomPaddingDp),
                b.getFloat("blurRadiusDp", d.blurRadiusDp),
                b.getFloat("lightAlpha", d.lightAlpha),
                b.getFloat("darkAlpha", d.darkAlpha),
                b.getFloat("iconScale", d.iconScale),
                b.getFloat("textSizeSp", d.textSizeSp),
                b.getBoolean("iconOnly", d.iconOnly),
                b.getBoolean("customPostColorEnabled", d.customPostColorEnabled),
                b.getInt("customPostColor", d.customPostColor),
                b.getBoolean("forceBlackBackdrop", d.forceBlackBackdrop),
                b.getBoolean("pressOnlyTransparentBackdrop", d.pressOnlyTransparentBackdrop),
                b.getString(
                        "wechatFirstTabLabel",
                        b.getBoolean("wechatFirstTabEnglish", d.wechatFirstTabEnglish)
                                ? WECHAT_FIRST_TAB_LABEL_ENGLISH : WECHAT_FIRST_TAB_LABEL_CHINESE
                )
        );
    }

    public static TargetAppSettings loadLocal(Context context, TargetApp app) {
        if (context == null || app == null) return defaults(app);
        TargetAppSettings best = read(GlassSettings.storageContext(context), app);
        try {
            if (!context.isDeviceProtectedStorage()) best = newer(best, read(context, app));
        } catch (Throwable ignored) {
            best = newer(best, read(context, app));
        }
        return best == null ? defaults(app) : best;
    }

    public static boolean saveLocal(Context context, TargetApp app, TargetAppSettings settings) {
        return saveLocal(context, app, settings, true);
    }

    /** Batch variant used by global appearance sync to emit one final cross-process notification. */
    static boolean saveLocal(
            Context context,
            TargetApp app,
            TargetAppSettings settings,
            boolean notifyChanged
    ) {
        if (context == null || app == null || settings == null) return false;
        TargetAppSettings current = loadLocal(context, app);
        long currentRevision = current == null ? 0L : current.revision;
        long revisionFloor = Math.max(settings.revision, currentRevision);
        long nextRevision = Math.max(
                System.currentTimeMillis(),
                revisionFloor == Long.MAX_VALUE ? Long.MAX_VALUE : revisionFloor + 1L
        );
        TargetAppSettings snapshot = settings.withRevision(nextRevision);
        boolean ok = write(GlassSettings.storageContext(context), app, snapshot);
        try {
            if (!context.isDeviceProtectedStorage()) write(context, app, snapshot);
        } catch (Throwable ignored) {
        }
        if (notifyChanged) GlassSettings.notifyChanged(context);
        return ok;
    }

    private static TargetAppSettings read(Context context, TargetApp app) {
        if (context == null) return null;
        try {
            SharedPreferences p = HookPreferenceTransport.open(context, PREF_NAME);
            String prefix = app.key;
            if (!p.contains(prefix + SUFFIX_ENABLED) && !p.contains(prefix + SUFFIX_HIDDEN)) return null;
            TargetAppSettings d = defaults(app);
            long revision = p.getLong(prefix + SUFFIX_REVISION, p.getLong("revision", 0L));
            return new TargetAppSettings(
                    revision,
                    p.getBoolean(prefix + SUFFIX_ENABLED, d.enabled),
                    p.getInt(prefix + SUFFIX_HIDDEN, d.hiddenMask),
                    p.getBoolean(prefix + SUFFIX_SHOW_POST, d.showPost),
                    p.getInt(prefix + SUFFIX_THEME, d.themeMode),
                    p.getInt(prefix + SUFFIX_TAB_WIDTH, Math.round(d.tabWidthDp * 10f)) / 10f,
                    p.getInt(prefix + SUFFIX_BOTTOM_PADDING, Math.round(d.bottomPaddingDp * 10f)) / 10f,
                    p.getInt(prefix + SUFFIX_BLUR, Math.round(d.blurRadiusDp * 10f)) / 10f,
                    p.getInt(prefix + SUFFIX_LIGHT_ALPHA, Math.round(d.lightAlpha * 1000f)) / 1000f,
                    p.getInt(prefix + SUFFIX_DARK_ALPHA, Math.round(d.darkAlpha * 1000f)) / 1000f,
                    p.getInt(prefix + SUFFIX_ICON_SCALE, Math.round(d.iconScale * 1000f)) / 1000f,
                    p.getInt(prefix + SUFFIX_TEXT_SIZE, Math.round(d.textSizeSp * 10f)) / 10f,
                    p.getBoolean(prefix + SUFFIX_ICON_ONLY, d.iconOnly),
                    p.getBoolean(prefix + SUFFIX_CUSTOM_COLOR_ENABLED, d.customPostColorEnabled),
                    p.getInt(prefix + SUFFIX_CUSTOM_COLOR, d.customPostColor),
                    p.getBoolean(prefix + SUFFIX_FORCE_BLACK_BACKDROP, d.forceBlackBackdrop),
                    p.getBoolean(prefix + SUFFIX_PRESS_ONLY_TRANSPARENT_BACKDROP, d.pressOnlyTransparentBackdrop),
                    p.getString(
                            prefix + SUFFIX_WECHAT_FIRST_TAB_LABEL,
                            p.getBoolean(prefix + SUFFIX_WECHAT_FIRST_TAB_ENGLISH, d.wechatFirstTabEnglish)
                                    ? WECHAT_FIRST_TAB_LABEL_ENGLISH : WECHAT_FIRST_TAB_LABEL_CHINESE
                    )
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean write(Context context, TargetApp app, TargetAppSettings s) {
        if (context == null) return false;
        try {
            String prefix = app.key;
            return HookPreferenceTransport.open(context, PREF_NAME).edit()
                    .putLong("revision", s.revision) // legacy shared-preference compatibility
                    .putLong(prefix + SUFFIX_REVISION, s.revision)
                    .putBoolean(prefix + SUFFIX_ENABLED, s.enabled)
                    .putInt(prefix + SUFFIX_HIDDEN, s.hiddenMask)
                    .putBoolean(prefix + SUFFIX_SHOW_POST, s.showPost)
                    .putInt(prefix + SUFFIX_THEME, s.themeMode)
                    .putInt(prefix + SUFFIX_TAB_WIDTH, Math.round(s.tabWidthDp * 10f))
                    .putInt(prefix + SUFFIX_BOTTOM_PADDING, Math.round(s.bottomPaddingDp * 10f))
                    .putInt(prefix + SUFFIX_BLUR, Math.round(s.blurRadiusDp * 10f))
                    .putInt(prefix + SUFFIX_LIGHT_ALPHA, Math.round(s.lightAlpha * 1000f))
                    .putInt(prefix + SUFFIX_DARK_ALPHA, Math.round(s.darkAlpha * 1000f))
                    .putInt(prefix + SUFFIX_ICON_SCALE, Math.round(s.iconScale * 1000f))
                    .putInt(prefix + SUFFIX_TEXT_SIZE, Math.round(s.textSizeSp * 10f))
                    .putBoolean(prefix + SUFFIX_ICON_ONLY, s.iconOnly)
                    .putBoolean(prefix + SUFFIX_CUSTOM_COLOR_ENABLED, s.customPostColorEnabled)
                    .putInt(prefix + SUFFIX_CUSTOM_COLOR, s.customPostColor)
                    .putBoolean(prefix + SUFFIX_FORCE_BLACK_BACKDROP, s.forceBlackBackdrop)
                    .putBoolean(prefix + SUFFIX_PRESS_ONLY_TRANSPARENT_BACKDROP, s.pressOnlyTransparentBackdrop)
                    .putString(prefix + SUFFIX_WECHAT_FIRST_TAB_LABEL, s.wechatFirstTabLabel)
                    .putBoolean(prefix + SUFFIX_WECHAT_FIRST_TAB_ENGLISH, s.wechatFirstTabEnglish)
                    .commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static TargetAppSettings newer(TargetAppSettings a, TargetAppSettings b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.revision >= a.revision ? b : a;
    }

    private static float clamp(float value, float min, float max) {
        return NativeCore.clampFloat(value, min, max);
    }

    private static int clamp(int value, int min, int max) {
        return NativeCore.clampInt(value, min, max);
    }

    public static String limitWeChatFirstTabLabelInput(String value) {
        if (value == null || value.isEmpty()) return "";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ');
        int codePoints = singleLine.codePointCount(0, singleLine.length());
        if (codePoints <= WECHAT_FIRST_TAB_LABEL_MAX_CODE_POINTS) return singleLine;
        int end = singleLine.offsetByCodePoints(0, WECHAT_FIRST_TAB_LABEL_MAX_CODE_POINTS);
        return singleLine.substring(0, end);
    }

    public static String normalizeWeChatFirstTabLabel(String value) {
        String normalized = value == null
                ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        if (normalized.isEmpty()) return WECHAT_FIRST_TAB_LABEL_CHINESE;
        return limitWeChatFirstTabLabelInput(normalized);
    }

}
