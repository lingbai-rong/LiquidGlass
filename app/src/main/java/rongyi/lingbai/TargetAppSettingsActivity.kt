package rongyi.lingbai

import android.os.Bundle
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import rongyi.lingbai.moduleui.GlassAlertDialog as AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import rongyi.lingbai.moduleui.ModuleGlassSurface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import rongyi.lingbai.moduleui.OverlayDropdownPreference as WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import rongyi.lingbai.moduleui.WindowDialog
import java.util.Locale

class TargetAppSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = TargetApp.fromKey(intent.getStringExtra(EXTRA_TARGET_KEY))
        if (app == null) {
            finish()
            return
        }
        setContent { TargetSettingsContent(app = app, onBack = { finish() }) }
    }

    companion object {
        const val EXTRA_TARGET_KEY = "target_key"
    }
}

@Composable
internal fun TargetSettingsContent(app: TargetApp, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val initial = remember { TargetAppSettings.loadLocal(context, app) }
    var themeMode by remember { mutableIntStateOf(initial.themeMode) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        GlassSettings.THEME_LIGHT -> false
        GlassSettings.THEME_DARK -> true
        else -> systemDark
    }
    val page = if (dark) Color(0xFF0D0E11) else Color(0xFFF4F4F6)
    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        if (LocalModulePageActive.current) ApplyTargetBars(dark, page)
        ModuleGlassSurface { TargetSettingsScreen(
            app = app,
            initial = initial,
            themeMode = themeMode,
            onThemeModeChange = { themeMode = it },
            pageColor = page,
            dark = dark,
            onBack = onBack,
        ) }
    }
}

@Composable
private fun ApplyTargetBars(dark: Boolean, pageColor: Color) {
    val view = LocalView.current
    SideEffect {
        val activity = view.context as? android.app.Activity ?: return@SideEffect
        activity.window.statusBarColor = pageColor.toArgb()
        activity.window.navigationBarColor = pageColor.toArgb()
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity.window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
    }
}

