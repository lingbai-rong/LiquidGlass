// SPDX-License-Identifier: GPL-3.0-or-later
package rongyi.lingbai.wechatpanel

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val lightScheme = lightColorScheme(
    primary = Color(0xFF336940),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB5F1BD),
    onPrimaryContainer = Color(0xFF19512A),
    secondary = Color(0xFF506352),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E8D2),
    onSecondaryContainer = Color(0xFF394B3B),
    tertiary = Color(0xFF39656E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBDEAF4),
    onTertiaryContainer = Color(0xFF204D55),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF6FBF2),
    onBackground = Color(0xFF181D18),
    surface = Color(0xFFF6FBF2),
    onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5DA),
    onSurfaceVariant = Color(0xFF414941),
    outline = Color(0xFF717970),
    outlineVariant = Color(0xFFC1C9BE),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2D322C),
    inverseOnSurface = Color(0xFFEEF2EA),
    inversePrimary = Color(0xFF99D4A2),
    surfaceDim = Color(0xFFD7DBD3),
    surfaceBright = Color(0xFFF6FBF2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F5ED),
    surfaceContainer = Color(0xFFEBEFE7),
    surfaceContainerHigh = Color(0xFFE5EAE1),
    surfaceContainerHighest = Color(0xFFDFE4DC),
)

private val darkScheme = darkColorScheme(
    primary = Color(0xFF99D4A2),
    onPrimary = Color(0xFF003917),
    primaryContainer = Color(0xFF19512A),
    onPrimaryContainer = Color(0xFFB5F1BD),
    secondary = Color(0xFFB7CCB7),
    onSecondary = Color(0xFF233426),
    secondaryContainer = Color(0xFF394B3B),
    onSecondaryContainer = Color(0xFFD3E8D2),
    tertiary = Color(0xFFA2CED8),
    onTertiary = Color(0xFF00363E),
    tertiaryContainer = Color(0xFF204D55),
    onTertiaryContainer = Color(0xFFBDEAF4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101510),
    onBackground = Color(0xFFDFE4DC),
    surface = Color(0xFF101510),
    onSurface = Color(0xFFDFE4DC),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BE),
    outline = Color(0xFF8B9389),
    outlineVariant = Color(0xFF414941),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFDFE4DC),
    inverseOnSurface = Color(0xFF2D322C),
    inversePrimary = Color(0xFF336940),
    surfaceDim = Color(0xFF101510),
    surfaceBright = Color(0xFF353A35),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF181D18),
    surfaceContainer = Color(0xFF1C211C),
    surfaceContainerHigh = Color(0xFF262B26),
    surfaceContainerHighest = Color(0xFF313631),
)

/** WeKit's default injected WeChat theme, kept byte-for-byte equivalent in its color values. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun InjectedUiTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = darkTheme ?: isSystemInDarkTheme()
    CompositionLocalProvider(LocalWeKitLocalizedContext provides PanelHostContext.resources) {
        MaterialExpressiveTheme(
            colorScheme = if (dark) darkScheme else lightScheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
