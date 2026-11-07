// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
// HyperChanger page, toolbar and grouping implementation adapted to LiquidGlass routes.
package rongyi.lingbai.moduleui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import rongyi.lingbai.R
import rongyi.lingbai.moduleStringResource
import kotlin.math.abs

val LocalDialogBackdrop = compositionLocalOf<Backdrop?> { null }
val LocalToolbarBackdrop = compositionLocalOf<LayerBackdrop?> { null }
private val LocalToolbarCollapsed = compositionLocalOf { 1f }

/** Dialogs/popups live in Scaffold's overlay, outside the layer they sample. */
@Composable
fun ModuleGlassSurface(content: @Composable () -> Unit) {
    val backdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalDialogBackdrop provides backdrop, LocalToolbarBackdrop provides backdrop) {
        Scaffold(containerColor = MiuixTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { content() }
        }
    }
}

@Composable
fun ModuleAppPage(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val scroll = MiuixScrollBehavior()
    val backdrop = rememberLayerBackdrop()
    val surface = MiuixTheme.colorScheme.surface
    val collapsedFraction = scroll.state.collapsedFraction.coerceIn(0f, 1f)
    CompositionLocalProvider(
        LocalToolbarBackdrop provides backdrop,
        LocalToolbarCollapsed provides collapsedFraction,
        LocalDialogBackdrop provides backdrop,
    ) {
        Scaffold(topBar = {
            Box {
                if (isRuntimeShaderSupported()) {
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = 1f - collapsedFraction }
                        .drawPlainBackdrop(backdrop = backdrop,
                            shape = { androidx.compose.ui.graphics.RectangleShape },
                            effects = { blur(6.dp.toPx()) },
                            onDrawSurface = { drawRect(surface.copy(alpha = 0.55f)) }))
                    ProgressiveBlurLayer(backdrop, collapsedFraction)
                } else {
                    Box(Modifier.matchParentSize().background(surface.copy(alpha = 0.82f * (1f - collapsedFraction))))
                }
                TopAppBar(
                    title = "", largeTitle = title, color = Color.Transparent,
                    scrollBehavior = scroll,
                    navigationIcon = { onBack?.let { GlassBackButton(backdrop, collapsedFraction, it) } },
                    actions = actions,
                )
                Box(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(52.dp).fillMaxWidth()
                    .graphicsLayer { alpha = scroll.state.collapsedFraction }.padding(horizontal = 64.dp),
                    contentAlignment = Alignment.Center) {
                    Text(title, style = MiuixTheme.textStyles.title3, maxLines = 1)
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { content(padding, scroll) }
        }
    }
}

/** Keeps existing account/colour form logic while using HyperChanger's actual glass window. */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val dark = MiuixTheme.colorScheme.surfaceContainer.luminance() < 0.5f
    androidx.compose.material3.MaterialTheme(colorScheme = if (dark) androidx.compose.material3.darkColorScheme()
        else androidx.compose.material3.lightColorScheme()) {
        WindowDialog(show = true, modifier = modifier, onDismissRequest = onDismissRequest) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                title?.invoke()
                text?.invoke()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
fun GlassToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    backdrop: LayerBackdrop? = LocalToolbarBackdrop.current,
    collapsedFraction: Float = LocalToolbarCollapsed.current,
    containerPadding: PaddingValues = PaddingValues(horizontal = 2.dp),
    iconTranslationX: Dp = 0.dp,
    onClick: () -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val drag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = -1f..1f,
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) },
        )
    }
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            androidx.compose.ui.geometry.Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
        }
    }
    val glassTint = MiuixTheme.colorScheme.surface.copy(alpha = TOOLBAR_GLASS_SURFACE_ALPHA)
    val iconColor = MiuixTheme.colorScheme.onSurface
    val glassAlpha by animateFloatAsState(collapsedFraction, tween(180), label = "toolbarButtonGlassAlpha")
    Box(
        Modifier.padding(containerPadding)
            .then(Modifier.size(46.dp))
            .then(highlight.gestureModifier)
            .then(drag.modifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = glassAlpha }
                .then(if (backdrop != null) Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(TOOLBAR_GLASS_BLUR_RADIUS.toPx())
                        toolbarGlassLens(drag.pressProgress)
                    },
                    highlight = { toolbarGlassHighlight(drag.pressProgress) },
                    shadow = { Shadow.Default.copy(radius = 5.dp, color = ComposeColor.Black, alpha = .24f) },
                    innerShadow = null,
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val velocity = abs(drag.velocity.coerceIn(-1f, 1f))
                        scaleX *= 1f + velocity * .12f
                        scaleY /= 1f + velocity * .08f
                        translationX = drag.value * 5.dp.toPx()
                    },
                    onDrawSurface = { drawCircle(glassTint) },
                ) else Modifier.background(glassTint, CircleShape))
                .then(highlight.modifier),
        ) {
        }
        Image(
            icon,
            description,
            Modifier.size(22.dp).graphicsLayer { translationX = iconTranslationX.toPx() },
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}


