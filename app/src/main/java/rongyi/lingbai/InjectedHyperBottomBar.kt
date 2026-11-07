// SPDX-License-Identifier: Apache-2.0
// Material presets derived from HyperChanger, Copyright 2026 btm_m.
// Adapted for LiquidGlass host routing and capture geometry.
package rongyi.lingbai

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import rongyi.lingbai.moduleui.HyperChangerInjectedNavigation
import rongyi.lingbai.moduleui.InjectedNavigationAppearance

/** Host routing around the original HyperChanger presets, without liquid-glass visual overrides. */
@Composable
internal fun InjectedHyperBottomBar(
    style: String,
    labels: List<String>,
    selectedIndex: Int,
    currentSelectedIndex: () -> Int,
    viewportWidthDp: Float,
    bottomInsetDp: Float,
    standardContentHeightDp: Float,
    onContentHeightChanged: (Float) -> Unit,
    capturedBackdrop: ImageBitmap?,
    nativeBackdropActive: Boolean,
    traditionalChinese: Boolean,
    settings: GlassSettings,
    dark: Boolean,
    onSelect: (Int) -> Unit,
    onPost: () -> Unit,
    iconContent: @Composable (Int, Color, Modifier) -> Unit,
    centerActionIndex: Int = -1,
    nativeCenterActionExpected: Boolean = false,
    separateCirclePlus: Boolean = false,
    syntheticBackdrop: Boolean = false,
    ovalPostButton: Boolean = false,
    portBackdropReady: Boolean = false,
    portBackdropDraw: ((android.graphics.Canvas, Float, Float) -> Unit)? = null,
    portBackdropRevision: () -> Int = { 0 },
    nativeActionIcon: ImageBitmap? = null,
) {
    val backdrop = rememberLayerBackdrop()
    val postColors = rememberGlassPostButtonColors(settings)
    val materialEnabled = !nativeBackdropActive && (portBackdropReady || capturedBackdrop != null || syntheticBackdrop)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val layout = InjectedNavigationStyle.layout(
            style, maxWidth.value, viewportWidthDp.takeIf { it > 0f } ?: maxWidth.value,
            labels.size, settings.showPostButton, centerActionIndex, separateCirclePlus,
            standardContentHeightDp, bottomInsetDp,
        )
        // Keep the host's existing capture coordinates. The source is never the injected bar.
        Box(
            Modifier.align(Alignment.BottomCenter).width(maxWidth).height(layout.captureHeightDp.dp)
                .alpha(0f).layerBackdrop(backdrop),
        ) {
            when {
                portBackdropReady && portBackdropDraw != null -> Canvas(Modifier.fillMaxSize()) {
                    portBackdropRevision()
                    drawIntoCanvas { portBackdropDraw(it.nativeCanvas, size.width, size.height) }
                }
                capturedBackdrop != null -> Image(
                    bitmap = capturedBackdrop, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                )
                syntheticBackdrop -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(
                    listOf(Color(0xFF050608), Color(0xFF11131A), Color(0xFF07080B)),
                )))
            }
        }
        Row(
            Modifier.padding(bottom = layout.bottomPaddingDp.dp).width(layout.rowWidthDp.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val displayLabels = layout.logicalIndices.mapIndexed { slot, logical ->
                if (slot == layout.actionSlot) "" else BottomBarLanguage.display(
                    labels.getOrElse(logical) { "" }, traditionalChinese,
                )
            }
            HyperChangerInjectedNavigation(
                labels = displayLabels,
                iconContent = { slot, color, modifier ->
                    val logical = layout.logicalIndices.getOrElse(slot) { -1 }
                    if (slot != layout.actionSlot && labels.getOrNull(logical)?.isNotEmpty() == true) {
                        iconContent(logical, color, modifier)
                    } else Box(modifier)
                },
                selectedIndex = layout.selectedSlot(selectedIndex),
                onSelected = { slot ->
                    if (slot == layout.actionSlot) onPost()
                    else layout.logicalIndices.getOrNull(slot)?.let { logical ->
                        if (logical in labels.indices && labels[logical].isNotEmpty()) onSelect(logical)
                    }
                    layout.selectedSlot(currentSelectedIndex())
                },
                floating = !layout.standard,
                iconOnly = settings.iconOnly,
                dark = dark,
                backdrop = backdrop,
                materialEnabled = materialEnabled,
                appearance = InjectedNavigationAppearance(
                    width = layout.barWidthDp.dp,
                    blockedIndex = layout.actionSlot,
                    bottomInset = bottomInsetDp.dp,
                    nativeBackdropActive = nativeBackdropActive,
                    onContentHeightChanged = onContentHeightChanged,
                    actionLabel = if (settings.showPostButton || separateCirclePlus) "发布"
                        else BottomBarLanguage.display(labels.getOrElse(centerActionIndex) { "+" }, traditionalChinese),
                    actionContent = {
                        when {
                            nativeActionIcon != null -> Image(bitmap = nativeActionIcon, contentDescription = null,
                                modifier = Modifier.size((34f * settings.iconScale).dp), contentScale = ContentScale.Fit)
                            separateCirclePlus -> GlassSeparatePostButton(dark, 46f, null)
                            settings.showPostButton && ovalPostButton -> GlassOvalPostButtonIcon(
                                postColors.background, postColors.content,
                                Modifier.width((56f * settings.iconScale).dp).height((40f * settings.iconScale).dp),
                            )
                            settings.showPostButton -> GlassPostButtonIcon(
                                postColors.background, postColors.content,
                                Modifier.size((34f * settings.iconScale).dp),
                            )
                            nativeCenterActionExpected -> Box(Modifier.size((34f * settings.iconScale).dp))
                            else -> AiButtonIcon(
                                BottomBarLanguage.display(labels.getOrElse(centerActionIndex) { "+" }, traditionalChinese),
                                postColors.background, postColors.content,
                                Modifier.size((34f * settings.iconScale).dp),
                            )
                        }
                    },
                ),
            )
            if (layout.separateCircle) {
                Spacer(Modifier.width(layout.circleGapDp.dp))
                if (layout.separateActionEnabled) {
                    GlassSeparatePostButton(dark, layout.circleDiameterDp, onPost)
                } else Spacer(Modifier.width(layout.circleDiameterDp.dp))
            }
        }
    }
}
