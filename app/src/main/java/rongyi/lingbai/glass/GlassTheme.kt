// Runtime glass theme adapted from the uploaded reference source.
// The original FloatingBottomBar is adapted from compose-miuix-ui's
// IosLiquidGlassNavigationBar example (Apache-2.0).
package rongyi.lingbai.glass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import rongyi.lingbai.GlassSettings

@Composable
fun GlassTheme(
    themeMode: Int = GlassSettings.THEME_SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        GlassSettings.THEME_LIGHT -> false
        GlassSettings.THEME_DARK -> true
        else -> systemDark
    }
    val controller = ThemeController(
        colorSchemeMode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        isDark = dark,
        paletteStyle = ThemePaletteStyle.TonalSpot,
    )
    MiuixTheme(controller = controller, content = content)
}

@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean = isSystemInDarkTheme()
