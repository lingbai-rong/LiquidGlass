package rongyi.lingbai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.util.Locale
import kotlinx.coroutines.delay

class CoolapkSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CoolapkSettingsContent(onBack = { finish() }) }
    }

}

@Composable
internal fun CoolapkSettingsContent(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    fun saveSettings(settings: GlassSettings, showToast: Boolean) {
        val ok = GlassSettings.saveLocal(context, settings.withRevision(System.currentTimeMillis()))
        if (showToast) Toast.makeText(context, if (ok) "設定已儲存" else "設定儲存可能未完全成功", Toast.LENGTH_SHORT).show()
    }
    val initial = remember {
        GlassSettings.loadLocal(context)
    }
    var appThemeMode by remember { mutableIntStateOf(initial.themeMode) }
    val systemDark = isSystemInDarkTheme()
    val appDark = when (appThemeMode) {
        GlassSettings.THEME_LIGHT -> false
        GlassSettings.THEME_DARK -> true
        else -> systemDark
    }
    val colors = if (appDark) darkColorScheme() else lightColorScheme()
    val pageColor = if (appDark) Color(0xFF0D0E11) else Color(0xFFF4F4F6)

    MiuixTheme(colors = colors) {
        if (LocalModulePageActive.current) ApplySystemBars(dark = appDark, pageColor = pageColor)
        ModuleGlassSurface { SettingsScreen(
            initial = initial,
            themeMode = appThemeMode,
            onThemeModeChange = { appThemeMode = it },
            appDark = appDark,
            onBack = onBack,
            onSave = { settings -> saveSettings(settings, true) },
            onAutoSave = { settings -> saveSettings(settings, false) },
            onReset = { settings ->
                saveSettings(settings, true)
                Toast.makeText(context, "已恢復底欄尺寸與玻璃效果", Toast.LENGTH_SHORT).show()
            },
            onOpenCoolapk = {
                val intent = context.packageManager.getLaunchIntentForPackage("com.coolapk.market")
                if (intent == null) {
                    Toast.makeText(context, "未找到酷安", Toast.LENGTH_SHORT).show()
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(intent)
                }
            },
        ) }
    }
}

@Composable
private fun ApplySystemBars(dark: Boolean, pageColor: Color) {
    val view = LocalView.current
    SideEffect {
        val activity = view.context as? Activity ?: return@SideEffect
        val window = activity.window
        window.statusBarColor = pageColor.toArgb()
        window.navigationBarColor = pageColor.toArgb()
        val lightFlags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else lightFlags,
            lightFlags,
        )
    }
}

