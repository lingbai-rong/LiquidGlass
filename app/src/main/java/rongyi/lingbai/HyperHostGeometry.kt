package rongyi.lingbai

import androidx.compose.runtime.mutableStateOf

/** Measured host geometry shared by Compose, native blur and the Android touch gate. */
internal class HyperHostGeometry {
    val viewportWidthDp = mutableStateOf(0f)
    val bottomInsetDp = mutableStateOf(0f)
    val standardContentHeightDp = mutableStateOf(InjectedNavigationStyle.INITIAL_STANDARD_CONTENT_HEIGHT_DP)
}
