package rongyi.lingbai

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.LocaleList

object ModuleUiPreferences {
    const val PREFS = "ui_preferences"
    const val KEY_THEME = "ui_theme"
    const val KEY_READ_APP_ICONS = "developer_read_app_icons"
    const val KEY_PAGE_WALLPAPER_ENABLED = "developer_page_wallpaper_enabled"

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    const val LANGUAGE_SYSTEM = ""
    const val LANGUAGE_ZH_CN = "zh-CN"
    const val LANGUAGE_ZH_TW = "zh-TW"
    const val LANGUAGE_EN = "en"

    private fun launcherComponent(context: Context): ComponentName =
        ComponentName(context.packageName, "${context.packageName}.LauncherAlias")

    fun readTheme(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, THEME_SYSTEM)

    fun saveTheme(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME, value.coerceIn(THEME_SYSTEM, THEME_DARK))
            .apply()
    }

    fun shouldReadAppIcons(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_READ_APP_ICONS, false)

    fun setReadAppIcons(context: Context, enabled: Boolean): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_READ_APP_ICONS, enabled)
            .commit()
    } catch (_: Throwable) {
        false
    }

    fun isPageWallpaperEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAGE_WALLPAPER_ENABLED, false)

    fun setPageWallpaperEnabled(context: Context, enabled: Boolean): Boolean = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAGE_WALLPAPER_ENABLED, enabled)
            .commit()
    } catch (_: Throwable) {
        false
    }

    fun isLauncherIconVisible(context: Context): Boolean {
        return when (context.packageManager.getComponentEnabledSetting(launcherComponent(context))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            // COMPONENT_ENABLED_STATE_DEFAULT follows the manifest declaration. The launcher
            // alias is enabled by default, so a fresh install must show the desktop icon and the
            // switch must report the same state.
            else -> true
        }
    }

    fun setLauncherIconVisible(context: Context, visible: Boolean): Boolean {
        return try {
            context.packageManager.setComponentEnabledSetting(
                launcherComponent(context),
                if (visible) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP,
            )
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun currentLanguage(context: Context): String {
        return try {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) LANGUAGE_SYSTEM else locales.toLanguageTags().substringBefore(',')
        } catch (_: Throwable) {
            LANGUAGE_SYSTEM
        }
    }

    fun setLanguage(context: Context, languageTag: String): Boolean {
        return try {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (languageTag.isBlank()) LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(languageTag)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
