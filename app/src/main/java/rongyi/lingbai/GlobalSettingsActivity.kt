package rongyi.lingbai

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import rongyi.lingbai.moduleui.ModuleGlassSurface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import rongyi.lingbai.moduleui.OverlayDropdownPreference as WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.util.Locale

class GlobalSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GlobalSettingsContent(onBack = { finish() }) }
    }
}

@Composable
internal fun GlobalSettingsContent(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (ModuleAppearanceSettings.read(context).theme) {
        ModuleUiPreferences.THEME_LIGHT -> false
        ModuleUiPreferences.THEME_DARK -> true
        else -> systemDark
    }
    val page = if (dark) Color(0xFF0D0E11) else Color(0xFFF4F4F6)
    val initial = remember {
        GlobalAppearanceStore.applyTo(context, GlassSettings.defaults())
    }
    val initialBackdropCapture = remember {
        GlobalAppearanceStore.isBackdropCaptureEnabled(context)
    }
    val initialNativeBlur = remember {
        GlobalAppearanceStore.isNativeBlurEnabled(context)
    }
    val initialSelectedAccent = remember {
        GlobalAppearanceStore.isSelectedAccentEnabled(context)
    }
    val initialBottomBarTraditionalChinese = remember {
        GlobalAppearanceStore.isBottomBarTraditionalChinese(context)
    }
    val initialNavigationHaptics = remember { GlobalAppearanceStore.isNavigationHapticsEnabled(context) }
    val initialNavigationStyle = remember { GlobalAppearanceStore.getNavigationStyle(context) }
    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        if (LocalModulePageActive.current) ApplyGlobalBars(dark, page)
        ModuleGlassSurface { GlobalAppearanceScreen(
            initial = initial,
            initialBackdropCapture = initialBackdropCapture,
            initialNativeBlur = initialNativeBlur,
            initialSelectedAccent = initialSelectedAccent,
            initialBottomBarTraditionalChinese = initialBottomBarTraditionalChinese,
            initialNavigationStyle = initialNavigationStyle,
            initialNavigationHaptics = initialNavigationHaptics,
            dark = dark,
            onBack = onBack,
            onSave = { settings: GlassSettings, toast: Boolean ->
                GlobalAppearanceStore.saveAndSyncAll(context, settings)
                if (toast) {
                    Toast.makeText(context, "全局外观已同步", Toast.LENGTH_SHORT).show()
                }
            },
            onBackdropCaptureChanged = { enabled ->
                GlobalAppearanceStore.setBackdropCaptureEnabled(
                    context,
                    enabled,
                )
            },
            onNativeBlurChanged = { enabled ->
                GlobalAppearanceStore.setNativeBlurEnabled(
                    context,
                    enabled,
                )
            },
            onSelectedAccentChanged = { enabled ->
                GlobalAppearanceStore.setSelectedAccentEnabled(
                    context,
                    enabled,
                )
            },
            onBottomBarLanguageChanged = { traditionalChinese ->
                GlobalAppearanceStore.setBottomBarTraditionalChinese(
                    context,
                    traditionalChinese,
                )
            },
            onNavigationStyleChanged = { style -> GlobalAppearanceStore.setNavigationStyle(context, style) },
            onNavigationHapticsChanged = { enabled -> GlobalAppearanceStore.setNavigationHapticsEnabled(context, enabled) },
        ) }
    }
}

@Composable
private fun ApplyGlobalBars(dark: Boolean, pageColor: Color) {
    val view = LocalView.current
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        activity.window.statusBarColor = pageColor.toArgb()
        activity.window.navigationBarColor = pageColor.toArgb()
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity.window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
    }
}