@Composable
private fun BoxScope.ProgressiveBlurLayer(backdrop: LayerBackdrop, collapsedFraction: Float) {
    Box(
        Modifier.matchParentSize()
            .expandDrawHeight(1.184625f)
            .graphicsLayer { alpha = collapsedFraction }
            .drawPlainBackdrop(
                backdrop = backdrop,
                shape = { androidx.compose.ui.graphics.RectangleShape },
                effects = {
                    blur(16.dp.toPx())
                    runtimeShaderEffect(
                        key = "progressive-blur-alpha-mask",
                        shaderString = PROGRESSIVE_BLUR_ALPHA_MASK_SHADER,
                        uniformShaderName = "content"
                    ) { setFloatUniform("size", size.width, size.height) }
                }
            )
    )
}

private const val TOOLBAR_GLASS_SURFACE_ALPHA = .80f
private val TOOLBAR_GLASS_BLUR_RADIUS = 2.dp
private val TOOLBAR_GLASS_LENS_HEIGHT = 16.dp
private val TOOLBAR_GLASS_LENS_AMOUNT = 32.dp

private fun BackdropEffectScope.toolbarGlassLens(progress: Float = 0f) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    lens(
        (TOOLBAR_GLASS_LENS_HEIGHT + 4.dp * clampedProgress).toPx(),
        (TOOLBAR_GLASS_LENS_AMOUNT + 8.dp * clampedProgress).toPx(),
        depthEffect = true,
        chromaticAberration = true,
    )
}

private fun toolbarGlassHighlight(progress: Float = 0f): Highlight {
    val clampedProgress = progress.coerceIn(0f, 1f)
    return Highlight.Default.copy(
        width = .75.dp,
        blurRadius = .4.dp,
        alpha = .76f + clampedProgress * .24f,
    )
}

@Composable
private fun GlassBackButton(backdrop: LayerBackdrop, collapsedFraction: Float, onClick: () -> Unit) {
    GlassToolbarIconButton(
        icon = MiuixIcons.Regular.ChevronBackward,
        description = moduleStringResource(R.string.action_back),
        onClick = onClick,
        backdrop = backdrop,
        collapsedFraction = collapsedFraction,
        containerPadding = PaddingValues(start = 8.dp),
        iconTranslationX = -1.15.dp,
    )
}


@Composable
fun OverlayDropdownPreference(
    title: String,
    summary: String? = null,
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showValueOnEnd: Boolean = false,
    onSelectedIndexChange: (Int) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val isEnabled = enabled && items.isNotEmpty()
    GlassDropdownPreference(
        title = title,
        summary = summary,
        items = items,
        selectedIndex = selectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)),
        enabled = isEnabled,
        modifier = modifier,
        backdrop = LocalToolbarBackdrop.current,
        showValueOnEnd = showValueOnEnd,
        onSelectedIndexChange = { selected ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            onSelectedIndexChange(selected)
        },
    )
}


private const val PROGRESSIVE_BLUR_ALPHA_MASK_SHADER = """
uniform shader content;
uniform float2 size;
half4 main(float2 coord) {
    float blurAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    return content.eval(coord) * blurAlpha;
}
"""

private fun Modifier.expandDrawHeight(factor: Float) = layout { measurable, constraints ->
    val expandedHeight = (constraints.maxHeight * factor).toInt()
    val placeable = measurable.measure(constraints.copy(minHeight = expandedHeight, maxHeight = expandedHeight))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
}

@Composable
fun ModuleAppList(padding: PaddingValues, scroll: ScrollBehavior, bottom: Int = 112, items: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).nestedScroll(scroll.nestedScrollConnection),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 4.dp, 16.dp, padding.calculateBottomPadding() + bottom.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = items
    )
}


@Composable
fun ModuleGroup(
    title: String,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(title, insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = insideMargin) { content() }
    }
}

