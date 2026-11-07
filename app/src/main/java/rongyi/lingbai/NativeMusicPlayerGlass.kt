package rongyi.lingbai

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.RelativeLayout
import rongyi.lingbai.liquidtab.hook.HookConfigReader
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Passive glass beneath native artwork, title, progress and playback controls. */
internal object NativeMusicPlayerGlass {
    private val panels = WeakHashMap<ViewGroup, WeakReference<MiuixGlassNavView>>()
    private var touchTarget: WeakReference<ViewGroup>? = null
    fun create(context: Context, player: ViewGroup, scene: () -> View?): MiuixGlassNavView {
        val app = TargetApp.fromPackage(context.packageName)
        val values = app?.let { TargetAppHookSettingsReader.load(context, it) }
        val panel = MiuixGlassNavView(context).apply {
            setLabels(arrayOf("")); setGlassOnlyPanel(true)
            isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            elevation = 0f; translationZ = 0f
            config = HookConfigReader.read(context, context.packageName)
            adapterNavigationSource = player
            adapterSceneRoot = WeakReference(player.rootView)
            if (values != null) applySettings(GlassSettings(values.revision, values.enabled, values.themeMode,
                values.tabWidthDp, values.bottomPaddingDp, values.blurRadiusDp, values.lightAlpha,
                values.darkAlpha, values.iconScale, values.textSizeSp, true, false, values.customPostColorEnabled, values.customPostColor))
            setForceSyntheticOnlyBackdrop(values?.forceBlackBackdrop == true)
            GlobalRenderSettingsReader.apply(context, this, false)
            installPlayerBackdrop(scene, player)
        }
        panels[player] = WeakReference(panel)
        val observer = player.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener { panel.updatePortMaterialBounds(); panel.updatePortBackdropFrame(0, player.isShown); true }
        if (observer.isAlive) observer.addOnPreDrawListener(listener)
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (observer.isAlive) observer.removeOnPreDrawListener(listener)
                panels.remove(player)
                if (touchTarget?.get() === player) touchTarget = null
                panel.dispose(); panel.removeOnAttachStateChangeListener(this)
            }
        })
        return panel
    }
    fun attach(player: ViewGroup, scene: () -> View?): MiuixGlassNavView? {
        panels[player]?.get()?.takeIf { it.parent === player }?.let { return it }
        val params = parameters(player) ?: return null
        val panel = create(player.context, player, scene)
        player.addView(panel, 0, params)
        return panel
    }
    private fun parameters(player: ViewGroup): ViewGroup.LayoutParams? = when (player) {
        is FrameLayout -> FrameLayout.LayoutParams(-1,-1)
        is RelativeLayout -> RelativeLayout.LayoutParams(-1,-1)
        else -> if (player.javaClass.name.contains("ConstraintLayout")) runCatching {
            val type = Class.forName("androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams", false, player.javaClass.classLoader)
            (type.getConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).newInstance(-1,-1) as ViewGroup.LayoutParams).also { lp ->
                for (name in listOf("leftToLeft", "rightToRight", "topToTop", "bottomToBottom")) type.getField(name).setInt(lp,0)
            }
        }.getOrNull() else null
    }
    fun dispatchTouch(root: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchTarget = null
            for ((player, reference) in panels) {
                val panel = reference.get() ?: continue
                if (player.rootView !== root.rootView || !player.isShown || panel.parent == null || !panel.isShown) continue
                val p = IntArray(2); player.getLocationOnScreen(p)
                if (Rect(p[0],p[1],p[0]+player.width,p[1]+player.height).contains(event.rawX.toInt(),event.rawY.toInt())) {
                    touchTarget = WeakReference(player); break
                }
            }
        }
        val player = touchTarget?.get() ?: return false
        val p = IntArray(2); player.getLocationOnScreen(p)
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(event.rawX-event.x-p[0],event.rawY-event.y-p[1])
        val consumed = try { player.dispatchTouchEvent(copy) } finally { copy.recycle() }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL || !consumed && event.actionMasked == MotionEvent.ACTION_DOWN) touchTarget = null
        return consumed
    }
    fun remove(player: ViewGroup) {
        panels.remove(player)?.get()?.let { panel -> panel.dispose(); (panel.parent as? ViewGroup)?.removeView(panel) }
        if (touchTarget?.get() === player) touchTarget = null
    }
}