@Composable
private fun GlobalAppearanceScreen(
    initial: GlassSettings,
    initialBackdropCapture: Boolean,
    initialNativeBlur: Boolean,
    initialSelectedAccent: Boolean,
    initialBottomBarTraditionalChinese: Boolean,
    initialNavigationStyle: String,
    initialNavigationHaptics: Boolean,
    dark: Boolean,
    onBack: () -> Unit,
    onSave: (GlassSettings, Boolean) -> Unit,
    onBackdropCaptureChanged: (Boolean) -> Unit,
    onNativeBlurChanged: (Boolean) -> Unit,
    onSelectedAccentChanged: (Boolean) -> Unit,
    onBottomBarLanguageChanged: (Boolean) -> Unit,
    onNavigationStyleChanged: (String) -> Unit,
    onNavigationHapticsChanged: (Boolean) -> Unit,
) {
    val page = if (dark) Color(0xFF0D0E11) else Color(0xFFF4F4F6)
    val card = MiuixTheme.colorScheme.surfaceContainer
    val title = MiuixTheme.colorScheme.onSurfaceContainer

    var tabWidth by remember { mutableFloatStateOf(initial.tabWidthDp) }
    var bottomPadding by remember { mutableFloatStateOf(initial.bottomPaddingDp) }
    var iconScale by remember { mutableFloatStateOf(initial.iconScale) }
    var textSize by remember { mutableFloatStateOf(initial.textSizeSp) }
    var blur by remember { mutableFloatStateOf(initial.blurRadiusDp) }
    var lightAlpha by remember { mutableFloatStateOf(initial.lightAlpha) }
    var darkAlpha by remember { mutableFloatStateOf(initial.darkAlpha) }
    var backdropCaptureDisabled by remember { mutableStateOf(!initialBackdropCapture) }
    var nativeBlurEnabled by remember { mutableStateOf(initialNativeBlur) }
    var selectedAccentEnabled by remember { mutableStateOf(initialSelectedAccent) }
    var navigationHapticsEnabled by remember { mutableStateOf(initialNavigationHaptics) }
    var navigationStyle by remember { mutableStateOf(initialNavigationStyle) }
    val navigationStyles = listOf(
        InjectedNavigationStyle.HYPER_OS,
        InjectedNavigationStyle.HYPER_OS_FLOATING,
        InjectedNavigationStyle.LIQUID_GLASS,
    )
    var bottomBarTraditionalChinese by remember {
        mutableStateOf(initialBottomBarTraditionalChinese)
    }

    fun snapshot() = GlassSettings(
        System.currentTimeMillis(), true, GlassSettings.THEME_SYSTEM,
        tabWidth, bottomPadding, blur, lightAlpha, darkAlpha,
        iconScale, textSize, true, false, false,
        GlassSettings.DEFAULT_CUSTOM_POST_COLOR, false,
    )


    val latestSnapshot by rememberUpdatedState(snapshot())
    var savedForExit by remember { mutableStateOf(false) }
    fun saveBeforeExit() {
        onSave(latestSnapshot, false)
        savedForExit = true
    }
    val pageBackState = rememberModuleBackState(enabled = !LocalModuleOwnsBack.current && LocalModulePageActive.current) {
        saveBeforeExit()
        onBack()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!savedForExit) onSave(latestSnapshot, false)
        }
    }

    LaunchedEffect(tabWidth, bottomPadding, iconScale, textSize, blur, lightAlpha, darkAlpha) {
        delay(320L)
        onSave(snapshot(), false)
    }

    Column(
        Modifier
            .fillMaxSize()
            .moduleBackTransform(pageBackState)
            .background(page)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        HyperPageHeader(
            title = "全局外观",
            subtitle = "底栏样式、尺寸和玻璃参数会同步到全部已适配应用",
            onBack = {
                saveBeforeExit()
                onBack()
            },
        )

        GlobalSection("底栏样式", title)
        GlobalCard(card) {
            WindowDropdownPreference(
                items = listOf(
                    moduleStringResource(R.string.module_nav_hyperos),
                    moduleStringResource(R.string.module_nav_hyperos_floating),
                    moduleStringResource(R.string.module_nav_liquid),
                ),
                selectedIndex = navigationStyles.indexOf(navigationStyle).coerceAtLeast(0),
                title = "其他应用的底栏样式",
                summary = if (navigationStyle == InjectedNavigationStyle.LIQUID_GLASS) {
                    "统一应用到所有已适配应用；切回目标应用后生效"
                } else "使用应用原有图标，悬浮宽度随项目数量适配",
                onSelectedIndexChange = { index ->
                    val style = navigationStyles.getOrNull(index) ?: return@WindowDropdownPreference
                    if (navigationStyle != style) {
                        navigationStyle = style
                        onNavigationStyleChanged(style)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GlobalSection("底栏交互", title)
        GlobalCard(card) {
            GlobalTogglePreference(
                title = "启用震动",
                summary = "控制三种底栏的点击与拖动切换震动，默认开启",
                checked = navigationHapticsEnabled,
                onCheckedChange = { enabled ->
                    navigationHapticsEnabled = enabled
                    onNavigationHapticsChanged(enabled)
                },
            )
        }

        if (navigationStyle == InjectedNavigationStyle.LIQUID_GLASS) {
            GlobalSection("液态玻璃尺寸", title)
            GlobalCard(card) {
                SliderPreference(
                    value = tabWidth,
                    onValueChange = { tabWidth = it },
                    title = "单项宽度",
                    summary = "仅用于液态玻璃；两种 HyperOS 样式使用原版尺寸",
                    valueText = "${tabWidth.toInt()} dp",
                    valueRange = 64f..92f,
                    steps = 27,
                    modifier = Modifier.fillMaxWidth(),
                )
                SliderPreference(
                    value = bottomPadding,
                    onValueChange = { bottomPadding = it },
                    title = "距屏幕底部",
                    summary = "仅用于液态玻璃；两种 HyperOS 样式使用原版参数",
                    valueText = "${bottomPadding.toInt()} dp",
                    valueRange = 4f..28f,
                    steps = 23,
                    modifier = Modifier.fillMaxWidth(),
                )
                SliderPreference(
                    value = iconScale,
                    onValueChange = { iconScale = it },
                    title = "图标大小",
                    summary = "仅用于液态玻璃；两种 HyperOS 样式使用原版参数",
                    valueText = "${(iconScale * 100).toInt()} %",
                    valueRange = 0.80f..1.20f,
                    modifier = Modifier.fillMaxWidth(),
                )
                SliderPreference(
                    value = textSize,
                    onValueChange = { textSize = it },
                    title = "文字大小",
                    summary = "仅用于液态玻璃；两种 HyperOS 样式使用原版参数",
                    valueText = String.format(Locale.US, "%.1f sp", textSize),
                    valueRange = 9f..14f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        GlobalSection("文字显示", title)
        GlobalCard(card) {
            WindowDropdownPreference(
                items = listOf("简体中文", "繁體中文"),
                selectedIndex = if (bottomBarTraditionalChinese) 1 else 0,
                title = "底栏语言",
                summary = "只影响注入底栏标签，默认使用简体中文",
                onSelectedIndexChange = { index ->
                    val traditional = index == 1
                    if (bottomBarTraditionalChinese != traditional) {
                        bottomBarTraditionalChinese = traditional
                        onBottomBarLanguageChanged(traditional)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GlobalSection("玻璃效果", title)
        GlobalCard(card) {
            GlobalTogglePreference(
                title = "启用原生模糊",
                summary = "开启后关闭所有 Bitmap、PixelCopy 与 TextureView 取景，整条底栏背景只由 HyperOS 系统合成",
                checked = nativeBlurEnabled,
                onCheckedChange = { enabled ->
                    nativeBlurEnabled = enabled
                    onNativeBlurChanged(enabled)
                },
            )
            GlobalTogglePreference(
                title = "关闭所有背景取景",
                summary = if (nativeBlurEnabled) {
                    "原生模糊已启用，截图取景会被强制关闭"
                } else {
                    "停止背景截取；HyperOS 样式使用备用底色，拖动与点击仍可用"
                },
                checked = backdropCaptureDisabled,
                onCheckedChange = { disabled ->
                    backdropCaptureDisabled = disabled
                    onBackdropCaptureChanged(!disabled)
                },
            )
            GlobalTogglePreference(
                title = "选中项使用强调色",
                summary = "仅用于液态玻璃；两种 HyperOS 样式保留原版选中效果",
                checked = selectedAccentEnabled,
                onCheckedChange = { enabled ->
                    selectedAccentEnabled = enabled
                    onSelectedAccentChanged(enabled)
                },
            )
            SliderPreference(
                value = blur,
                onValueChange = { blur = it },
                title = "背景模糊",
                summary = "仅用于液态玻璃；两种 HyperOS 样式使用原版参数",
                valueText = String.format(Locale.US, "%.1f dp", blur),
                valueRange = 0f..8f,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = lightAlpha,
                onValueChange = { lightAlpha = it },
                title = "浅色玻璃浓度",
                summary = "仅用于液态玻璃；两种 HyperOS 样式使用原版参数",
                valueText = "${(lightAlpha * 100).toInt()} %",
                valueRange = 0.10f..0.75f,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = darkAlpha,
                onValueChange = { darkAlpha = it },
                title = "深色玻璃浓度",
                summary = "仅用于液态玻璃；两种 HyperOS 样式使用原版参数",
                valueText = "${(darkAlpha * 100).toInt()} %",
                valueRange = 0.30f..0.92f,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(card)
                .clickable {
                    val d = GlassSettings.defaults()
                    tabWidth = d.tabWidthDp
                    bottomPadding = d.bottomPaddingDp
                    iconScale = d.iconScale
                    textSize = d.textSizeSp
                    blur = d.blurRadiusDp
                    lightAlpha = d.lightAlpha
                    darkAlpha = d.darkAlpha
                    backdropCaptureDisabled = !GlobalAppearanceStore.DEFAULT_BACKDROP_CAPTURE_ENABLED
                    onBackdropCaptureChanged(GlobalAppearanceStore.DEFAULT_BACKDROP_CAPTURE_ENABLED)
                    nativeBlurEnabled = GlobalAppearanceStore.DEFAULT_NATIVE_BLUR_ENABLED
                    onNativeBlurChanged(nativeBlurEnabled)
                    selectedAccentEnabled = GlobalAppearanceStore.DEFAULT_SELECTED_ACCENT_ENABLED
                    onSelectedAccentChanged(selectedAccentEnabled)
                    bottomBarTraditionalChinese =
                        GlobalAppearanceStore.DEFAULT_BOTTOM_BAR_LANGUAGE ==
                            GlobalAppearanceStore.BOTTOM_BAR_LANGUAGE_TRADITIONAL
                    onBottomBarLanguageChanged(bottomBarTraditionalChinese)
                    onSave(snapshot(), true)
                }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("恢复尺寸与玻璃默认值", color = Color(0xFFF5A623), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun GlobalTogglePreference(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary,
        modifier = Modifier.fillMaxWidth(),
        titleColor = BasicComponentDefaults.titleColor(
            color = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
        summaryColor = BasicComponentDefaults.summaryColor(
            color = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.58f),
        ),
    )
}

@Composable
private fun GlobalSection(text: String, _color: Color) {
    SmallTitle(
        text = text,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp,
            top = 19.dp,
            bottom = 8.dp,
        ),
    )
}

@Composable
private fun GlobalCard(_color: Color, content: @Composable ColumnScope.() -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}
