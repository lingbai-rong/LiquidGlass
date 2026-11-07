// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.meituan_main

import android.view.View
import android.view.ViewGroup
import rongyi.lingbai.liquidtab.hook.adapters.AppNativeChromeController
import kotlin.math.abs

/** Removes only Meituan main's native shell while preserving its live click targets. */
internal class MeituanMainNativeChrome : AppNativeChromeController {
    override fun update(host: View, source: ViewGroup?) {
        source ?: return
        if (!source.javaClass.name.endsWith(".PTLinearLayout")) return
        val block = source.parent as? ViewGroup ?: return
        if (block.javaClass.name != "com.meituan.android.pt.homepage.tab.TabBlockV2") return

        rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearBackground(source)
        rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearBackground(block)
        for (index in 0 until block.childCount) {
            val layer = block.getChildAt(index)
            if (layer === source) continue
            rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearBackground(layer)
            if (layer.alpha != 0f) rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearAlpha(layer)
        }

        val shell = block.parent as? ViewGroup ?: return
        val nativeContentHeight = (0 until source.childCount)
            .map(source::getChildAt)
            .filter { it.visibility == View.VISIBLE }
            .maxOfOrNull { it.height }
            ?.coerceAtLeast(1) ?: return
        for (index in 0 until shell.childCount) {
            val content = shell.getChildAt(index)
            val idName = runCatching {
                if (content.id == View.NO_ID) null else content.resources.getResourceEntryName(content.id)
            }.getOrNull()
            if (idName != "main") continue
            val params = content.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            var changed = false
            if (params.bottomMargin in 1..block.height.coerceAtLeast(1)) {
                params.bottomMargin = 0
                changed = true
            }
            if (params.height > 0 && abs(shell.height - content.height - nativeContentHeight) <= 2) {
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                changed = true
            }
            if (changed) {
                content.layoutParams = params
                content.requestLayout()
                shell.requestLayout()
            }
            return
        }
    }
}
