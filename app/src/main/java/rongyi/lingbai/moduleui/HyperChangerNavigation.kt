// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
// Ported from the user-supplied HyperChanger; module defaults retained, with a host adapter.
package rongyi.lingbai.moduleui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import rongyi.lingbai.InjectedNavigationStyle

private data class HostTab(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** HyperChanger's actual HyperNavigation + HyperFloatingNavigationBar, with a module-app adapter. */
@Composable
fun HyperChangerNavigation(
    labels: List<String>,
    icons: List<ImageVector>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    floating: Boolean,
    labelMode: String,
    dark: Boolean,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    require(labels.size == icons.size && labels.isNotEmpty())
    val view = LocalView.current
    val index = remember { mutableIntStateOf(selectedIndex) }
    LaunchedEffect(selectedIndex) { index.intValue = selectedIndex }
    val tabs = labels.mapIndexed { i, label -> HostTab(label, icons[i]) { index.intValue = i; onSelected(i) } }
    Box(modifier.fillMaxWidth().height(if (floating) 100.dp else 76.dp), contentAlignment = Alignment.BottomCenter) {
        HyperNavigation(
            sourceView = view, tabs = tabs, selectedIndex = index,
            floating = floating, backdrop = backdrop, blurRadius = 18,
            showIcons = labelMode != "text_only", showLabels = labelMode != "icon_only",
            textOnly = labelMode == "text_only", advancedMaterial = true,
            hostIsDarkTheme = dark,
        )
    }
}

/** Only host geometry/routing lives here; visual presets come from HyperChanger. */
internal data class InjectedNavigationAppearance(
    val width: Dp,
    val blockedIndex: Int,
    val bottomInset: Dp,
    val nativeBackdropActive: Boolean,
    val onContentHeightChanged: (Float) -> Unit,
    val actionLabel: String,
    val actionContent: (@Composable () -> Unit)?,
)

@Composable
internal fun HyperChangerInjectedNavigation(
    labels: List<String>,
    iconContent: @Composable (Int, Color, Modifier) -> Unit,
    selectedIndex: Int,
    onSelected: (Int) -> Int,
    floating: Boolean,
    iconOnly: Boolean,
    dark: Boolean,
    backdrop: Backdrop,
    materialEnabled: Boolean,
    appearance: InjectedNavigationAppearance,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val index = remember { mutableIntStateOf(selectedIndex) }
    SideEffect { index.intValue = selectedIndex.coerceIn(labels.indices) }
    // Read the actual host result after dispatch, so rejected clicks restore the indicator.
    val tabs = labels.mapIndexed { i, label ->
        HostTab(label, HyperIcons.Empty) { index.intValue = onSelected(i).coerceIn(labels.indices) }
    }
    val height = if (floating) InjectedNavigationStyle.FLOATING_HEIGHT_DP.dp else 76.dp + appearance.bottomInset
    Box(modifier.width(appearance.width).height(height)) {
        HyperNavigation(
            sourceView = LocalView.current, tabs = tabs, selectedIndex = index,
            floating = floating, backdrop = backdrop, blurRadius = 18,
            showIcons = true, showLabels = !iconOnly, textOnly = false,
            advancedMaterial = materialEnabled, hostIsDarkTheme = dark,
            tabIconContent = iconContent,
            injected = appearance,
        )
    }
}

@Composable
private fun HyperNavigation(
    sourceView: View,
    tabs: List<HostTab>,
    selectedIndex: MutableIntState,
    floating: Boolean,
    backdrop: Backdrop,
    blurRadius: Int,
    showIcons: Boolean,
    showLabels: Boolean,
    textOnly: Boolean,
    advancedMaterial: Boolean,
    hostIsDarkTheme: Boolean,
    accentColorOverride: Color? = null,
    tabImageVector: ((Int) -> ImageVector)? = null,
    tabIconContent: (@Composable (Int, Color, Modifier) -> Unit)? = null,
    injected: InjectedNavigationAppearance? = null,
) {
    val controller = remember(hostIsDarkTheme) {
        ThemeController(
            if (hostIsDarkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light
        )
    }
    val view = LocalView.current
    val onTabClick: (Int) -> Unit = { index ->
        // Injected bars share the host dispatch gate with liquid glass. Module UI keeps
        // HyperChanger's feedback here; this avoids two pulses for one injected action.
        if (injected == null) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        tabs[index].onClick()
    }
    MiuixTheme(controller = controller) {
        val isDarkTheme = hostIsDarkTheme
        val useAdvancedMaterial = advancedMaterial && isRuntimeShaderSupported()
        val density = LocalDensity.current
        val materialBlurRadius = with(density) {
            blurRadius.coerceIn(0, 40).dp.toPx()
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            if (floating) {
                HyperFloatingNavigationBar(
                    tabs = tabs,
                    selectedIndex = selectedIndex,
                    backdrop = backdrop,
                    blurRadius = blurRadius.coerceIn(0, 40).toFloat(),
                    showIcons = showIcons,
                    showLabels = showLabels,
                    advancedMaterial = useAdvancedMaterial,
                    isDarkTheme = isDarkTheme,
                    accentColorOverride = accentColorOverride,
                    tabImageVector = tabImageVector,
                    tabIconContent = tabIconContent,
                    injected = injected,
                    onTabSelected = onTabClick
                )
            } else {
                val standardMaterialEnabled = useAdvancedMaterial
                val standardBarMaterial = if (standardMaterialEnabled) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { androidx.compose.ui.graphics.RectangleShape },
                        effects = {
                            vibrancy()
                            blur(materialBlurRadius)
                        },
                        highlight = null,
                        onDrawSurface = {
                            drawRect(
                                if (isDarkTheme) Color.Black.copy(alpha = 0.58f)
                                else Color.White.copy(alpha = 0.62f)
                            )
                        }
                    )
                } else {
                    Modifier
                }
                NavigationBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .graphicsLayer {
                            shape = androidx.compose.ui.graphics.RectangleShape
                            clip = true
                            shadowElevation = 0f
                            ambientShadowColor = Color.Transparent
                            spotShadowColor = Color.Transparent
                        }
                        .then(standardBarMaterial)
                        .then(if (injected != null) Modifier.onSizeChanged { size ->
                            injected.onContentHeightChanged(
                                (with(density) { size.height.toDp().value } - injected.bottomInset.value).coerceAtLeast(1f),
                            )
                        }.padding(bottom = injected.bottomInset) else Modifier),
                    color = if (standardMaterialEnabled) Color.Transparent
                        else if (injected?.nativeBackdropActive == true) {
                            if (isDarkTheme) Color.Black.copy(alpha = 0.58f) else Color.White.copy(alpha = 0.62f)
                        } else MiuixTheme.colorScheme.surface,
                    showDivider = false,
                    defaultWindowInsetsPadding = injected == null,
                    mode = if (showLabels) {
                        NavigationBarDisplayMode.IconAndText
                    } else {
                        NavigationBarDisplayMode.IconOnly
                    }
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val actionContent = injected?.actionContent
                        if (injected?.blockedIndex == index && actionContent != null) {
                            Box(
                                Modifier.weight(1f).height(NavigationBarDefaults.ItemHeight)
                                    .semantics { contentDescription = injected.actionLabel }
                                    .clickable(interactionSource = null, indication = null,
                                        role = Role.Button, onClick = { onTabClick(index) }),
                                contentAlignment = Alignment.Center,
                            ) { actionContent() }
                        } else if (textOnly) {
                            FontNavigationItem(
                                selected = selectedIndex.intValue == index,
                                onClick = { onTabClick(index) },
                                label = tab.label,
                                modifier = Modifier.weight(1f),
                                textOnly = true,
                                selectedColor = accentColorOverride,
                                icon = {}
                            )
                        } else if (injected != null && tabIconContent != null) {
                            HostNavigationBarItem(
                                selected = selectedIndex.intValue == index,
                                onClick = { onTabClick(index) },
                                label = tab.label,
                                showLabels = showLabels,
                                enabled = tab.label.isNotEmpty(),
                                icon = { color, modifier -> tabIconContent(index, color, modifier) },
                            )
                        } else if (tabIconContent != null) {
                            FontNavigationItem(
                                selected = selectedIndex.intValue == index,
                                onClick = { onTabClick(index) },
                                label = if (showLabels) tab.label else "",
                                modifier = Modifier.weight(1f),
                                selectedColor = accentColorOverride,
                                icon = { color ->
                                    if (showIcons) tabIconContent(index, color, Modifier.size(NavigationBarDefaults.IconSize))
                                }
                            )
                        } else {
                            NavigationBarItem(
                                selected = selectedIndex.intValue == index,
                                onClick = { onTabClick(index) },
                                icon = if (showIcons) {
                                    tab.icon ?: tabImageVector?.invoke(index) ?: hyperIcon(tab.label)
                                } else {
                                    HyperIcons.Empty
                                },
                                label = tab.label
                            )
                        }
                    }
                }
            }
        }
    }
}

