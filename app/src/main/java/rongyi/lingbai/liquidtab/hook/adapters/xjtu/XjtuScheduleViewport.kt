// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.xjtu

import rongyi.lingbai.liquidtab.hook.adapterRootView

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.view.View
import android.webkit.WebView
import rongyi.lingbai.liquidtab.hook.GlassHostLayout
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Give the schedule WebView a viewport that ends above the glass navigation bar. */
internal object XjtuScheduleViewport {
    private data class ViewState(val originalHeight: Int, var appliedHeight: Int)

    private val webViews = WeakHashMap<WebView, ViewState>()
    private var activeWebView = WeakReference<WebView>(null)
    private var activeScene = WeakReference<ViewGroup>(null)
    private var originalSceneBackground: Drawable? = null
    private var whiteSceneBackground: ColorDrawable? = null
    private var activeContentRoot = WeakReference<View>(null)
    private var originalContentBackground: Drawable? = null
    private var whiteContentBackground: ColorDrawable? = null

    fun update(host: GlassHostLayout, scene: ViewGroup?, isSchedule: Boolean) {
        if (!isSchedule || scene == null || !host.isShown || host.height <= 0) {
            restore()
            return
        }
        val web = findWebView(scene) ?: run { restore(); return }
        if (web.width <= 0 || web.height <= 0) return

        val previous = activeWebView.get()
        if (previous !== web) restore()
        val state = webViews.getOrPut(web) { ViewState(web.layoutParams.height, web.layoutParams.height) }

        val hostPos = IntArray(2).also(host::getLocationOnScreen)
        val bottom = hostPos[1] + host.outerRect.top.toInt()
        val webPos = IntArray(2).also(web::getLocationOnScreen)
        val targetHeight = (bottom - webPos[1]).coerceIn(1, scene.height.coerceAtLeast(1))

        if (web.layoutParams.height != targetHeight) {
            web.layoutParams = web.layoutParams.apply { height = targetHeight }
        }
        state.appliedHeight = targetHeight
        activeWebView = WeakReference(web)

        if (activeScene.get() !== scene) {
            restoreSceneBackground()
            activeScene = WeakReference(scene)
            originalSceneBackground = scene.background
            whiteSceneBackground = ColorDrawable(Color.WHITE)
        }
        val white = whiteSceneBackground ?: ColorDrawable(Color.WHITE).also { whiteSceneBackground = it }
        if (scene.background !== white) scene.background = white

        val contentRoot = host.adapterRootView.findViewById<View>(android.R.id.content) ?: host.adapterRootView
        if (activeContentRoot.get() !== contentRoot) {
            restoreContentBackground()
            activeContentRoot = WeakReference(contentRoot)
            originalContentBackground = contentRoot.background
            whiteContentBackground = ColorDrawable(Color.WHITE)
        }
        val whiteContent = whiteContentBackground ?: ColorDrawable(Color.WHITE).also { whiteContentBackground = it }
        if (contentRoot.background !== whiteContent) contentRoot.background = whiteContent
    }

    fun restore() {
        activeWebView.get()?.let { web ->
            val state = webViews.remove(web)
            if (state != null && web.layoutParams.height == state.appliedHeight) {
                web.layoutParams = web.layoutParams.apply { height = state.originalHeight }
            }
        }
        activeWebView.clear()
        restoreSceneBackground()
        restoreContentBackground()
    }

    private fun restoreSceneBackground() {
        activeScene.get()?.let { scene ->
            if (scene.background === whiteSceneBackground) scene.background = originalSceneBackground
        }
        activeScene.clear()
        originalSceneBackground = null
        whiteSceneBackground = null
    }

    private fun restoreContentBackground() {
        activeContentRoot.get()?.let { contentRoot ->
            if (contentRoot.background === whiteContentBackground) contentRoot.background = originalContentBackground
        }
        activeContentRoot.clear()
        originalContentBackground = null
        whiteContentBackground = null
    }

    private fun findWebView(root: ViewGroup): WebView? {
        val stack = ArrayDeque<android.view.View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 256) {
            val view = stack.removeLast()
            if (view is WebView && view.isShown && view.width > 0 && view.height > 0) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        return null
    }
}
