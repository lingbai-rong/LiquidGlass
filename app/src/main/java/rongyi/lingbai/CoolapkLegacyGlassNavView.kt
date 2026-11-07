package rongyi.lingbai

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import rongyi.lingbai.glass.FloatingBottomBar
import rongyi.lingbai.glass.FloatingBottomBarItem
import rongyi.lingbai.glass.GlassTheme
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val LEGACY_KSU_ROW_HORIZONTAL_PADDING_DP = 8f
private const val LEGACY_POST_SLOT_WIDTH_DP = 48f
private const val LEGACY_REFERENCE_CAPTURE_HEIGHT_DP = 84f

private class CoolapkNativePostMirrorView(
    context: Context,
    private val settingsProvider: () -> GlassSettings,
    private val fallbackPostClick: () -> Boolean,
    private val onNativePostAccepted: () -> Unit,
) : View(context) {
    private var targetRef: WeakReference<View>? = null
    private var activeTouch = false
    private var targetAcceptedTouch = false
    private var animateUntil = 0L
    private val clipPath = android.graphics.Path()

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = null
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    fun setTarget(view: View?) {
        targetRef = if (view == null) null else WeakReference(view)
        invalidate()
    }

    private fun buttonCenterY(settings: GlassSettings): Float {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        // FloatingBottomBar's visible pill is 64dp high. The native CoolAPK action is
        // mirrored into the exact center slot of that pill, while bottomPadding remains
        // owned by the module setting.
        return height - settings.bottomPaddingDp * density - 32f * density
    }

    private fun hitWidthRadiusPx(): Float = 34f * resources.displayMetrics.density.coerceAtLeast(1f)
    private fun hitHeightRadiusPx(): Float = 22f * resources.displayMetrics.density.coerceAtLeast(1f)

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val settings = settingsProvider()
        if (!settings.showPostButton || width <= 0 || height <= 0) return
        // Keep this overlay only as a touch forwarder to CoolAPK's hidden native
        // post button. The visible center button is now fully drawn by our own
        // compose source using the same click path, so we intentionally do not draw
        // the host button here.
        if (activeTouch || SystemClock.uptimeMillis() < animateUntil) {
            postInvalidateOnAnimation()
        }
    }

    private fun isInsideNativeButton(x: Float, y: Float): Boolean {
        val settings = settingsProvider()
        if (!settings.showPostButton) return false
        val dx = x - width * 0.5f
        val dy = y - buttonCenterY(settings)
        val rx = hitWidthRadiusPx()
        val ry = hitHeightRadiusPx()
        val nx = dx / rx
        val ny = dy / ry
        return nx * nx + ny * ny <= 1f
    }

    private fun dispatchToOriginal(event: MotionEvent): Boolean {
        val target = targetRef?.get() ?: return false
        if (target.width <= 0 || target.height <= 0) return false
        val settings = settingsProvider()
        val centerX = width * 0.5f
        val centerY = buttonCenterY(settings)
        val mapped = MotionEvent.obtain(event)
        mapped.setLocation(
            target.width * 0.5f + (event.x - centerX),
            target.height * 0.5f + (event.y - centerY),
        )
        return try {
            target.dispatchTouchEvent(mapped)
        } catch (_: Throwable) {
            false
        } finally {
            mapped.recycle()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isInsideNativeButton(event.x, event.y)) return false
                activeTouch = true
                targetAcceptedTouch = dispatchToOriginal(event)
                animateUntil = SystemClock.uptimeMillis() + 420L
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!activeTouch) return false
                targetAcceptedTouch = dispatchToOriginal(event) || targetAcceptedTouch
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!activeTouch) return false
                targetAcceptedTouch = dispatchToOriginal(event) || targetAcceptedTouch
                if (!targetAcceptedTouch) fallbackPostClick()
                else if (isInsideNativeButton(event.x, event.y)) onNativePostAccepted()
                activeTouch = false
                animateUntil = SystemClock.uptimeMillis() + 420L
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!activeTouch) return false
                dispatchToOriginal(event)
                activeTouch = false
                targetAcceptedTouch = false
                animateUntil = SystemClock.uptimeMillis() + 180L
                postInvalidateOnAnimation()
                return true
            }
        }
        return false
    }
}

