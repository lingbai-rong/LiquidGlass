package rongyi.lingbai

import android.content.Context

/** Preferences for the module app only; host navigation keeps its existing settings. */
data class ModuleAppearanceSettings(
    val theme: Int = ModuleUiPreferences.THEME_SYSTEM,
    val navigationStyle: String = LIQUID_GLASS,
    val labelMode: String = ICON_AND_TEXT,
    val predictiveBack: Boolean = true,
    val backProgress: Int = 90,
) {
    companion object {
        const val HYPER_OS = "hyper_os"
        const val HYPER_OS_FLOATING = "hyper_os_floating"
        const val LIQUID_GLASS = "liquid_glass"
        const val ICON_AND_TEXT = "icon_and_text"
        const val ICON_ONLY = "icon_only"
        const val TEXT_ONLY = "text_only"
        val styles = listOf(HYPER_OS, HYPER_OS_FLOATING, LIQUID_GLASS)
        val labels = listOf(ICON_AND_TEXT, ICON_ONLY, TEXT_ONLY)

        fun read(context: Context): ModuleAppearanceSettings {
            val prefs = context.getSharedPreferences(ModuleUiPreferences.PREFS, Context.MODE_PRIVATE)
            return ModuleAppearanceSettings(
                theme = ModuleUiPreferences.readTheme(context).coerceIn(0, 2),
                navigationStyle = prefs.getString("navigation_style", LIQUID_GLASS)
                    ?.takeIf { it in styles } ?: LIQUID_GLASS,
                labelMode = prefs.getString("navigation_label_mode", ICON_AND_TEXT)
                    ?.takeIf { it in labels } ?: ICON_AND_TEXT,
                predictiveBack = prefs.getBoolean("predictive_back", true),
                backProgress = prefs.getInt("predictive_back_progress", 90).coerceIn(10, 100),
            )
        }
    }

    fun save(context: Context): Boolean = context
        .getSharedPreferences(ModuleUiPreferences.PREFS, Context.MODE_PRIVATE).edit()
        .putInt(ModuleUiPreferences.KEY_THEME, theme.coerceIn(0, 2))
        .putString("navigation_style", navigationStyle.takeIf { it in styles } ?: LIQUID_GLASS)
        .putString("navigation_label_mode", labelMode.takeIf { it in labels } ?: ICON_AND_TEXT)
        .putBoolean("predictive_back", predictiveBack)
        .putInt("predictive_back_progress", backProgress.coerceIn(10, 100))
        .commit()
}
