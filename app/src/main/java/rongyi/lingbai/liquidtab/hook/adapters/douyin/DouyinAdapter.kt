// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.douyin

import android.view.View
import android.view.ViewGroup
import rongyi.lingbai.liquidtab.hook.NavigationCandidate
import rongyi.lingbai.liquidtab.hook.adapters.AdapterNavigationSearch
import rongyi.lingbai.liquidtab.hook.adapters.AdapterNavigationFrame
import rongyi.lingbai.liquidtab.hook.adapters.AppNavigationState
import rongyi.lingbai.liquidtab.hook.adapters.TargetAdapter
import rongyi.lingbai.liquidtab.targets.TargetSpec

/** Douyin 40.3.0: only root_view is an authoritative five-cell navigation row. */
internal object DouyinAdapter : TargetAdapter {
    override fun installHooks(lpparam: rongyi.lingbai.LoadPackageParam, spec: TargetSpec) =
        DouyinPlayerTextureHooks.install(lpparam)
    override val key = "douyin"
    override val allowRenderNodeRefresh = true
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val foregroundNavigationOverSelection = true
    override val usesNavigationOpticalMix = true
    override val requestedFrameRateMax = 120f
    override val continuousFramePump = true
    override val opticalIconBandScale = 1.5f
    override val secondaryBlurScale = 0.72f
    override val skipBackdropStableDelay = true
    override val forceCompositorFrames = true
    override val restingOpticalMix = 0.86f
    override val hasPerFrameScene = true
    override fun createNavigationState(context: android.content.Context): AppNavigationState =
        DouyinNavigationState(context)
    override fun createNativeChromeController() = object : rongyi.lingbai.liquidtab.hook.adapters.AppNativeChromeController {
        override fun update(host: View, source: ViewGroup?) {
            source ?: return
            suppressNativeBottomChrome(source)
        }
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val row = AdapterNavigationSearch.findFirst(root) { view ->
            AdapterNavigationSearch.resourceEntryName(view) == "root_view" &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width >= root.width * 0.9f && view.height > 0 &&
                view.childCount == 5 &&
                (0 until view.childCount).all { view.getChildAt(it).isClickable }
        }
        return row?.let { NavigationCandidate(it, 5, 1_000) }
    }

    /** Retain the native click controller but remove its full-width chrome. */
    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearBackground(navigation)
        val rowShell = navigation.parent as? ViewGroup ?: return
        rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearBackground(rowShell)
        for (index in 0 until rowShell.childCount) {
            val child = rowShell.getChildAt(index)
            if (child !== navigation && child.width >= navigation.width * 0.8f) rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearAlpha(child)
        }
        val chrome = rowShell.parent as? ViewGroup ?: return
        rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearBackground(chrome)
        for (index in 0 until chrome.childCount) {
            val child = chrome.getChildAt(index)
            if (child !== rowShell && child.width >= navigation.width * 0.8f &&
                child.height >= navigation.height * 0.8f
            ) rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearAlpha(child)
        }
        (chrome.parent as? ViewGroup)?.let(rongyi.lingbai.liquidtab.hook.PortChromeOwnership::clearBackground)

    }
}