/** OS4-style floating navigation: one capsule, a moving selected pill, and a damped drag. */
@Composable
private fun HyperFloatingNavigationBar(
    tabs: List<HostTab>,
    selectedIndex: MutableIntState,
    backdrop: Backdrop,
    blurRadius: Float,
    showIcons: Boolean,
    showLabels: Boolean,
    advancedMaterial: Boolean,
    isDarkTheme: Boolean,
    accentColorOverride: Color?,
    tabImageVector: ((Int) -> ImageVector)?,
    tabIconContent: (@Composable (Int, Color, Modifier) -> Unit)?,
    injected: InjectedNavigationAppearance?,
    onTabSelected: (Int) -> Unit,
) {
    // Keep the OS4 floating bar compact without changing the other navigation styles.
    val floatingScale = 0.82f
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val tabCount = tabs.size.coerceAtLeast(1)
    val initialIndex = selectedIndex.intValue.coerceIn(0, tabCount - 1)
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)
    val currentInjected by rememberUpdatedState(injected)
    val dragAnimation = remember(tabCount) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = initialIndex.toFloat(),
            valueRange = 0f..(tabCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // OS4 compresses the floating pill while it is held, then springs back on release.
            pressedScale = 0.9f,
            onDragStopped = {
                val target = InjectedNavigationStyle.resolveDragSlot(
                    targetValue.fastRoundToInt(), selectedIndex.intValue,
                    currentInjected?.blockedIndex ?: -1, tabCount,
                )
                if (target != selectedIndex.intValue) currentOnTabSelected(target)
                val accepted = if (currentInjected != null) selectedIndex.intValue else target
                animateToValue(accepted.toFloat())
            },
            onDrag = { size, amount ->
                val slotWidth = size.width.toFloat() / tabCount.coerceAtLeast(1)
                if (slotWidth > 0f) {
                    updateValue((targetValue + amount.x / slotWidth).fastCoerceIn(0f, (tabCount - 1).toFloat()))
                }
            },
            // OS4 restores the pressed scale as soon as the finger is released.
            awaitTargetBeforeRelease = false,
        )
    }
    LaunchedEffect(selectedIndex.intValue) {
        dragAnimation.animateToValue(selectedIndex.intValue.coerceIn(0, tabCount - 1).toFloat())
    }
    val contentColor = if (isDarkTheme) Color.White else Color.Black
    val selectedContentColor = accentColorOverride ?: contentColor
    val pressedIndex = if (dragAnimation.pressProgress > 0.001f) {
        dragAnimation.value.fastRoundToInt().fastCoerceIn(0, tabCount - 1)
    } else {
        -1
    }
    val selectedSurface = if (isDarkTheme) {
        Color.White.copy(alpha = 0.18f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }
    val containerSurface = if (isDarkTheme) {
        Color.Black.copy(alpha = 0.30f)
    } else {
        Color(0xFFF5F5F5).copy(alpha = 0.42f)
    }
    val fallbackSurface = if (injected?.nativeBackdropActive == true) containerSurface else if (isDarkTheme) {
        Color(0xFF242424).copy(alpha = 0.88f)
    } else {
        Color(0xFFF5F5F5).copy(alpha = 0.82f)
    }
    val capsule = Capsule()
    val shadowColor = Color.Black.copy(alpha = 0.35f)

    BoxWithConstraints(
        if (injected != null) Modifier.width(injected.width).height(72.dp * floatingScale)
        else Modifier
            .fillMaxWidth(0.53f * floatingScale)
            .navigationBarsPadding()
            .padding(bottom = 8.dp * floatingScale)
            .height(72.dp * floatingScale),
        contentAlignment = Alignment.CenterStart,
    ) {
        val innerPadding = 4.dp * floatingScale
        val innerWidthPx = with(density) { (maxWidth - innerPadding * 2).toPx() }
        val tabWidthPx = innerWidthPx / tabCount
        val tabWidth = with(density) { tabWidthPx.toDp() }
        val selectedTranslation = dragAnimation.value * tabWidthPx
        Box(
            Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 6.dp,
                    shape = capsule,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
                .then(
                    if (advancedMaterial) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { capsule },
                            effects = {
                                vibrancy()
                                blur(with(density) { (blurRadius * 0.24f).dp.toPx() })
                                // Keep the lens pronounced while avoiding an overly strong refraction.
                                lens(
                                    refractionHeight = 16.dp.toPx(),
                                    refractionAmount = 32.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = false,
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = if (isDarkTheme) 0.78f else 0.96f)
                            },
                            onDrawSurface = {
                                drawRect(containerSurface)
                            },
                        )
                    } else Modifier.clip(capsule).background(fallbackSurface)
                )
        )
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            // Draw the selection behind labels, while a transparent overlay below captures drag.
            Box(
                Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = selectedTranslation
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                    }
                    .clip(capsule)
                    .background(selectedSurface)
            )
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val actionContent = injected?.actionContent
                    val isAction = injected?.blockedIndex == index && actionContent != null
                    val pressedProgress = if (pressedIndex == index) dragAnimation.pressProgress else 0f
                    val color = if (pressedProgress > 0f) {
                        Color.Gray
                    } else {
                        // OS4 uses the hover capsule, rather than a different tint, to show selection.
                        selectedContentColor
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .graphicsLayer {
                                val scale = lerp(1f, 0.85f, pressedProgress)
                                scaleX = scale
                                scaleY = scale
                            }
                            .clickable(
                                interactionSource = null,
                                indication = null,
                                role = if (isAction) Role.Button else Role.Tab,
                            ) {
                                if (injected != null || index != selectedIndex.intValue) onTabSelected(index)
                            }
                            .then(if (isAction) Modifier.semantics {
                                contentDescription = injected?.actionLabel.orEmpty()
                            } else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (isAction) actionContent?.invoke()
                        else if (showIcons) {
                            val icon = tab.icon ?: tabImageVector?.invoke(index) ?: hyperIcon(tab.label)
                            if (tabIconContent != null) {
                                tabIconContent(index, color, Modifier.size(28.dp * floatingScale))
                            } else {
                                Image(icon, null, Modifier.size(28.dp * floatingScale), colorFilter = ColorFilter.tint(color))
                            }
                        }
                        if (showLabels && tab.label.isNotEmpty() && tab.label != "+") {
                            BasicText(
                                tab.label,
                                style = TextStyle(
                                    color = color,
                                    fontSize = if (showIcons) 15.sp * floatingScale else 17.sp * floatingScale,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                ),
                                modifier = if (showIcons) Modifier.padding(top = 2.dp * floatingScale) else Modifier,
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .width(tabWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = selectedTranslation
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                    }
                    .then(dragAnimation.modifier)
                    .then(if (injected != null) Modifier.clickable(
                        interactionSource = null, indication = null, role = Role.Tab,
                        onClick = { onTabSelected(selectedIndex.intValue) },
                    ) else Modifier)
            )
        }
    }
}

@Composable
private fun FontNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier,
    textOnly: Boolean = false,
    selectedColor: Color? = null,
    icon: @Composable (Color) -> Unit
) {
    val color = if (selected && selectedColor != null) {
        selectedColor
    } else {
        MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = if (selected) 1f else 0.55f)
    }
    Column(
        modifier = modifier.height(56.dp).clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon(color)
        if (label.isNotEmpty()) {
            BasicText(label, style = TextStyle(color = color, fontSize = if (textOnly) 16.5.sp else 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal))
        }
    }
}
