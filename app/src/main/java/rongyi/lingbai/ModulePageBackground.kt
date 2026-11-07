package rongyi.lingbai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/** Shared artwork layer for the three root settings pages. */
@Composable
internal fun ModulePageBackground(
    pageColor: Color,
    dark: Boolean,
    wallpaperEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pageColor),
    ) {
        if (wallpaperEnabled) {
            Image(
                painter = painterResource(R.drawable.module_pages_background),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                alignment = Alignment.Center,
                contentScale = ContentScale.Crop,
                alpha = if (dark) 0.55f else 1f,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        pageColor.copy(alpha = if (dark) 0.84f else 0.72f),
                    ),
            )
        }
    }
}
