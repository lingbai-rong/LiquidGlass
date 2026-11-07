package rongyi.lingbai.liquidtab.hook

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import java.util.WeakHashMap

/** Restore only native properties changed by the adapter on disable or detach. */
object PortChromeOwnership {
    private data class Saved(val background: Drawable?, val foreground: Drawable?, val visibility: Int,
                             val image: Drawable?, val alpha: Float, val clickable: Boolean, val accessibility: Int,
                             var hidden: Boolean = false, var imageCleared: Boolean = false, var alphaCleared: Boolean = false,
                             var interactionCleared: Boolean = false)
    private val saved = WeakHashMap<View, Saved>()
    private fun remember(view: View): Saved = saved.getOrPut(view) {
        Saved(view.background, view.foreground, view.visibility, (view as? ImageView)?.drawable, view.alpha,
              view.isClickable, view.importantForAccessibility)
    }
    fun clearBackground(view: View) { remember(view); view.background = null }
    fun clearForeground(view: View) { remember(view); view.foreground = null }
    fun hide(view: View) { remember(view).hidden = true; view.visibility = View.GONE }
    fun clearImage(view: ImageView) { remember(view).imageCleared = true; view.setImageDrawable(null) }
    fun clearAlpha(view: View) { remember(view).alphaCleared = true; view.alpha = 0f }
    fun disableDirectTouch(view: View, hideAccessibility: Boolean = false) {
        remember(view).interactionCleared = true
        view.isClickable = false
        if (hideAccessibility) view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    fun showIfOwned(view: View) {
        val original = saved[view] ?: return
        if (original.hidden && view.visibility == View.GONE) view.visibility = original.visibility
        original.hidden = false
    }
    fun restoreForRoot(root: View) {
        val entries = saved.entries.filter { it.key.rootView === root.rootView }
        for ((view, value) in entries) {
            if (view.background == null) view.background = value.background
            if (view.foreground == null) view.foreground = value.foreground
            if (value.hidden && view.visibility == View.GONE) view.visibility = value.visibility
            if (value.alphaCleared && view.alpha == 0f) view.alpha = value.alpha
            if (value.interactionCleared) {
                if (!view.isClickable) view.isClickable = value.clickable
                if (view.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                    view.importantForAccessibility = value.accessibility
            }
            if (value.imageCleared && view is ImageView && view.drawable == null) view.setImageDrawable(value.image)
            saved.remove(view)
        }
    }
}