/**
 * The injected navigation view is only visual/touch UI. Keep it out of the
 * host app accessibility tree so MIUI screenshot/OCR will not scan the internal
 * Compose virtual nodes. ComposeView is final in current AndroidX, so we do not
 * subclass it; the parent FrameLayout hides descendants instead.
 */
class CoolapkLegacyGlassNavView(context: Context) : FrameLayout(context), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
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
    private val nativeBlurView = NativeBlurPillView(context)
    private val nativeBackdropActiveState = mutableStateOf(false)
    private val composeView = ComposeView(context)
    private var targetComposeView: WeakReference<View>? = null
    private var activeTouchSequence: Boolean = false
    private val selectedIndex = mutableIntStateOf(0)
    private val labelsState = mutableStateOf(listOf("首页", "数码", "发现", "我的"))
    private val settingsState = mutableStateOf(GlassSettings.defaults())
    private val nativePostMirrorView = CoolapkNativePostMirrorView(
        context = context,
        settingsProvider = { settingsState.value },
        fallbackPostClick = { dispatchOriginalPostClick() },
        onNativePostAccepted = { performNavigationHaptic() },
    )
    private val capturedBackdropState = mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    private val selectedAccentEnabledState = mutableStateOf(false)
    private val navigationStyleState = mutableStateOf(InjectedNavigationStyle.LIQUID_GLASS)
    private val hyperGeometry = HyperHostGeometry()
    private val bottomBarTraditionalChineseState = mutableStateOf(false)
    private val backdropGenerationState = mutableIntStateOf(0)
    private var backdropCaptureEnabled = true
    private var nativeBlurEnabled = false
    // Host scroll changes are captured in the next host pre-draw using the uploaded reference
    // timing model. The injected view remains its own lifecycle owner so host applications with a
    // different AndroidX class loader cannot crash on owner casts.
    private val bitmapBackdropManager = CoolapkLegacyBitmapBackdropManager(
        glassView = this,
        onBitmapUpdated = { bitmap -> capturedBackdropState.value = bitmap?.asImageBitmap() },
    )
    private var lastDispatchedIndex = -1
    private var lastDispatchAt = 0L

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
                key(backdropGenerationState.intValue, navigationStyleState.value) {
                    LegacyReferenceGlassBottomBarHost(
                    navigationStyle = navigationStyleState.value,
                    hyperGeometry = hyperGeometry,
                    currentSelectedIndex = { selectedIndex.intValue },
                    onHyperContentHeightChanged = ::onHyperContentHeightChanged,
                    labels = labelsState.value,
                    selectedIndex = selectedIndex.intValue,
                     capturedBackdrop = capturedBackdropState.value,
                     nativeBackdropActive = nativeBackdropActiveState.value,
                     selectedAccentEnabled = selectedAccentEnabledState.value,
                     traditionalChinese = bottomBarTraditionalChineseState.value,
                     settings = currentSettings,
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
        addView(nativePostMirrorView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun effectiveDark(settings: GlassSettings = settingsState.value): Boolean = when (settings.themeMode) {
        GlassSettings.THEME_LIGHT -> false
        GlassSettings.THEME_DARK -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun bitmapCaptureAllowed(): Boolean = backdropCaptureEnabled && !nativeBlurEnabled

    private fun hyperNavigationLayout(): InjectedNavigationStyle.Layout {
        val settings = settingsState.value
        val hostWidthDp = width / resources.displayMetrics.density
        return InjectedNavigationStyle.layout(
            navigationStyleState.value, hostWidthDp,
            hyperGeometry.viewportWidthDp.value.takeIf { it > 0f } ?: hostWidthDp,
            labelsState.value.size, settings.showPostButton,
            -1, false,
            hyperGeometry.standardContentHeightDp.value, hyperGeometry.bottomInsetDp.value,
        )
    }

    fun setHyperNavigationBottomInset(bottomPx: Int) {
        val inset = bottomPx.coerceAtLeast(0) / resources.displayMetrics.density
        if (hyperGeometry.bottomInsetDp.value == inset) return
        hyperGeometry.bottomInsetDp.value = inset
        updateHyperCaptureGeometry()
        updateNativeBackdrop()
    }

    private fun onHyperContentHeightChanged(heightDp: Float) {
        if (kotlin.math.abs(hyperGeometry.standardContentHeightDp.value - heightDp) < 0.1f) return
        hyperGeometry.standardContentHeightDp.value = heightDp
        // onSizeChanged runs during Compose layout; update Android children on the next frame.
        postOnAnimation {
            if (!disposed) {
                updateHyperCaptureGeometry()
                updateNativeBackdrop()
            }
        }
    }

    private fun updateHyperCaptureGeometry() {
        val captureHeight = if (navigationStyleState.value == InjectedNavigationStyle.LIQUID_GLASS) {
            LEGACY_REFERENCE_CAPTURE_HEIGHT_DP
        } else hyperNavigationLayout().captureHeightDp
        bitmapBackdropManager.setCaptureHeightDp(captureHeight)
    }

    private fun updateNativeBackdrop(): Boolean {
        if (!nativeBlurEnabled || width <= 0 || height <= 0 || !HyperOsNativeBlur.isAvailable()) {
            nativeBlurView.disable()
            nativeBackdropActiveState.value = false
            return false
        }
        val settings = settingsState.value
        val labels = labelsState.value.ifEmpty { listOf("首页", "数码", "发现", "我的") }
        val desiredContentDp = if (settings.showPostButton) {
            settings.tabWidthDp * 4f + LEGACY_POST_SLOT_WIDTH_DP
        } else {
            settings.tabWidthDp * labels.size
        }.coerceAtLeast(1f)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val availableContentDp = (width / density - LEGACY_KSU_ROW_HORIZONTAL_PADDING_DP).coerceAtLeast(1f)
        val widthScale = (availableContentDp / desiredContentDp).coerceIn(0.1f, 1f)
        val barWidthDp = desiredContentDp * widthScale + LEGACY_KSU_ROW_HORIZONTAL_PADDING_DP
        val hyperLayout = if (navigationStyleState.value != InjectedNavigationStyle.LIQUID_GLASS) {
            hyperNavigationLayout()
        } else null
        val lp = (nativeBlurView.layoutParams as? LayoutParams) ?: LayoutParams(1, 1)
        lp.width = ((hyperLayout?.barWidthDp ?: barWidthDp) * density).toInt().coerceAtLeast(1)
        lp.height = ((hyperLayout?.barHeightDp ?: 64f) * density).toInt().coerceAtLeast(1)
        lp.gravity = Gravity.BOTTOM or if (hyperLayout != null) Gravity.LEFT else Gravity.CENTER_HORIZONTAL
        lp.leftMargin = ((hyperLayout?.barLeftDp ?: 0f) * density).toInt()
        lp.bottomMargin = ((hyperLayout?.bottomPaddingDp ?: settings.bottomPaddingDp) * density).toInt().coerceAtLeast(0)
        nativeBlurView.layoutParams = lp
        val dark = effectiveDark(settings)
        val radius = if (hyperLayout != null) {
            ((if (hyperLayout.standard) 18f else 18f * 0.24f) * density).toInt().coerceAtLeast(1)
        } else if (dark) {
            (settings.blurRadiusDp * 4f).toInt().coerceIn(1, 36)
        } else {
            (settings.blurRadiusDp * 2.75f).toInt().coerceIn(1, 24)
        }
        val active = nativeBlurView.update(if (hyperLayout?.standard == true) 0f else (hyperLayout?.barHeightDp?.div(2f) ?: 32f) * density, radius, dark, 0f)
        nativeBackdropActiveState.value = active
        if (active) {
            bitmapBackdropManager.pause()
            bitmapBackdropManager.resetBuffer()
            capturedBackdropState.value = null
        }
        return active
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hyperGeometry.viewportWidthDp.value = w / resources.displayMetrics.density
        if (w != oldw || h != oldh) post {
            if (!updateNativeBackdrop() && bitmapCaptureAllowed() && isAttachedToWindow) {
                bitmapBackdropManager.setSource(null)
                bitmapBackdropManager.start(hostActivity ?: ActivityFinder.from(context))
                refreshBackdropFrame()
            }
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
        resumeLocalLifecycle()
        if (nativeBlurEnabled) {
            post {
                bitmapBackdropManager.pause()
                bitmapBackdropManager.resetBuffer()
                capturedBackdropState.value = null
                updateNativeBackdrop()
            }
        } else if (backdropCaptureEnabled) {
            post {
                if (!bitmapCaptureAllowed()) return@post
                nativeBlurView.disable()
                nativeBackdropActiveState.value = false
                bitmapBackdropManager.setSource(null)
                bitmapBackdropManager.start(hostActivity ?: ActivityFinder.from(context))
                refreshBackdropFrame()
            }
        }
    }

    override fun onDetachedFromWindow() {
        // Host view transitions may detach and reattach the same injected view. Pausing at CREATED
        // avoids trying to resurrect a DESTROYED LifecycleRegistry on the next attachment.
        bitmapBackdropManager.pause()
        nativeBlurView.disable()
        nativeBackdropActiveState.value = false
        pauseLocalLifecycle()
        clearInjectedRootOwners()
        super.onDetachedFromWindow()
    }

    /** Permanently release this injected view before removing it from DecorView. */
    fun dispose() {
        if (disposed) return
        // Pause before flipping disposed so lifecycle events are still delivered in valid order.
        pauseLocalLifecycle()
        disposed = true
        bitmapBackdropManager.stop()
        nativeBlurView.disable()
        nativeBackdropActiveState.value = false
        capturedBackdropState.value = null
        clearInjectedRootOwners()
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
        if (!labels.isNullOrEmpty()) {
            labelsState.value = labels.toList()
            post { updateNativeBackdrop() }
        }
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
        nativePostMirrorView.invalidate()
        updateNativeBackdrop()
        if (bitmapCaptureAllowed()) bitmapBackdropManager.markDirty()
        if (layoutModeChanged) {
            if (bitmapCaptureAllowed()) bitmapBackdropManager.resetBuffer()
            composeView.requestLayout()
            composeView.invalidate()
            requestLayout()
            invalidate()
        }
        if (isAttachedToWindow && bitmapCaptureAllowed()) {
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
        nativePostMirrorView.setTarget(view)
    }

    fun setBackdropCaptureEnabled(enabled: Boolean) {
        if (backdropCaptureEnabled == enabled) return
        backdropCaptureEnabled = enabled
        if (nativeBlurEnabled) {
            bitmapBackdropManager.pause()
            bitmapBackdropManager.resetBuffer()
            capturedBackdropState.value = null
            if (isAttachedToWindow) post { updateNativeBackdrop() }
            backdropGenerationState.intValue = backdropGenerationState.intValue + 1
            return
        }
        if (!enabled) {
            bitmapBackdropManager.pause()
            bitmapBackdropManager.resetBuffer()
            capturedBackdropState.value = null
            backdropGenerationState.intValue = backdropGenerationState.intValue + 1
            return
        }
        if (isAttachedToWindow) post {
            if (!bitmapCaptureAllowed()) return@post
            nativeBlurView.disable()
            nativeBackdropActiveState.value = false
            bitmapBackdropManager.setSource(null)
            bitmapBackdropManager.start(hostActivity ?: ActivityFinder.from(context))
            postOnAnimation { refreshBackdropFrame() }
        }
        backdropGenerationState.intValue = backdropGenerationState.intValue + 1
    }

    fun setNativeBlurEnabled(enabled: Boolean) {
        if (nativeBlurEnabled == enabled) return
        nativeBlurEnabled = enabled
        capturedBackdropState.value = null
        backdropGenerationState.intValue = backdropGenerationState.intValue + 1
        if (enabled) {
            bitmapBackdropManager.pause()
            bitmapBackdropManager.resetBuffer()
            if (isAttachedToWindow) post { updateNativeBackdrop() }
            return
        }

        nativeBlurView.disable()
        nativeBackdropActiveState.value = false
        if (bitmapCaptureAllowed() && isAttachedToWindow) post {
            if (!bitmapCaptureAllowed()) return@post
            bitmapBackdropManager.setSource(null)
            bitmapBackdropManager.start(hostActivity ?: ActivityFinder.from(context))
            postOnAnimation { refreshBackdropFrame() }
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
        // HyperOS dispatches the publish action through Compose; the legacy wide touch mirror
        // would overlap neighbouring slots in the much narrower reference capsule.
        nativePostMirrorView.visibility = if (normalized == InjectedNavigationStyle.LIQUID_GLASS) View.VISIBLE else View.GONE
        updateHyperCaptureGeometry()
        activeTouchSequence = false
        updateNativeBackdrop()
        nativePostMirrorView.invalidate()
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

    /**
     * Move only the glass indicator. This method never sends a click back to CoolAPK,
     * so it is safe to call from host callbacks, resume hooks and theme recreation.
     */
    fun setSelectedIndexFromHost(index: Int) {
        val max = (labelsState.value.size - 1).coerceAtLeast(0)
        selectedIndex.intValue = index.coerceIn(0, max)
    }

    /**
     * The original bottom bar is transparent, not removed. Compose still exposes its
     * selected tab through accessibility semantics. Use that as the source of truth so
     * restored pages, back navigation and activity recreation cannot leave the lens on
     * a different item. Returns -1 when this CoolAPK build does not expose semantics.
     */
    fun detectOriginalSelectedIndex(): Int {
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

    private fun markBackdropDirty() {
        if (bitmapCaptureAllowed()) bitmapBackdropManager.markDirty()
    }

    /**
     * Bitmap backdrop refresh. This does not call PixelCopy, MediaProjection or any
     * screenshot service; it only marks the reusable off-screen Bitmap as dirty.
     */
    fun refreshBackdropSoon() = markBackdropDirty()

    fun refreshBackdropFrame() = markBackdropDirty()

    /** Scroll callbacks only flip the dirty flag; rendering happens once at pre-draw. */
    fun markBackdropMoving() {
        if (bitmapCaptureAllowed()) bitmapBackdropManager.markMoving()
    }

    fun markBackdropIdle() {
        if (bitmapCaptureAllowed()) bitmapBackdropManager.markIdle()
    }

    fun pauseBackdropCapture() {
        bitmapBackdropManager.pause()
    }

    fun startRealtimeBackdropRefresh() {
        if (bitmapCaptureAllowed()) bitmapBackdropManager.resume()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeTouchSequence = isInsideInteractiveBar(event)
                if (!activeTouchSequence) return false
            }
            MotionEvent.ACTION_MOVE -> if (!activeTouchSequence) return false
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!activeTouchSequence) return false
            }
        }

        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            activeTouchSequence = false
        }
        return handled
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
        if (navigationStyleState.value != InjectedNavigationStyle.LIQUID_GLASS) {
            return hyperNavigationLayout().contains(
                event.x / density, (height - event.y) / density - hyperNavigationLayout().bottomPaddingDp,
            )
        }
        val labelCount = labelsState.value.size.coerceAtLeast(1)
        val contentDp = if (settings.showPostButton) {
            settings.tabWidthDp * 4f + LEGACY_POST_SLOT_WIDTH_DP
        } else {
            settings.tabWidthDp * labelCount
        }
        val pillWidthPx = ((contentDp + LEGACY_KSU_ROW_HORIZONTAL_PADDING_DP) * density)
            .coerceAtMost(width.toFloat())
        val horizontalSlop = 8f * density
        val left = (width - pillWidthPx) * 0.5f - horizontalSlop
        val right = (width + pillWidthPx) * 0.5f + horizontalSlop

        val barBottom = height - settings.bottomPaddingDp * density
        // 56dp pill + the reference pressed expansion and a small gesture slop.
        val top = barBottom - 72f * density
        val bottom = barBottom + 8f * density
        return event.x in left..right && event.y in top..bottom
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!activeTouchSequence) return false
        return true
    }


    private fun commitSelectedIndex(index: Int) {
        val safeIndex = index.coerceIn(0, (labelsState.value.size - 1).coerceAtLeast(0))
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
        val accepted = dispatchOriginalComposeClick(safeIndex)
        if (accepted) {
            performNavigationHaptic()
            selectedIndex.intValue = safeIndex
            GlassNavInstaller.syncSelectedIndex(hostActivity ?: ActivityFinder.from(context), safeIndex)
        } else {
            // Do not leave the lens on a page CoolAPK did not actually accept.
            selectedIndex.intValue = previousIndex
        }
    }

    private fun dispatchOriginalComposeClick(index: Int): Boolean {
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

@Composable
private fun LegacyReferenceGlassBottomBarHost(
    navigationStyle: String,
    hyperGeometry: HyperHostGeometry,
    currentSelectedIndex: () -> Int,
    onHyperContentHeightChanged: (Float) -> Unit,
    labels: List<String>,
    selectedIndex: Int,
    capturedBackdrop: androidx.compose.ui.graphics.ImageBitmap?,
    nativeBackdropActive: Boolean,
    selectedAccentEnabled: Boolean,
    traditionalChinese: Boolean,
    settings: GlassSettings,
    onSelect: (Int) -> Unit,
    onPost: () -> Unit,
) {
    val safeLabels = if (labels.isEmpty()) listOf("首页", "数码", "发现", "我的") else labels
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = when (settings.themeMode) {
        GlassSettings.THEME_LIGHT -> false
        GlassSettings.THEME_DARK -> true
        else -> systemDark
    }
    if (navigationStyle != InjectedNavigationStyle.LIQUID_GLASS) {
        InjectedHyperBottomBar(
            style = navigationStyle, labels = safeLabels, selectedIndex = selectedIndex,
            currentSelectedIndex = currentSelectedIndex,
            viewportWidthDp = hyperGeometry.viewportWidthDp.value,
            bottomInsetDp = hyperGeometry.bottomInsetDp.value,
            standardContentHeightDp = hyperGeometry.standardContentHeightDp.value,
            onContentHeightChanged = onHyperContentHeightChanged,
            capturedBackdrop = capturedBackdrop, nativeBackdropActive = nativeBackdropActive,
            traditionalChinese = traditionalChinese,
            settings = settings, dark = effectiveDark, onSelect = onSelect, onPost = onPost,
            iconContent = { index, color, modifier -> LegacyNavIcon(index, color, modifier) },
            ovalPostButton = true,
        )
        return
    }
    // 2.25 keeps the existing backdrop pipeline while expanding only the render canvas. The reusable bitmap is placed
    // in an invisible source box and recorded by layerBackdrop exactly as it was in 1.20.
    val backdrop = rememberLayerBackdrop()
    val showPostButton = settings.showPostButton
    val slotCount = if (showPostButton) 5 else safeLabels.size
    val selectedSlot = if (showPostButton) fourTabLogicalIndexToFiveSlot(selectedIndex) else selectedIndex

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // The reference source uses equal weighted slots. Preserve the previous compact
        // CoolAPK width by calculating the whole pill as four navigation widths plus one
        // independent 48dp post allowance, then let the reference bar divide it evenly.
        val desiredContentDp = if (showPostButton) {
            settings.tabWidthDp * 4f + LEGACY_POST_SLOT_WIDTH_DP
        } else {
            settings.tabWidthDp * safeLabels.size
        }.coerceAtLeast(1f)
        val availableContentDp = (maxWidth.value - LEGACY_KSU_ROW_HORIZONTAL_PADDING_DP).coerceAtLeast(1f)
        val widthScale = (availableContentDp / desiredContentDp).coerceIn(0.1f, 1f)
        val barWidth = (desiredContentDp * widthScale + LEGACY_KSU_ROW_HORIZONTAL_PADDING_DP).dp
        // Exact 1.20 source arrangement: record the captured strip through an alpha-zero Box.
        // The 1.20 OnDraw/OnPreDraw capture manager then refreshes this layer while content moves.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .width(maxWidth)
                .height(LEGACY_REFERENCE_CAPTURE_HEIGHT_DP.dp)
                .alpha(0f)
                .layerBackdrop(backdrop),
            contentAlignment = Alignment.Center,
        ) {
            if (capturedBackdrop != null) {
                Image(
                    bitmap = capturedBackdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }

        key(showPostButton, settings.iconOnly, settings.tabWidthDp, barWidth) {
            FloatingBottomBar(
                modifier = Modifier
                    .padding(bottom = settings.bottomPaddingDp.dp)
                    .width(barWidth),
                selectedIndex = { selectedSlot.coerceIn(0, slotCount - 1) },
                onSelected = { slot ->
                    if (!showPostButton) {
                        onSelect(slot.coerceIn(0, safeLabels.lastIndex))
                    } else {
                        when (slot.coerceIn(0, 4)) {
                            0 -> onSelect(0)
                            1 -> onSelect(1)
                            2 -> {
                                // Slot 2 is the independent post button. A dragged lens is
                                // never allowed to settle there; it skips to the other side.
                                if (selectedIndex <= 1) onSelect(2) else onSelect(1)
                            }
                            3 -> onSelect(2)
                            else -> onSelect(3)
                        }
                    }
                },
                backdrop = backdrop,
                tabsCount = slotCount,
                blockedIndex = if (showPostButton) 2 else -1,
                isBlurEnabled = capturedBackdrop != null,
                isNativeBackdropEnabled = nativeBackdropActive,
                forceDark = effectiveDark,
                selectedAccentEnabled = selectedAccentEnabled,
            ) {
                if (showPostButton) {
                    for (slot in 0 until 5) {
                        if (slot == 2) {
                            FloatingBottomBarItem(
                                onClick = onPost,
                            ) {
                                val postColors = rememberGlassPostButtonColors(settings)
                                GlassOvalPostButtonIcon(
                                    backgroundColor = postColors.background,
                                    contentColor = postColors.content,
                                    modifier = Modifier
                                        .width((56f * settings.iconScale).dp)
                                        .height((40f * settings.iconScale).dp),
                                )
                            }
                        } else {
                            val logicalIndex = fiveSlotToFourTabLogicalIndex(slot)
                            val label = safeLabels[logicalIndex]
                            LegacyNavigationBarContent(
                                index = logicalIndex,
                                label = label,
                                traditionalChinese = traditionalChinese,
                                settings = settings,
                                onClick = { onSelect(logicalIndex) },
                            )
                        }
                    }
                } else {
                    safeLabels.forEachIndexed { index, label ->
                        LegacyNavigationBarContent(
                            index = index,
                            label = label,
                            traditionalChinese = traditionalChinese,
                            settings = settings,
                            onClick = { onSelect(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.LegacyNavigationBarContent(
    index: Int,
    label: String,
    traditionalChinese: Boolean,
    settings: GlassSettings,
    onClick: () -> Unit,
) {
    val displayLabel = BottomBarLanguage.display(label, traditionalChinese)
    FloatingBottomBarItem(
        onClick = onClick,
    ) {
        val color = LocalContentColor.current
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val baseIconSize = if (settings.iconOnly) 31f else 24f
            LegacyNavIcon(
                index = index,
                color = color,
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
private fun LegacyNavIcon(
    index: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawDefaultNavIcon(index, color)
    }
}
