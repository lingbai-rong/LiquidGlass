package rongyi.lingbai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import com.kyant.backdrop.backdrops.layerBackdrop as referenceLayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop as rememberReferenceBackdrop
import rongyi.lingbai.moduleui.HyperChangerNavigation
import rongyi.lingbai.moduleui.LocalDialogBackdrop
import rongyi.lingbai.moduleui.LocalToolbarBackdrop
import rongyi.lingbai.moduleui.ModuleAppPage
import rongyi.lingbai.moduleui.ModuleAppList
import rongyi.lingbai.moduleui.ModuleGroup
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Settings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import rongyi.lingbai.moduleStringResource as stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rongyi.lingbai.glass.FloatingBottomBar
import rongyi.lingbai.glass.FloatingBottomBarItem
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import rongyi.lingbai.moduleui.EditableSliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import rongyi.lingbai.moduleui.OverlayDropdownPreference as WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private val MODULE_PAGE_TOP_OFFSET = 2.dp
private val MODULE_BOTTOM_BAR_WIDTH = 252.dp
private val MODULE_BOTTOM_BAR_BOTTOM_PADDING = 30.dp
private val MODULE_PAGE_BOTTOM_CONTENT_PADDING = 126.dp
private val MODULE_HOME_CONTENT_MAX_WIDTH = 840.dp
private val MODULE_HOME_DASHBOARD_MIN_HEIGHT = 164.dp
private val MODULE_HOME_DASHBOARD_MAX_HEIGHT = 220.dp

private const val ROOT_HOME = 0
private const val ROOT_CONFIG = 1
private const val ROOT_SETTINGS = 2
private const val ROOT_PAGE_COUNT = 3

