// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.youtube

import rongyi.lingbai.XposedBridge
import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import rongyi.lingbai.liquidtab.config.GlassConfig
import rongyi.lingbai.liquidtab.hook.GlassHostLayout
import rongyi.lingbai.liquidtab.hook.NavigationCandidate
import rongyi.lingbai.liquidtab.hook.adapters.AdapterNavigationSearch
import rongyi.lingbai.liquidtab.hook.adapters.TargetAdapter

/** YouTube-specific navigation diagnostics, outside the generic finder. */
internal object YouTubeAdapter : TargetAdapter {
    override val key = "youtube"
    override val usesContentDarkMode = true
    override val hasPerFrameScene = true
    override val nativeNavigationDrawsInSourceSpace = true
    override val skipNavigationSnapshot = true
    override val suppressPlatformElevation = true
    override val usesOpticalSurfacePipeline = true
    override val usesDirectOpticalBackdropMode = true
    override val usesContentAlignedOpticalSurface = true
    override val ownsOpticalSurfaceLayout = true
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val opticalSurfaceFollowsBarTransform = false
    override val allowsSystemBackgroundBlur = false
    override val reuseOpticalBlur = true
    override val reuseSurfaceBlurDuringBarAnimation = true
    // A changing backdrop callback and the UI animation pump otherwise
    // submit the same optical Surface twice during one animation frame.
    override val deferOpticalRenderDuringBarAnimation = true

    override fun opticalSurfaceLayout(
        contentWidth: Int, contentHeight: Int, hostLeft: Int, hostTop: Int,
        hostWidth: Int, hostHeight: Int, overflow: Int,
    ): rongyi.lingbai.liquidtab.hook.adapters.OpticalSurfaceLayout? {
        if (hostWidth <= 0 || hostHeight <= 0) return null
        return rongyi.lingbai.liquidtab.hook.adapters.OpticalSurfaceLayout(
            hostWidth + overflow * 2, hostHeight + overflow * 2,
            (hostLeft - overflow).coerceAtLeast(0), (hostTop - overflow).coerceAtLeast(0),
            0, 0, android.view.Gravity.TOP or android.view.Gravity.START)
    }

    override fun opticalCaptureGeometry(
        rootWidth: Int, rootHeight: Int, left: Int, top: Int, width: Int, height: Int, padding: Int,
    ): rongyi.lingbai.liquidtab.hook.adapters.OpticalCaptureGeometry? {
        val rect = android.graphics.Rect((left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0), (left + width + padding).coerceAtMost(rootWidth),
            (top + height + padding).coerceAtMost(rootHeight))
        if (rect.isEmpty) return null
        val w = (rect.width() / 3).coerceAtLeast(1)
        val h = (rect.height() / 3).coerceAtLeast(1)
        return rongyi.lingbai.liquidtab.hook.adapters.OpticalCaptureGeometry(rect, w, h,
            android.graphics.Rect(0, 0, w, h))
    }

    override fun configureSourceText(view: TextView, config: GlassConfig, density: Float): Boolean {
        // Keep the native layout at its baseline. The renderer scales each
        // leaf on the destination Canvas, outside thumbnail_layout's clip.
        view.textSize = config.textSize
        view.scaleX = 1f
        view.scaleY = 1f
        return true
    }

    override fun configureSourceIcon(view: ImageView, config: GlassConfig, density: Float): Boolean {
        view.scaleX = 1f
        view.scaleY = 1f
        return true
    }

    override fun createNavigationState(context: Context, host: GlassHostLayout): YouTubeNavigationState =
        YouTubeNavigationState(host)

    override fun onNavigationCandidates(candidates: List<NavigationCandidate>) {
        XposedBridge.log(
            "[OfflineGlass][YouTubeDiag] find candidates=${candidates.joinToString { candidate ->
                "${candidate.view.javaClass.simpleName}/" +
                    "${AdapterNavigationSearch.resourceEntryName(candidate.view) ?: "no-id"}/" +
                    "slots=${candidate.slotCount}/score=${candidate.score}/" +
                    "${candidate.view.width}x${candidate.view.height}"
            }}",
        )
    }
}