@Composable
private fun SettingsScreen(
    initial: GlassSettings,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    appDark: Boolean,
    onBack: () -> Unit,
    onSave: (GlassSettings) -> Unit,
    onAutoSave: (GlassSettings) -> Unit,
    onReset: (GlassSettings) -> Unit,
    onOpenCoolapk: () -> Unit,
) {
    val pageColor = if (appDark) Color(0xFF0D0E11) else Color(0xFFF4F4F6)
    val cardColor = MiuixTheme.colorScheme.surfaceContainer
    val titleColor = MiuixTheme.colorScheme.onSurfaceContainer
    val primary = MiuixTheme.colorScheme.primary
    val warning = Color(0xFFF5A623)

    var enabled by remember { mutableStateOf(initial.enabled) }
    var tabWidthDp by remember { mutableFloatStateOf(initial.tabWidthDp) }
    var bottomPaddingDp by remember { mutableFloatStateOf(initial.bottomPaddingDp) }
    var blurRadiusDp by remember { mutableFloatStateOf(initial.blurRadiusDp) }
    var lightAlpha by remember { mutableFloatStateOf(initial.lightAlpha) }
    var darkAlpha by remember { mutableFloatStateOf(initial.darkAlpha) }
    var iconScale by remember { mutableFloatStateOf(initial.iconScale) }
    var textSizeSp by remember { mutableFloatStateOf(initial.textSizeSp) }
    var iconOnly by remember { mutableStateOf(initial.iconOnly) }
    var showPostButton by remember { mutableStateOf(initial.showPostButton) }
    var customColorEnabled by remember { mutableStateOf(initial.customColorEnabled) }
    var customPostColor by remember { mutableIntStateOf(initial.customPostColor) }
    var showColorDialog by remember { mutableStateOf(false) }
    var lastSaved by remember { mutableLongStateOf(initial.revision) }

    fun currentSettings() = GlassSettings(
        lastSaved,
        enabled,
        themeMode,
        tabWidthDp,
        bottomPaddingDp,
        blurRadiusDp,
        lightAlpha,
        darkAlpha,
        iconScale,
        textSizeSp,
        iconOnly,
        showPostButton,
        customColorEnabled,
        customPostColor,
    )

    val latestSettings by rememberUpdatedState(currentSettings())
    var savedForExit by remember { mutableStateOf(false) }
    fun saveBeforeExit() {
        onAutoSave(latestSettings.withRevision(System.currentTimeMillis()))
        savedForExit = true
    }
    val pageBackState = rememberModuleBackState(enabled = !LocalModuleOwnsBack.current && LocalModulePageActive.current) {
        saveBeforeExit()
        onBack()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!savedForExit) {
                onAutoSave(latestSettings.withRevision(System.currentTimeMillis()))
            }
        }
    }

    // Persist the selected mode and slider values even when the user leaves the
    // settings app without pressing the launch button. Rapid slider changes are debounced.
    LaunchedEffect(
        enabled,
        themeMode,
        tabWidthDp,
        bottomPaddingDp,
        blurRadiusDp,
        lightAlpha,
        darkAlpha,
        iconScale,
        textSizeSp,
        iconOnly,
        showPostButton,
        customColorEnabled,
        customPostColor,
    ) {
        delay(350L)
        val settings = currentSettings().withRevision(System.currentTimeMillis())
        lastSaved = settings.revision
        onAutoSave(settings)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .moduleBackTransform(pageBackState)
            .background(pageColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CoolapkSecondLevelHeader(
            title = "酷安",
            titleColor = titleColor,
            onBack = {
                saveBeforeExit()
                onBack()
            },
        )

        Spacer(modifier = Modifier.height(8.dp))
        PreferenceCard(cardColor) {
            CoolapkQuickSwitchRow(
                title = "启用液态玻璃",
                summary = "",
                checked = enabled,
                titleColor = titleColor,
                summaryColor = titleColor.copy(alpha = 0.62f),
                onCheckedChange = { enabled = it },
            )
        }

        SectionLabel("外觀模式", titleColor)
        PreferenceCard(cardColor) {
            RadioButtonPreference(
                title = "跟隨系統",
                summary = "自動跟隨系統淺色或深色模式",
                selected = themeMode == GlassSettings.THEME_SYSTEM,
                onClick = { onThemeModeChange(GlassSettings.THEME_SYSTEM) },
                radioButtonLocation = RadioButtonLocation.End,
                modifier = Modifier.fillMaxWidth(),
            )
            RadioButtonPreference(
                title = "強制淺色",
                summary = "固定使用淺色玻璃和深色圖示文字",
                selected = themeMode == GlassSettings.THEME_LIGHT,
                onClick = { onThemeModeChange(GlassSettings.THEME_LIGHT) },
                radioButtonLocation = RadioButtonLocation.End,
                modifier = Modifier.fillMaxWidth(),
            )
            RadioButtonPreference(
                title = "強制深色",
                summary = "固定使用黑色玻璃和白色圖示文字",
                selected = themeMode == GlassSettings.THEME_DARK,
                onClick = { onThemeModeChange(GlassSettings.THEME_DARK) },
                radioButtonLocation = RadioButtonLocation.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionLabel("底欄內容", titleColor)
        PreferenceCard(cardColor) {
            RadioButtonPreference(
                title = "僅顯示大圖示",
                summary = "隱藏首頁、數碼、發現、我的文字，僅保留置中大圖示",
                selected = iconOnly,
                onClick = { iconOnly = true },
                radioButtonLocation = RadioButtonLocation.End,
                modifier = Modifier.fillMaxWidth(),
            )
            RadioButtonPreference(
                title = "圖示和文字",
                summary = "同時顯示導覽圖示與文字標籤",
                selected = !iconOnly,
                onClick = { iconOnly = false },
                radioButtonLocation = RadioButtonLocation.End,
                modifier = Modifier.fillMaxWidth(),
            )
            RadioButtonPreference(
                title = "顯示中間發帖按鈕",
                summary = "恢復中間獨立發帖入口，玻璃選取框會直接跨過它",
                selected = showPostButton,
                onClick = { showPostButton = true },
                radioButtonLocation = RadioButtonLocation.End,
                modifier = Modifier.fillMaxWidth(),
            )
            RadioButtonPreference(
                title = "隱藏中間發帖按鈕",
                summary = "繼續使用四個導覽項目的緊湊版面",
                selected = !showPostButton,
                onClick = { showPostButton = false },
                radioButtonLocation = RadioButtonLocation.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionLabel("發帖按鈕顏色", titleColor)
        PreferenceCard(cardColor) {
            CustomColorTogglePreference(
                enabled = customColorEnabled,
                titleColor = titleColor,
                summaryColor = titleColor.copy(alpha = 0.62f),
                onEnabledChange = { customColorEnabled = it },
            )
            CustomColorPreference(
                color = customPostColor,
                titleColor = titleColor,
                summaryColor = titleColor.copy(alpha = 0.62f),
                onClick = { showColorDialog = true },
            )
        }

        if (showColorDialog) {
            CustomColorDialog(
                initialColor = customPostColor,
                appDark = appDark,
                onDismiss = { showColorDialog = false },
                onConfirm = { color ->
                    customPostColor = color
                    // Choosing a color only changes the saved custom color. The switch
                    // remains authoritative: off keeps the original green, on uses this color.
                    showColorDialog = false
                },
            )
        }

        SectionLabel("底欄尺寸", titleColor)
        PreferenceCard(cardColor) {
            SliderPreference(
                value = tabWidthDp,
                onValueChange = { tabWidthDp = it },
                title = "單項寬度",
                summary = "全域同步；每個導覽項目的最小寬度",
                valueText = "${tabWidthDp.toInt()} dp",
                valueRange = 64f..92f,
                steps = 27,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = bottomPaddingDp,
                onValueChange = { bottomPaddingDp = it },
                title = "距螢幕底部",
                summary = "全域同步；調整底欄與手勢條之間的距離",
                valueText = "${bottomPaddingDp.toInt()} dp",
                valueRange = 4f..28f,
                steps = 23,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = iconScale,
                onValueChange = { iconScale = it },
                title = "圖示大小",
                summary = "全域同步；調整導覽圖示的整體比例",
                valueText = "${(iconScale * 100).toInt()} %",
                valueRange = 0.80f..1.20f,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = textSizeSp,
                onValueChange = { textSizeSp = it },
                title = "文字大小",
                summary = "全域同步；調整底欄文字大小",
                valueText = String.format(Locale.US, "%.1f sp", textSizeSp),
                valueRange = 9f..14f,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionLabel("玻璃效果", titleColor)
        PreferenceCard(cardColor) {
            SliderPreference(
                value = blurRadiusDp,
                onValueChange = { blurRadiusDp = it },
                title = "背景模糊",
                summary = "全域同步；模糊越高，玻璃越柔和但效能開銷越大",
                valueText = String.format(Locale.US, "%.1f dp", blurRadiusDp),
                valueRange = 0f..8f,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = lightAlpha,
                onValueChange = { lightAlpha = it },
                title = "淺色玻璃濃度",
                summary = "控制淺色模式下灰白玻璃的遮罩濃度",
                valueText = "${(lightAlpha * 100).toInt()} %",
                valueRange = 0.10f..0.75f,
                modifier = Modifier.fillMaxWidth(),
            )
            SliderPreference(
                value = darkAlpha,
                onValueChange = { darkAlpha = it },
                title = "深色玻璃濃度",
                summary = "控制深色模式下黑色玻璃的遮罩濃度",
                valueText = "${(darkAlpha * 100).toInt()} %",
                valueRange = 0.30f..0.92f,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionLabel("套用", titleColor)
        val neutralButtonBackground = if (appDark) Color(0xFF24262C) else Color.White
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val gap = 10.dp
            val buttonWidth = (maxWidth - gap) / 2
            Row(modifier = Modifier.fillMaxWidth()) {
                ActionButton(
                    text = "儲存並開啟酷安",
                    background = neutralButtonBackground,
                    foreground = primary,
                    modifier = Modifier.width(buttonWidth),
                ) {
                    val settings = currentSettings().copy(revision = System.currentTimeMillis())
                    lastSaved = settings.revision
                    onSave(settings)
                    onOpenCoolapk()
                }
                Spacer(modifier = Modifier.width(gap))
                ActionButton(
                    text = "恢復外觀預設",
                    background = neutralButtonBackground,
                    foreground = warning,
                    modifier = Modifier.width(buttonWidth),
                ) {
                    // Restore shared appearance only. Do not disable the module or change
                    // theme, icon/text mode, post button or custom color choices.
                    tabWidthDp = GlassSettings.DEFAULT_TAB_WIDTH.toFloat()
                    bottomPaddingDp = GlassSettings.DEFAULT_BOTTOM_PADDING.toFloat()
                    blurRadiusDp = GlassSettings.DEFAULT_BLUR_RADIUS / 10f
                    lightAlpha = GlassSettings.DEFAULT_LIGHT_ALPHA / 100f
                    darkAlpha = GlassSettings.DEFAULT_DARK_ALPHA / 100f
                    iconScale = GlassSettings.DEFAULT_ICON_SCALE / 100f
                    textSizeSp = GlassSettings.DEFAULT_TEXT_SIZE / 10f
                    val reset = GlassSettings(
                        System.currentTimeMillis(), enabled, themeMode,
                        tabWidthDp, bottomPaddingDp, blurRadiusDp,
                        lightAlpha, darkAlpha, iconScale, textSizeSp,
                        iconOnly, showPostButton, customColorEnabled, customPostColor,
                    )
                    lastSaved = reset.revision
                    onReset(reset)
                }
            }
        }
    }
}

@Composable
private fun CoolapkSecondLevelHeader(
    title: String,
    titleColor: Color,
    onBack: () -> Unit,
) {
    HyperPageHeader(title = title, onBack = onBack, largeTitle = true)
}

@Composable
private fun CoolapkQuickSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    titleColor: Color,
    summaryColor: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary.ifEmpty { null },
        modifier = Modifier.fillMaxWidth(),
        titleColor = BasicComponentDefaults.titleColor(color = titleColor),
        summaryColor = BasicComponentDefaults.summaryColor(color = summaryColor),
    )
}

@Composable
private fun CustomColorTogglePreference(
    enabled: Boolean,
    titleColor: Color,
    summaryColor: Color,
    onEnabledChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        checked = enabled,
        onCheckedChange = onEnabledChange,
        title = "自訂取色",
        summary = "開啟後使用下方自訂顏色，關閉後保持原版綠色",
        modifier = Modifier.fillMaxWidth(),
        titleColor = BasicComponentDefaults.titleColor(color = titleColor),
        summaryColor = BasicComponentDefaults.summaryColor(color = summaryColor),
    )
}

@Composable
private fun CustomColorPreference(
    color: Int,
    titleColor: Color,
    summaryColor: Color,
    onClick: () -> Unit,
) {
    ArrowPreference(
        title = "選擇顏色",
        summary = "${customColorName(color)} · ${String.format(Locale.US, "#%06X", color and 0xFFFFFF)}",
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        titleColor = BasicComponentDefaults.titleColor(color = titleColor),
        summaryColor = BasicComponentDefaults.summaryColor(color = summaryColor),
        startAction = {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color(color)),
            )
        },
    )
}

@Composable
private fun CustomColorDialog(
    initialColor: Int,
    appDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsv = remember(initialColor) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initialColor, it) }
    }
    var hue by remember(initialColor) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember(initialColor) { mutableFloatStateOf(initialHsv[2]) }

    fun selectedColor(): Int = android.graphics.Color.HSVToColor(
        floatArrayOf(hue, saturation, brightness),
    )

    fun applyColor(color: Int) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
    }

    val currentColor = selectedColor()
    val textColor = if (appDark) Color.White else Color(0xFF17181B)
    val secondaryText = textColor.copy(alpha = 0.62f)
    val presets = listOf(
        0xFFFF3B30.toInt(),
        0xFFFF8A00.toInt(),
        0xFFFFCC00.toInt(),
        0xFF34C759.toInt(),
        0xFF00B8D4.toInt(),
        0xFF0A84FF.toInt(),
        0xFF7C4DFF.toInt(),
        0xFFFF2D87.toInt(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "自訂取色",
                color = textColor,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color(currentColor))
                            .border(
                                width = 1.dp,
                                color = textColor.copy(alpha = 0.18f),
                                shape = CircleShape,
                            ),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = customColorName(currentColor),
                            color = textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = String.format(Locale.US, "#%06X · 單色", currentColor and 0xFFFFFF),
                            color = secondaryText,
                            fontSize = 13.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    presets.take(4).forEach { preset ->
                        ColorPreset(
                            color = preset,
                            selected = colorsClose(preset, currentColor),
                            borderColor = textColor,
                            onClick = { applyColor(preset) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    presets.drop(4).forEach { preset ->
                        ColorPreset(
                            color = preset,
                            selected = colorsClose(preset, currentColor),
                            borderColor = textColor,
                            onClick = { applyColor(preset) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                ColorSliderRow(
                    label = "色相",
                    value = hue,
                    valueText = "${hue.toInt()}°",
                    valueRange = 0f..360f,
                    textColor = textColor,
                    onValueChange = { hue = it },
                )
                ColorSliderRow(
                    label = "飽和",
                    value = saturation,
                    valueText = "${(saturation * 100).toInt()}%",
                    valueRange = 0f..1f,
                    textColor = textColor,
                    onValueChange = { saturation = it },
                )
                ColorSliderRow(
                    label = "亮度",
                    value = brightness,
                    valueText = "${(brightness * 100).toInt()}%",
                    valueRange = 0.15f..1f,
                    textColor = textColor,
                    onValueChange = { brightness = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedColor()) }) {
                Text("確定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun ColorPreset(
    color: Int,
    selected: Boolean,
    borderColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) borderColor else borderColor.copy(alpha = 0.14f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ColorSliderRow(
    label: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    textColor: Color,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, color = textColor, fontSize = 13.sp)
            Text(valueText, color = textColor.copy(alpha = 0.62f), fontSize = 13.sp)
        }
        MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun colorsClose(first: Int, second: Int): Boolean {
    val dr = android.graphics.Color.red(first) - android.graphics.Color.red(second)
    val dg = android.graphics.Color.green(first) - android.graphics.Color.green(second)
    val db = android.graphics.Color.blue(first) - android.graphics.Color.blue(second)
    return dr * dr + dg * dg + db * db < 28 * 28
}

private fun customColorName(color: Int): String {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color, hsv)
    if (hsv[1] < 0.10f) return when {
        hsv[2] < 0.25f -> "黑色"
        hsv[2] > 0.86f -> "白色"
        else -> "灰色"
    }
    return when (hsv[0]) {
        in 0f..14.999f, in 345f..360f -> "紅色"
        in 15f..44.999f -> "橙色"
        in 45f..71.999f -> "黃色"
        in 72f..164.999f -> "綠色"
        in 165f..199.999f -> "青色"
        in 200f..254.999f -> "藍色"
        in 255f..291.999f -> "紫色"
        else -> "粉色"
    }
}

@Composable
private fun ActionButton(
    text: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = foreground,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionLabel(text: String, _color: Color) {
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
private fun PreferenceCard(
    _color: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

private fun GlassSettings.copy(
    revision: Long = this.revision,
    enabled: Boolean = this.enabled,
    themeMode: Int = this.themeMode,
    tabWidthDp: Float = this.tabWidthDp,
    bottomPaddingDp: Float = this.bottomPaddingDp,
    blurRadiusDp: Float = this.blurRadiusDp,
    lightAlpha: Float = this.lightAlpha,
    darkAlpha: Float = this.darkAlpha,
    iconScale: Float = this.iconScale,
    textSizeSp: Float = this.textSizeSp,
    iconOnly: Boolean = this.iconOnly,
    showPostButton: Boolean = this.showPostButton,
    customColorEnabled: Boolean = this.customColorEnabled,
    customPostColor: Int = this.customPostColor,
): GlassSettings = GlassSettings(
    revision,
    enabled,
    themeMode,
    tabWidthDp,
    bottomPaddingDp,
    blurRadiusDp,
    lightAlpha,
    darkAlpha,
    iconScale,
    textSizeSp,
    iconOnly,
    showPostButton,
    customColorEnabled,
    customPostColor,
)