class MainActivity : ComponentActivity() {
    private var refreshToken by mutableIntStateOf(0)
    private val appleMusicModelDelegate = lazy {
        androidx.lifecycle.ViewModelProvider(this)[AppleMusicSettingsModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread(
            {
                RemovedAccountData.clear(applicationContext)
                HookPreferenceTransport.prepareExistingSettings(applicationContext)
            },
            "LiquidGlass-settings-publisher",
        ).start()
        setContent {
            val systemDark = isSystemInDarkTheme()
            var appearance by remember { mutableStateOf(ModuleAppearanceSettings.read(this@MainActivity)) }
            val updateAppearance: (ModuleAppearanceSettings) -> Unit = { value ->
                if (value.save(this@MainActivity)) appearance = value
                else Toast.makeText(this@MainActivity, getString(R.string.settings_save_failed), Toast.LENGTH_SHORT).show()
            }
            var loggingEnabled by remember { mutableStateOf(ModuleLog.isEnabled()) }
            val dark = when (appearance.theme) {
                ModuleUiPreferences.THEME_LIGHT -> false
                ModuleUiPreferences.THEME_DARK -> true
                else -> systemDark
            }
            val page = if (dark) HyperCeilerStyle.DarkPage else HyperCeilerStyle.LightPage

            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                val pageStack = rememberModulePageStack()
                val appleMusic = remember { appleMusicModelDelegate.value }
                val appleMusicDocuments = rememberAppleMusicDocuments(appleMusic)
                if (pageStack.isEmpty) ApplyHomeBars(dark, page)

                val rootPagerState = rememberPagerState(
                    initialPage = ROOT_HOME,
                    pageCount = { ROOT_PAGE_COUNT },
                )
                val rootPagerScope = rememberCoroutineScope()
                val rootPagerFlingBehavior = PagerDefaults.flingBehavior(
                    state = rootPagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(ROOT_PAGE_COUNT - 1),
                )
                // The glass always keeps the user's final destination selected while the pager
                // moves through intermediate pages. In particular, a Home -> Settings request
                // must visibly pass across Config without Config feeding back as a new target.
                var selectedRootTab by rememberSaveable { mutableIntStateOf(ROOT_HOME) }
                var rootNavigationTarget by remember { mutableStateOf<Int?>(null) }
                var rootNavigationJob by remember { mutableStateOf<Job?>(null) }
                val settledRootPage = rootPagerState.settledPage
                val rootScrollInProgress = rootPagerState.isScrollInProgress
                LaunchedEffect(settledRootPage, rootScrollInProgress, rootNavigationTarget) {
                    if (!rootScrollInProgress) {
                        val requestedTarget = rootNavigationTarget
                        if (requestedTarget == null || requestedTarget == settledRootPage) {
                            selectedRootTab = settledRootPage
                            if (requestedTarget == settledRootPage) {
                                rootNavigationTarget = null
                            }
                        }
                    }
                }
                val navigateRootTo: (Int) -> Unit = navigate@{ requestedPage ->
                    val targetPage = requestedPage.coerceIn(0, ROOT_PAGE_COUNT - 1)
                    if (
                        targetPage == rootPagerState.settledPage &&
                        !rootPagerState.isScrollInProgress
                    ) {
                        selectedRootTab = targetPage
                        rootNavigationTarget = null
                        return@navigate
                    }

                    rootNavigationJob?.cancel()
                    selectedRootTab = targetPage
                    rootNavigationTarget = targetPage
                    rootNavigationJob = rootPagerScope.launch {
                        val pageDistance = abs(targetPage - rootPagerState.currentPage)
                        val durationMillis = if (pageDistance > 1) 560 else 340
                        try {
                            rootPagerState.animateScrollToPage(
                                page = targetPage,
                                animationSpec = tween(
                                    durationMillis = durationMillis,
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                        } finally {
                            if (
                                rootNavigationTarget == targetPage &&
                                rootPagerState.settledPage == targetPage
                            ) {
                                rootNavigationTarget = null
                            }
                        }
                    }
                }
                var launcherIconVisible by remember {
                    mutableStateOf(ModuleUiPreferences.isLauncherIconVisible(this@MainActivity))
                }
                var readAppIcons by remember {
                    mutableStateOf(ModuleUiPreferences.shouldReadAppIcons(this@MainActivity))
                }
                var pageWallpaperEnabled by remember {
                    mutableStateOf(ModuleUiPreferences.isPageWallpaperEnabled(this@MainActivity))
                }
                val moduleSelectedAccentEnabled = remember(refreshToken) {
                    GlobalAppearanceStore.isSelectedAccentEnabled(this@MainActivity)
                }
                val moduleBackdropCaptureEnabled = remember(refreshToken) {
                    GlobalAppearanceStore.isBackdropCaptureEnabled(this@MainActivity)
                }
                val moduleNativeBlurEnabled = remember(refreshToken) {
                    GlobalAppearanceStore.isNativeBlurEnabled(this@MainActivity)
                }

                val coolapk = remember(refreshToken) { GlassSettings.loadLocal(this@MainActivity) }
                BackHandler(enabled = pageStack.isEmpty && selectedRootTab != ROOT_HOME) {
                    navigateRootTo(ROOT_HOME)
                }
                val openGlobal: () -> Unit = { pageStack.push("global") }
                val openAuthor: () -> Unit = {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AUTHOR_URL)))
                    } catch (_: Throwable) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.unable_open_author),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                val openCoolapk: () -> Unit = { pageStack.push("coolapk") }
                val openTarget: (TargetApp) -> Unit = { app -> pageStack.push("target:${app.key}") }

                val context = this@MainActivity
                val coolapkInfo = remember(refreshToken, readAppIcons) {
                    loadLocalAppInfo(context, "com.coolapk.market", "酷安", readAppIcons)
                }
                val appleMusicInfo = remember(refreshToken, readAppIcons) {
                    loadLocalAppInfo(context, "com.apple.android.music", "Apple Music", readAppIcons)
                }
                val appleMusicEnabled = remember(refreshToken) {
                    dev.amenhancer.module.AmppSettingsEditor.hasEnabledFeatures(context)
                }
                val targetEntries: List<AppEntry> = remember(refreshToken, readAppIcons) {
                    TargetApp.all().map { app: TargetApp ->
                        val info = loadLocalAppInfo(
                            context,
                            app.packageName,
                            app.displayName,
                            readAppIcons,
                        )
                        val config = TargetAppSettings.loadLocal(context, app)
                        AppEntry(
                            name = if (app == TargetApp.MI_PHONE) app.displayName else info.label,
                            packageName = app.packageName,
                            icon = info.icon,
                            fallbackText = app.displayName.take(1),
                            fallbackColor = targetFallbackColor(app.key),
                            installed = info.installed,
                            enabled = config.enabled,
                            onOpen = { openTarget(app) },
                        )
                    }
                }
                val allApps = listOf(
                    AppEntry(
                        coolapkInfo.label,
                        "com.coolapk.market",
                        coolapkInfo.icon,
                        "酷",
                        Color(0xFF4BAE4F),
                        coolapkInfo.installed,
                        coolapk.enabled,
                        openCoolapk,
                    ),
                    AppEntry(
                        appleMusicInfo.label,
                        "com.apple.android.music",
                        appleMusicInfo.icon,
                        "♪",
                        Color(0xFFFA3757),
                        appleMusicInfo.installed,
                        appleMusicEnabled,
                        { pageStack.push("apple_music") },
                    ),
                ) + targetEntries
                val miPackages = remember {
                    TargetApp.miSystemApps().map { it.packageName }.toSet()
                }
                val miApps = targetEntries.filter { it.packageName in miPackages }
                val regularApps = allApps.filter { it.packageName !in miPackages }

                val moduleBackdrop = rememberLayerBackdrop()
                val referenceBackdrop = rememberReferenceBackdrop()
                CompositionLocalProvider(
                    LocalDialogBackdrop provides referenceBackdrop,
                    LocalToolbarBackdrop provides referenceBackdrop,
                ) {
                    // The root Scaffold draws glass popups outside the page capture.
                    Scaffold(containerColor = page) {
                        Box(Modifier.fillMaxSize().background(page)) {
                            Box(Modifier.fillMaxSize().modulePageInput(pageStack.isEmpty)) {
                                Box(Modifier.fillMaxSize().layerBackdrop(moduleBackdrop)
                                    .referenceLayerBackdrop(referenceBackdrop)) {
                                    ModulePageBackground(page, dark, pageWallpaperEnabled)
                                    HorizontalPager(
                                        state = rootPagerState,
                                        userScrollEnabled = pageStack.isEmpty,
                                        beyondViewportPageCount = ROOT_PAGE_COUNT - 1,
                                        flingBehavior = rootPagerFlingBehavior,
                                        modifier = Modifier.fillMaxSize().padding(top = MODULE_PAGE_TOP_OFFSET),
                                    ) { rootPage ->
                                        CompositionLocalProvider(LocalModulePageActive provides
                                            (pageStack.isEmpty && rootPage == selectedRootTab)) {
                                            when (rootPage) {
                                                ROOT_HOME -> ModuleHomeDashboard(
                                                    pageColor = page, dark = dark,
                                                    enabledAppCount = allApps.count { it.installed && it.enabled },
                                                    installedAppCount = allApps.count { it.installed },
                                                    nativeBlurEnabled = moduleNativeBlurEnabled,
                                                    backdropCaptureEnabled = moduleBackdropCaptureEnabled,
                                                    onOpenAppearance = openGlobal,
                                                    onOpenConfig = { navigateRootTo(ROOT_CONFIG) },
                                                    onOpenAuthor = openAuthor,
                                                )
                                                ROOT_CONFIG -> ModuleConfigScreen(
                                                    pageColor = page, apps = allApps, homeApps = regularApps,
                                                    miApps = miApps, onOpenGlobal = openGlobal,
                                                    onOpenMiApps = { pageStack.push("mi_apps") },
                                                    onOpenMissingApps = { pageStack.push("missing_apps") },
                                                )
                                                else -> ModuleSettingsScreen(
                                                    pageColor = page,
                                                    launcherIconVisible = launcherIconVisible,
                                                    appearance = appearance,
                                                    onAppearanceChange = updateAppearance,
                                                    languageSelection = ModuleLanguageStore.selection(this@MainActivity),
                                                    onOpenLanguage = { pageStack.push("language") },
                                                    onOpenDeveloper = { pageStack.push("developer") },
                                                    onLauncherIconChange = { visible ->
                                                        val changed = ModuleUiPreferences.setLauncherIconVisible(
                                                            this@MainActivity,
                                                            visible,
                                                        )
                                                        if (changed) launcherIconVisible = visible
                                                        Toast.makeText(
                                                            this@MainActivity,
                                                            getString(
                                                                when {
                                                                    !changed -> R.string.launcher_icon_failed
                                                                    visible -> R.string.launcher_icon_shown
                                                                    else -> R.string.launcher_icon_hidden
                                                                }
                                                            ),
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                ModuleBottomNavigation(
                                    backdrop = moduleBackdrop, referenceBackdrop = referenceBackdrop,
                                    dark = dark, selectedIndex = selectedRootTab,
                                    selectedAccentEnabled = moduleSelectedAccentEnabled,
                                    appearance = appearance, onSelected = navigateRootTo,
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }
                            ModulePageStackHost(pageStack, appearance, page, onPageRemoved = { refreshToken++ }) { route, back ->
                                when {
                                    route == "language" -> ModuleLanguageScreen(onBack = back)
                                    route == "developer" -> ModuleDeveloperScreen(
                                        pageColor = page,
                                        readAppIcons = readAppIcons,
                                        pageWallpaperEnabled = pageWallpaperEnabled,
                                        loggingEnabled = loggingEnabled,
                                        onLoggingChange = { enabled ->
                                            if (ModuleLog.setEnabled(this@MainActivity, enabled)) loggingEnabled = enabled
                                            else Toast.makeText(this@MainActivity, getString(R.string.settings_save_failed), Toast.LENGTH_SHORT).show()
                                        },
                                        onReadAppIconsChange = { enabled ->
                                            if (ModuleUiPreferences.setReadAppIcons(
                                                    this@MainActivity,
                                                    enabled,
                                                )
                                            ) {
                                                readAppIcons = enabled
                                                refreshToken++
                                            }
                                        },
                                        onPageWallpaperEnabledChange = { enabled ->
                                            if (ModuleUiPreferences.setPageWallpaperEnabled(
                                                    this@MainActivity,
                                                    enabled,
                                                )
                                            ) {
                                                pageWallpaperEnabled = enabled
                                            }
                                        },
                                        onBack = { back() },
                                    )
                                    route == "global" -> GlobalSettingsContent(onBack = back)
                                    route == "coolapk" -> CoolapkSettingsContent(onBack = back)
                                    route == "apple_music" -> AppleMusicSettingsContent(
                                        appleMusic, appleMusicDocuments,
                                        onLyrics = { pageStack.push("apple_music_lyrics") }, onBack = back,
                                    )
                                    route == "apple_music_lyrics" -> AppleMusicLyricsContent(
                                        appleMusic, appleMusicDocuments,
                                        onEdit = { group -> appleMusic.openEditor(group) {
                                            pageStack.push("apple_music_lyrics_edit")
                                        } },
                                        onImport = { appleMusic.openEditor(null) {
                                            pageStack.push("apple_music_lyrics_edit")
                                            appleMusicDocuments.ttml()
                                        } },
                                        onBack = back,
                                    )
                                    route == "apple_music_lyrics_edit" -> AppleMusicLyricsEditorContent(
                                        appleMusic, appleMusicDocuments, onBack = back,
                                    )
                                    route.startsWith("target:") -> TargetApp.fromKey(route.removePrefix("target:"))?.let { app ->
                                        TargetSettingsContent(app = app, onBack = back)
                                    }
                                    route == "mi_apps" -> AppGroupScreen(
                                        pageColor = page, title = stringResource(R.string.mi_apps),
                                        subtitle = stringResource(R.string.mi_apps_page_summary), apps = miApps,
                                        emptyText = stringResource(R.string.mi_apps_empty), onBack = back,
                                    )
                                    route == "missing_apps" -> AppGroupScreen(
                                        pageColor = page, title = stringResource(R.string.missing_apps),
                                        subtitle = stringResource(R.string.missing_apps_page_summary),
                                        apps = regularApps.filter { !it.installed },
                                        emptyText = stringResource(R.string.missing_apps_empty), onBack = back,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dev.amenhancer.module.AmppSettingsEditor.grantHostAccess(this)
        if (appleMusicModelDelegate.isInitialized()) appleMusicModelDelegate.value.refresh()
        refreshToken++
    }

    companion object {
        private const val AUTHOR_URL = "http://www.coolapk.com/u/4924418"
    }
}

@Composable
private fun ApplyHomeBars(dark: Boolean, pageColor: Color) {
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
private fun ModuleBottomNavigation(
    backdrop: Backdrop,
    referenceBackdrop: com.kyant.backdrop.Backdrop,
    dark: Boolean,
    selectedIndex: Int,
    selectedAccentEnabled: Boolean,
    appearance: ModuleAppearanceSettings,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf(stringResource(R.string.nav_home), stringResource(R.string.nav_config), stringResource(R.string.nav_settings))
    val icons: List<@Composable (Color) -> Unit> = listOf(
        { color -> ModuleHomeIcon(color, Modifier.size(24.dp)) },
        { color -> ModuleConfigIcon(color, Modifier.size(24.dp)) },
        { color -> ModuleSettingsIcon(color, Modifier.size(24.dp)) },
    )
    if (appearance.navigationStyle != ModuleAppearanceSettings.LIQUID_GLASS) {
        HyperChangerNavigation(
            labels = labels, icons = listOf(ModuleHomeVector, MiuixIcons.Regular.All, MiuixIcons.Regular.Settings),
            selectedIndex = selectedIndex, onSelected = onSelected,
            floating = appearance.navigationStyle == ModuleAppearanceSettings.HYPER_OS_FLOATING,
            labelMode = appearance.labelMode, dark = dark, backdrop = referenceBackdrop, modifier = modifier,
        )
        return
    }
    Box(modifier.fillMaxWidth().padding(bottom = MODULE_BOTTOM_BAR_BOTTOM_PADDING), contentAlignment = Alignment.Center) {
        FloatingBottomBar(
            modifier = Modifier.width(MODULE_BOTTOM_BAR_WIDTH),
            selectedIndex = { selectedIndex }, onSelected = onSelected,
            backdrop = backdrop, tabsCount = ROOT_PAGE_COUNT,
            isBlurEnabled = true, transparentBackdropSurface = true,
            selectedAccentEnabled = selectedAccentEnabled,
            iconOnlyMode = appearance.labelMode == ModuleAppearanceSettings.ICON_ONLY,
        ) {
            labels.forEachIndexed { index, label ->
                FloatingBottomBarItem(
                    onClick = { onSelected(index) },
                    modifier = Modifier.semantics {
                        contentDescription = label
                        selected = index == selectedIndex
                    },
                ) { ModuleNavigationContent(label, appearance.labelMode, LocalContentColor.current, icons[index]) }
            }
        }
    }
}

private val ModuleHomeVector by lazy {
    ImageVector.Builder("LiquidGlassHome", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3.2f, 11f); lineTo(12f, 3.2f); lineTo(20.8f, 11f)
            lineTo(18.6f, 11f); lineTo(18.6f, 20f); lineTo(14.1f, 20f)
            lineTo(14.1f, 14.8f); lineTo(9.9f, 14.8f); lineTo(9.9f, 20f)
            lineTo(5.4f, 20f); lineTo(5.4f, 11f); close()
        }
    }.build()
}

@Composable
private fun ModuleNavigationContent(label: String, mode: String, color: Color, icon: @Composable (Color) -> Unit) {
    if (mode != ModuleAppearanceSettings.TEXT_ONLY) icon(color)
    if (mode == ModuleAppearanceSettings.ICON_AND_TEXT) Spacer(Modifier.height(2.dp))
    if (mode != ModuleAppearanceSettings.ICON_ONLY) {
        Text(label, color = color,
            fontSize = if (mode == ModuleAppearanceSettings.TEXT_ONLY) 14.sp else 11.sp,
            fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun ModuleHomeDashboard(
    pageColor: Color,
    dark: Boolean,
    enabledAppCount: Int,
    installedAppCount: Int,
    nativeBlurEnabled: Boolean,
    backdropCaptureEnabled: Boolean,
    onOpenAppearance: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenAuthor: () -> Unit,
) {
    val context = LocalContext.current
    val versionName = remember(context) { moduleVersionName(context) }
    val deviceModel = remember {
        Build.MODEL.ifBlank { "Unknown" }
    }
    val deviceName = remember(context, deviceModel) {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: deviceModel
    }
    val systemName = remember {
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }
    val renderMode = when {
        nativeBlurEnabled -> stringResource(R.string.render_mode_native)
        backdropCaptureEnabled -> stringResource(R.string.render_mode_capture)
        else -> stringResource(R.string.render_mode_disabled)
    }
    val statusCardColor = if (dark) Color(0xFF153924) else Color(0xFFDDF8E4)
    val statusContentColor = if (dark) Color(0xFFE9FFF0) else Color(0xFF102818)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = stringResource(R.string.app_name),
            largeTitle = stringResource(R.string.app_name),
            color = Color.Transparent,
            defaultWindowInsetsPadding = false,
        )
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = MODULE_HOME_CONTENT_MAX_WIDTH)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 12.dp,
                    bottom = MODULE_PAGE_BOTTOM_CONTENT_PADDING,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val dashboardHeight = (maxWidth * 0.48f).coerceIn(
                            MODULE_HOME_DASHBOARD_MIN_HEIGHT,
                            MODULE_HOME_DASHBOARD_MAX_HEIGHT,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(dashboardHeight),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HomeStatusCard(
                                versionName = versionName,
                                statusCardColor = statusCardColor,
                                statusContentColor = statusContentColor,
                                onClick = onOpenAppearance,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                HomeStatCard(
                                    title = stringResource(R.string.home_enabled_apps),
                                    value = enabledAppCount.toString(),
                                    onClick = onOpenConfig,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                )
                                HomeStatCard(
                                    title = stringResource(R.string.home_installed_apps),
                                    value = installedAppCount.toString(),
                                    onClick = onOpenConfig,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                )
                            }
                        }
                    }
                }

                item {
                    SmallTitle(
                        text = stringResource(R.string.home_device_info),
                        insideMargin = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp,
                            top = 2.dp,
                            bottom = 8.dp,
                        ),
                    )
                    HomeInfoGroupCard(
                        deviceName = deviceName,
                        deviceModel = deviceModel,
                        systemName = systemName,
                        renderMode = renderMode,
                        versionName = versionName,
                    )
                }

                item {
                    HomeAboutCard(
                        versionName = versionName,
                        onClick = onOpenAuthor,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeAboutCard(versionName: String, onClick: () -> Unit) {
    MiuixCard(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = stringResource(R.string.about_module),
            summary = stringResource(R.string.about_module_summary, versionName),
            onClick = onClick,
        )
    }
}

@Composable
private fun HomeStatusCard(
    versionName: String,
    statusCardColor: Color,
    statusContentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MiuixCard(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = statusCardColor),
        insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(Modifier.fillMaxSize()) {
            HomeStatusCheckIcon(
                color = Color(0xFF27C968),
                modifier = Modifier
                    .size(170.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 38.dp, y = 45.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_status_title),
                    color = statusContentColor,
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.home_working_version, versionName),
                    color = statusContentColor.copy(alpha = 0.82f),
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HomeStatCard(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer
    MiuixCard(
        modifier = modifier,
        insideMargin = androidx.compose.foundation.layout.PaddingValues(16.dp),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title,
                color = foreground.copy(alpha = 0.55f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                color = foreground,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HomeInfoGroupCard(
    deviceName: String,
    deviceModel: String,
    systemName: String,
    renderMode: String,
    versionName: String,
) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            BasicComponent(
                title = stringResource(R.string.home_device_name),
                summary = deviceName,
            )
            BasicComponent(
                title = stringResource(R.string.home_device_model),
                summary = deviceModel,
            )
            BasicComponent(
                title = stringResource(R.string.home_system),
                summary = systemName,
            )
            BasicComponent(
                title = stringResource(R.string.home_render_mode),
                summary = renderMode,
            )
            BasicComponent(
                title = stringResource(R.string.home_version),
                summary = versionName,
            )
        }
    }
}

@Composable
private fun HomeStatusCheckIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.065f
        drawCircle(color = color, radius = size.minDimension * 0.39f, style = Stroke(stroke))
        drawLine(
            color = color,
            start = Offset(size.width * 0.31f, size.height * 0.54f),
            end = Offset(size.width * 0.46f, size.height * 0.69f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.46f, size.height * 0.69f),
            end = Offset(size.width * 0.74f, size.height * 0.37f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ModuleSettingsScreen(
    pageColor: Color,
    launcherIconVisible: Boolean,
    appearance: ModuleAppearanceSettings,
    onAppearanceChange: (ModuleAppearanceSettings) -> Unit,
    languageSelection: String,
    onOpenLanguage: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onLauncherIconChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val languageSummary = when (languageSelection) {
        "" -> stringResource(R.string.language_system)
        "zh-CN" -> "简体中文"
        "zh-TW" -> "繁體中文"
        "en" -> "English"
        "ja" -> "日本語"
        else -> ModuleLanguageStore.packs(context)
            .firstOrNull { ModuleLanguageStore.packSelection(it) == languageSelection }?.name ?: languageSelection
    }
    ModuleAppPage(stringResource(R.string.module_settings_title)) { padding, scroll ->
        ModuleAppList(padding, scroll) {
            item {
                ModuleGroup(stringResource(R.string.section_app_settings)) {
                    ArrowPreference(title = stringResource(R.string.language), summary = languageSummary, onClick = onOpenLanguage)
                    WindowDropdownPreference(
                        title = stringResource(R.string.module_theme),
                        items = listOf(stringResource(R.string.language_system), stringResource(R.string.module_theme_light), stringResource(R.string.module_theme_dark)),
                        selectedIndex = appearance.theme,
                        onSelectedIndexChange = { onAppearanceChange(appearance.copy(theme = it)) },
                    )
                    WindowDropdownPreference(
                        title = stringResource(R.string.module_navigation_style),
                        items = listOf(stringResource(R.string.module_nav_hyperos), stringResource(R.string.module_nav_hyperos_floating), stringResource(R.string.module_nav_liquid)),
                        selectedIndex = ModuleAppearanceSettings.styles.indexOf(appearance.navigationStyle).coerceAtLeast(0),
                        onSelectedIndexChange = { index -> ModuleAppearanceSettings.styles.getOrNull(index)?.let { onAppearanceChange(appearance.copy(navigationStyle = it)) } },
                    )
                    WindowDropdownPreference(
                        title = stringResource(R.string.module_navigation_labels),
                        items = listOf(stringResource(R.string.module_labels_both), stringResource(R.string.module_labels_icon), stringResource(R.string.module_labels_text)),
                        selectedIndex = ModuleAppearanceSettings.labels.indexOf(appearance.labelMode).coerceAtLeast(0),
                        onSelectedIndexChange = { index -> ModuleAppearanceSettings.labels.getOrNull(index)?.let { onAppearanceChange(appearance.copy(labelMode = it)) } },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.module_predictive_back),
                        checked = appearance.predictiveBack,
                        onCheckedChange = { onAppearanceChange(appearance.copy(predictiveBack = it)) },
                    )
                    if (appearance.predictiveBack) {
                        var progress by remember(appearance.backProgress) { androidx.compose.runtime.mutableFloatStateOf(appearance.backProgress.toFloat()) }
                        EditableSliderPreference(
                            title = stringResource(R.string.module_back_progress),
                            value = progress, valueText = "${progress.toInt()}%", valueRange = 10f..100f, steps = 89, defaultValue = 90f,
                            onValueChange = { progress = it },
                            onValueChangeFinished = { onAppearanceChange(appearance.copy(backProgress = progress.toInt())) },
                        )
                    }
                    SwitchPreference(
                        title = stringResource(R.string.show_launcher_icon),
                        summary = stringResource(R.string.show_launcher_icon_summary),
                        checked = launcherIconVisible,
                        onCheckedChange = onLauncherIconChange,
                    )
                }
            }
            item {
                ModuleGroup(stringResource(R.string.section_advanced)) {
                    ArrowPreference(
                        title = stringResource(R.string.developer_options),
                        summary = stringResource(R.string.developer_options_summary),
                        onClick = onOpenDeveloper,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiuixSectionTitle(text: String) {
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
private fun ModuleDeveloperScreen(
    pageColor: Color,
    readAppIcons: Boolean,
    pageWallpaperEnabled: Boolean,
    loggingEnabled: Boolean,
    onLoggingChange: (Boolean) -> Unit,
    onReadAppIconsChange: (Boolean) -> Unit,
    onPageWallpaperEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    ModuleAppPage(stringResource(R.string.developer_title), onBack) { padding, scroll ->
        ModuleAppList(padding, scroll, 28) {
            item {
                ModuleGroup(stringResource(R.string.developer_subtitle)) {
                    SwitchPreference(
                        title = stringResource(R.string.module_logging),
                        summary = stringResource(R.string.module_logging_summary),
                        checked = loggingEnabled, onCheckedChange = onLoggingChange,
                    )
                }
            }
            item {
                ModuleGroup(stringResource(R.string.developer_section_apps)) {
                    SwitchPreference(
                        title = stringResource(R.string.developer_read_app_icons),
                        summary = stringResource(R.string.developer_read_app_icons_summary),
                        checked = readAppIcons, onCheckedChange = onReadAppIconsChange,
                    )
                }
            }
            item {
                ModuleGroup(stringResource(R.string.developer_section_appearance)) {
                    SwitchPreference(
                        title = stringResource(R.string.developer_page_wallpaper),
                        summary = stringResource(R.string.developer_page_wallpaper_summary),
                        checked = pageWallpaperEnabled, onCheckedChange = onPageWallpaperEnabledChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleHomeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // Keep the module shell visually consistent with the CoolAPK navigation adapter.
        val scale = size.minDimension / 24f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx - 8.8f * scale, cy - 1.0f * scale)
            lineTo(cx, cy - 8.8f * scale)
            lineTo(cx + 8.8f * scale, cy - 1.0f * scale)
            lineTo(cx + 6.6f * scale, cy - 1.0f * scale)
            lineTo(cx + 6.6f * scale, cy + 8.0f * scale)
            lineTo(cx + 2.1f * scale, cy + 8.0f * scale)
            lineTo(cx + 2.1f * scale, cy + 2.8f * scale)
            lineTo(cx - 2.1f * scale, cy + 2.8f * scale)
            lineTo(cx - 2.1f * scale, cy + 8.0f * scale)
            lineTo(cx - 6.6f * scale, cy + 8.0f * scale)
            lineTo(cx - 6.6f * scale, cy - 1.0f * scale)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun ModuleConfigIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.085f
        val ys = floatArrayOf(size.height * 0.25f, size.height * 0.50f, size.height * 0.75f)
        val knobs = floatArrayOf(size.width * 0.66f, size.width * 0.34f, size.width * 0.62f)
        ys.indices.forEach { i ->
            drawLine(
                color,
                Offset(size.width * 0.12f, ys[i]),
                Offset(size.width * 0.88f, ys[i]),
                stroke,
                StrokeCap.Round,
            )
            drawCircle(color, stroke * 1.35f, Offset(knobs[i], ys[i]))
        }
    }
}

@Composable
private fun ModuleSettingsIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = size.minDimension * 0.38f
        val toothLength = size.minDimension * 0.11f
        val toothWidth = size.minDimension * 0.085f
        repeat(8) { index ->
            val angle = Math.toRadians((index * 45.0) - 90.0)
            val ux = kotlin.math.cos(angle).toFloat()
            val uy = kotlin.math.sin(angle).toFloat()
            drawLine(
                color = color,
                start = Offset(cx + ux * (outer - toothLength), cy + uy * (outer - toothLength)),
                end = Offset(cx + ux * outer, cy + uy * outer),
                strokeWidth = toothWidth,
                cap = StrokeCap.Square,
            )
        }
        drawCircle(
            color = color,
            radius = size.minDimension * 0.245f,
            style = Stroke(size.minDimension * 0.13f),
        )
        drawCircle(
            color = color,
            radius = size.minDimension * 0.085f,
            style = Stroke(size.minDimension * 0.055f),
        )
    }
}

@Composable
private fun ModuleLauncherIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.075f
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.17f, size.height * 0.17f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.66f, size.height * 0.66f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.14f),
            style = Stroke(stroke),
        )
        drawCircle(color, size.minDimension * 0.08f, center)
    }
}

@Composable
private fun ModuleGlobeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.07f
        drawCircle(color, size.minDimension * 0.34f, center, style = Stroke(stroke))
        drawOval(
            color = color,
            topLeft = Offset(size.width * 0.35f, size.height * 0.16f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.30f, size.height * 0.68f),
            style = Stroke(stroke),
        )
        drawLine(
            color,
            Offset(size.width * 0.18f, size.height * 0.50f),
            Offset(size.width * 0.82f, size.height * 0.50f),
            stroke,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun ModuleConfigScreen(
    pageColor: Color,
    apps: List<AppEntry>,
    homeApps: List<AppEntry>,
    miApps: List<AppEntry>,
    onOpenGlobal: () -> Unit,
    onOpenMiApps: () -> Unit,
    onOpenMissingApps: () -> Unit,
) {
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = searchMode && LocalModulePageActive.current) {
        searchMode = false
        query = ""
    }

    if (searchMode) {
        HyperSearchScreen(pageColor, apps, query, { query = it }) {
            searchMode = false
            query = ""
        }
        return
    }

    val installedApps = homeApps.filter { it.installed }
    val missingCount = homeApps.count { !it.installed }
    val installedMiCount = miApps.count { it.installed }
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = stringResource(R.string.config_title),
            largeTitle = stringResource(R.string.config_title),
            subtitle = stringResource(R.string.config_subtitle),
            color = Color.Transparent,
            defaultWindowInsetsPadding = false,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = 4.dp,
                bottom = MODULE_PAGE_BOTTOM_CONTENT_PADDING,
            ),
        ) {
            item {
                HyperSearchLauncher(
                    placeholder = stringResource(R.string.search_settings),
                    onClick = { searchMode = true },
                )
                Spacer(Modifier.height(4.dp))
            }

            item { MiuixSectionTitle(stringResource(R.string.section_features)) }
            item {
                MiuixCard {
                    ArrowPreference(
                        title = stringResource(R.string.global_appearance),
                        summary = stringResource(R.string.global_appearance_summary),
                        onClick = onOpenGlobal,
                        startAction = {
                            MiuixPreferenceIcon {
                                TuneIcon(HyperCeilerStyle.Accent, Modifier.size(23.dp))
                            }
                        },
                    )
                }
            }

            item { MiuixSectionTitle(stringResource(R.string.section_app_settings)) }
            item {
                MiuixCard {
                    installedApps.forEach { app ->
                        MiuixAppPreference(app)
                    }
                    ArrowPreference(
                        title = stringResource(R.string.mi_apps),
                        summary = stringResource(R.string.mi_apps_summary, installedMiCount, miApps.size),
                        onClick = onOpenMiApps,
                        startAction = {
                            MiuixPreferenceIcon {
                                MiAppsIcon(foreground, Modifier.size(22.dp))
                            }
                        },
                    )
                }
            }

            if (missingCount > 0) {
                item { MiuixSectionTitle(stringResource(R.string.section_other)) }
                item {
                    MiuixCard {
                        ArrowPreference(
                            title = stringResource(R.string.missing_apps),
                            summary = stringResource(R.string.missing_apps_summary, missingCount),
                            onClick = onOpenMissingApps,
                            startAction = {
                                MiuixPreferenceIcon {
                                    MissingIcon(foreground, Modifier.size(22.dp))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixPreferenceIcon(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(42.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(HyperCeilerStyle.Accent.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun HyperSearchScreen(
    pageColor: Color,
    apps: List<AppEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val matches = apps.filter {
        query.isBlank() || it.name.contains(query, true) || it.packageName.contains(query, true)
    }
    val secondary = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.50f)
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(pageColor),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 10.dp,
            bottom = MODULE_PAGE_BOTTOM_CONTENT_PADDING,
        ),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    HyperBackIcon(MiuixTheme.colorScheme.onSurfaceContainer, Modifier.size(23.dp))
                }
                Spacer(Modifier.width(4.dp))
                HyperSearchAnchor(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.search_settings),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))
        }
        if (matches.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_matching_settings),
                    color = secondary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
                )
            }
        } else {
            item {
                MiuixCard {
                    matches.forEach { app ->
                        MiuixAppPreference(app)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiuixAppPreference(app: AppEntry) {
    val foreground = MiuixTheme.colorScheme.onSurfaceContainer
    val secondary = foreground.copy(alpha = 0.50f)
    val stateText = when {
        !app.installed -> stringResource(R.string.state_not_installed)
        app.enabled -> stringResource(R.string.state_enabled)
        else -> stringResource(R.string.state_disabled)
    }
    val stateColor = when {
        !app.installed -> secondary
        app.enabled -> HyperCeilerStyle.Success
        else -> HyperCeilerStyle.Warning
    }
    ArrowPreference(
        title = app.name,
        summary = app.packageName,
        enabled = app.installed,
        onClick = app.onOpen,
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (app.icon == null) app.fallbackColor else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (app.icon != null) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = app.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        app.fallbackText,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        endActions = {
            Text(
                text = stateText,
                color = stateColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 7.dp),
            )
        },
    )
}

@Composable
private fun AppGroupScreen(
    pageColor: Color,
    title: String,
    subtitle: String,
    apps: List<AppEntry>,
    emptyText: String,
    onBack: () -> Unit,
) {
    val secondary = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.52f)
    ModuleAppPage(title, onBack) { padding, scroll ->
        ModuleAppList(padding, scroll, 28) {
            item { Text(subtitle, color = secondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
            item {
                MiuixCard(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    if (apps.isEmpty()) {
                        Text(
                            emptyText,
                            color = secondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(18.dp),
                        )
                    } else {
                        apps.forEach { app -> MiuixAppPreference(app) }
                    }
                }
            }
        }
    }
}

private fun moduleVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "26.31"
} catch (_: Throwable) {
    "26.31"
}

private data class InstalledAppInfo(
    val label: String,
    val icon: ImageBitmap?,
    val installed: Boolean,
)

private data class AppEntry(
    val name: String,
    val packageName: String,
    val icon: ImageBitmap?,
    val fallbackText: String,
    val fallbackColor: Color,
    val installed: Boolean,
    val enabled: Boolean,
    val onOpen: () -> Unit,
)

private fun targetFallbackColor(key: String): Color = when (key) {
    "wechat" -> Color(0xFF07C160)
    "jd" -> Color(0xFFE2231A)
    "moondrop" -> Color(0xFF6750A4)
    "bilibili", "bilibili_hd" -> Color(0xFFFF6699)
    "midea" -> Color(0xFF2D7FF9)
    "meituan" -> Color(0xFFFFC300)
    "mihome" -> Color(0xFFFF6900)
    "netease" -> Color(0xFFE60026)
    "xhs" -> Color(0xFFFF2442)
    "pdd" -> Color(0xFFE02E24)
    "mi_file_manager" -> Color(0xFFFFA000)
    "mi_phone" -> Color(0xFF43A047)
    "mi_messages" -> Color(0xFF26A69A)
    "mi_calendar" -> Color(0xFFEF5350)
    "mi_gallery" -> Color(0xFF7E57C2)
    "mi_notes" -> Color(0xFFFFB300)
    else -> Color(0xFF5F6368)
}

private fun loadLocalAppInfo(
    context: Context,
    packageName: String,
    fallbackName: String,
    readIcon: Boolean,
): InstalledAppInfo {
    val pm = context.packageManager
    return try {
        val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val label = pm.getApplicationLabel(info).toString().takeIf { it.isNotBlank() } ?: fallbackName
        val icon = if (readIcon) drawableToImageBitmap(pm.getApplicationIcon(info)) else null
        InstalledAppInfo(label, icon, true)
    } catch (_: Throwable) {
        InstalledAppInfo(fallbackName, null, false)
    }
}

private fun drawableToImageBitmap(source: Drawable): ImageBitmap? = try {
    val drawable = source.constantState?.newDrawable()?.mutate() ?: source.mutate()
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
    val bitmap = Bitmap.createBitmap(
        width.coerceAtMost(512),
        height.coerceAtMost(512),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = AndroidCanvas(bitmap)
    drawable.setBounds(0, 0, bitmap.width, bitmap.height)
    drawable.draw(canvas)
    bitmap.asImageBitmap()
} catch (_: Throwable) {
    null
}

@Composable
private fun TuneIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.085f
        val ys = floatArrayOf(size.height * 0.25f, size.height * 0.50f, size.height * 0.75f)
        val knobs = floatArrayOf(size.width * 0.66f, size.width * 0.34f, size.width * 0.62f)
        ys.indices.forEach { i ->
            drawLine(color, Offset(size.width * 0.10f, ys[i]), Offset(size.width * 0.90f, ys[i]), stroke, StrokeCap.Round)
            drawCircle(color, stroke * 1.35f, Offset(knobs[i], ys[i]))
        }
    }
}

@Composable
private fun MiAppsIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension * 0.16f
        val inset = size.minDimension * 0.27f
        drawCircle(color, radius, Offset(inset, inset))
        drawCircle(color, radius, Offset(size.width - inset, inset))
        drawCircle(color, radius, Offset(inset, size.height - inset))
        drawCircle(color, radius, Offset(size.width - inset, size.height - inset))
    }
}

@Composable
private fun MissingIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.075f
        drawRoundRect(color, Offset(size.width * 0.16f, size.height * 0.22f), androidx.compose.ui.geometry.Size(size.width * 0.68f, size.height * 0.60f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f), style = Stroke(stroke))
        drawLine(color, Offset(size.width * 0.36f, size.height * 0.14f), Offset(size.width * 0.64f, size.height * 0.14f), stroke, StrokeCap.Round)
    }
}
