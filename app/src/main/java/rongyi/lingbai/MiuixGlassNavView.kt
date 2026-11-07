package rongyi.lingbai

import rongyi.lingbai.liquidtab.hook.GlassHostLayout
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import rongyi.lingbai.glass.FloatingActionBottomBar
import rongyi.lingbai.glass.FloatingActionBottomBarItem
import rongyi.lingbai.glass.FloatingBottomBar
import rongyi.lingbai.glass.FloatingBottomBarItem
import rongyi.lingbai.glass.GlassTheme
import rongyi.lingbai.glass.liquid.lens
import rongyi.lingbai.glass.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val KSU_ROW_HORIZONTAL_PADDING_DP = 8f
private const val POST_SLOT_WIDTH_DP = 48f
private const val SEPARATE_CIRCLE_DIAMETER_DP = 46f
private const val SEPARATE_CIRCLE_GAP_DP = 8f
private const val REFERENCE_CAPTURE_HEIGHT_DP = 84f

/**
 * The injected navigation view is only visual/touch UI. Keep it out of the
 * host app accessibility tree so MIUI screenshot/OCR will not scan the internal
 * Compose virtual nodes. ComposeView is final in current AndroidX, so we do not
 * subclass it; the parent FrameLayout hides descendants instead.
 */
private const val MODULE_PACKAGE = "rongyi.lingbai"

private class ModuleResourceContext(
    base: Context,
    private val moduleContext: Context,
) : ContextWrapper(base) {
    override fun getResources(): Resources = moduleContext.resources
    override fun getAssets(): AssetManager = moduleContext.assets
    override fun getTheme(): Resources.Theme = moduleContext.theme
    override fun getPackageName(): String = moduleContext.packageName
}

