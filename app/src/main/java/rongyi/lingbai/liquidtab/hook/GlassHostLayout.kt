package rongyi.lingbai.liquidtab.hook

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import rongyi.lingbai.liquidtab.config.GlassConfig
import rongyi.lingbai.liquidtab.hook.adapters.AppNavigationState
import java.lang.ref.WeakReference

/** Adapter lifecycle facade. All visible material is rendered by LiquidGlass. */
open class GlassHostLayout(context: Context) : FrameLayout(context) {
    var adapterSceneRoot: WeakReference<View>? = null
    var config = GlassConfig()
    var adapterNavigationSource: ViewGroup? = null
    var adapterSelectedIndex = 0
    internal var appNavigationState: AppNavigationState? = null
    var barVisibilityTarget = true
    val outerRect = RectF()
    var liveBackdropActiveForAdapter = false
    var referenceSurfaceCopies = false
    var msgScrolledToStop = false
    var referenceIdleDeadline = 0L
    var referenceSlotCount = 0
    var referenceBackdropRevision = 0L
    fun clearLiveBackdropForAdapter() { liveBackdropActiveForAdapter = false }
    fun disableOpticalSurfaceNativeBlurForAdapter() = Unit
    fun enableOpticalSurfaceRendererForAdapter() { referenceSurfaceCopies = true }
    fun disableOpticalSurfaceRendererForAdapter() { referenceSurfaceCopies = false }
    fun invalidateOpticalBackdropForAdapter() { referenceBackdropRevision++; invalidate() }
    fun updateAdapterSlotCount(count: Int, selected: Int = adapterSelectedIndex) { referenceSlotCount = count; adapterSelectedIndex = selected }
    fun adapterOnScrollStopHidePageEntered(delay: Long) {
        referenceIdleDeadline = SystemClock.uptimeMillis() + delay
        msgScrolledToStop = false
    }
    fun adapterCancelScrollStopHide() { referenceIdleDeadline = 0L; msgScrolledToStop = false }
}

val View.adapterRootView: View
    get() = (this as? GlassHostLayout)?.adapterSceneRoot?.get() ?: rootView
fun View.adapterHasWindowFocus(): Boolean = adapterRootView.hasWindowFocus()
val View.adapterWindowVisibility: Int get() = adapterRootView.windowVisibility
fun glassHostHeightPx(context: Context, config: GlassConfig): Int =
    glassHostHeightPx(context.resources.displayMetrics.density, config)
fun glassHostHeightPx(density: Float, config: GlassConfig): Int = (config.barHeight * density).toInt()
fun globalGlassBottomGapPx(context: Context, config: GlassConfig): Int =
    (config.bottomPadding * context.resources.displayMetrics.density).toInt()
fun globalGlassBottomGapPx(view: View): Int = globalGlassBottomGapPx(
    view.context, (view as? GlassHostLayout)?.config ?: HookConfigReader.read(view.context, view.context.packageName))