@Composable
private fun TargetSettingsScreen(
    app: TargetApp,
    initial: TargetAppSettings,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    pageColor: Color,
    dark: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalView.current.context
    val card = MiuixTheme.colorScheme.surfaceContainer
    val title = MiuixTheme.colorScheme.onSurfaceContainer
    val secondary = title.copy(alpha = 0.58f)

    var enabled by remember { mutableStateOf(initial.enabled) }
    var hiddenMask by remember { mutableIntStateOf(initial.hiddenMask) }
    var showPost by remember { mutableStateOf(initial.showPost) }
    var tabWidth by remember { mutableFloatStateOf(initial.tabWidthDp) }
    var bottomPadding by remember { mutableFloatStateOf(initial.bottomPaddingDp) }
    var blur by remember { mutableFloatStateOf(initial.blurRadiusDp) }
    var lightAlpha by remember { mutableFloatStateOf(initial.lightAlpha) }
    var darkAlpha by remember { mutableFloatStateOf(initial.darkAlpha) }
    var iconScale by remember { mutableFloatStateOf(initial.iconScale) }
    var textSize by remember { mutableFloatStateOf(initial.textSizeSp) }
    var iconOnly by remember { mutableStateOf(initial.iconOnly) }
    var customColorEnabled by remember { mutableStateOf(initial.customPostColorEnabled) }
    var customPostColor by remember { mutableIntStateOf(initial.customPostColor) }
    var forceBlackBackdrop by remember { mutableStateOf(initial.forceBlackBackdrop) }
    var pressOnlyTransparentBackdrop by remember { mutableStateOf(initial.pressOnlyTransparentBackdrop) }
    var wechatFirstTabLabel by remember { mutableStateOf(initial.wechatFirstTabLabel) }
    var wechatSidePanelEnabled by remember {
        mutableStateOf(WeChatSidePanelPreference.loadLocal(context))
    }
    var wechatRoundAvatarsEnabled by remember(app.key) {
        mutableStateOf(app == TargetApp.WECHAT && WeChatRoundAvatarPreference.loadLocal(context))
    }
    var showColorDialog by remember { mutableStateOf(false) }
    var showWeChatLabelDialog by remember { mutableStateOf(false) }
    var wechatFirstTabLabelDraft by remember { mutableStateOf("") }

    fun snapshot(hiddenMaskOverride: Int = hiddenMask): TargetAppSettings = TargetAppSettings(
        System.currentTimeMillis(),
        enabled,
        hiddenMaskOverride,
        showPost,
        themeMode,
        tabWidth,
        bottomPadding,
        blur,
        lightAlpha,
        darkAlpha,
        iconScale,
        textSize,
        iconOnly,
        customColorEnabled,
        customPostColor,
        forceBlackBackdrop,
        pressOnlyTransparentBackdrop,
        wechatFirstTabLabel,
    )


    val latestSnapshot by rememberUpdatedState(snapshot())
    var savedForExit by remember { mutableStateOf(false) }
    fun saveBeforeExit() {
        if (TargetAppSettings.saveLocal(context, app, latestSnapshot)) {
            savedForExit = true
        }
    }
    val pageBackState = rememberModuleBackState(enabled = !LocalModuleOwnsBack.current && LocalModulePageActive.current) {
        saveBeforeExit()
        onBack()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!savedForExit) TargetAppSettings.saveLocal(context, app, latestSnapshot)
        }
    }

    LaunchedEffect(
        enabled, hiddenMask, showPost, themeMode, tabWidth, bottomPadding, blur,
        lightAlpha, darkAlpha, iconScale, textSize, iconOnly,
        customColorEnabled, customPostColor, forceBlackBackdrop, pressOnlyTransparentBackdrop,
        wechatFirstTabLabel,
    ) {
        delay(320L)
        if (!TargetAppSettings.saveLocal(context, app, snapshot())) {
            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .moduleBackTransform(pageBackState)
            .background(pageColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        HyperPageHeader(
            title = app.displayName,
            largeTitle = true,
            onBack = {
                saveBeforeExit()
                onBack()
            },
        )

        TargetSection("功能", title)
        TargetSettingsCard(card) {
            TargetSwitchRow(
                title = "启用液态玻璃",
                summary = "彻底关闭原生底栏的显示和触摸，仅保留导航控制器",
                checked = enabled,
                titleColor = title,
                secondary = secondary,
                onChecked = { enabled = it },
            )
        }

        if (app.optionLabels.isNotEmpty() && app != TargetApp.WECHAT) {
            TargetSection("栏目", title)
            TargetSettingsCard(card) {
                app.optionLabels.forEachIndexed { option, label ->
                    val labelIndex = app.optionLabelIndexes[option]
                    if (app == TargetApp.DOUYIN && labelIndex == 2) return@forEachIndexed
                    val checked = (hiddenMask and (1 shl labelIndex)) != 0
                    TargetSwitchRow(
                        title = label,
                        summary = "仅从液态底栏隐藏，页面入口仍由应用原生逻辑管理",
                        checked = checked,
                        titleColor = title,
                        secondary = secondary,
                        onChecked = { hide ->
                            val nextMask = if (hide) {
                                hiddenMask or (1 shl labelIndex)
                            } else {
                                hiddenMask and (1 shl labelIndex).inv()
                            }
                            hiddenMask = nextMask
                            // Column visibility changes alter the navigation model itself. Persist
                            // immediately instead of waiting for the debounced appearance saver so
                            // Flutter hosts such as MOONDROP can rebuild their logical/native map
                            // before the settings Activity leaves the foreground.
                            if (!TargetAppSettings.saveLocal(
                                    context,
                                    app,
                                    snapshot(hiddenMaskOverride = nextMask),
                                )
                            ) {
                                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }

        if (app == TargetApp.DOUYIN) {
            TargetSection("抖音专用", title)
            TargetSettingsCard(card) {
                val plusBit = 1 shl 2
                val plusVisible = (hiddenMask and plusBit) == 0
                TargetSwitchRow(
                    title = "显示 + 发布按钮",
                    summary = "关闭后隐藏右侧圆形 +，四个普通页签位置保持不变",
                    checked = plusVisible,
                    titleColor = title,
                    secondary = secondary,
                    onChecked = { visible ->
                        hiddenMask = if (visible) hiddenMask and plusBit.inv() else hiddenMask or plusBit
                    },
                )
                TargetSwitchRow(
                    title = "强制黑色液态背景",
                    summary = "开启后停止读取抖音画面，使用黑色折射纹理；按压液态效果保留",
                    checked = forceBlackBackdrop,
                    titleColor = title,
                    secondary = secondary,
                    onChecked = { enabled ->
                        forceBlackBackdrop = enabled
                        if (enabled) pressOnlyTransparentBackdrop = false
                    },
                )
                TargetSwitchRow(
                    title = "透明待机，按压放大",
                    summary = "平时不显示玻璃底色，像 + 一样透出原画面；按下选中块时才显示 1.2× 放大、折射、色散和高光",
                    checked = pressOnlyTransparentBackdrop,
                    titleColor = title,
                    secondary = secondary,
                    onChecked = { enabled ->
                        pressOnlyTransparentBackdrop = enabled
                        if (enabled) forceBlackBackdrop = false
                    },
                )
            }
        }

        if (app == TargetApp.WECHAT) {
            TargetSection("微信专用", title)
            TargetSettingsCard(card) {
                TargetSwitchRow(
                    title = moduleStringResource(R.string.wechat_round_avatars_title),
                    summary = moduleStringResource(R.string.wechat_round_avatars_summary),
                    checked = wechatRoundAvatarsEnabled,
                    titleColor = title,
                    secondary = secondary,
                    onChecked = { value ->
                        if (WeChatRoundAvatarPreference.saveLocal(context, value)) {
                            wechatRoundAvatarsEnabled = value
                        } else {
                            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                TargetSwitchRow(
                    title = "微信负一屏",
                    summary = "在微信首页向右滑动打开；支持天气、日期、支付、快捷入口和一言卡片",
                    checked = wechatSidePanelEnabled,
                    titleColor = title,
                    secondary = secondary,
                    onChecked = { value ->
                        wechatSidePanelEnabled = value
                        if (!WeChatSidePanelPreference.saveLocal(context, value)) {
                            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                val firstTabLabelSelection = when (wechatFirstTabLabel) {
                    TargetAppSettings.WECHAT_FIRST_TAB_LABEL_CHINESE -> 0
                    TargetAppSettings.WECHAT_FIRST_TAB_LABEL_ENGLISH -> 1
                    else -> 2
                }
                WindowDropdownPreference(
                    items = listOf(
                        TargetAppSettings.WECHAT_FIRST_TAB_LABEL_CHINESE,
                        TargetAppSettings.WECHAT_FIRST_TAB_LABEL_ENGLISH,
                        "自定义",
                    ),
                    selectedIndex = firstTabLabelSelection,
                    title = "底栏首标签",
                    summary = if (firstTabLabelSelection == 2) {
                        "当前显示：$wechatFirstTabLabel"
                    } else {
                        "选择固定名称，或输入最多 ${TargetAppSettings.WECHAT_FIRST_TAB_LABEL_MAX_CODE_POINTS} 个字符"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onSelectedIndexChange = { index ->
                        when (index) {
                            0 -> wechatFirstTabLabel = TargetAppSettings.WECHAT_FIRST_TAB_LABEL_CHINESE
                            1 -> wechatFirstTabLabel = TargetAppSettings.WECHAT_FIRST_TAB_LABEL_ENGLISH
                            2 -> {
                                wechatFirstTabLabelDraft = if (firstTabLabelSelection == 2) {
                                    wechatFirstTabLabel
                                } else {
                                    ""
                                }
                                showWeChatLabelDialog = true
                            }
                        }
                    },
                )
                val discoverBit = 1 shl 2
                val discoverHidden = (hiddenMask and discoverBit) != 0
                TargetSwitchRow(
                    title = "隐藏发现页",
                    summary = "底栏只显示微信、通讯录、我三个标签",
                    checked = discoverHidden,
                    titleColor = title,
                    secondary = secondary,
                    onChecked = { hide ->
                        hiddenMask = if (hide) hiddenMask or discoverBit else hiddenMask and discoverBit.inv()
                    },
                )
            }
        }

        if (app.hasPostAction) {
            TargetSection("中心按钮", title)
            TargetSettingsCard(card) {
                val postTitle = when (app.key) {
                    "bilibili" -> "显示圆形发视频按钮"
                    "xhs" -> "显示圆形发帖按钮"
                    else -> "显示圆形发闲置按钮"
                }
                TargetSwitchRow(
                    title = postTitle,
                    summary = "使用圆形加号，点击调用应用原来的发布入口",
                    checked = showPost,
                    titleColor = title,
                    secondary = secondary,
                    onChecked = { showPost = it },
                )
                if (app.key == "bilibili" || app.key == "xhs") {
                    val defaultColorSummary = if (app.key == "xhs") {
                        "关闭时使用小红书默认红色 #FF2442"
                    } else {
                        "关闭时使用哔哩哔哩默认粉色 #FB7299"
                    }
                    TargetSwitchRow(
                        title = "自定义发布按钮颜色",
                        summary = defaultColorSummary,
                        checked = customColorEnabled,
                        titleColor = title,
                        secondary = secondary,
                        onChecked = { customColorEnabled = it },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showColorDialog = true }
                            .padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("发布按钮颜色", color = title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Text(
                                String.format(Locale.US, "#%06X", customPostColor and 0xFFFFFF),
                                color = secondary,
                                fontSize = 13.sp,
                            )
                        }
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(customPostColor)),
                        )
                    }
                }
            }
        }

        TargetSection("外观模式", title)
        TargetSettingsCard(card) {
            RadioButtonPreference(
                title = "跟随系统",
                summary = "自动跟随系统浅色或深色模式",
                selected = themeMode == GlassSettings.THEME_SYSTEM,
                onClick = { onThemeModeChange(GlassSettings.THEME_SYSTEM) },
                radioButtonLocation = RadioButtonLocation.End,
                modifier = Modifier.fillMaxWidth(),
            )
            RadioButtonPreference(
                title = "强制浅色",
                summary = "固定使用浅色玻璃",
                selected = themeMode == GlassSettings.THEME_LIGHT,
                onClick = { onThemeModeChange(GlassSettings.THEME_LIGHT) },
                radioButtonLocation = RadioButtonLocation.End,
                modifier = Modifier.fillMaxWidth(),
            )
            RadioButtonPreference(
                title = "强制深色",
                summary = "固定使用深色玻璃",
                selected = themeMode == GlassSettings.THEME_DARK,
                onClick = { onThemeModeChange(GlassSettings.THEME_DARK) },
                radioButtonLocation = RadioButtonLocation.End,
                modifier = Modifier.fillMaxWidth(),
            )
            TargetSwitchRow(
                title = "仅显示大图标",
                summary = "关闭后同时显示图标和文字",
                checked = iconOnly,
                titleColor = title,
                secondary = secondary,
                onChecked = { iconOnly = it },
            )
        }

        TargetSection("底栏尺寸", title)
        TargetSettingsCard(card) {
            SliderPreference(
                value = tabWidth,
                onValueChange = { tabWidth = it },
                title = "单项宽度",
                summary = "仅应用于 ${app.displayName}",
                valueText = "${tabWidth.toInt()} dp",
                valueRange = 64f..92f,
                steps = 27,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = bottomPadding,
                onValueChange = { bottomPadding = it },
                title = "距屏幕底部",
                summary = "仅应用于 ${app.displayName}",
                valueText = "${bottomPadding.toInt()} dp",
                valueRange = 4f..28f,
                steps = 23,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = iconScale,
                onValueChange = { iconScale = it },
                title = "图标大小",
                summary = "仅应用于 ${app.displayName}",
                valueText = "${(iconScale * 100).toInt()} %",
                valueRange = 0.80f..1.20f,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = textSize,
                onValueChange = { textSize = it },
                title = "文字大小",
                summary = "仅应用于 ${app.displayName}",
                valueText = String.format(Locale.US, "%.1f sp", textSize),
                valueRange = 9f..14f,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        TargetSection("玻璃效果", title)
        TargetSettingsCard(card) {
            SliderPreference(
                value = blur,
                onValueChange = { blur = it },
                title = "背景模糊",
                summary = "数值越大玻璃越柔和",
                valueText = String.format(Locale.US, "%.1f dp", blur),
                valueRange = 0f..8f,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = lightAlpha,
                onValueChange = { lightAlpha = it },
                title = "浅色玻璃浓度",
                summary = "控制浅色模式遮罩浓度",
                valueText = "${(lightAlpha * 100).toInt()} %",
                valueRange = 0.10f..0.75f,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = darkAlpha,
                onValueChange = { darkAlpha = it },
                title = "深色玻璃浓度",
                summary = "控制深色模式遮罩浓度",
                valueText = "${(darkAlpha * 100).toInt()} %",
                valueRange = 0.30f..0.92f,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(card)
                .clickable {
                    val d = TargetAppSettings.defaults(app)
                    tabWidth = d.tabWidthDp
                    bottomPadding = d.bottomPaddingDp
                    blur = d.blurRadiusDp
                    lightAlpha = d.lightAlpha
                    darkAlpha = d.darkAlpha
                    iconScale = d.iconScale
                    textSize = d.textSizeSp
                    iconOnly = d.iconOnly
                    onThemeModeChange(d.themeMode)
                    if (app == TargetApp.DOUYIN) {
                        val defaults = TargetAppSettings.defaults(app)
                        forceBlackBackdrop = defaults.forceBlackBackdrop
                        pressOnlyTransparentBackdrop = defaults.pressOnlyTransparentBackdrop
                    }
                    if (app.key == "bilibili" || app.key == "xhs") {
                        customColorEnabled = false
                        customPostColor = if (app.key == "xhs") 0xFFFF2442.toInt() else 0xFFFB7299.toInt()
                    }
                }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("恢复此应用的尺寸与玻璃默认值", color = Color(0xFFF5A623), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showColorDialog) {
        TargetColorDialog(
            initialColor = customPostColor,
            dark = dark,
            onDismiss = { showColorDialog = false },
            onConfirm = {
                customPostColor = it
                customColorEnabled = true
                showColorDialog = false
            },
        )
    }

    WeChatFirstTabLabelDialog(
        show = showWeChatLabelDialog,
        value = wechatFirstTabLabelDraft,
        onValueChange = { wechatFirstTabLabelDraft = it },
        onDismiss = { showWeChatLabelDialog = false },
        onConfirm = {
            wechatFirstTabLabel = TargetAppSettings.normalizeWeChatFirstTabLabel(it)
            showWeChatLabelDialog = false
        },
    )
}

@Composable
private fun TargetSection(text: String, _color: Color) {
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
private fun TargetSettingsCard(_color: Color, content: @Composable ColumnScope.() -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun TargetSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    titleColor: Color,
    secondary: Color,
    onChecked: (Boolean) -> Unit,
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onChecked,
        title = title,
        summary = summary.ifEmpty { null },
        modifier = Modifier.fillMaxWidth(),
        titleColor = BasicComponentDefaults.titleColor(color = titleColor),
        summaryColor = BasicComponentDefaults.summaryColor(color = secondary),
    )
}

@Composable
private fun WeChatFirstTabLabelDialog(
    show: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    WindowDialog(
        show = show,
        title = "自定义底栏首标签",
        summary = "最多 ${TargetAppSettings.WECHAT_FIRST_TAB_LABEL_MAX_CODE_POINTS} 个字符，留空恢复为微信",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MiuixTextField(
                value = value,
                onValueChange = {
                    onValueChange(TargetAppSettings.limitWeChatFirstTabLabelInput(it))
                },
                modifier = Modifier.fillMaxWidth(),
                label = "标签文本",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                MiuixTextButton(text = "取消", onClick = onDismiss)
                MiuixTextButton(text = "确定", onClick = { onConfirm(value) })
            }
        }
    }
}

@Composable
private fun TargetColorDialog(
    initialColor: Int,
    dark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val hsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
    }
    var hue by remember(initialColor) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(hsv[1]) }
    var value by remember(initialColor) { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
    val text = if (dark) Color.White else Color(0xFF202124)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义发布按钮颜色") },
        text = {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(color)),
                )
                Spacer(Modifier.height(14.dp))
                Text("色相 ${hue.toInt()}°", color = text)
                MiuixSlider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("饱和度 ${(saturation * 100).toInt()}%", color = text)
                MiuixSlider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("亮度 ${(value * 100).toInt()}%", color = text)
                MiuixSlider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 0.2f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(color) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