class MiuixGlassNavView(context: Context) : GlassHostLayout(context), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val hostActivity: Activity? = ActivityFinder.from(context)
    // Freeze the host night-mode token at creation. If CoolAPK changes its own
    // day/night mode, the installer replaces this entire ComposeView instead of
    // mutating miuix theme/backdrop objects in place.
    private val createdNightMask: Int = currentNightMask(context)
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val localViewModelStore = ViewModelStore()
    private var rootLifecycleOwnerInjected = false
    private var rootViewModelOwnerInjected = false
    private var rootSavedStateOwnerInjected = false
    private var disposed = false
    // Every host APK uses its own 0x7f resource namespace. The injected Compose/Miuix tree
    // must therefore resolve resources from LiquidGlass for every adapted application, not from
    // a package allow-list. This covers TIM's normal composition crash as well as the earlier
    // Bilibili accessibility-prefetch crash.
    private val isolateComposeResources = context.packageName != MODULE_PACKAGE
    private val composeResourceContext: Context = resolveComposeResourceContext(context)
    private val nativeBlurView = NativeBlurPillView(context)
    private val nativeBackdropActiveState = mutableStateOf(false)
    private val composeView = ComposeView(composeResourceContext)
    private var targetComposeView: WeakReference<View>? = null
    private var activeTouchSequence: Boolean = false
    private val selectedIndex = mutableIntStateOf(0)
    private val labelsState = mutableStateOf(listOf("首页", "数码", "发现", "我的"))
    private val navigationIconsState = mutableStateOf<List<ImageBitmap?>>(emptyList())
    private var navigationIconResourceIds = IntArray(0)
    private var navigationIconBitmapSources: List<Bitmap?> = emptyList()
    // KSU's drag/indicator objects remember the tab count and value range. Recreate that
    // navigation model whenever a host hides or restores a column; otherwise a Flutter host can
    // keep the old three-tab animation state after its visible label list becomes two tabs.
    private val navigationModelGenerationState = mutableIntStateOf(0)
    private val settingsState = mutableStateOf(GlassSettings.defaults())
    private val iconModeState = mutableIntStateOf(0)
    private var hostNavigationAdapter: HostNavigationAdapter? = null
    private var portBackdrop: PortBackdrop? = null
    private val portBackdropReadyState = mutableStateOf(false)
    private val portBackdropRevisionState = mutableIntStateOf(0)
    private val glassOnlyPanelState = mutableStateOf(false)
    private val playerHeightState = mutableStateOf(54f)
    internal fun portCaptureAllowed(): Boolean = backdropCaptureEnabled && !nativeBlurEnabled && !forceSyntheticOnlyBackdrop && !disposed
    internal fun portDark(): Boolean = effectiveDark()
    internal fun portLabels(): List<String> = labelsState.value
    internal fun setGlassOnlyPanel(value: Boolean) { glassOnlyPanelState.value = value }
    internal fun setPortBackdropReady(value: Boolean) { if (portBackdropReadyState.value != value) portBackdropReadyState.value = value }
    internal fun invalidatePortBackdropDrawing() { portBackdropRevisionState.intValue++; composeView.invalidate() }
    internal fun installPortBackdrop(value: PortBackdrop?) {
        if (portBackdrop !== value) portBackdrop?.dispose()
        portBackdrop = value
        portBackdropReadyState.value = false
        capturedBackdropState.value = null
        if (value != null) backdropStop()
    }
    internal fun installPlayerBackdrop(scene: () -> View?, player: View) {
        val activity = ActivityFinder.from(context) ?: return
        installPortBackdrop(PortBackdrop(activity, this, sceneProvider = scene, excluded = player))
    }
    internal fun updatePortBackdropFrame(selected: Int, allowed: Boolean) { portBackdrop?.update(selected, allowed) }
    internal fun updatePortMaterialBounds() {
        val density = resources.displayMetrics.density
        if (glassOnlyPanelState.value) {
            outerRect.set(0f, 0f, width.toFloat(), height.toFloat())
            val next = (height / density).coerceAtLeast(1f)
            if (playerHeightState.value != next) playerHeightState.value = next
            config = config.copy(barHeight = next, bottomPadding = 0f)
        } else if (usesHyperNavigation()) {
            val layout = hyperNavigationLayout()
            val bottom = height - layout.bottomPaddingDp * density
            outerRect.set(layout.barLeftDp * density, bottom - layout.barHeightDp * density,
                (layout.barLeftDp + layout.barWidthDp) * density, bottom)
            config = config.copy(barHeight = layout.barHeightDp, bottomPadding = layout.bottomPaddingDp)
        } else {
            val bottom = height - settingsState.value.bottomPaddingDp * density
            outerRect.set(0f, bottom - 64f*density, width.toFloat(), bottom)
            config = config.copy(barHeight = 64f, bottomPadding = settingsState.value.bottomPaddingDp)
        }
    }
    private val centerActionIndexState = mutableIntStateOf(-1)
    private val nativeCenterActionExpectedState = mutableStateOf(false)
    private val separateCirclePlusState = mutableStateOf(false)
    private var nativeCenterActionView: View? = null
    private var nativeCenterOriginalParent: WeakReference<ViewGroup>? = null
    private var nativeCenterOriginalIndex: Int = -1
    private var nativeCenterOriginalScaleX = 1f
    private var nativeCenterOriginalScaleY = 1f
    private var nativeCenterOriginalLayoutParams: ViewGroup.LayoutParams? = null
    private val capturedBackdropState = mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    // Some hosts (Compass) intentionally capture only while a real camera Surface is visible.
    // Keep the liquid shader alive between camera sessions by recording a synthetic dark scene
    // into the same Backdrop; this is a render-only texture and never reads host screen pixels.
    private val syntheticLiquidBackdropState = mutableStateOf(false)
    private val selectedAccentEnabledState = mutableStateOf(false)
    private val navigationStyleState = mutableStateOf(InjectedNavigationStyle.LIQUID_GLASS)
    private val hyperGeometry = HyperHostGeometry()
    private var navigationStyleChanged: Runnable? = null
    private val bottomBarTraditionalChineseState = mutableStateOf(false)
    private val transientActionModeState = mutableStateOf(false)
    private val transientActionEnabledState = mutableStateOf(listOf(true, true, true, true))
    private val backdropGenerationState = mutableIntStateOf(0)
    private var backdropCaptureEnabled: Boolean = true
    private var nativeBlurEnabled: Boolean = false
    // Per-app override used by Douyin: keep the liquid lens active but never sample host pixels.
    // This is separate from the global capture switch so turning the per-app option back off can
    // immediately resume whichever global capture/native-blur mode the user already selected.
    private var forceSyntheticOnlyBackdrop: Boolean = false
    // Douyin 26.66: no idle glass surface; keep a live Bitmap only as an invisible lens source.
    private val pressOnlyTransparentBackdropState = mutableStateOf(false)
    private var configuredBackdropSource: WeakReference<View>? = null
    private var configuredExactBackdropSource = false
    private var configuredBackdropExcludedViews: Array<View> = emptyArray()
    private var configuredBackdropCaptureHeightDp: Float = REFERENCE_CAPTURE_HEIGHT_DP
    private var configuredWindowBlankFallbackColor: Int = 0
    private var lowLatencyBackdropCaptureEnabled: Boolean = false
    // Host scroll changes are captured in the next host pre-draw using the uploaded reference
    // timing model. The injected view remains its own lifecycle owner so host applications with a
    // different AndroidX class loader cannot crash on owner casts.
    private val bitmapBackdropManagerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        BitmapBackdropManager(
            glassView = this,
            onBitmapUpdated = { bitmap -> capturedBackdropState.value = bitmap?.asImageBitmap() },
        ).also { manager ->
            manager.setCaptureHeightDp(if (usesHyperNavigation()) hyperNavigationLayout().captureHeightDp else REFERENCE_CAPTURE_HEIGHT_DP)
            manager.setLowLatencyCaptureMode(lowLatencyBackdropCaptureEnabled)
            manager.setNativeAssistMode(nativeBackdropActiveState.value)
            manager.setExactSourceEnabled(configuredExactBackdropSource)
        }
    }
    private val bitmapBackdropManager: BitmapBackdropManager
        get() = bitmapBackdropManagerDelegate.value
    // Compass, Bilibili and QQ Music use the exact CoolAPK 2.25 frame-synchronous Bitmap capture.
    // Compass keeps exact screen coordinates and adds only its separate camera SurfaceView pixels
    // to the same Bitmap; the KSU/2.57 pressed magnification and lens renderer remains unchanged.
    private val coolapkLegacyBackdropManagerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        CoolapkLegacyBitmapBackdropManager(
            glassView = this,
            onBitmapUpdated = { bitmap -> capturedBackdropState.value = bitmap?.asImageBitmap() },
        ).also { manager ->
            manager.setCaptureHeightDp(effectiveCaptureHeightDp())
            manager.setExcludedViews(configuredBackdropExcludedViews)
            manager.setPreservePhysicalCoordinates(configuredLegacyPhysicalCoordinates)
            manager.setNativeAssistMode(nativeBackdropActiveState.value)
        }
    }
    private val coolapkLegacyBackdropManager: CoolapkLegacyBitmapBackdropManager
        get() = coolapkLegacyBackdropManagerDelegate.value
    private val windowPixelCopyBackdropManagerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        WindowPixelCopyBackdropManager(
            glassView = this,
            onBitmapUpdated = { bitmap -> capturedBackdropState.value = bitmap?.asImageBitmap() },
        ).also { manager ->
            manager.setCaptureHeightDp(effectiveCaptureHeightDp(), usesHyperNavigation())
            manager.setBlankFrameFallbackColor(configuredWindowBlankFallbackColor)
            manager.setDouyinScreenCaptureEnabled(!pressOnlyTransparentBackdropState.value)
            manager.setNativeAssistMode(nativeBackdropActiveState.value)
        }
    }
    private val windowPixelCopyBackdropManager: WindowPixelCopyBackdropManager
        get() = windowPixelCopyBackdropManagerDelegate.value
    private var useCoolapkLegacyBackdropCapture: Boolean = false
    private var useWindowPixelCopyBackdropCapture: Boolean = false
    private var configuredLegacyPhysicalCoordinates: Boolean = false
    private var configuredHardwareBackdropCapture: Boolean = true
    private var appliedLegacyHardwareBackdropCapture: Boolean? = null
    private var lastBackdropSelectionIndex: Int = -1
    private var lastDispatchedIndex = -1
    private var lastDispatchAt = 0L
    // File Explorer selection mode must never leak a touch through to the ordinary
    // 最近/浏览/云盘 row. Compose still receives the gesture for the liquid press animation,
    // while this Android-level fallback owns the whole sequence and dispatches the command if
    // the nested clickable did not do so.
    private var transientTouchIndex = -1
    private var transientTouchMoved = false
    private var lastTransientActionIndex = -1
    private var lastTransientActionAt = 0L

    init {
        setWillNotDraw(false)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
        clipToOutline = false
        outlineProvider = null
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        contentDescription = null

        try {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
            this.setViewTreeLifecycleOwner(this)
            this.setViewTreeViewModelStoreOwner(this)
            this.setViewTreeSavedStateRegistryOwner(this)
        } catch (_: Throwable) {}

        // Keep Compose/lens rendering on the GPU. Host pixels are captured only when a
        // scroll/layout event marks the small backdrop strip dirty.
        try { setLayerType(View.LAYER_TYPE_HARDWARE, null) } catch (_: Throwable) {}
        clipChildren = false
        clipToPadding = false
        try { composeView.setLayerType(View.LAYER_TYPE_HARDWARE, null) } catch (_: Throwable) {}
        composeView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        composeView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        composeView.contentDescription = null
        composeView.isFocusable = false
        composeView.clipChildren = false
        composeView.clipToPadding = false
        composeView.clipToOutline = false
        composeView.outlineProvider = null
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setContent {
            val currentSettings = settingsState.value
            GlassTheme(themeMode = currentSettings.themeMode) {
                key(
                    backdropGenerationState.intValue,
                    navigationModelGenerationState.intValue,
                    navigationStyleState.value,
                ) {
                    ReferenceGlassBottomBarHost(
                    navigationStyle = navigationStyleState.value,
                    hyperGeometry = hyperGeometry,
                    portBackdropReady = portBackdropReadyState.value,
                    portBackdropDraw = if (portBackdrop != null) ({ canvas, w, h -> portBackdrop?.draw(canvas, w, h) }) else null,
                    portBackdropRevision = { portBackdropRevisionState.intValue },
                    glassOnlyPanel = glassOnlyPanelState.value,
                    playerHeightDp = playerHeightState.value,
                    currentSelectedIndex = { selectedIndex.intValue },
                    onHyperContentHeightChanged = ::onHyperContentHeightChanged,
                    labels = labelsState.value,
                    navigationIcons = navigationIconsState.value,
                    selectedIndex = selectedIndex.intValue,
                     capturedBackdrop = capturedBackdropState.value,
                     syntheticLiquidBackdrop = syntheticLiquidBackdropState.value,
                     pressOnlyTransparentBackdrop = pressOnlyTransparentBackdropState.value,
                     nativeBackdropActive = nativeBackdropActiveState.value,
                     selectedAccentEnabled = selectedAccentEnabledState.value,
                     traditionalChinese = bottomBarTraditionalChineseState.value,
                     settings = currentSettings,
                    iconMode = iconModeState.intValue,
                    centerActionIndex = centerActionIndexState.intValue,
                    nativeCenterActionExpected = nativeCenterActionExpectedState.value,
                    separateCirclePlus = separateCirclePlusState.value,
                    actionMode = transientActionModeState.value,
                    actionEnabled = transientActionEnabledState.value,
                    onSelect = { index ->
                        commitSelectedIndex(index)
                    },
                    onPost = {
                        dispatchOriginalPostClick()
                    }
                    )
                }
            }
        }
        addView(nativeBlurView, LayoutParams(1, 1))
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (isolateComposeResources) {
            // HyperOS accessibility prefetch can reach the inner AndroidComposeView directly. Keep
            // every injected host tree isolated; package-specific lists previously left TIM exposed.
            // host accessibility tree and repeat after layout because AndroidComposeView is created
            // lazily by Compose.
            composeView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                suppressInjectedComposeAccessibility()
            }
            composeView.post { suppressInjectedComposeAccessibility() }
        }
    }

    private fun effectiveDark(settings: GlassSettings = settingsState.value): Boolean = when (settings.themeMode) {
        GlassSettings.THEME_LIGHT -> false
        GlassSettings.THEME_DARK -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun bitmapCaptureAllowed(): Boolean =
        portBackdrop == null && backdropCaptureEnabled && !nativeBlurEnabled && !forceSyntheticOnlyBackdrop

    private fun usesHyperNavigation(): Boolean =
        navigationStyleState.value != InjectedNavigationStyle.LIQUID_GLASS && !transientActionModeState.value && !glassOnlyPanelState.value

    private fun hyperNavigationLayout(): InjectedNavigationStyle.Layout {
        val settings = settingsState.value
        val hostWidthDp = width / resources.displayMetrics.density
        return InjectedNavigationStyle.layout(
            navigationStyleState.value, hostWidthDp,
            hyperGeometry.viewportWidthDp.value.takeIf { it > 0f } ?: hostWidthDp,
            labelsState.value.size, settings.showPostButton,
            centerActionIndexState.intValue, separateCirclePlusState.value,
            hyperGeometry.standardContentHeightDp.value, hyperGeometry.bottomInsetDp.value,
        )
    }

    fun setHyperNavigationBottomInset(bottomPx: Int) {
        val inset = bottomPx.coerceAtLeast(0) / resources.displayMetrics.density
        if (hyperGeometry.bottomInsetDp.value == inset) return
        hyperGeometry.bottomInsetDp.value = inset
        updateHyperCaptureGeometry()
        updateNativeBackdrop()
        updateNativeCenterActionLayout()
    }

    private fun onHyperContentHeightChanged(heightDp: Float) {
        if (kotlin.math.abs(hyperGeometry.standardContentHeightDp.value - heightDp) < 0.1f) return
        hyperGeometry.standardContentHeightDp.value = heightDp
        // onSizeChanged runs during Compose layout; update Android children on the next frame.
        postOnAnimation {
            if (!disposed) {
                updateHyperCaptureGeometry()
                updateNativeBackdrop()
                updateNativeCenterActionLayout()
            }
        }
    }

    fun getNavigationStyle(): String = navigationStyleState.value

    fun setHyperNavigationViewport(widthPx: Int) {
        val widthDp = widthPx.coerceAtLeast(1) / resources.displayMetrics.density
        if (hyperGeometry.viewportWidthDp.value == widthDp) return
        hyperGeometry.viewportWidthDp.value = widthDp
        updateNativeBackdrop()
        updateNativeCenterActionLayout()
    }

    fun setOnNavigationStyleChanged(listener: Runnable?) {
        navigationStyleChanged = listener
    }

    private fun applyNativeAssistMode(enabled: Boolean) {
        if (bitmapBackdropManagerDelegate.isInitialized()) {
            bitmapBackdropManager.setNativeAssistMode(enabled)
        }
        if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
            coolapkLegacyBackdropManager.setNativeAssistMode(enabled)
        }
        if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
            windowPixelCopyBackdropManager.setNativeAssistMode(enabled)
        }
    }

    private fun updateNativeBackdrop(): Boolean {
        if (forceSyntheticOnlyBackdrop || !nativeBlurEnabled || width <= 0 || height <= 0 || !HyperOsNativeBlur.isAvailable()) {
            nativeBlurView.disable()
            nativeBackdropActiveState.value = false
            applyNativeAssistMode(false)
            return false
        }
        val settings = settingsState.value
        val labels = labelsState.value.ifEmpty { listOf("首页", "数码", "发现", "我的") }
        val showPostButton = settings.showPostButton
        val separateCircleActive = separateCirclePlusState.value && labels.size >= 5
        val showCenterActionTab = !showPostButton && labels.size == 5 &&
            centerActionIndexState.intValue in labels.indices
        val desiredContentDp = if (separateCircleActive) {
            settings.tabWidthDp * 4f + KSU_ROW_HORIZONTAL_PADDING_DP
        } else if (showPostButton) {
            settings.tabWidthDp * labels.size + POST_SLOT_WIDTH_DP
        } else if (showCenterActionTab) {
            settings.tabWidthDp * 4f + POST_SLOT_WIDTH_DP
        } else {
            settings.tabWidthDp * labels.size
        }.coerceAtLeast(1f)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val availableContentDp = (width / density - KSU_ROW_HORIZONTAL_PADDING_DP).coerceAtLeast(1f)
        val widthScale = (availableContentDp / desiredContentDp).coerceIn(0.1f, 1f)
        val barWidthDp = desiredContentDp * widthScale + KSU_ROW_HORIZONTAL_PADDING_DP
        val hyperLayout = if (usesHyperNavigation()) hyperNavigationLayout() else null
        val lp = (nativeBlurView.layoutParams as? LayoutParams) ?: LayoutParams(1, 1)
        lp.width = if (glassOnlyPanelState.value) width else ((hyperLayout?.barWidthDp ?: barWidthDp) * density).toInt().coerceAtLeast(1)
        lp.height = if (glassOnlyPanelState.value) height else ((hyperLayout?.barHeightDp ?: 64f) * density).toInt().coerceAtLeast(1)
        lp.gravity = Gravity.BOTTOM or if (hyperLayout != null) Gravity.LEFT else Gravity.CENTER_HORIZONTAL
        lp.leftMargin = ((hyperLayout?.barLeftDp ?: 0f) * density).toInt()
        lp.bottomMargin = if (glassOnlyPanelState.value) 0 else ((hyperLayout?.bottomPaddingDp ?: settings.bottomPaddingDp) * density).toInt().coerceAtLeast(0)
        nativeBlurView.layoutParams = lp
        val dark = effectiveDark(settings)
        // Native blur averages the real host pixels directly. A large radius on a mostly white
        // page mixes black text/icons into the whole surface and produces the familiar grey fog.
        // Keep light pages much clearer; dark pages can tolerate a slightly wider radius.
        val blurRadiusPx = if (hyperLayout != null) {
            ((if (hyperLayout.standard) 18f else 18f * 0.24f) * density).toInt().coerceAtLeast(1)
        } else if (dark) {
            // The full native bar needs a wider radius than the moving lens. This hides host
            // text/details behind the bar while keeping capture completely disabled.
            (settings.blurRadiusDp * 7f).toInt().coerceIn(8, 72)
        } else {
            (settings.blurRadiusDp * 5.5f).toInt().coerceIn(8, 52)
        }
        val active = nativeBlurView.update(
            if (glassOnlyPanelState.value) height / 2f else if (hyperLayout?.standard == true) 0f else (hyperLayout?.barHeightDp?.div(2f) ?: 32f) * density,
            blurRadiusPx,
            dark,
            0f,
        )
        val changed = nativeBackdropActiveState.value != active
        nativeBackdropActiveState.value = active
        applyNativeAssistMode(false)
        if (active) suspendAndClearAllBackdropManagers()
        if (changed) backdropGenerationState.intValue = backdropGenerationState.intValue + 1
        return active
    }

    private fun restartFallbackBackdropIfNeeded() {
        if (!bitmapCaptureAllowed() || !isAttachedToWindow) return
        applyNativeAssistMode(false)
        backdropSetSource(configuredBackdropSource?.get())
        backdropStart(hostActivity ?: ActivityFinder.from(context))
        postOnAnimation { refreshBackdropFrame() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            post {
                updateNativeCenterActionLayout()
                val native = updateNativeBackdrop()
                if (!native) restartFallbackBackdropIfNeeded()
            }
        }
    }

    private fun suppressInjectedComposeAccessibility() {
        if (!isolateComposeResources) return
        val queue = ArrayDeque<View>()
        queue.add(composeView)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 32) {
            val view = queue.removeFirst()
            try {
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                view.contentDescription = null
                view.isFocusable = false
                if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") {
                    // Compose installs its virtual-node provider through an accessibility delegate.
                    // This injected navigation is intentionally not exposed to accessibility, so
                    // removing only this inner delegate prevents stale prefetch requests from
                    // resolving module resource ids through Midea's Resources table.
                    view.accessibilityDelegate = null
                }
            } catch (_: Throwable) {}
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    try { queue.addLast(view.getChildAt(index)) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun resolveComposeResourceContext(hostContext: Context): Context {
        if (hostContext.packageName == MODULE_PACKAGE) {
            InjectedResourceResolverGuard.registerModuleContext(hostContext)
            return hostContext
        }
        return try {
            val rawModuleContext = hostContext.createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            val configuredModuleContext = rawModuleContext.createConfigurationContext(
                Configuration(hostContext.resources.configuration),
            )
            InjectedResourceResolverGuard.registerModuleContext(configuredModuleContext)
            // Keep Activity/window/system-service behaviour from the host while resolving every
            // Compose and Miuix resource id from the module APK. This is universal by design:
            // numeric 0x7f ids are never safe to resolve through an unrelated host Resources table.
            ModuleResourceContext(hostContext, configuredModuleContext)
        } catch (_: Throwable) {
            // The Xposed Resources hook can still resolve a module id after a rare early
            // createPackageContext failure. Keeping the host context here avoids blocking install.
            hostContext
        }
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = localViewModelStore
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? = null

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        try {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = View::class.java.name
            info.contentDescription = null
            info.text = null
            info.isClickable = false
            info.isFocusable = false
        } catch (_: Throwable) {
            // Do not let screenshot/accessibility prefetch crash the host app.
        }
    }

    private fun clearInjectedRootOwners() {
        try {
            val root = rootView
            if (root != null && root !== this) {
                if (rootLifecycleOwnerInjected) root.setViewTreeLifecycleOwner(null)
                if (rootViewModelOwnerInjected) root.setViewTreeViewModelStoreOwner(null)
                if (rootSavedStateOwnerInjected) root.setViewTreeSavedStateRegistryOwner(null)
            }
        } catch (_: Throwable) {
        } finally {
            rootLifecycleOwnerInjected = false
            rootViewModelOwnerInjected = false
            rootSavedStateOwnerInjected = false
        }
    }

    private fun resumeLocalLifecycle() {
        if (disposed) return
        try {
            if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            }
            if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
            if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        } catch (_: Throwable) {
        }
    }

    private fun pauseLocalLifecycle() {
        if (disposed) return
        try {
            if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
        } catch (_: Throwable) {
        }
    }

    private fun backdropSetSource(view: View?) {
        if (!bitmapCaptureAllowed()) return
        when {
            useWindowPixelCopyBackdropCapture -> Unit
            useCoolapkLegacyBackdropCapture -> coolapkLegacyBackdropManager.setSource(view)
            else -> bitmapBackdropManager.setSource(view)
        }
    }

    private fun backdropStart(activity: Activity?) {
        if (!bitmapCaptureAllowed()) return
        when {
            useWindowPixelCopyBackdropCapture -> {
                if (bitmapBackdropManagerDelegate.isInitialized()) {
                    bitmapBackdropManager.pause()
                }
                if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
                    coolapkLegacyBackdropManager.pause()
                }
                windowPixelCopyBackdropManager.start(activity)
            }
            useCoolapkLegacyBackdropCapture -> {
                if (bitmapBackdropManagerDelegate.isInitialized()) {
                    bitmapBackdropManager.pause()
                }
                if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
                    windowPixelCopyBackdropManager.pause()
                }
                coolapkLegacyBackdropManager.start(activity)
            }
            else -> {
                if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
                    coolapkLegacyBackdropManager.pause()
                }
                if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
                    windowPixelCopyBackdropManager.pause()
                }
                bitmapBackdropManager.start(activity)
            }
        }
    }

    private fun backdropPause() {
        when {
            useWindowPixelCopyBackdropCapture ->
                if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
                    windowPixelCopyBackdropManager.pause()
                }
            useCoolapkLegacyBackdropCapture ->
                if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
                    coolapkLegacyBackdropManager.pause()
                }
            else ->
                if (bitmapBackdropManagerDelegate.isInitialized()) {
                    bitmapBackdropManager.pause()
                }
        }
    }

    private fun backdropStop() {
        if (bitmapBackdropManagerDelegate.isInitialized()) bitmapBackdropManager.stop()
        if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
            coolapkLegacyBackdropManager.stop()
        }
        if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
            windowPixelCopyBackdropManager.stop()
        }
    }

    private fun backdropResetBuffer() {
        if (!bitmapCaptureAllowed()) return
        when {
            useWindowPixelCopyBackdropCapture -> windowPixelCopyBackdropManager.resetBuffer()
            useCoolapkLegacyBackdropCapture -> coolapkLegacyBackdropManager.resetBuffer()
            else -> bitmapBackdropManager.resetBuffer()
        }
    }

    private fun backdropMarkDirty() {
        if (!bitmapCaptureAllowed()) return
        when {
            useWindowPixelCopyBackdropCapture -> windowPixelCopyBackdropManager.markDirty()
            useCoolapkLegacyBackdropCapture -> coolapkLegacyBackdropManager.markDirty()
            else -> bitmapBackdropManager.markDirty()
        }
    }

    private fun backdropMarkMoving() {
        if (!bitmapCaptureAllowed()) return
        when {
            useWindowPixelCopyBackdropCapture -> windowPixelCopyBackdropManager.markMoving()
            useCoolapkLegacyBackdropCapture -> coolapkLegacyBackdropManager.markMoving()
            else -> bitmapBackdropManager.markMoving()
        }
    }

    private fun backdropMarkIdle() {
        if (!bitmapCaptureAllowed()) return
        when {
            useWindowPixelCopyBackdropCapture -> windowPixelCopyBackdropManager.markIdle()
            useCoolapkLegacyBackdropCapture -> coolapkLegacyBackdropManager.markIdle()
            else -> bitmapBackdropManager.markIdle()
        }
    }

    private fun backdropNotifyTransition(delayMs: Long) {
        if (!bitmapCaptureAllowed()) return
        when {
            useWindowPixelCopyBackdropCapture -> windowPixelCopyBackdropManager.notifyContentTransition(delayMs)
            useCoolapkLegacyBackdropCapture -> coolapkLegacyBackdropManager.notifyContentTransition(delayMs)
            else -> bitmapBackdropManager.notifyContentTransition(delayMs)
        }
    }

    private fun backdropFinishTransition() {
        if (!bitmapCaptureAllowed()) return
        when {
            useWindowPixelCopyBackdropCapture -> windowPixelCopyBackdropManager.finishContentTransition()
            useCoolapkLegacyBackdropCapture -> coolapkLegacyBackdropManager.finishContentTransition()
            else -> bitmapBackdropManager.finishContentTransition()
        }
    }

    private fun backdropResume() {
        if (!bitmapCaptureAllowed()) return
        when {
            useWindowPixelCopyBackdropCapture -> windowPixelCopyBackdropManager.resume()
            useCoolapkLegacyBackdropCapture -> coolapkLegacyBackdropManager.resume()
            else -> bitmapBackdropManager.resume()
        }
    }

    private fun backdropRebind(activity: Activity?) {
        if (!bitmapCaptureAllowed()) return
        when {
            useWindowPixelCopyBackdropCapture -> windowPixelCopyBackdropManager.rebind(activity)
            useCoolapkLegacyBackdropCapture -> coolapkLegacyBackdropManager.rebind(activity)
            else -> bitmapBackdropManager.rebind(activity)
        }
    }

    private fun suspendAndClearAllBackdropManagers() {
        if (bitmapBackdropManagerDelegate.isInitialized()) {
            bitmapBackdropManager.suspendAndClear()
        }
        if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
            coolapkLegacyBackdropManager.pause()
            coolapkLegacyBackdropManager.resetBuffer()
        }
        if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
            windowPixelCopyBackdropManager.suspendAndClear()
        }
        applyNativeAssistMode(false)
        capturedBackdropState.value = null
    }

    private fun backdropSuspendAndClear() = suspendAndClearAllBackdropManagers()

    override fun onAttachedToWindow() {
        if (disposed) {
            super.onAttachedToWindow()
            return
        }
        try {
            val root = rootView
            if (root != null && root !== this) {
                if (root.findViewTreeLifecycleOwner() == null) {
                    root.setViewTreeLifecycleOwner(this)
                    rootLifecycleOwnerInjected = true
                }
                if (root.findViewTreeViewModelStoreOwner() == null) {
                    root.setViewTreeViewModelStoreOwner(this)
                    rootViewModelOwnerInjected = true
                }
                if (root.findViewTreeSavedStateRegistryOwner() == null) {
                    root.setViewTreeSavedStateRegistryOwner(this)
                    rootSavedStateOwnerInjected = true
                }
            }
            this.setViewTreeLifecycleOwner(this)
            this.setViewTreeViewModelStoreOwner(this)
            this.setViewTreeSavedStateRegistryOwner(this)
        } catch (_: Throwable) {
        }
        super.onAttachedToWindow()
        if (isolateComposeResources) {
            // Some HyperOS accessibility services query the virtual Compose tree immediately
            // after attachment, before the first layout callback. Remove the injected delegate
            // now and repeat after Compose has had opportunities to recreate AndroidComposeView.
            suppressInjectedComposeAccessibility()
            post { suppressInjectedComposeAccessibility() }
            postDelayed({ suppressInjectedComposeAccessibility() }, 32L)
            postDelayed({ suppressInjectedComposeAccessibility() }, 120L)
            postDelayed({ suppressInjectedComposeAccessibility() }, 360L)
        }
        resumeLocalLifecycle()
        if (nativeBlurEnabled) {
            post {
                suspendAndClearAllBackdropManagers()
                updateNativeBackdrop()
            }
        } else if (backdropCaptureEnabled) {
            post {
                nativeBlurView.disable()
                nativeBackdropActiveState.value = false
                backdropSetSource(configuredBackdropSource?.get())
                backdropStart(hostActivity ?: ActivityFinder.from(context))
                refreshBackdropFrame()
            }
        } else {
            nativeBlurView.disable()
            nativeBackdropActiveState.value = false
            applyNativeAssistMode(false)
            backdropSuspendAndClear()
        }
    }

    override fun onDetachedFromWindow() {
        // Host view transitions may detach and reattach the same injected view. Pausing at CREATED
        // avoids trying to resurrect a DESTROYED LifecycleRegistry on the next attachment.
        backdropPause()
        nativeBlurView.disable()
        nativeBackdropActiveState.value = false
        applyNativeAssistMode(false)
        pauseLocalLifecycle()
        clearInjectedRootOwners()
        super.onDetachedFromWindow()
    }

    /** Permanently release this injected view before removing it from DecorView. */
    fun dispose() {
        if (disposed) return
        (hostNavigationAdapter as? LiquidTabPortAdapter)?.unbind()
        portBackdrop?.dispose(); portBackdrop = null
        // Pause before flipping disposed so lifecycle events are still delivered in valid order.
        pauseLocalLifecycle()
        disposed = true
        navigationStyleChanged = null
        backdropStop()
        nativeBlurView.disable()
        nativeBackdropActiveState.value = false
        capturedBackdropState.value = null
        clearInjectedRootOwners()
        restoreNativeCenterActionView()
        try { composeView.disposeComposition() } catch (_: Throwable) {}
        try {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED &&
                lifecycleRegistry.currentState != Lifecycle.State.INITIALIZED
            ) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        } catch (_: Throwable) {
        }
        try { localViewModelStore.clear() } catch (_: Throwable) {}
    }

    fun isThemeCompatible(hostContext: Context): Boolean =
        createdNightMask == currentNightMask(hostContext)

    private fun currentNightMask(hostContext: Context): Int = try {
        hostContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    } catch (_: Throwable) {
        Configuration.UI_MODE_NIGHT_UNDEFINED
    }

    fun setLabels(labels: Array<String>?) {
        if (labels == null) return
        val next = labels.toList()
        if (next.isEmpty() || labelsState.value == next) return
        labelsState.value = next
        navigationModelGenerationState.intValue++
        val max = (next.size - 1).coerceAtLeast(0)
        if (selectedIndex.intValue > max) selectedIndex.intValue = max
        lastDispatchedIndex = -1
        composeView.requestLayout()
        composeView.invalidate()
        requestLayout()
        invalidate()
        post {
            updateNativeBackdrop()
            backdropSetSource(configuredBackdropSource?.get())
            backdropStart(hostActivity ?: ActivityFinder.from(context))
            refreshBackdropFrame()
        }
    }

    fun setNavigationIconResourceIds(resourceIds: IntArray?) {
        val nextIds = resourceIds?.copyOf() ?: IntArray(0)
        if (navigationIconBitmapSources.isEmpty()
            && navigationIconResourceIds.contentEquals(nextIds)) return
        navigationIconBitmapSources = emptyList()
        navigationIconResourceIds = nextIds
        navigationIconsState.value = nextIds.map { resourceId ->
            if (resourceId == 0) null else rasterizeHostDrawable(resourceId)
        }
        composeView.invalidate()
        invalidate()
    }

    fun setNavigationIconBitmaps(bitmaps: Array<out Bitmap?>?) {
        val next = bitmaps?.toList() ?: emptyList()
        if (navigationIconBitmapSources.size == next.size
            && navigationIconBitmapSources.indices.all {
                navigationIconBitmapSources[it] === next[it]
            }) return
        navigationIconBitmapSources = next
        navigationIconResourceIds = IntArray(0)
        navigationIconsState.value = next.map { bitmap ->
            if (bitmap == null || bitmap.isRecycled) null else try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)?.asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        }
        composeView.invalidate()
        invalidate()
    }

    private fun rasterizeHostDrawable(resourceId: Int): ImageBitmap? = try {
        val original = context.resources.getDrawable(resourceId, context.theme)
        val drawable = original.constantState
            ?.newDrawable(context.resources, context.theme)
            ?.mutate()
            ?: original.mutate()
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(AndroidCanvas(bitmap))
        bitmap.asImageBitmap()
    } catch (_: Throwable) {
        null
    }

    fun setTransientActionMode(enabled: Boolean) {
        if (transientActionModeState.value == enabled) return
        transientActionModeState.value = enabled
        updateHyperCaptureGeometry()
        navigationModelGenerationState.intValue++
        lastDispatchedIndex = -1
        composeView.requestLayout()
        composeView.invalidate()
        requestLayout()
        invalidate()
        if (isAttachedToWindow) {
            post {
                updateNativeBackdrop()
                postOnAnimation { refreshBackdropFrame() }
                postDelayed({ refreshBackdropSoon() }, 96L)
            }
        }
    }

    fun setTransientActionEnabled(enabled: BooleanArray?) {
        val next = enabled?.toList() ?: listOf(true, true, true, true)
        if (transientActionEnabledState.value == next) return
        transientActionEnabledState.value = next
        composeView.invalidate()
        invalidate()
    }

    fun applySettings(settings: GlassSettings?) {
        val next = settings ?: GlassSettings.defaults()
        val current = settingsState.value
        if (current.revision == next.revision &&
            current.themeMode == next.themeMode &&
            current.tabWidthDp == next.tabWidthDp &&
            current.bottomPaddingDp == next.bottomPaddingDp &&
            current.blurRadiusDp == next.blurRadiusDp &&
            current.lightAlpha == next.lightAlpha &&
            current.darkAlpha == next.darkAlpha &&
            current.iconScale == next.iconScale &&
            current.textSizeSp == next.textSizeSp &&
            current.iconOnly == next.iconOnly &&
            current.showPostButton == next.showPostButton &&
            current.customColorEnabled == next.customColorEnabled &&
            current.customPostColor == next.customPostColor &&
            current.showChannel == next.showChannel
        ) return

        val layoutModeChanged = current.tabWidthDp != next.tabWidthDp ||
            current.bottomPaddingDp != next.bottomPaddingDp ||
            current.iconOnly != next.iconOnly ||
            current.showPostButton != next.showPostButton
        settingsState.value = next
        updateNativeCenterActionLayout()
        updateNativeBackdrop()
        if (bitmapCaptureAllowed()) backdropMarkDirty()
        if (layoutModeChanged) {
            backdropResetBuffer()
            composeView.requestLayout()
            composeView.invalidate()
            requestLayout()
            invalidate()
        }
        if (isAttachedToWindow) {
            // Structural switches rebuild FloatingBottomBar's remembered drag/backdrop state.
            // Capture after layout as well as on two later frames so the lens cannot stay
            // attached to the previous four/five-slot geometry.
            postOnAnimation { refreshBackdropFrame() }
            if (layoutModeChanged) {
                postDelayed({ refreshBackdropFrame() }, 90L)
                postDelayed({ refreshBackdropSoon() }, 240L)
            }
        }
    }

    fun setTargetComposeView(view: View?) {
        targetComposeView = if (view == null) null else WeakReference(view)
    }


    fun setBackdropExcludedViews(views: Array<View>?) {
        val next = views?.filter { it !== this }?.toTypedArray() ?: emptyArray()
        val unchanged = next.size == configuredBackdropExcludedViews.size &&
            next.indices.all { next[it] === configuredBackdropExcludedViews[it] }
        if (unchanged) return
        configuredBackdropExcludedViews = next
        if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
            coolapkLegacyBackdropManager.setExcludedViews(next)
        }
        if (bitmapCaptureAllowed() && isAttachedToWindow) {
            backdropResetBuffer()
            postOnAnimation { refreshBackdropFrame() }
        }
    }

    fun setUseCoolapkLegacyBackdropCapture(enabled: Boolean) {
        if (useCoolapkLegacyBackdropCapture == enabled) return
        if (enabled) {
            if (bitmapBackdropManagerDelegate.isInitialized()) {
                bitmapBackdropManager.suspendAndClear()
            }
            if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
                windowPixelCopyBackdropManager.suspendAndClear()
            }
            useWindowPixelCopyBackdropCapture = false
        } else {
            if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
                coolapkLegacyBackdropManager.pause()
            }
        }
        useCoolapkLegacyBackdropCapture = enabled
        capturedBackdropState.value = null
        backdropGenerationState.intValue = backdropGenerationState.intValue + 1
        if (nativeBlurEnabled && isAttachedToWindow) {
            post { updateNativeBackdrop() }
        } else if (bitmapCaptureAllowed() && isAttachedToWindow) {
            updateNativeBackdrop()
            backdropSetSource(configuredBackdropSource?.get())
            backdropStart(hostActivity ?: ActivityFinder.from(context))
            postOnAnimation { refreshBackdropFrame() }
            postDelayed({ refreshBackdropSoon() }, 96L)
            postDelayed({ refreshBackdropSoon() }, 240L)
        }
    }

    fun setUseWindowPixelCopyBackdropCapture(enabled: Boolean) {
        if (useWindowPixelCopyBackdropCapture == enabled) return
        if (enabled) {
            if (bitmapBackdropManagerDelegate.isInitialized()) {
                bitmapBackdropManager.suspendAndClear()
            }
            if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
                coolapkLegacyBackdropManager.pause()
            }
            useCoolapkLegacyBackdropCapture = false
            configuredBackdropSource = null
        } else {
            if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
                windowPixelCopyBackdropManager.pause()
            }
        }
        useWindowPixelCopyBackdropCapture = enabled
        capturedBackdropState.value = null
        backdropGenerationState.intValue = backdropGenerationState.intValue + 1
        if (nativeBlurEnabled && isAttachedToWindow) {
            post { updateNativeBackdrop() }
        } else if (bitmapCaptureAllowed() && isAttachedToWindow) {
            updateNativeBackdrop()
            backdropStart(hostActivity ?: ActivityFinder.from(context))
            postOnAnimation { refreshBackdropFrame() }
            postDelayed({ refreshBackdropSoon() }, 96L)
            postDelayed({ refreshBackdropSoon() }, 240L)
        }
    }


    fun setCoolapkLegacyPreservePhysicalCoordinates(enabled: Boolean) {
        if (configuredLegacyPhysicalCoordinates == enabled) return
        configuredLegacyPhysicalCoordinates = enabled
        if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
            coolapkLegacyBackdropManager.setPreservePhysicalCoordinates(enabled)
        }
        if (useCoolapkLegacyBackdropCapture && bitmapCaptureAllowed() && isAttachedToWindow) {
            backdropResetBuffer()
            postOnAnimation { refreshBackdropFrame() }
        }
    }

    fun setWindowPixelCopyBlankFallbackColor(color: Int) {
        configuredWindowBlankFallbackColor = color
        if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
            windowPixelCopyBackdropManager.setBlankFrameFallbackColor(color)
        }
    }

    private fun effectiveCaptureHeightDp(): Float =
        if (usesHyperNavigation()) hyperNavigationLayout().captureHeightDp else configuredBackdropCaptureHeightDp

    private fun updateHyperCaptureGeometry() {
        val hyper = usesHyperNavigation()
        if (bitmapBackdropManagerDelegate.isInitialized()) {
            bitmapBackdropManager.setCaptureHeightDp(if (hyper) hyperNavigationLayout().captureHeightDp else REFERENCE_CAPTURE_HEIGHT_DP)
        }
        if (coolapkLegacyBackdropManagerDelegate.isInitialized()) {
            coolapkLegacyBackdropManager.setCaptureHeightDp(effectiveCaptureHeightDp())
        }
        if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
            windowPixelCopyBackdropManager.setCaptureHeightDp(effectiveCaptureHeightDp(), hyper)
        }
    }

    fun setCoolapkLegacyCaptureHeightDp(heightDp: Float) {
        configuredBackdropCaptureHeightDp = heightDp
        updateHyperCaptureGeometry()
    }

    fun setBackdropSourceView(view: View?) {
        configuredBackdropSource = if (
            useWindowPixelCopyBackdropCapture || view == null
        ) null else WeakReference(view)
        if (bitmapCaptureAllowed()) {
            backdropSetSource(
                if (useWindowPixelCopyBackdropCapture) null else view,
            )
        }
    }

    fun setExactBackdropSourceEnabled(enabled: Boolean) {
        if (configuredExactBackdropSource == enabled) return
        configuredExactBackdropSource = enabled
        if (bitmapCaptureAllowed()) {
            bitmapBackdropManager.setExactSourceEnabled(enabled)
            if (isAttachedToWindow) {
                backdropResetBuffer()
                postOnAnimation { refreshBackdropFrame() }
            }
        }
    }

    /**
     * Keep refraction/pressed-liquid rendering active even when no real Bitmap is captured.
     * The shader samples an internal near-black texture until a real captured frame arrives.
     */
    fun setSyntheticLiquidBackdropEnabled(enabled: Boolean) {
        if (syntheticLiquidBackdropState.value == enabled) return
        syntheticLiquidBackdropState.value = enabled
        backdropGenerationState.intValue = backdropGenerationState.intValue + 1
        composeView.invalidate()
        invalidate()
    }

    /**
     * Per-app hard-black liquid mode. No host pixels are read while enabled, but the synthetic
     * near-black texture keeps magnification/refraction/chromatic aberration/highlight alive.
     */
    fun setForceSyntheticOnlyBackdrop(enabled: Boolean) {
        if (forceSyntheticOnlyBackdrop == enabled) return
        forceSyntheticOnlyBackdrop = enabled
        capturedBackdropState.value = null
        syntheticLiquidBackdropState.value = enabled
        backdropGenerationState.intValue = backdropGenerationState.intValue + 1

        if (enabled) {
            nativeBlurView.disable()
            nativeBackdropActiveState.value = false
            applyNativeAssistMode(false)
            suspendAndClearAllBackdropManagers()
        } else if (isAttachedToWindow) {
            post {
                if (nativeBlurEnabled) {
                    updateNativeBackdrop()
                } else if (bitmapCaptureAllowed()) {
                    backdropSetSource(configuredBackdropSource?.get())
                    backdropStart(hostActivity ?: ActivityFinder.from(context))
                    postOnAnimation { refreshBackdropFrame() }
                    postDelayed({ refreshBackdropSoon() }, 96L)
                    postDelayed({ refreshBackdropSoon() }, 220L)
                }
            }
        }
        composeView.invalidate()
        invalidate()
    }

    /**
     * Douyin press-only transparent mode: the live captured Bitmap stays invisible at rest and is
     * sampled only by the moving selection lens while pressProgress > 0. The ambient pill surface
     * is transparent, so normal video/UI pixels remain visually untouched.
     */
    fun setPressOnlyTransparentBackdrop(enabled: Boolean) {
        if (pressOnlyTransparentBackdropState.value == enabled) return
        pressOnlyTransparentBackdropState.value = enabled
        if (windowPixelCopyBackdropManagerDelegate.isInitialized()) {
            windowPixelCopyBackdropManager.setDouyinScreenCaptureEnabled(!enabled)
        }
        // This mode needs a real live Bitmap source; never combine it with synthetic-only black.
        if (enabled && forceSyntheticOnlyBackdrop) {
            forceSyntheticOnlyBackdrop = false
            syntheticLiquidBackdropState.value = false
        }
        capturedBackdropState.value = null
        backdropGenerationState.intValue = backdropGenerationState.intValue + 1
        if (isAttachedToWindow && bitmapCaptureAllowed()) {
            post {
                backdropSetSource(configuredBackdropSource?.get())
                backdropStart(hostActivity ?: ActivityFinder.from(context))
                refreshBackdropFrame()
                postDelayed({ refreshBackdropSoon() }, 24L)
                postDelayed({ refreshBackdropSoon() }, 64L)
            }
        }
        composeView.invalidate()
        invalidate()
    }

    fun setBackdropCaptureEnabled(enabled: Boolean) {
        if (backdropCaptureEnabled == enabled) return
        backdropCaptureEnabled = enabled
        if (nativeBlurEnabled) {
            // Native mode always owns the background. This switch is remembered only for the
            // bitmap mode that will be restored when native blur is turned off.
            suspendAndClearAllBackdropManagers()
            if (isAttachedToWindow) post { updateNativeBackdrop() }
            capturedBackdropState.value = null
            backdropGenerationState.intValue = backdropGenerationState.intValue + 1
            return
        }
        if (!enabled) {
            backdropSuspendAndClear()
            capturedBackdropState.value = null
            backdropGenerationState.intValue = backdropGenerationState.intValue + 1
            return
        }
        if (isAttachedToWindow) {
            post {
                nativeBlurView.disable()
                nativeBackdropActiveState.value = false
                backdropSetSource(configuredBackdropSource?.get())
                backdropStart(hostActivity ?: ActivityFinder.from(context))
                postOnAnimation { refreshBackdropFrame() }
                postDelayed({ refreshBackdropSoon() }, 96L)
                postDelayed({ refreshBackdropSoon() }, 220L)
            }
        }
        backdropGenerationState.intValue = backdropGenerationState.intValue + 1
    }

    fun setNativeBlurEnabled(enabled: Boolean) {
        if (nativeBlurEnabled == enabled) return
        nativeBlurEnabled = enabled
        capturedBackdropState.value = null
        backdropGenerationState.intValue = backdropGenerationState.intValue + 1
        if (enabled) {
            // Hard stop every app-specific path, including managers that are not the currently
            // selected profile. No settled assist frame, PixelCopy or TextureView snapshot remains.
            suspendAndClearAllBackdropManagers()
            if (isAttachedToWindow) post { updateNativeBackdrop() }
            return
        }

        nativeBlurView.disable()
        nativeBackdropActiveState.value = false
        applyNativeAssistMode(false)
        if (bitmapCaptureAllowed() && isAttachedToWindow) {
            post {
                backdropSetSource(configuredBackdropSource?.get())
                backdropStart(hostActivity ?: ActivityFinder.from(context))
                postOnAnimation { refreshBackdropFrame() }
                postDelayed({ refreshBackdropSoon() }, 96L)
                postDelayed({ refreshBackdropSoon() }, 220L)
            }
        } else {
            suspendAndClearAllBackdropManagers()
        }
    }

    fun setSelectedAccentEnabled(enabled: Boolean) {
        if (selectedAccentEnabledState.value == enabled) return
        selectedAccentEnabledState.value = enabled
    }

    private var navigationHapticsEnabled = GlobalAppearanceStore.DEFAULT_NAVIGATION_HAPTICS_ENABLED

    fun setNavigationHapticsEnabled(enabled: Boolean) {
        navigationHapticsEnabled = enabled
    }

    private fun performNavigationHaptic() {
        if (navigationHapticsEnabled) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    fun setNavigationStyle(style: String?) {
        val normalized = InjectedNavigationStyle.normalize(style)
        if (navigationStyleState.value == normalized) return
        navigationStyleState.value = normalized
        activeTouchSequence = false
        navigationStyleChanged?.run()
        updateHyperCaptureGeometry()
        updateNativeBackdrop()
        updateNativeCenterActionLayout()
        composeView.requestLayout()
        requestLayout()
        if (isAttachedToWindow && bitmapCaptureAllowed()) postOnAnimation { refreshBackdropFrame() }
    }

    fun setBottomBarTraditionalChinese(enabled: Boolean) {
        if (bottomBarTraditionalChineseState.value == enabled) return
        bottomBarTraditionalChineseState.value = enabled
        composeView.invalidate()
        invalidate()
    }

    fun setLowLatencyBackdropCaptureEnabled(enabled: Boolean) {
        if (lowLatencyBackdropCaptureEnabled == enabled) return
        lowLatencyBackdropCaptureEnabled = enabled
        if (bitmapBackdropManagerDelegate.isInitialized()) {
            bitmapBackdropManager.setLowLatencyCaptureMode(enabled)
        }
    }

    fun setBackdropCaptureProfile(
        movingIntervalMs: Long,
        settleDelayMs: Long,
        includeHardwareLayers: Boolean,
    ) {
        // Capture and glass animation are decoupled. Light hosts may refresh at 24-28 ms,
        // expensive hosts automatically back off, and explicit/final frames use a separate
        // 16 ms fast lane so an old bitmap is never left behind after the motion ends.
        val minimumMovingIntervalMs = if (lowLatencyBackdropCaptureEnabled) 12L else 21L
        val effectiveMovingIntervalMs = movingIntervalMs.coerceAtLeast(minimumMovingIntervalMs)
        configuredHardwareBackdropCapture = includeHardwareLayers
        when {
            useWindowPixelCopyBackdropCapture -> windowPixelCopyBackdropManager.setCaptureProfile(
                effectiveMovingIntervalMs,
                settleDelayMs,
                includeHardwareLayers,
            )
            useCoolapkLegacyBackdropCapture -> {
                val effectiveHardwareCapture = effectiveHardwareCaptureForSelection(
                    selectedIndex.intValue,
                )
                coolapkLegacyBackdropManager.setHardwareLayerCaptureEnabled(
                    effectiveHardwareCapture,
                )
                appliedLegacyHardwareBackdropCapture = effectiveHardwareCapture
            }
            else -> bitmapBackdropManager.setCaptureProfile(
                effectiveMovingIntervalMs,
                settleDelayMs,
                includeHardwareLayers,
            )
        }
        if (nativeBlurEnabled) {
            suspendAndClearAllBackdropManagers()
        } else {
            applyNativeAssistMode(false)
        }
    }

    fun setCoolapkLegacyHardwareLayerCaptureEnabled(enabled: Boolean) {
        coolapkLegacyBackdropManager.setHardwareLayerCaptureEnabled(enabled)
        appliedLegacyHardwareBackdropCapture = enabled
    }

    fun setCoolapkLegacyRequireVisibleSurfaceForCapture(enabled: Boolean) {
        coolapkLegacyBackdropManager.setRequireVisibleSurfaceForCapture(enabled)
    }

    /** Compass 26.58: camera close immediately switches the lens back to synthetic black. */
    fun forceCompassCameraBackdropBlack() {
        if (!useCoolapkLegacyBackdropCapture) return
        coolapkLegacyBackdropManager.forceCameraBackdropBlackUntilReopened()
    }

    /** Compass 26.60: CameraView2 process start alone must never expose its live Surface. */
    fun noteCompassCameraProcessStarted() {
        if (!useCoolapkLegacyBackdropCapture) return
        coolapkLegacyBackdropManager.noteCameraProcessStarted()
    }

    /** Compass 26.60: host camera-mask/e1 state is the authoritative visual preview gate. */
    fun setCompassCameraPreviewVisible(visible: Boolean) {
        if (!useCoolapkLegacyBackdropCapture) return
        coolapkLegacyBackdropManager.setCameraPreviewVisible(visible)
    }

    /** Compatibility entry kept for older callers: only preview state may unlock capture. */
    fun resumeCompassCameraBackdropCapture() {
        noteCompassCameraProcessStarted()
    }

    /** Compass 26.59 fallback recheck; 26.60 preview gate still wins before any Surface capture. */
    fun recheckCompassCameraFrontVisibility() {
        if (!useCoolapkLegacyBackdropCapture) return
        coolapkLegacyBackdropManager.recheckCameraFrontVisibilityNow()
    }

    private fun selectionHardwareCaptureMode(index: Int): Int = try {
        hostNavigationAdapter?.hardwareBackdropCaptureModeForSelection(index) ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun effectiveHardwareCaptureForSelection(index: Int): Boolean =
        when (selectionHardwareCaptureMode(index)) {
            0 -> false
            1 -> true
            else -> configuredHardwareBackdropCapture
        }

    private fun applyPageAwareBackdropSelection(index: Int, pageChanged: Boolean) {
        val mode = selectionHardwareCaptureMode(index)
        if (useCoolapkLegacyBackdropCapture && mode in 0..1) {
            val enabled = mode == 1
            if (appliedLegacyHardwareBackdropCapture != enabled) {
                coolapkLegacyBackdropManager.setHardwareLayerCaptureEnabled(enabled)
                appliedLegacyHardwareBackdropCapture = enabled
            }
            if (pageChanged && bitmapCaptureAllowed()) {
                // Clear before requesting the incoming page. In particular, a red camera frame
                // must not remain visible while Compass replaces it with the black Level page.
                coolapkLegacyBackdropManager.resetForPageChange()
                if (isAttachedToWindow) {
                    postOnAnimation {
                        if (selectedIndex.intValue == index) refreshBackdropFrame()
                    }
                    postDelayed({
                        if (selectedIndex.intValue == index) refreshBackdropSoon()
                    }, 72L)
                    postDelayed({
                        if (selectedIndex.intValue == index) refreshBackdropSoon()
                    }, 180L)
                    postDelayed({
                        if (selectedIndex.intValue == index) refreshBackdropSoon()
                    }, 360L)
                }
            }
        }
        lastBackdropSelectionIndex = index
    }

    fun setBackdropBlankFrameGuard(enabled: Boolean) {
        when {
            useWindowPixelCopyBackdropCapture -> windowPixelCopyBackdropManager.setPreserveLastFrameOnBlank(enabled)
            useCoolapkLegacyBackdropCapture -> coolapkLegacyBackdropManager.setPreserveLastFrameOnBlank(enabled)
            else -> bitmapBackdropManager.setPreserveLastFrameOnBlank(enabled)
        }
    }

    fun setHostNavigationAdapter(adapter: HostNavigationAdapter?) {
        if (hostNavigationAdapter !== adapter) (hostNavigationAdapter as? LiquidTabPortAdapter)?.unbind()
        hostNavigationAdapter = adapter
        (adapter as? LiquidTabPortAdapter)?.bind(this)
        syncDynamicBackdropSource()
        val nextCenterAction = try { adapter?.centerActionTabIndex() ?: -1 } catch (_: Throwable) { -1 }
        nativeCenterActionExpectedState.value = try {
            adapter?.usesNativeCenterActionView() == true
        } catch (_: Throwable) {
            false
        }
        val nextSeparateCircle = try { adapter?.separateCirclePlus() == true } catch (_: Throwable) { false }
        if (separateCirclePlusState.value != nextSeparateCircle) {
            separateCirclePlusState.value = nextSeparateCircle
            navigationModelGenerationState.intValue++
            backdropResetBuffer()
            composeView.requestLayout()
            composeView.invalidate()
            requestLayout()
            invalidate()
        }
        if (centerActionIndexState.intValue != nextCenterAction) {
            centerActionIndexState.intValue = nextCenterAction
            backdropResetBuffer()
            composeView.requestLayout()
            composeView.invalidate()
            requestLayout()
            invalidate()
        }
    }

    /** Keep Douyin's real PublishButton object, drawable and click listener intact. */
    fun setNativeCenterActionView(view: View?) {
        if (view === nativeCenterActionView && view?.parent === this) {
            updateNativeCenterActionLayout()
            try { view.bringToFront() } catch (_: Throwable) {}
            return
        }
        restoreNativeCenterActionView()
        if (view == null || disposed) return
        val parent = view.parent as? ViewGroup ?: return
        if (parent === this) {
            nativeCenterActionView = view
            updateNativeCenterActionLayout()
            return
        }
        try {
            nativeCenterOriginalParent = WeakReference(parent)
            nativeCenterOriginalIndex = parent.indexOfChild(view)
            nativeCenterOriginalScaleX = view.scaleX
            nativeCenterOriginalScaleY = view.scaleY
            nativeCenterOriginalLayoutParams = view.layoutParams
            val measuredWidth = maxOf(view.width, view.measuredWidth)
            val measuredHeight = maxOf(view.height, view.measuredHeight)
            parent.removeView(view)
            val density = resources.displayMetrics.density.coerceAtLeast(1f)
            val widthPx = if (measuredWidth > 0) measuredWidth else (48f * density).toInt()
            val heightPx = if (measuredHeight > 0) measuredHeight else (48f * density).toInt()
            val lp = LayoutParams(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            addView(view, lp)
            nativeCenterActionView = view
            try {
                view.visibility = View.VISIBLE
                view.alpha = 1f
                view.translationX = 0f
                view.translationY = 0f
                view.bringToFront()
            } catch (_: Throwable) {}
            updateNativeCenterActionLayout()
        } catch (_: Throwable) {
            nativeCenterActionView = null
        }
    }

    private fun updateNativeCenterActionLayout() {
        val view = nativeCenterActionView ?: return
        if (view.parent !== this) return
        try {
            val lp = (view.layoutParams as? LayoutParams) ?: return
            val density = resources.displayMetrics.density.coerceAtLeast(1f)
            val childHeight = if (lp.height > 0) lp.height.toFloat()
                else maxOf(view.height, view.measuredHeight).toFloat()
            val childWidth = if (lp.width > 0) lp.width.toFloat()
                else maxOf(view.width, view.measuredWidth).toFloat()
            view.scaleX = nativeCenterOriginalScaleX
            view.scaleY = nativeCenterOriginalScaleY
            if (usesHyperNavigation() && width > 0 && height > 0) {
                val layout = hyperNavigationLayout()
                val slotWidth = if (layout.separateCircle) layout.circleDiameterDp else
                    (layout.barWidthDp - if (layout.standard) 0f else 2f * InjectedNavigationStyle.FLOATING_INNER_PADDING_DP) /
                        layout.logicalIndices.size
                val scale = minOf(1f, (slotWidth - 4f).coerceAtLeast(1f) * density / childWidth.coerceAtLeast(1f),
                    (layout.barHeightDp - 8f).coerceAtLeast(1f) * density / childHeight.coerceAtLeast(1f))
                view.scaleX = nativeCenterOriginalScaleX * scale
                view.scaleY = nativeCenterOriginalScaleY * scale
                lp.gravity = Gravity.TOP or Gravity.LEFT
                lp.leftMargin = (layout.actionCenterXDp() * density - childWidth / 2f).toInt()
                lp.topMargin = (height - (layout.bottomPaddingDp + layout.contentCenterYDp) * density -
                    childHeight / 2f).toInt()
                lp.bottomMargin = 0
            } else if (separateCirclePlusState.value && width > 0 && height > 0) {
                // Four tabs in the pill plus an independent circular "+" floating to the right.
                // Place the real host PublishButton over that circle using the same geometry as
                // the Compose row (pill width + gap + circle diameter, all centered).
                val settings = settingsState.value
                val navWidthDp = (width / density).coerceAtLeast(1f)
                val pillDesiredDp = settings.tabWidthDp * 4f + KSU_ROW_HORIZONTAL_PADDING_DP
                val availableDp = (navWidthDp - KSU_ROW_HORIZONTAL_PADDING_DP).coerceAtLeast(1f)
                val widthScale = (availableDp / pillDesiredDp).coerceIn(0.1f, 1f)
                val pillWidthDp = pillDesiredDp * widthScale + KSU_ROW_HORIZONTAL_PADDING_DP
                val circleDp = SEPARATE_CIRCLE_DIAMETER_DP * settings.iconScale.coerceIn(0.8f, 1.2f)
                val totalRowDp = pillWidthDp + SEPARATE_CIRCLE_GAP_DP + circleDp
                val circleCenterXDp = (navWidthDp - totalRowDp) / 2f + pillWidthDp +
                    SEPARATE_CIRCLE_GAP_DP + circleDp / 2f
                lp.gravity = Gravity.TOP or Gravity.LEFT
                lp.leftMargin = (circleCenterXDp * density - childWidth / 2f).toInt().coerceAtLeast(0)
                lp.topMargin = (
                    height - settings.bottomPaddingDp * density -
                        (32f * density) - childHeight / 2f
                    ).toInt().coerceAtLeast(0)
            } else {
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.leftMargin = 0
                lp.topMargin = 0
                lp.bottomMargin = (
                    settingsState.value.bottomPaddingDp * density +
                        (64f * density - childHeight) / 2f
                    ).toInt()
            }
            view.layoutParams = lp
            view.bringToFront()
        } catch (_: Throwable) {}
    }

    private fun restoreNativeCenterActionView() {
        val view = nativeCenterActionView ?: return
        nativeCenterActionView = null
        view.scaleX = nativeCenterOriginalScaleX
        view.scaleY = nativeCenterOriginalScaleY
        try { if (view.parent === this) removeView(view) } catch (_: Throwable) {}
        val parent = nativeCenterOriginalParent?.get()
        val originalParams = nativeCenterOriginalLayoutParams
        val originalIndex = nativeCenterOriginalIndex
        nativeCenterOriginalParent = null
        nativeCenterOriginalLayoutParams = null
        nativeCenterOriginalIndex = -1
        if (parent != null) {
            try {
                if (view.parent is ViewGroup) (view.parent as ViewGroup).removeView(view)
                val safeIndex = originalIndex.coerceIn(0, parent.childCount)
                if (originalParams != null) parent.addView(view, safeIndex, originalParams)
                else parent.addView(view, safeIndex)
            } catch (_: Throwable) {}
        }
    }

    fun setIconMode(mode: Int) {
        iconModeState.intValue = mode.coerceIn(0, 4)
    }

    /**
     * Move only the glass indicator. This method never sends a click back to CoolAPK,
     * so it is safe to call from host callbacks, resume hooks and theme recreation.
     */
    fun getSelectedIndexForHost(): Int = selectedIndex.intValue

    fun setSelectedIndexFromHost(index: Int) {
        val max = (labelsState.value.size - 1).coerceAtLeast(0)
        val safeIndex = index.coerceIn(0, max)
        val pageChanged = lastBackdropSelectionIndex >= 0 &&
            lastBackdropSelectionIndex != safeIndex
        selectedIndex.intValue = safeIndex
        applyPageAwareBackdropSelection(safeIndex, pageChanged)
    }

    /**
     * Compass can replace CameraView2 while remaining on the same logical bottom tab. Force the
     * page-aware capture mode to be re-applied and discard any camera-backed Bitmap that belonged
     * to the previous sub-page. This is intentionally separate from setSelectedIndexFromHost(),
     * whose normal same-index path must stay cheap for the shared polling loop.
     */
    fun forceHostPageBackdropResync(index: Int = selectedIndex.intValue) {
        val max = (labelsState.value.size - 1).coerceAtLeast(0)
        val safeIndex = index.coerceIn(0, max)
        selectedIndex.intValue = safeIndex
        applyPageAwareBackdropSelection(safeIndex, false)
        if (!useCoolapkLegacyBackdropCapture || !bitmapCaptureAllowed()) return
        coolapkLegacyBackdropManager.resetForPageChange()
        if (isAttachedToWindow) {
            postOnAnimation {
                if (selectedIndex.intValue == safeIndex) refreshBackdropFrame()
            }
            postDelayed({
                if (selectedIndex.intValue == safeIndex) refreshBackdropSoon()
            }, 72L)
            postDelayed({
                if (selectedIndex.intValue == safeIndex) refreshBackdropSoon()
            }, 180L)
            // CameraView2 may publish/recreate its Surface after the tab animation has finished.
            // Keep two late one-shot captures so Level -> Compass acquires the real camera pixels.
            postDelayed({
                if (selectedIndex.intValue == safeIndex) refreshBackdropSoon()
            }, 520L)
            postDelayed({
                if (selectedIndex.intValue == safeIndex) refreshBackdropSoon()
            }, 960L)
        }
    }

    /**
     * The original bottom bar is transparent, not removed. Compose still exposes its
     * selected tab through accessibility semantics. Use that as the source of truth so
     * restored pages, back navigation and activity recreation cannot leave the lens on
     * a different item. Returns -1 when this CoolAPK build does not expose semantics.
     */
    fun detectOriginalSelectedIndex(): Int {
        hostNavigationAdapter?.let { return try { it.detectSelectedIndex() } catch (_: Throwable) { -1 } }
        val target = targetComposeView?.get() ?: return -1
        val provider = try { target.accessibilityNodeProvider } catch (_: Throwable) { null } ?: return -1
        val labels = arrayOf("首页", "数码", "发现", "我的")

        // First use text lookup. Compose often puts the selected state on a parent
        // several virtual-node levels above the text, so walk farther than before.
        for (index in labels.indices) {
            val nodes = try {
                provider.findAccessibilityNodeInfosByText(labels[index], AccessibilityNodeProvider.HOST_VIEW_ID)
            } catch (_: Throwable) {
                null
            } ?: continue
            try {
                for (node in nodes) {
                    if (node != null && isNodeOrAncestorSelected(node, 12)) return index
                }
            } finally {
                for (node in nodes) {
                    try { node?.recycle() } catch (_: Throwable) {}
                }
            }
        }

        // Some CoolAPK versions do not expose tab text as the selected semantic node.
        // Traverse the provider tree and map any selected virtual node by its X position.
        val root = try { provider.createAccessibilityNodeInfo(AccessibilityNodeProvider.HOST_VIEW_ID) } catch (_: Throwable) { null }
            ?: return -1
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < 160) {
                val node = queue.removeFirst()
                visited++
                try {
                    if (isNodeSelectedState(node)) {
                        val mapped = mapSelectedNodeByBounds(node, target)
                        if (mapped >= 0) return mapped
                    }
                    val count = try { node.childCount } catch (_: Throwable) { 0 }
                    for (i in 0 until count.coerceAtMost(24)) {
                        val child = try { node.getChild(i) } catch (_: Throwable) { null }
                        if (child != null) queue.add(child)
                    }
                } finally {
                    try { node.recycle() } catch (_: Throwable) {}
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                try { queue.removeFirst().recycle() } catch (_: Throwable) {}
            }
        }
        return -1
    }

    private fun isNodeOrAncestorSelected(start: AccessibilityNodeInfo, maxDepth: Int): Boolean {
        var node: AccessibilityNodeInfo? = start
        var ownsNode = false
        repeat(maxDepth) {
            val current = node ?: return false
            try {
                if (isNodeSelectedState(current)) return true
                val parent = try { current.parent } catch (_: Throwable) { null }
                if (ownsNode) try { current.recycle() } catch (_: Throwable) {}
                node = parent
                ownsNode = parent != null
            } catch (_: Throwable) {
                return false
            }
        }
        if (ownsNode) try { node?.recycle() } catch (_: Throwable) {}
        return false
    }

    private fun isNodeSelectedState(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isSelected || node.isChecked) return true
            val state = node.stateDescription?.toString()
            if (!state.isNullOrEmpty()) {
                val normalized = state.lowercase()
                normalized.contains("已选择") || normalized.contains("选中") ||
                    normalized.contains("selected") || normalized.contains("checked")
            } else false
        } catch (_: Throwable) {
            false
        }
    }

    private fun mapSelectedNodeByBounds(node: AccessibilityNodeInfo, target: View): Int {
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty || target.width <= 0) return -1
            val targetLoc = IntArray(2)
            target.getLocationOnScreen(targetLoc)
            val ratio = (rect.exactCenterX() - targetLoc[0]) / target.width.toFloat()
            val centers = floatArrayOf(0.10f, 0.30f, 0.70f, 0.90f)
            var best = 0
            var distance = kotlin.math.abs(ratio - centers[0])
            for (i in 1 until centers.size) {
                val d = kotlin.math.abs(ratio - centers[i])
                if (d < distance) {
                    best = i
                    distance = d
                }
            }
            if (distance <= 0.16f) best else -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun syncDynamicBackdropSource() {
        if (!bitmapCaptureAllowed() || useCoolapkLegacyBackdropCapture ||
            useWindowPixelCopyBackdropCapture
        ) return
        val next = try { hostNavigationAdapter?.backdropSourceView } catch (_: Throwable) { null }
        if (next == null || !next.isAttachedToWindow || next.width <= 0 || next.height <= 0) return
        val current = configuredBackdropSource?.get()
        if (current === next) return
        configuredBackdropSource = WeakReference(next)
        backdropSetSource(next)
    }

    private fun markBackdropDirty() {
        if (!bitmapCaptureAllowed()) return
        syncDynamicBackdropSource()
        backdropMarkDirty()
    }

    /**
     * Bitmap backdrop refresh. This does not call PixelCopy, MediaProjection or any
     * screenshot service; it only marks the reusable off-screen Bitmap as dirty.
     */
    fun refreshBackdropSoon() = markBackdropDirty()

    fun refreshBackdropFrame() = markBackdropDirty()

    /** Used only by targeted host recovery; does not trigger a capture by itself. */
    /** Scroll callbacks only flip the dirty flag; rendering happens once at pre-draw. */
    fun markBackdropMoving() {
        if (bitmapCaptureAllowed()) backdropMarkMoving()
    }

    fun markBackdropIdle() {
        if (bitmapCaptureAllowed()) backdropMarkIdle()
    }

    /** Keep the last valid glass frame while the host swaps Fragment/Surface content. */
    fun notifyHostContentTransition(settleDelayMs: Long = 120L) {
        backdropNotifyTransition(settleDelayMs)
    }

    fun finishHostContentTransition() {
        syncDynamicBackdropSource()
        backdropFinishTransition()
    }

    fun pauseBackdropCapture() {
        backdropPause()
    }

    fun startRealtimeBackdropRefresh() {
        if (bitmapCaptureAllowed()) {
            syncDynamicBackdropSource()
            backdropResume()
            refreshBackdropFrame()
        }
    }

    /** Recreate both the host capture source and Miuix backdrop objects after QQ page replacement. */
    fun rebindBackdropSource(activity: Activity? = hostActivity ?: ActivityFinder.from(context)) {
        if (!bitmapCaptureAllowed()) return
        capturedBackdropState.value = null
        backdropGenerationState.intValue = backdropGenerationState.intValue + 1
        backdropRebind(activity)
        postOnAnimation { refreshBackdropFrame() }
        postDelayed({ refreshBackdropSoon() }, 64L)
        postDelayed({ refreshBackdropSoon() }, 180L)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (glassOnlyPanelState.value) return false
        adapterSceneRoot?.get()?.let { root -> if (NativeMusicPlayerGlass.dispatchTouch(root, event)) return true }
        val actionMode = transientActionModeState.value
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeTouchSequence = if (actionMode) {
                    event.x >= 0f && event.x <= width.toFloat() &&
                        event.y >= 0f && event.y <= height.toFloat()
                } else {
                    isInsideInteractiveBar(event)
                }
                if (!activeTouchSequence) return false
                // The pressed KSU lens must magnify a current captured screen Bitmap rather than
                // falling back to the neutral no-capture surface. Pull the capture deadline forward
                // on DOWN; camera pixels still merge into the same Bitmap through Surface PixelCopy.
                if (useCoolapkLegacyBackdropCapture && bitmapCaptureAllowed()) {
                    refreshBackdropFrame()
                }
                if (pressOnlyTransparentBackdropState.value && bitmapCaptureAllowed()) {
                    // Pull a fresh Douyin window frame into the lens immediately on DOWN. PixelCopy
                    // is asynchronous, so a second/third request covers the next display frames
                    // while the 1.2x pressed animation is expanding.
                    refreshBackdropFrame()
                    postDelayed({ refreshBackdropSoon() }, 20L)
                    postDelayed({ refreshBackdropSoon() }, 44L)
                }
                if (actionMode) {
                    transientTouchIndex = actionIndexForTouch(event)
                    if (!isTransientActionEnabled(transientTouchIndex)) {
                        transientTouchIndex = -1
                    }
                    transientTouchMoved = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!activeTouchSequence) return false
                if (actionMode && transientTouchIndex >= 0) {
                    val current = actionIndexForTouch(event)
                    if (current != transientTouchIndex) transientTouchMoved = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!activeTouchSequence) return false
            }
        }

        // Let Compose render its normal pressed liquid animation first. In selection mode the
        // injected row nevertheless owns the complete gesture even when a child clickable returns
        // false, otherwise Android can deliver the same DOWN/UP to the hidden normal navigation.
        val handled = super.dispatchTouchEvent(event)
        if (actionMode) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val index = transientTouchIndex
                val sameSlot = index >= 0 && actionIndexForTouch(event) == index
                val now = SystemClock.uptimeMillis()
                val composeAlreadyDispatched = lastTransientActionIndex == index &&
                    now - lastTransientActionAt < 180L
                if (sameSlot && !transientTouchMoved && !composeAlreadyDispatched) {
                    val accepted = try { hostNavigationAdapter?.select(index) ?: false }
                    catch (_: Throwable) { false }
                    if (accepted) {
                        lastTransientActionIndex = index
                        lastTransientActionAt = now
                        refreshBackdropSoon()
                    }
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                activeTouchSequence = false
                transientTouchIndex = -1
                transientTouchMoved = false
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activeTouchSequence = false
        }
        return handled
    }

    private fun actionIndexForTouch(event: MotionEvent): Int {
        val count = labelsState.value.size
        if (count <= 0 || width <= 0) return -1
        if (transientActionModeState.value) {
            val density = resources.displayMetrics.density
            val desiredPillPx = (settingsState.value.tabWidthDp * count +
                KSU_ROW_HORIZONTAL_PADDING_DP) * density
            val pillWidthPx = desiredPillPx.coerceAtMost(width.toFloat())
            val left = (width - pillWidthPx) * 0.5f
            val right = left + pillWidthPx
            if (event.x < left || event.x > right) return -1
            val local = ((event.x - left) / pillWidthPx).coerceIn(0f, 0.999999f)
            return (local * count).toInt().coerceIn(0, count - 1)
        }
        val density = resources.displayMetrics.density
        val desiredContentPx = settingsState.value.tabWidthDp * count * density
        val pillWidthPx = (desiredContentPx + KSU_ROW_HORIZONTAL_PADDING_DP * density)
            .coerceAtMost(width.toFloat())
        val left = (width - pillWidthPx) * 0.5f
        val right = left + pillWidthPx
        if (event.x < left || event.x > right) return -1
        val local = ((event.x - left) / pillWidthPx).coerceIn(0f, 0.999999f)
        return (local * count).toInt().coerceIn(0, count - 1)
    }

    private fun isTransientActionEnabled(index: Int): Boolean {
        if (index < 0) return false
        return transientActionEnabledState.value.getOrNull(index) ?: true
    }

    /**
     * Only the visible pill is touchable. The injected host is intentionally taller than the pill
     * so the pressed liquid lens can expand upward, but that transparent layout area must not eat
     * taps intended for the application's own controls behind it.
     */
    private fun isInsideInteractiveBar(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        val density = resources.displayMetrics.density
        val settings = settingsState.value
        if (usesHyperNavigation()) return hyperNavigationLayout().contains(
            event.x / density, (height - event.y) / density - hyperNavigationLayout().bottomPaddingDp,
        )
        val labelCount = labelsState.value.size.coerceAtLeast(1)
        val separateCircleActive = separateCirclePlusState.value && labelCount >= 5
        val showCenterActionTab = !settings.showPostButton &&
            labelCount == 5 && centerActionIndexState.intValue in 0 until labelCount
        val contentDp = if (separateCircleActive) {
            settings.tabWidthDp * 4f + KSU_ROW_HORIZONTAL_PADDING_DP
        } else if (settings.showPostButton) {
            settings.tabWidthDp * labelCount + POST_SLOT_WIDTH_DP
        } else if (showCenterActionTab) {
            settings.tabWidthDp * 4f + POST_SLOT_WIDTH_DP
        } else {
            settings.tabWidthDp * labelCount
        }
        val pillWidthPx = ((contentDp + KSU_ROW_HORIZONTAL_PADDING_DP) * density)
            .coerceAtMost(width.toFloat())
        val horizontalSlop = 8f * density
        val actionMode = transientActionModeState.value
        if (actionMode) {
            // The selection overlay is a dedicated bottom host. Consume its whole rectangle so
            // no DOWN/UP sequence can ever reach 最近/浏览/云盘 behind it.
            return event.x >= 0f && event.x <= width.toFloat() &&
                event.y >= 0f && event.y <= height.toFloat()
        }
        val left = (width - pillWidthPx) * 0.5f - horizontalSlop
        var right = (width + pillWidthPx) * 0.5f + horizontalSlop
        if (separateCircleActive) {
            // The independent circular "+" floats beside the pill at the right end.
            val circlePx = (SEPARATE_CIRCLE_DIAMETER_DP * settings.iconScale.coerceIn(0.8f, 1.2f)) *
                density
            right += SEPARATE_CIRCLE_GAP_DP * density + circlePx
        }

        val barBottom = height - settings.bottomPaddingDp * density
        // 56dp pill + the reference pressed expansion and a small gesture slop. Selection mode
        // receives a slightly taller gate so every visible pressed lens remains inside our View.
        val top = barBottom - (if (actionMode) 88f else 72f) * density
        val bottom = barBottom + 8f * density
        return event.x in left..right && event.y in top..bottom
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!activeTouchSequence) return false
        return true
    }


    private fun commitSelectedIndex(index: Int) {
        val safeIndex = index.coerceIn(0, (labelsState.value.size - 1).coerceAtLeast(0))
        if (transientActionModeState.value) {
            if (!isTransientActionEnabled(safeIndex)) return
            val accepted = dispatchOriginalComposeClick(safeIndex)
            if (accepted) {
                performNavigationHaptic()
                lastTransientActionIndex = safeIndex
                lastTransientActionAt = SystemClock.uptimeMillis()
            }
            refreshBackdropSoon()
            return
        }
        val now = SystemClock.uptimeMillis()

        // FloatingBottomBar can report the same selection twice: once from the item click
        // and once again after its internal selectedIndex animation catches up.
        if (selectedIndex.intValue == safeIndex && lastDispatchedIndex == safeIndex && now - lastDispatchAt < 320L) {
            refreshBackdropSoon()
            return
        }

        val previousIndex = selectedIndex.intValue
        lastDispatchedIndex = safeIndex
        lastDispatchAt = now
        val transitionDelay = try {
            hostNavigationAdapter?.backdropTransitionDelayMs() ?: 0L
        } catch (_: Throwable) {
            0L
        }
        if (transitionDelay > 0L) {
            // Freeze only hosts whose page replacement is asynchronous. Other apps keep the
            // existing real-time path with no added delay.
            notifyHostContentTransition(transitionDelay)
        }
        val accepted = dispatchOriginalComposeClick(safeIndex)
        if (accepted) {
            performNavigationHaptic()
            setSelectedIndexFromHost(safeIndex)
            if (hostNavigationAdapter == null) {
                GlassNavInstaller.syncSelectedIndex(hostActivity ?: ActivityFinder.from(context), safeIndex)
            } else {
                refreshBackdropSoon()
                postDelayed({
                    val detected = try { hostNavigationAdapter?.detectSelectedIndex() ?: -1 } catch (_: Throwable) { -1 }
                    if (detected >= 0) setSelectedIndexFromHost(detected)
                }, 120L)
                postDelayed({ refreshBackdropSoon() }, 260L)
            }
        } else {
            // Do not leave the lens on a page CoolAPK did not actually accept.
            selectedIndex.intValue = previousIndex
            if (transitionDelay > 0L) finishHostContentTransition()
        }
    }

    private fun dispatchOriginalComposeClick(index: Int): Boolean {
        hostNavigationAdapter?.let { return try { it.select(index) } catch (_: Throwable) { false } }
        return try {
            val target = targetComposeView?.get() ?: return false
            if (target.width <= 0 || target.height <= 0) return false
            val ratios = floatArrayOf(0.10f, 0.30f, 0.70f, 0.90f)
            val ratio = ratios[index.coerceIn(0, ratios.lastIndex)]
            val x = target.width * ratio
            val y = target.height * 0.50f
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 58L, MotionEvent.ACTION_UP, x, y, 0)
            val downAccepted = try { target.dispatchTouchEvent(down) } catch (_: Throwable) { false }
            val upAccepted = try { target.dispatchTouchEvent(up) } catch (_: Throwable) { false }
            down.recycle()
            up.recycle()
            val accepted = downAccepted || upAccepted
            if (accepted) {
                refreshBackdropSoon()
                // CoolAPK often commits its route after the touch returns. Re-read the
                // original selected semantics so the glass follows the real page.
                postDelayed({ GlassNavInstaller.syncSelectionFromOriginal(hostActivity ?: ActivityFinder.from(context)) }, 80L)
                postDelayed({ GlassNavInstaller.syncSelectionFromOriginal(hostActivity ?: ActivityFinder.from(context)) }, 220L)
                postDelayed({ GlassNavInstaller.syncSelectionFromOriginal(hostActivity ?: ActivityFinder.from(context)) }, 520L)
                postDelayed({ refreshBackdropSoon() }, 160L)
                postDelayed({ markBackdropIdle() }, 420L)
            }
            accepted
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * The original CoolAPK bar has a fifth, independent post action at its center.
     * Dispatch that exact middle-slot touch without changing the selected page.
     */
    private fun dispatchOriginalPostClick(): Boolean {
        val accepted = dispatchOriginalPostClickWithoutFeedback()
        if (accepted) performNavigationHaptic()
        return accepted
    }

    private fun dispatchOriginalPostClickWithoutFeedback(): Boolean {
        hostNavigationAdapter?.let { return try { it.post() } catch (_: Throwable) { false } }
        return try {
            val target = targetComposeView?.get() ?: return false
            if (target.width <= 0 || target.height <= 0) return false
            val x = target.width * 0.50f
            val y = target.height * 0.50f
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(now, now + 58L, MotionEvent.ACTION_UP, x, y, 0)
            val downAccepted = try { target.dispatchTouchEvent(down) } catch (_: Throwable) { false }
            val upAccepted = try { target.dispatchTouchEvent(up) } catch (_: Throwable) { false }
            down.recycle()
            up.recycle()
            val accepted = downAccepted || upAccepted
            if (accepted) {
                refreshBackdropSoon()
                postDelayed({ refreshBackdropSoon() }, 160L)
                postDelayed({ markBackdropIdle() }, 360L)
            }
            accepted
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Douyin separate-circle layout maps the four ordinary tabs to slots 0..3 and reserves
 * slot 4 for the circular "+" action (host native index 2 is the publish controller).
 */
private fun separateCircleSelectedSlot(logical: Int): Int = when (logical) {
    0 -> 0
    1 -> 1
    3 -> 2
    4 -> 3
    else -> 0
}

private fun separateCircleLogicalForSlot(slot: Int): Int = when (slot) {
    0 -> 0
    1 -> 1
    2 -> 3
    3 -> 4
    else -> -1
}

private fun separateCircleLabelAt(slot: Int, labels: List<String>): String = when (slot) {
    0 -> labels.getOrNull(0) ?: ""
    1 -> labels.getOrNull(1) ?: ""
    2 -> labels.getOrNull(3) ?: ""
    3 -> labels.getOrNull(4) ?: ""
    else -> ""
}

@Composable
private fun ReferenceGlassBottomBarHost(
    navigationStyle: String,
    hyperGeometry: HyperHostGeometry,
    currentSelectedIndex: () -> Int,
    onHyperContentHeightChanged: (Float) -> Unit,
    labels: List<String>,
    navigationIcons: List<ImageBitmap?>,
    selectedIndex: Int,
    capturedBackdrop: androidx.compose.ui.graphics.ImageBitmap?,
    syntheticLiquidBackdrop: Boolean,
    pressOnlyTransparentBackdrop: Boolean,
    nativeBackdropActive: Boolean,
    selectedAccentEnabled: Boolean,
    traditionalChinese: Boolean,
    settings: GlassSettings,
    iconMode: Int,
    centerActionIndex: Int,
    nativeCenterActionExpected: Boolean,
    separateCirclePlus: Boolean,
    actionMode: Boolean,
    actionEnabled: List<Boolean>,
    onSelect: (Int) -> Unit,
    onPost: () -> Unit,
    portBackdropReady: Boolean = false,
    portBackdropDraw: ((AndroidCanvas, Float, Float) -> Unit)? = null,
    portBackdropRevision: () -> Int = { 0 },
    glassOnlyPanel: Boolean = false,
    playerHeightDp: Float = 54f,
) {
    val safeLabels = if (labels.isEmpty()) listOf("首页", "数码", "发现", "我的") else labels
    val systemDark = isSystemInDarkTheme()
    val configuredDark = when (settings.themeMode) {
        GlassSettings.THEME_LIGHT -> false
        GlassSettings.THEME_DARK -> true
        else -> systemDark
    }
    // Compass synthetic mode is intentionally a black liquid-glass material even if a global
    // light-theme preference was selected. A real camera frame can still be sampled underneath.
    val effectiveDark = syntheticLiquidBackdrop || configuredDark
    if (navigationStyle != InjectedNavigationStyle.LIQUID_GLASS && !actionMode && !glassOnlyPanel) {
        InjectedHyperBottomBar(
            style = navigationStyle, labels = safeLabels, selectedIndex = selectedIndex,
            currentSelectedIndex = currentSelectedIndex,
            viewportWidthDp = hyperGeometry.viewportWidthDp.value,
            bottomInsetDp = hyperGeometry.bottomInsetDp.value,
            standardContentHeightDp = hyperGeometry.standardContentHeightDp.value,
            onContentHeightChanged = onHyperContentHeightChanged,
            capturedBackdrop = capturedBackdrop, nativeBackdropActive = nativeBackdropActive,
            portBackdropReady = portBackdropReady, portBackdropDraw = portBackdropDraw, portBackdropRevision = portBackdropRevision,
            nativeActionIcon = navigationIcons.getOrNull(centerActionIndex),
            traditionalChinese = traditionalChinese,
            settings = settings, dark = effectiveDark, onSelect = onSelect, onPost = onPost,
            iconContent = { index, color, modifier ->
                NavIcon(
                    index = index, label = safeLabels[index], color = color,
                    nativeIcon = navigationIcons.getOrNull(index), iconMode = iconMode,
                    modifier = modifier,
                )
            },
            centerActionIndex = centerActionIndex,
            // Douyin keeps its native publish button hidden in the original container. Its
            // separate-circle adapter forwards clicks, so Compose must still draw the plus.
            nativeCenterActionExpected = nativeCenterActionExpected && !separateCirclePlus,
            separateCirclePlus = separateCirclePlus, syntheticBackdrop = syntheticLiquidBackdrop,
        )
        return
    }
    // 2.61 restores the 2.25-style combined backdrop pipeline while expanding only the render canvas. The reusable bitmap is placed
    // in an invisible source box and recorded by layerBackdrop exactly as it was in 1.20.
    val backdrop = rememberLayerBackdrop()
    // Real pixels and liquid rendering are intentionally independent. Compass 26.57 keeps
    // capture completely idle on its ordinary Compass/Level pages, but still feeds the lens a
    // synthetic dark texture so press magnification, refraction, dispersion and highlight remain.
    val liquidBackdropEnabled = portBackdropReady || capturedBackdrop != null || syntheticLiquidBackdrop || pressOnlyTransparentBackdrop
    val showPostButton = settings.showPostButton
    val showCenterActionTab = !showPostButton &&
        safeLabels.size == 5 && centerActionIndex in safeLabels.indices
    val separateCircleMode = separateCirclePlus && safeLabels.size >= 5
    val postButtonColors = rememberGlassPostButtonColors(settings)
    val postSlot = if (showPostButton) dynamicPostSlotIndex(safeLabels.size) else -1
    val slotCount = if (showPostButton) safeLabels.size + 1 else safeLabels.size
    val selectedSlot = when {
        separateCircleMode -> separateCircleSelectedSlot(selectedIndex)
        showPostButton -> logicalIndexToSlotWithPost(selectedIndex, safeLabels.size)
        showCenterActionTab && selectedIndex == centerActionIndex ->
            (centerActionIndex - 1).coerceAtLeast(0)
        else -> selectedIndex
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // The reference source uses equal weighted slots. For hosts with an independent post
        // action, size the pill from the *currently visible* navigation count plus one 48dp
        // action allowance; hidden tabs must shrink the row instead of leaving a phantom slot.
        val desiredContentDp = if (glassOnlyPanel) maxWidth.value else if (separateCircleMode) {
            settings.tabWidthDp * 4f + KSU_ROW_HORIZONTAL_PADDING_DP
        } else if (showPostButton) {
            settings.tabWidthDp * safeLabels.size + POST_SLOT_WIDTH_DP
        } else if (showCenterActionTab) {
            settings.tabWidthDp * 4f + POST_SLOT_WIDTH_DP
        } else {
            settings.tabWidthDp * safeLabels.size
        }.coerceAtLeast(1f)
        val availableContentDp = (maxWidth.value - KSU_ROW_HORIZONTAL_PADDING_DP).coerceAtLeast(1f)
        val widthScale = (availableContentDp / desiredContentDp).coerceIn(0.1f, 1f)
        val barWidth = (desiredContentDp * widthScale + KSU_ROW_HORIZONTAL_PADDING_DP).dp
        val circleDiameterDp = if (separateCircleMode) {
            SEPARATE_CIRCLE_DIAMETER_DP * settings.iconScale.coerceIn(0.8f, 1.2f)
        } else {
            0f
        }
        val circleGapDp = if (separateCircleMode) SEPARATE_CIRCLE_GAP_DP else 0f
        // Exact 1.20 source arrangement: record the captured strip through an alpha-zero Box.
        // The 1.20 OnDraw/OnPreDraw capture manager then refreshes this layer while content moves.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(maxWidth)
                .height(REFERENCE_CAPTURE_HEIGHT_DP.dp)
                .alpha(0f)
                .layerBackdrop(backdrop),
            contentAlignment = Alignment.Center,
        ) {
            if (portBackdropReady && portBackdropDraw != null) {
                Canvas(Modifier.fillMaxSize()) {
                    portBackdropRevision()
                    drawIntoCanvas { portBackdropDraw(it.nativeCanvas, size.width, size.height) }
                }
            } else if (capturedBackdrop != null) {
                Image(
                    bitmap = capturedBackdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            } else if (syntheticLiquidBackdrop) {
                // Render-only near-black texture. The small luminance variation is deliberately
                // invisible as a standalone background (the source box itself is alpha-zero), but
                // it gives the liquid lens real spatial information to bend. This produces visible
                // refraction/dispersion without taking a screenshot of Compass or Level.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF050608),
                                Color(0xFF0E1015),
                                Color(0xFF07080B),
                                Color(0xFF11131A),
                                Color(0xFF050608),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x221F2530), Color.Transparent),
                            center = Offset(size.width * 0.24f, size.height * 0.32f),
                            radius = size.height * 1.65f,
                        ),
                        radius = size.height * 1.65f,
                        center = Offset(size.width * 0.24f, size.height * 0.32f),
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0x18151A22), Color.Transparent),
                            center = Offset(size.width * 0.78f, size.height * 0.72f),
                            radius = size.height * 1.35f,
                        ),
                        radius = size.height * 1.35f,
                        center = Offset(size.width * 0.78f, size.height * 0.72f),
                    )
                }
            } else {
                // Generic hosts preserve the previous no-capture fallback behavior.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (effectiveDark) Color(0xFF202124) else Color(0xFFF2F2F2))
                )
            }
        }

        key(
            safeLabels,
            slotCount,
            showPostButton,
            showCenterActionTab,
            separateCircleMode,
            centerActionIndex,
            actionMode,
            actionEnabled,
            settings.iconOnly,
            settings.tabWidthDp,
            barWidth,
            circleDiameterDp,
        ) {
            if (glassOnlyPanel) {
                FloatingActionBottomBar(modifier = Modifier.width(maxWidth), backdrop = backdrop,
                    isBlurEnabled = liquidBackdropEnabled, isNativeBackdropEnabled = nativeBackdropActive,
                    forceDark = effectiveDark, heightDp = playerHeightDp, interactive = false) {}
            } else if (actionMode) {
                FloatingActionBottomBar(
                    modifier = Modifier
                        .padding(bottom = settings.bottomPaddingDp.dp)
                        .width(barWidth),
                    backdrop = backdrop,
                    isBlurEnabled = liquidBackdropEnabled,
                    isNativeBackdropEnabled = nativeBackdropActive,
                    forceDark = effectiveDark,
                ) {
                    safeLabels.forEachIndexed { index, label ->
                        val enabled = actionEnabled.getOrNull(index) ?: true
                        ActionBarContent(
                            index = index,
                            label = label,
                            traditionalChinese = traditionalChinese,
                            settings = settings,
                            contentColor = (if (effectiveDark) Color.White else Color(0xFF202124))
                                .copy(alpha = if (enabled) 1f else 0.36f),
                            backdrop = backdrop,
                            enabled = enabled,
                            itemBlurEnabled = liquidBackdropEnabled,
                            onClick = { onSelect(index) },
                        )
                    }
                }
            } else if (separateCircleMode) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = settings.bottomPaddingDp.dp)
                        .width(barWidth + circleGapDp.dp + circleDiameterDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FloatingBottomBar(
                        modifier = Modifier.weight(1f),
                        selectedIndex = { separateCircleSelectedSlot(selectedIndex).coerceIn(0, 3) },
                        onSelected = { slot ->
                            val logical = separateCircleLogicalForSlot(slot)
                            if (logical >= 0) onSelect(logical)
                        },
                        backdrop = backdrop,
                        tabsCount = 4,
                        isBlurEnabled = liquidBackdropEnabled,
                        isNativeBackdropEnabled = nativeBackdropActive,
                        nativeBlurRadiusPx = if (effectiveDark) {
                            (settings.blurRadiusDp * 7f).toInt().coerceIn(8, 72)
                        } else {
                            (settings.blurRadiusDp * 5.5f).toInt().coerceIn(8, 52)
                        },
                        forceDark = effectiveDark,
                        selectedAccentEnabled = selectedAccentEnabled,
                        pressOnlyLiquidBackdrop = pressOnlyTransparentBackdrop,
                iconOnlyMode = settings.iconOnly,
                    ) {
                        for (slot in 0 until 4) {
                            val logicalIndex = separateCircleLogicalForSlot(slot)
                            NavigationBarContent(
                                index = logicalIndex.coerceAtLeast(0),
                                label = separateCircleLabelAt(slot, safeLabels),
                                traditionalChinese = traditionalChinese,
                                nativeIcon = navigationIcons.getOrNull(logicalIndex),
                                settings = settings,
                                iconMode = iconMode,
                                onClick = { if (logicalIndex >= 0) onSelect(logicalIndex) },
                            )
                        }
                    }
                    Spacer(Modifier.width(circleGapDp.dp))
                    // Independent circular "+" floating beside the pill. It is a glass element:
                    // the real screen (including the video behind it) shows through the circle,
                    // either via HyperOS native blur or the captured backdrop. Taps are handled
                    // by this separate element, so the bar's drag/lens gesture can never swallow
                    // them; the actual click is dispatched to Douyin's original PublishButton.
                    val circleEnabled = centerActionIndex in safeLabels.indices
                    if (circleEnabled) {
                        GlassSeparatePostButton(effectiveDark, circleDiameterDp, onPost)
                    } else {
                        // 隐藏“发视频”时圆钮整体隐藏，只保留占位，四个页签位置不变。
                        Spacer(Modifier.width(circleDiameterDp.dp))
                    }
                }
            } else {
                FloatingBottomBar(
                modifier = Modifier
                    .padding(bottom = settings.bottomPaddingDp.dp)
                    .width(barWidth),
                selectedIndex = { selectedSlot.coerceIn(0, slotCount - 1) },
                onSelected = { slot ->
                    if (!showPostButton) {
                        // For center-action hosts, FloatingBottomBar suppresses blocked-slot
                        // releases before this callback. Ordinary slots keep normal selection.
                        onSelect(slot.coerceIn(0, safeLabels.lastIndex))
                    } else {
                        // FloatingBottomBar already redirects a drag that ends on the blocked post
                        // slot to a neighboring selectable slot. Translate the resulting physical
                        // slot back through the current visible-list model instead of assuming 4+1.
                        val logicalIndex = slotToLogicalIndexWithPost(slot, safeLabels.size)
                        if (logicalIndex >= 0) onSelect(logicalIndex)
                    }
                },
                backdrop = backdrop,
                tabsCount = slotCount,
                blockedIndex = when {
                    showPostButton -> postSlot
                    showCenterActionTab -> centerActionIndex
                    else -> -1
                },
                restoreOnBlockedIndex = showCenterActionTab,
                isBlurEnabled = liquidBackdropEnabled,
                isNativeBackdropEnabled = nativeBackdropActive,
                nativeBlurRadiusPx = if (effectiveDark) {
                    (settings.blurRadiusDp * 7f).toInt().coerceIn(8, 72)
                } else {
                    (settings.blurRadiusDp * 5.5f).toInt().coerceIn(8, 52)
                },
                forceDark = effectiveDark,
                selectedAccentEnabled = selectedAccentEnabled,
                pressOnlyLiquidBackdrop = pressOnlyTransparentBackdrop,
                iconOnlyMode = settings.iconOnly,
            ) {
                if (showPostButton) {
                    for (slot in 0 until slotCount) {
                        if (slot == postSlot) {
                            FloatingBottomBarItem(
                                onClick = onPost,
                            ) {
                                GlassPostButtonIcon(
                                    backgroundColor = postButtonColors.background,
                                    contentColor = postButtonColors.content,
                                    modifier = Modifier.size((34f * settings.iconScale).dp),
                                )
                            }
                        } else {
                            val logicalIndex = slotToLogicalIndexWithPost(slot, safeLabels.size)
                            if (logicalIndex in safeLabels.indices) {
                                val label = safeLabels[logicalIndex]
                                NavigationBarContent(
                                    index = logicalIndex,
                                    label = label,
                                    traditionalChinese = traditionalChinese,
                                    nativeIcon = navigationIcons.getOrNull(logicalIndex),
                                    settings = settings,
                                    iconMode = iconMode,
                                    onClick = { onSelect(logicalIndex) },
                                )
                            }
                        }
                    }
                } else if (showCenterActionTab) {
                    safeLabels.forEachIndexed { index, label ->
                        if (index == centerActionIndex) {
                            FloatingBottomBarItem(
                                // AI is a standalone action like CoolAPK's post button.
                                // It opens only on a direct tap and never becomes the lens target.
                                onClick = onPost,
                            ) {
                                if (nativeCenterActionExpected) {
                                    // The real host PublishButton is layered above this slot.
                                    Box(modifier = Modifier.size((34f * settings.iconScale).dp))
                                } else if (navigationIcons.getOrNull(index) != null) {
                                    NavIcon(index = index, label = label, color = postButtonColors.content,
                                        nativeIcon = navigationIcons.getOrNull(index), iconMode = iconMode,
                                        modifier = Modifier.size((34f * settings.iconScale).dp))
                                } else {
                                    AiButtonIcon(
                                        label = BottomBarLanguage.display(label, traditionalChinese),
                                        backgroundColor = postButtonColors.background,
                                        contentColor = postButtonColors.content,
                                        modifier = Modifier.size((34f * settings.iconScale).dp),
                                    )
                                }
                            }
                        } else {
                            NavigationBarContent(
                                index = index,
                                label = label,
                                traditionalChinese = traditionalChinese,
                                nativeIcon = navigationIcons.getOrNull(index),
                                settings = settings,
                                iconMode = iconMode,
                                onClick = { onSelect(index) },
                            )
                        }
                    }
                } else {
                    safeLabels.forEachIndexed { index, label ->
                        NavigationBarContent(
                            index = index,
                            label = label,
                            traditionalChinese = traditionalChinese,
                            nativeIcon = navigationIcons.getOrNull(index),
                            settings = settings,
                            iconMode = iconMode,
                            onClick = { onSelect(index) },
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ActionBarContent(
    index: Int,
    label: String,
    traditionalChinese: Boolean,
    settings: GlassSettings,
    contentColor: Color,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop,
    enabled: Boolean,
    itemBlurEnabled: Boolean,
    onClick: () -> Unit,
) {
    val displayLabel = BottomBarLanguage.display(label, traditionalChinese)
    FloatingActionBottomBarItem(
        onClick = onClick,
        backdrop = backdrop,
        enabled = enabled,
        isBlurEnabled = itemBlurEnabled,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // File-selection commands must always show their own icon and label. Do not inherit
            // the host navigation's icon-only mode or index icon family: those settings can make
            // destructive actions look blank or map them to unrelated tab glyphs.
            FileManagerActionIcon(
                index = index,
                label = label,
                color = contentColor,
                modifier = Modifier.size((25f * settings.iconScale.coerceIn(0.85f, 1.25f)).dp),
            )
            BasicText(
                text = displayLabel,
                style = androidx.compose.ui.text.TextStyle(
                    color = contentColor,
                    fontSize = settings.textSizeSp.coerceIn(10f, 14f).sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavigationBarContent(
    index: Int,
    label: String,
    traditionalChinese: Boolean,
    nativeIcon: ImageBitmap?,
    settings: GlassSettings,
    iconMode: Int,
    onClick: () -> Unit,
) {
    val displayLabel = BottomBarLanguage.display(label, traditionalChinese)
    if (label.isEmpty()) {
        FloatingBottomBarItem(onClick = {}) {
            Box(modifier = Modifier.size((24f * settings.iconScale).dp))
        }
        return
    }
    FloatingBottomBarItem(
        onClick = onClick,
    ) {
        val color = LocalContentColor.current
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val baseIconSize = if (settings.iconOnly) 28f else 24f
            NavIcon(
                index = index,
                label = label,
                color = color,
                nativeIcon = nativeIcon,
                iconMode = iconMode,
                modifier = Modifier.size((baseIconSize * settings.iconScale).dp)
            )
            if (!settings.iconOnly) {
                BasicText(
                    text = displayLabel,
                    style = androidx.compose.ui.text.TextStyle(
                        color = color,
                        fontSize = settings.textSizeSp.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun AiButtonIcon(
    label: String,
    backgroundColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension * 0.46f
            drawCircle(color = backgroundColor, radius = radius, center = center)
        }
        BasicText(
            text = label,
            style = androidx.compose.ui.text.TextStyle(
                color = contentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun FileManagerActionIcon(
    index: Int,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val stroke = 2.15f * s
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        val join = androidx.compose.ui.graphics.StrokeJoin.Round
        when (index) {
            0 -> {
                if (label.contains("解密", ignoreCase = true) ||
                    label.contains("decrypt", ignoreCase = true)
                ) {
                    // Private folder: an opened padlock, distinct from the normal Send action.
                    val shackle = Path().apply {
                        moveTo(cx - 4.8f * s, cy - 2.5f * s)
                        lineTo(cx - 4.8f * s, cy - 5.2f * s)
                        cubicTo(
                            cx - 4.8f * s,
                            cy - 9.0f * s,
                            cx - 1.8f * s,
                            cy - 10.2f * s,
                            cx + 1.0f * s,
                            cy - 10.2f * s,
                        )
                        cubicTo(
                            cx + 4.0f * s,
                            cy - 10.2f * s,
                            cx + 5.8f * s,
                            cy - 8.0f * s,
                            cx + 5.8f * s,
                            cy - 5.2f * s,
                        )
                    }
                    drawPath(
                        shackle,
                        color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = cap,
                            join = join,
                        ),
                    )
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(cx - 7.5f * s, cy - 2.8f * s),
                        size = Size(15.0f * s, 11.5f * s),
                        cornerRadius = CornerRadius(2.2f * s),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                    )
                    drawCircle(color, 1.6f * s, Offset(cx, cy + 1.2f * s))
                    drawLine(
                        color,
                        Offset(cx, cy + 2.2f * s),
                        Offset(cx, cy + 5.5f * s),
                        1.8f * s,
                        cap,
                    )
                } else {
                    // Normal selection mode: an open tray with an outgoing arrow.
                    val tray = Path().apply {
                        moveTo(cx - 8.0f * s, cy - 1.5f * s)
                        lineTo(cx - 8.0f * s, cy + 7.0f * s)
                        quadraticBezierTo(cx - 8.0f * s, cy + 8.5f * s, cx - 6.5f * s, cy + 8.5f * s)
                        lineTo(cx + 6.5f * s, cy + 8.5f * s)
                        quadraticBezierTo(cx + 8.0f * s, cy + 8.5f * s, cx + 8.0f * s, cy + 7.0f * s)
                        lineTo(cx + 8.0f * s, cy + 2.2f * s)
                    }
                    drawPath(tray, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = cap, join = join))
                    drawLine(color, Offset(cx, cy + 2.5f * s), Offset(cx, cy - 8.0f * s), stroke, cap)
                    drawLine(color, Offset(cx, cy - 8.0f * s), Offset(cx - 4.2f * s, cy - 3.8f * s), stroke, cap)
                    drawLine(color, Offset(cx, cy - 8.0f * s), Offset(cx + 4.2f * s, cy - 3.8f * s), stroke, cap)
                }
            }
            1 -> {
                // Move: folder outline with a right arrow.
                val folder = Path().apply {
                    moveTo(cx - 9.0f * s, cy - 6.2f * s)
                    lineTo(cx - 2.8f * s, cy - 6.2f * s)
                    lineTo(cx - 0.2f * s, cy - 3.6f * s)
                    lineTo(cx + 8.8f * s, cy - 3.6f * s)
                    lineTo(cx + 8.0f * s, cy + 7.2f * s)
                    lineTo(cx - 8.2f * s, cy + 7.2f * s)
                    close()
                }
                drawPath(folder, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = cap, join = join))
                drawLine(color, Offset(cx - 3.5f * s, cy + 1.2f * s), Offset(cx + 4.5f * s, cy + 1.2f * s), stroke, cap)
                drawLine(color, Offset(cx + 4.5f * s, cy + 1.2f * s), Offset(cx + 1.2f * s, cy - 2.0f * s), stroke, cap)
                drawLine(color, Offset(cx + 4.5f * s, cy + 1.2f * s), Offset(cx + 1.2f * s, cy + 4.4f * s), stroke, cap)
            }
            2 -> {
                // Delete: clear bin silhouette with lid and two slots.
                drawRoundRect(
                    color = color,
                    topLeft = Offset(cx - 6.2f * s, cy - 3.6f * s),
                    size = Size(12.4f * s, 11.5f * s),
                    cornerRadius = CornerRadius(1.8f * s),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                drawLine(color, Offset(cx - 8.0f * s, cy - 6.0f * s), Offset(cx + 8.0f * s, cy - 6.0f * s), stroke, cap)
                drawLine(color, Offset(cx - 2.8f * s, cy - 8.2f * s), Offset(cx + 2.8f * s, cy - 8.2f * s), stroke, cap)
                drawLine(color, Offset(cx - 2.2f * s, cy - 0.8f * s), Offset(cx - 2.2f * s, cy + 4.8f * s), 1.75f * s, cap)
                drawLine(color, Offset(cx + 2.2f * s, cy - 0.8f * s), Offset(cx + 2.2f * s, cy + 4.8f * s), 1.75f * s, cap)
            }
            else -> {
                // More: neutral horizontal overflow dots.
                drawCircle(color, 2.0f * s, Offset(cx - 6.3f * s, cy))
                drawCircle(color, 2.0f * s, Offset(cx, cy))
                drawCircle(color, 2.0f * s, Offset(cx + 6.3f * s, cy))
            }
        }
    }
}

@Composable
private fun NavIcon(
    index: Int,
    label: String,
    color: Color,
    nativeIcon: ImageBitmap?,
    iconMode: Int,
    modifier: Modifier = Modifier,
) {
    if (nativeIcon != null) {
        Image(
            bitmap = nativeIcon,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit,
            colorFilter = if (iconMode == 4) null else ColorFilter.tint(color),
        )
        return
    }
    if (iconMode == 3 || iconMode == 4) {
        PortedNavigationIcon(label, color, modifier)
        return
    }
    Canvas(modifier = modifier) {
        when (iconMode) {
            2 -> drawAdaptiveNavIcon(label, color)
            else -> drawDefaultNavIcon(index, color)
        }
    }
}


internal fun DrawScope.drawAdaptiveNavIcon(label: String, color: Color) {
    if (drawSupplementalNavigationIcon(label, color)) return
    val s = size.minDimension / 24f
    val cx = size.width / 2f
    val cy = size.height / 2f
    val key = label.lowercase()
    val detail = if (color.red > 0.8f && color.green > 0.8f && color.blue > 0.8f) {
        Color(0xFF303238)
    } else {
        Color.White.copy(alpha = 0.92f)
    }

    fun home() {
        val p = Path().apply {
            moveTo(cx - 8.8f * s, cy - 1f * s)
            lineTo(cx, cy - 8.8f * s)
            lineTo(cx + 8.8f * s, cy - 1f * s)
            lineTo(cx + 6.5f * s, cy - 1f * s)
            lineTo(cx + 6.5f * s, cy + 8f * s)
            lineTo(cx + 1.9f * s, cy + 8f * s)
            lineTo(cx + 1.9f * s, cy + 2.8f * s)
            lineTo(cx - 1.9f * s, cy + 2.8f * s)
            lineTo(cx - 1.9f * s, cy + 8f * s)
            lineTo(cx - 6.5f * s, cy + 8f * s)
            lineTo(cx - 6.5f * s, cy - 1f * s)
            close()
        }
        drawPath(p, color)
    }

    when {
        key.contains("我的设备") || key.contains("我的裝置") || key.contains("my device") -> {
            // UU Remote: desktop monitor + phone, matching the host's new 4.29 icon family.
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - 9.2f * s, cy - 7.2f * s),
                size = Size(13.5f * s, 11.0f * s),
                cornerRadius = CornerRadius(1.8f * s),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * s),
            )
            drawLine(color, Offset(cx - 8.0f * s, cy + 7.0f * s), Offset(cx + 1.8f * s, cy + 7.0f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 3.3f * s, cy + 3.8f * s), Offset(cx - 3.3f * s, cy + 6.2f * s), 2.0f * s)
            drawRoundRect(
                color = color,
                topLeft = Offset(cx + 1.5f * s, cy - 2.0f * s),
                size = Size(7.8f * s, 12.2f * s),
                cornerRadius = CornerRadius(1.6f * s),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * s),
            )
        }
        key.contains("云设备") || key.contains("云电脑") -> {
            // UU Remote cloud outline.
            val cloud = Path().apply {
                moveTo(cx - 8.5f * s, cy + 5.5f * s)
                cubicTo(cx - 11.0f * s, cy + 3.0f * s, cx - 9.2f * s, cy - 1.0f * s, cx - 5.8f * s, cy - 1.5f * s)
                cubicTo(cx - 5.3f * s, cy - 6.2f * s, cx - 1.8f * s, cy - 8.8f * s, cx + 2.2f * s, cy - 7.0f * s)
                cubicTo(cx + 4.0f * s, cy - 6.2f * s, cx + 5.1f * s, cy - 4.6f * s, cx + 5.4f * s, cy - 2.8f * s)
                cubicTo(cx + 10.0f * s, cy - 2.2f * s, cx + 10.8f * s, cy + 3.6f * s, cx + 7.0f * s, cy + 5.5f * s)
            }
            drawPath(cloud, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawLine(color, Offset(cx - 8.2f * s, cy + 5.5f * s), Offset(cx + 7.2f * s, cy + 5.5f * s), 2.5f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key.contains("远程协助") || key.contains("遠程協助") || key.contains("remote assist") -> {
            // UU Remote assist: two devices linked diagonally.
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - 9.2f * s, cy - 6.8f * s),
                size = Size(8.0f * s, 6.4f * s),
                cornerRadius = CornerRadius(1.6f * s),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.3f * s),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(cx + 1.2f * s, cy + 0.4f * s),
                size = Size(8.0f * s, 6.4f * s),
                cornerRadius = CornerRadius(1.6f * s),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.3f * s),
            )
            val linkA = Path().apply {
                moveTo(cx - 7.0f * s, cy + 2.0f * s)
                cubicTo(cx - 7.0f * s, cy + 7.0f * s, cx - 1.5f * s, cy + 8.0f * s, cx + 1.0f * s, cy + 5.0f * s)
            }
            val linkB = Path().apply {
                moveTo(cx + 7.0f * s, cy - 2.0f * s)
                cubicTo(cx + 7.0f * s, cy - 7.0f * s, cx + 1.5f * s, cy - 8.0f * s, cx - 1.0f * s, cy - 5.0f * s)
            }
            drawPath(linkA, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawPath(linkB, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        key.contains("发送") || key.contains("傳送") || key.contains("分享") -> {
            val r = 2.6f * s
            val left = Offset(cx - 7.2f * s, cy + 4.8f * s)
            val top = Offset(cx + 5.8f * s, cy - 6.5f * s)
            val bottom = Offset(cx + 6.5f * s, cy + 6.2f * s)
            drawLine(color, left, top, 2.1f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, left, bottom, 2.1f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(color, r, left)
            drawCircle(color, r, top)
            drawCircle(color, r, bottom)
        }
        key.contains("移动") || key.contains("移動") -> {
            val folder = Path().apply {
                moveTo(cx - 9f * s, cy - 5.8f * s)
                lineTo(cx - 2.5f * s, cy - 5.8f * s)
                lineTo(cx + 0.2f * s, cy - 3.3f * s)
                lineTo(cx + 8.7f * s, cy - 3.3f * s)
                lineTo(cx + 8.0f * s, cy + 6.8f * s)
                lineTo(cx - 8.2f * s, cy + 6.8f * s)
                close()
            }
            drawPath(folder, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.0f * s, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawLine(color, Offset(cx - 3.5f * s, cy + 1.0f * s), Offset(cx + 4.2f * s, cy + 1.0f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx + 1.0f * s, cy - 1.8f * s), Offset(cx + 4.2f * s, cy + 1.0f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx + 1.0f * s, cy + 3.8f * s), Offset(cx + 4.2f * s, cy + 1.0f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key.contains("删除") || key.contains("刪除") -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - 6.2f * s, cy - 3.8f * s),
                size = Size(12.4f * s, 11.2f * s),
                cornerRadius = CornerRadius(1.8f * s),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.1f * s),
            )
            drawLine(color, Offset(cx - 8.0f * s, cy - 6.2f * s), Offset(cx + 8.0f * s, cy - 6.2f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 2.8f * s, cy - 8.3f * s), Offset(cx + 2.8f * s, cy - 8.3f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 2.2f * s, cy - 1.0f * s), Offset(cx - 2.2f * s, cy + 4.6f * s), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx + 2.2f * s, cy - 1.0f * s), Offset(cx + 2.2f * s, cy + 4.6f * s), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key.contains("更多") || key == "more" -> {
            drawCircle(color, 2.0f * s, Offset(cx - 6.2f * s, cy))
            drawCircle(color, 2.0f * s, Offset(cx, cy))
            drawCircle(color, 2.0f * s, Offset(cx + 6.2f * s, cy))
        }
        key.contains("最近") -> {
            drawCircle(
                color,
                8.2f * s,
                Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * s),
            )
            drawLine(color, Offset(cx, cy), Offset(cx, cy - 5.2f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx, cy), Offset(cx + 4.4f * s, cy + 2.8f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key.contains("浏览") || key == "文件" -> {
            val folder = Path().apply {
                moveTo(cx - 9f * s, cy - 6f * s)
                lineTo(cx - 2f * s, cy - 6f * s)
                lineTo(cx + 0.5f * s, cy - 3.5f * s)
                lineTo(cx + 9f * s, cy - 3.5f * s)
                lineTo(cx + 8f * s, cy + 7f * s)
                lineTo(cx - 8f * s, cy + 7f * s)
                close()
            }
            drawPath(folder, color)
        }
        key.contains("云盘") || key.contains("雲盤") -> {
            val cloud = Path().apply {
                moveTo(cx - 8.5f * s, cy + 6f * s)
                cubicTo(cx - 11f * s, cy + 3f * s, cx - 9f * s, cy - 1f * s, cx - 5.5f * s, cy - 1.5f * s)
                cubicTo(cx - 4.5f * s, cy - 7f * s, cx + 2f * s, cy - 8f * s, cx + 5f * s, cy - 3f * s)
                cubicTo(cx + 10f * s, cy - 3f * s, cx + 11f * s, cy + 3f * s, cx + 7.5f * s, cy + 6f * s)
                close()
            }
            drawPath(cloud, color)
        }
        key.contains("通话") || key.contains("通話") -> {
            val receiver = Path().apply {
                moveTo(cx - 8.7f * s, cy - 7.3f * s)
                cubicTo(cx - 10.5f * s, cy - 2f * s, cx - 5.2f * s, cy + 5.2f * s, cx + 1.2f * s, cy + 8f * s)
                cubicTo(cx + 4.4f * s, cy + 9.4f * s, cx + 7.7f * s, cy + 8.2f * s, cx + 9f * s, cy + 5f * s)
                lineTo(cx + 4.2f * s, cy + 1.7f * s)
                lineTo(cx + 1.2f * s, cy + 4.5f * s)
                cubicTo(cx - 2.3f * s, cy + 2.8f * s, cx - 4.7f * s, cy + 0.2f * s, cx - 6f * s, cy - 3f * s)
                lineTo(cx - 3.2f * s, cy - 5.8f * s)
                close()
            }
            drawPath(receiver, color)
        }
        key.contains("联系人") || key.contains("聯絡人")
                || key.contains("通讯录") || key.contains("通訊錄") -> {
            drawCircle(color, 3.4f * s, Offset(cx - 3.2f * s, cy - 4.5f * s))
            drawCircle(color.copy(alpha = 0.78f), 2.8f * s, Offset(cx + 5.3f * s, cy - 2.8f * s))
            drawRoundRect(color, Offset(cx - 9f * s, cy + 0.3f * s), Size(12f * s, 8.4f * s), CornerRadius(4.2f * s))
            drawRoundRect(color.copy(alpha = 0.78f), Offset(cx + 2f * s, cy + 1.5f * s), Size(7.5f * s, 6.8f * s), CornerRadius(3.4f * s))
        }
        key == "主要" -> {
            drawRoundRect(color, Offset(cx - 9f * s, cy - 7f * s), Size(18f * s, 13f * s), CornerRadius(4f * s))
            val tail = Path().apply {
                moveTo(cx - 4f * s, cy + 5f * s)
                lineTo(cx - 7f * s, cy + 9f * s)
                lineTo(cx + 1f * s, cy + 6f * s)
                close()
            }
            drawPath(tail, color)
        }
        key.contains("推广") || key.contains("推廣") -> {
            val horn = Path().apply {
                moveTo(cx - 8.5f * s, cy - 3.5f * s)
                lineTo(cx + 4.5f * s, cy - 8f * s)
                lineTo(cx + 4.5f * s, cy + 8f * s)
                lineTo(cx - 8.5f * s, cy + 3.5f * s)
                close()
            }
            drawPath(horn, color)
            drawLine(color, Offset(cx - 5f * s, cy + 3.5f * s), Offset(cx - 2.5f * s, cy + 8.5f * s), 2.5f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx + 8f * s, cy - 4f * s), Offset(cx + 10f * s, cy - 6f * s), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx + 8f * s, cy + 4f * s), Offset(cx + 10f * s, cy + 6f * s), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key == "年" || key == "月" || key == "周" || key == "日" -> {
            drawRoundRect(
                color,
                Offset(cx - 8.5f * s, cy - 8f * s),
                Size(17f * s, 16f * s),
                CornerRadius(2.6f * s),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.1f * s),
            )
            drawLine(color, Offset(cx - 7f * s, cy - 3.5f * s), Offset(cx + 7f * s, cy - 3.5f * s), 2f * s)
            when (key) {
                "年" -> {
                    for (x in listOf(-3.8f, 3.8f)) for (y in listOf(0.2f, 4.8f)) {
                        drawCircle(color, 1.35f * s, Offset(cx + x * s, cy + y * s))
                    }
                }
                "月" -> {
                    for (row in 0..1) for (column in 0..2) {
                        drawCircle(color, 1.1f * s, Offset(cx + (column - 1) * 4.2f * s, cy + (row * 4.3f + 0.2f) * s))
                    }
                }
                "周" -> {
                    for (column in -3..3) {
                        drawCircle(color, 0.9f * s, Offset(cx + column * 2.2f * s, cy + 2.7f * s))
                    }
                }
                else -> drawCircle(color, 2.4f * s, Offset(cx, cy + 2.4f * s))
            }
        }
        key.contains("照片") -> {
            drawRoundRect(
                color,
                Offset(cx - 9f * s, cy - 8f * s),
                Size(18f * s, 16f * s),
                CornerRadius(2.5f * s),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.1f * s),
            )
            drawCircle(color, 2f * s, Offset(cx + 4f * s, cy - 3.5f * s))
            val mountain = Path().apply {
                moveTo(cx - 7f * s, cy + 5.5f * s)
                lineTo(cx - 2f * s, cy)
                lineTo(cx + 1f * s, cy + 3f * s)
                lineTo(cx + 4f * s, cy)
                lineTo(cx + 7f * s, cy + 5.5f * s)
            }
            drawPath(mountain, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        key.contains("相册") || key.contains("相簿") || key.contains("影集") -> {
            drawRoundRect(color.copy(alpha = 0.55f), Offset(cx - 6f * s, cy - 9f * s), Size(14f * s, 13f * s), CornerRadius(2f * s))
            drawRoundRect(color, Offset(cx - 9f * s, cy - 5f * s), Size(16f * s, 14f * s), CornerRadius(2.5f * s))
            drawCircle(detail, 1.8f * s, Offset(cx + 2.8f * s, cy - 1f * s))
            val mountain = Path().apply {
                moveTo(cx - 6.5f * s, cy + 6f * s)
                lineTo(cx - 2f * s, cy + 1f * s)
                lineTo(cx + 1f * s, cy + 4f * s)
                lineTo(cx + 5f * s, cy + 0.5f * s)
                lineTo(cx + 6f * s, cy + 6f * s)
                close()
            }
            drawPath(mountain, detail)
        }
        key.contains("待办") || key.contains("待辦") -> {
            drawRoundRect(
                color,
                Offset(cx - 9f * s, cy - 7f * s),
                Size(6f * s, 6f * s),
                CornerRadius(1.4f * s),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * s),
            )
            drawLine(color, Offset(cx - 7.8f * s, cy - 3.8f * s), Offset(cx - 5.8f * s, cy - 1.8f * s), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 5.8f * s, cy - 1.8f * s), Offset(cx - 2.2f * s, cy - 6.2f * s), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx, cy - 3.8f * s), Offset(cx + 9f * s, cy - 3.8f * s), 2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawRoundRect(color, Offset(cx - 9f * s, cy + 2f * s), Size(6f * s, 6f * s), CornerRadius(1.4f * s), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * s))
            drawLine(color, Offset(cx, cy + 5f * s), Offset(cx + 9f * s, cy + 5f * s), 2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key.contains("超级用户") || key.contains("superuser") -> {
            // Magisk SU fallback: shield + check, drawn directly in the current Canvas space.
            val shield = Path().apply {
                moveTo(cx, cy - 9.5f * s)
                lineTo(cx + 7.5f * s, cy - 6.3f * s)
                lineTo(cx + 6.8f * s, cy + 1.1f * s)
                cubicTo(
                    cx + 6.3f * s, cy + 5.5f * s,
                    cx + 3.2f * s, cy + 8.5f * s,
                    cx, cy + 10f * s,
                )
                cubicTo(
                    cx - 3.2f * s, cy + 8.5f * s,
                    cx - 6.3f * s, cy + 5.5f * s,
                    cx - 6.8f * s, cy + 1.1f * s,
                )
                lineTo(cx - 7.5f * s, cy - 6.3f * s)
                close()
            }
            drawPath(
                shield,
                color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2f * s,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
            drawLine(
                color,
                Offset(cx - 3f * s, cy),
                Offset(cx - 0.7f * s, cy + 2.3f * s),
                2f * s,
                androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawLine(
                color,
                Offset(cx - 0.7f * s, cy + 2.3f * s),
                Offset(cx + 4f * s, cy - 2.7f * s),
                2f * s,
                androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        key == "日志" || key.contains("logs") -> {
            drawRoundRect(
                color,
                Offset(cx - 7f * s, cy - 9f * s),
                Size(14f * s, 18f * s),
                CornerRadius(2f * s),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * s),
            )
            drawLine(color, Offset(cx - 4f * s, cy - 4f * s), Offset(cx + 4f * s, cy - 4f * s), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 4f * s, cy), Offset(cx + 4f * s, cy), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 4f * s, cy + 4f * s), Offset(cx + 2f * s, cy + 4f * s), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key == "模块" || key.contains("modules") -> {
            val block = 6.3f * s
            val radius = CornerRadius(1.5f * s)
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.9f * s)
            drawRoundRect(color, Offset(cx - 8f * s, cy - 8f * s), Size(block, block), radius, style = stroke)
            drawRoundRect(color, Offset(cx + 1.7f * s, cy - 8f * s), Size(block, block), radius, style = stroke)
            drawRoundRect(color, Offset(cx - 8f * s, cy + 1.7f * s), Size(block, block), radius, style = stroke)
            drawRoundRect(color, Offset(cx + 1.7f * s, cy + 1.7f * s), Size(block, block), radius, style = stroke)
        }
        key == "inbox" || key.contains("收件箱") -> {
            // GitHub Inbox: open tray with the centered receive notch.
            val tray = Path().apply {
                moveTo(cx - 8.5f * s, cy - 6.5f * s)
                lineTo(cx + 8.5f * s, cy - 6.5f * s)
                lineTo(cx + 9.2f * s, cy + 7.0f * s)
                quadraticTo(cx + 9.2f * s, cy + 8.5f * s, cx + 7.2f * s, cy + 8.5f * s)
                lineTo(cx - 7.2f * s, cy + 8.5f * s)
                quadraticTo(cx - 9.2f * s, cy + 8.5f * s, cx - 9.2f * s, cy + 7.0f * s)
                close()
            }
            drawPath(
                tray,
                color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.2f * s,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
            val notch = Path().apply {
                moveTo(cx - 4.2f * s, cy + 1.2f * s)
                quadraticTo(cx - 2.8f * s, cy + 4.2f * s, cx, cy + 4.2f * s)
                quadraticTo(cx + 2.8f * s, cy + 4.2f * s, cx + 4.2f * s, cy + 1.2f * s)
            }
            drawPath(
                notch,
                color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.2f * s,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
        }
        key == "探索" || key == "explore" -> {
            // GitHub Explore uses a telescope rather than the generic discovery compass.
            val tube = Path().apply {
                moveTo(cx - 8.5f * s, cy - 5.6f * s)
                lineTo(cx + 5.0f * s, cy - 8.2f * s)
                lineTo(cx + 7.2f * s, cy - 2.8f * s)
                lineTo(cx - 5.8f * s, cy + 1.0f * s)
                close()
            }
            drawPath(
                tube,
                color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.1f * s,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
            drawLine(color, Offset(cx - 7.8f * s, cy - 6.0f * s), Offset(cx - 9.3f * s, cy - 1.6f * s), 2.3f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(color, 2.0f * s, Offset(cx + 0.8f * s, cy + 0.8f * s))
            drawLine(color, Offset(cx + 0.8f * s, cy + 2.0f * s), Offset(cx - 4.8f * s, cy + 8.6f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx + 0.8f * s, cy + 2.0f * s), Offset(cx + 6.6f * s, cy + 8.6f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key == "copilot" -> {
            // GitHub Copilot: paired lenses, bridge and the compact robot-head outline.
            val head = Path().apply {
                moveTo(cx - 8.8f * s, cy - 2.8f * s)
                cubicTo(cx - 8.8f * s, cy - 7.2f * s, cx - 4.8f * s, cy - 9.0f * s, cx, cy - 7.0f * s)
                cubicTo(cx + 4.8f * s, cy - 9.0f * s, cx + 8.8f * s, cy - 7.2f * s, cx + 8.8f * s, cy - 2.8f * s)
                lineTo(cx + 7.2f * s, cy + 6.6f * s)
                quadraticTo(cx + 4.0f * s, cy + 9.0f * s, cx, cy + 9.0f * s)
                quadraticTo(cx - 4.0f * s, cy + 9.0f * s, cx - 7.2f * s, cy + 6.6f * s)
                close()
            }
            drawPath(
                head,
                color,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 2.0f * s,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
            drawCircle(color = color, radius = 3.2f * s, center = Offset(cx - 4.0f * s, cy - 0.5f * s), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.0f * s))
            drawCircle(color = color, radius = 3.2f * s, center = Offset(cx + 4.0f * s, cy - 0.5f * s), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.0f * s))
            drawLine(color, Offset(cx - 0.8f * s, cy - 0.5f * s), Offset(cx + 0.8f * s, cy - 0.5f * s), 2.0f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 3.8f * s, cy + 5.0f * s), Offset(cx + 3.8f * s, cy + 5.0f * s), 1.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        // Xiaomi Fitness uses four independent destinations rather than a generic home row.
        // Keep these glyphs label-driven so they continue to work when the host's own drawables
        // are unavailable to the isolated Compose resource context.
        key == "健康" || key.contains("health") -> {
            // Heart with a small pulse notch.
            val heart = Path().apply {
                moveTo(cx, cy + 8.8f * s)
                cubicTo(cx - 2.0f * s, cy + 6.8f * s, cx - 9.0f * s, cy + 1.2f * s, cx - 9.0f * s, cy - 3.0f * s)
                cubicTo(cx - 9.0f * s, cy - 7.0f * s, cx - 4.0f * s, cy - 9.0f * s, cx, cy - 5.0f * s)
                cubicTo(cx + 4.0f * s, cy - 9.0f * s, cx + 9.0f * s, cy - 7.0f * s, cx + 9.0f * s, cy - 3.0f * s)
                cubicTo(cx + 9.0f * s, cy + 1.2f * s, cx + 2.0f * s, cy + 6.8f * s, cx, cy + 8.8f * s)
                close()
            }
            drawPath(heart, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.1f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            val pulse = Path().apply {
                moveTo(cx - 6.5f * s, cy + 0.7f * s)
                lineTo(cx - 3.2f * s, cy + 0.7f * s)
                lineTo(cx - 1.5f * s, cy - 2.6f * s)
                lineTo(cx + 1.0f * s, cy + 3.0f * s)
                lineTo(cx + 2.8f * s, cy + 0.7f * s)
                lineTo(cx + 6.5f * s, cy + 0.7f * s)
            }
            drawPath(pulse, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        key == "运动" || key.contains("sport") || key.contains("exercise") -> {
            // Running figure, intentionally compact to match the host's tab proportions.
            drawCircle(color, 2.6f * s, Offset(cx + 3.0f * s, cy - 7.0f * s))
            drawLine(color, Offset(cx + 1.0f * s, cy - 3.6f * s), Offset(cx - 2.0f * s, cy + 1.0f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 1.5f * s, cy - 1.0f * s), Offset(cx + 4.8f * s, cy + 1.8f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 1.8f * s, cy + 0.7f * s), Offset(cx - 7.2f * s, cy + 5.8f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 1.8f * s, cy + 0.7f * s), Offset(cx + 1.8f * s, cy + 7.7f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 7.2f * s, cy + 5.8f * s), Offset(cx - 9.0f * s, cy + 5.8f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx + 1.8f * s, cy + 7.7f * s), Offset(cx + 5.8f * s, cy + 7.7f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key == "设备" || key.contains("device") || key.contains("devices") -> {
            // Watch/device outline with a centered screen detail.
            drawRoundRect(color, Offset(cx - 6.8f * s, cy - 7.0f * s), Size(13.6f * s, 14.0f * s), CornerRadius(3.0f * s), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.1f * s))
            drawLine(color, Offset(cx - 3.5f * s, cy - 10.0f * s), Offset(cx + 3.5f * s, cy - 10.0f * s), 2.0f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, Offset(cx - 3.5f * s, cy + 10.0f * s), Offset(cx + 3.5f * s, cy + 10.0f * s), 2.0f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawRoundRect(detail, Offset(cx - 3.5f * s, cy - 3.2f * s), Size(7.0f * s, 5.8f * s), CornerRadius(1.2f * s), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.4f * s))
        }
        key.contains("首页") || key == "推荐" || key.contains("主页") || key == "米家" || key.contains("闲鱼") || key.contains("智控") -> home()
        key.contains("朋友") || key.contains("friends") -> {
            // Douyin friends: two-person silhouette, distinct from Home and Profile.
            drawCircle(color, 3.5f * s, Offset(cx - 3.5f * s, cy - 4.5f * s))
            drawCircle(color.copy(alpha = 0.82f), 3.0f * s, Offset(cx + 4.2f * s, cy - 3.2f * s))
            drawRoundRect(
                color,
                Offset(cx - 9.0f * s, cy + 1.0f * s),
                Size(11.5f * s, 8.0f * s),
                CornerRadius(4.0f * s),
            )
            drawRoundRect(
                color.copy(alpha = 0.82f),
                Offset(cx + 0.5f * s, cy + 1.8f * s),
                Size(8.5f * s, 6.8f * s),
                CornerRadius(3.4f * s),
            )
        }
        key == "我" || key.contains("我的") || key.contains("個人資料")
                || key.contains("mine") || key.contains("profile") -> {
            drawCircle(color, 4f * s, Offset(cx, cy - 4.8f * s))
            drawRoundRect(color, Offset(cx - 8f * s, cy + 1.6f * s), Size(16f * s, 10f * s), CornerRadius(5f * s))
        }
        key.contains("设置") -> {
            // Match the settings/sliders glyph used by LiquidGlass' own settings page.
            val stroke = 2.04f * s
            val ys = floatArrayOf(cy - 6.0f * s, cy, cy + 6.0f * s)
            val knobs = floatArrayOf(cx + 3.84f * s, cx - 3.84f * s, cx + 2.88f * s)
            for (i in ys.indices) {
                drawLine(
                    color,
                    Offset(cx - 9.12f * s, ys[i]),
                    Offset(cx + 9.12f * s, ys[i]),
                    stroke,
                    androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawCircle(color, stroke * 1.35f, Offset(knobs[i], ys[i]))
            }
        }
        key.contains("新发现") || key == "new" -> {
            val star = Path().apply {
                moveTo(cx, cy - 9f * s)
                lineTo(cx + 2.5f * s, cy - 2.8f * s)
                lineTo(cx + 9f * s, cy - 2.5f * s)
                lineTo(cx + 4f * s, cy + 1.8f * s)
                lineTo(cx + 5.8f * s, cy + 8.2f * s)
                lineTo(cx, cy + 4.6f * s)
                lineTo(cx - 5.8f * s, cy + 8.2f * s)
                lineTo(cx - 4f * s, cy + 1.8f * s)
                lineTo(cx - 9f * s, cy - 2.5f * s)
                lineTo(cx - 2.5f * s, cy - 2.8f * s)
                close()
            }
            drawPath(star, color)
        }
        key.contains("广播") || key.contains("廣播") || key == "radio" -> {
            drawCircle(color, 2.5f * s, Offset(cx, cy + 2f * s))
            drawArc(color, 205f, 130f, false, Offset(cx - 6f * s, cy - 4f * s), Size(12f * s, 12f * s), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawArc(color, 210f, 120f, false, Offset(cx - 10f * s, cy - 8f * s), Size(20f * s, 20f * s), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        key.contains("资料库") || key.contains("資料庫") || key == "library" -> {
            drawRoundRect(color, Offset(cx - 8.5f * s, cy - 8f * s), Size(5f * s, 16f * s), CornerRadius(1.2f * s))
            drawRoundRect(color, Offset(cx - 2.5f * s, cy - 8f * s), Size(5f * s, 16f * s), CornerRadius(1.2f * s))
            val book = Path().apply {
                moveTo(cx + 4f * s, cy - 8f * s)
                lineTo(cx + 8.5f * s, cy - 6.5f * s)
                lineTo(cx + 5f * s, cy + 8f * s)
                lineTo(cx + 0.5f * s, cy + 6.5f * s)
                close()
            }
            drawPath(book, color)
        }
        key.contains("搜索") -> {
            drawCircle(color = color, radius = 6.6f * s, center = Offset(cx - 2f * s, cy - 2f * s), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.6f * s))
            drawLine(color, Offset(cx + 3f * s, cy + 3f * s), Offset(cx + 8f * s, cy + 8f * s), 2.8f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key.contains("消息") || key.contains("微信") || key == "chats" -> {
            drawRoundRect(color, Offset(cx - 9f * s, cy - 7f * s), Size(18f * s, 13f * s), CornerRadius(4.5f * s))
            val tail = Path().apply { moveTo(cx - 4f * s, cy + 5f * s); lineTo(cx - 7f * s, cy + 9f * s); lineTo(cx + 1f * s, cy + 6f * s); close() }
            drawPath(tail, color)
        }
        key.contains("购物车") || key.contains("商城") || key.contains("会员购") || key.contains("市集") -> {
            drawLine(color, Offset(cx - 9f * s, cy - 7f * s), Offset(cx - 6f * s, cy - 7f * s), 2.2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            val cart = Path().apply { moveTo(cx - 6f * s, cy - 6f * s); lineTo(cx - 4f * s, cy + 3f * s); lineTo(cx + 7f * s, cy + 3f * s); lineTo(cx + 9f * s, cy - 4f * s); close() }
            drawPath(cart, color)
            drawCircle(color, 1.8f * s, Offset(cx - 2f * s, cy + 7f * s)); drawCircle(color, 1.8f * s, Offset(cx + 6f * s, cy + 7f * s))
        }
        key.contains("订单") -> {
            drawRoundRect(color, Offset(cx - 7f * s, cy - 9f * s), Size(14f * s, 18f * s), CornerRadius(2.5f * s))
            for (dy in listOf(-4f, 0f, 4f)) drawLine(detail, Offset(cx - 4f * s, cy + dy * s), Offset(cx + 4f * s, cy + dy * s), 1.5f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key.contains("笔记") || key.contains("产品信息") || key == "产品" -> {
            drawRoundRect(color, Offset(cx - 7.5f * s, cy - 9f * s), Size(15f * s, 18f * s), CornerRadius(2.5f * s))
            drawLine(detail, Offset(cx - 4f * s, cy - 4f * s), Offset(cx + 4f * s, cy - 4f * s), 1.4f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(detail, Offset(cx - 4f * s, cy), Offset(cx + 4f * s, cy), 1.4f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(detail, Offset(cx - 4f * s, cy + 4f * s), Offset(cx + 2f * s, cy + 4f * s), 1.4f * s, androidx.compose.ui.graphics.StrokeCap.Round)
        }
        key.contains("视频") || key.contains("直播") -> {
            drawRoundRect(color, Offset(cx - 9f * s, cy - 7f * s), Size(18f * s, 14f * s), CornerRadius(3.5f * s))
            val play = Path().apply { moveTo(cx - 2f * s, cy - 4f * s); lineTo(cx + 5f * s, cy); lineTo(cx - 2f * s, cy + 4f * s); close() }
            drawPath(play, detail)
        }
        key == "发现" || key == "發現" || key.contains("关注") || key.contains("动态")
                || key.contains("地区") || key.contains("逛") -> {
            drawCircle(color = color, radius = 8.4f * s, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.1f * s))
            val needle = Path().apply { moveTo(cx + 4.5f * s, cy - 5.5f * s); lineTo(cx + 1f * s, cy + 2f * s); lineTo(cx - 5f * s, cy + 5f * s); lineTo(cx - 1f * s, cy - 2f * s); close() }
            drawPath(needle, color)
        }
        key.contains("神券") || key.contains("券") -> {
            val ticket = Path().apply {
                moveTo(cx - 9f * s, cy - 6f * s); lineTo(cx + 9f * s, cy - 6f * s); lineTo(cx + 9f * s, cy - 2f * s)
                cubicTo(cx + 5f * s, cy - 2f * s, cx + 5f * s, cy + 2f * s, cx + 9f * s, cy + 2f * s)
                lineTo(cx + 9f * s, cy + 6f * s); lineTo(cx - 9f * s, cy + 6f * s); lineTo(cx - 9f * s, cy + 2f * s)
                cubicTo(cx - 5f * s, cy + 2f * s, cx - 5f * s, cy - 2f * s, cx - 9f * s, cy - 2f * s); close()
            }
            drawPath(ticket, color)
            drawLine(detail, Offset(cx, cy - 4f * s), Offset(cx, cy + 4f * s), 1.4f * s)
        }
        key.contains("智能") || key.contains("ai") -> {
            drawCircle(color, 8.5f * s, Offset(cx, cy))
            val spark = Path().apply { moveTo(cx, cy - 6f * s); lineTo(cx + 2f * s, cy - 2f * s); lineTo(cx + 6f * s, cy); lineTo(cx + 2f * s, cy + 2f * s); lineTo(cx, cy + 6f * s); lineTo(cx - 2f * s, cy + 2f * s); lineTo(cx - 6f * s, cy); lineTo(cx - 2f * s, cy - 2f * s); close() }
            drawPath(spark, detail)
        }
        key.contains("服务") -> {
            drawCircle(color = color, radius = 8.5f * s, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * s))
            drawCircle(color, 2.2f * s, Offset(cx, cy))
            drawLine(color, Offset(cx, cy - 8f * s), Offset(cx, cy - 4f * s), 2f * s)
            drawLine(color, Offset(cx, cy + 4f * s), Offset(cx, cy + 8f * s), 2f * s)
            drawLine(color, Offset(cx - 8f * s, cy), Offset(cx - 4f * s, cy), 2f * s)
            drawLine(color, Offset(cx + 4f * s, cy), Offset(cx + 8f * s, cy), 2f * s)
        }
        else -> home()
    }
}
