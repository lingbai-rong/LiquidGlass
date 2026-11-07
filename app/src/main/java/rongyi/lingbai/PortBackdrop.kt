package rongyi.lingbai

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rongyi.lingbai.liquidtab.hook.GlassHostLayout
import rongyi.lingbai.liquidtab.hook.adapters.AdapterOwnedOverlay
import rongyi.lingbai.liquidtab.hook.adapters.AppVideoBackdrop
import java.lang.ref.WeakReference

/** Keeps live GPU page nodes and app-owned video frames in the existing material's source layer. */
internal class PortBackdrop(
    private val activity: Activity,
    private val host: MiuixGlassNavView,
    private val adapter: LiquidTabPortAdapter? = null,
    private val sceneProvider: (() -> View?)? = null,
    private val excluded: View? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var scene = WeakReference<View>(null)
    private var video: AppVideoBackdrop? = null
    private var strip: Bitmap? = null
    private var stripScreen = RectF()
    private var pending = false
    private var lastCopy = 0L
    private var generation = 0
    private var disposed = false
    private var selected = -1
    private var live = false
    private var ready = false
    private var enabled = false
    private val nodeField by lazy {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/view/View;")
            View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
        }.getOrNull()
    }

    fun update(index: Int, pageAllowed: Boolean) {
        if (disposed) return
        val nextEnabled = pageAllowed && host.portCaptureAllowed() && adapter?.state?.blocksBackdropSampling != true
        if (!nextEnabled) {
            if (enabled || ready) { generation++; strip = null; video = null; ready = false; host.setPortBackdropReady(false) }
            enabled = false; return
        }
        enabled = true
        val next = sceneProvider?.invoke() ?: adapter?.getBackdropSourceView()
        if (scene.get() !== next || selected != index) {
            generation++; strip = null; video = null; lastCopy = 0L
            scene = WeakReference(next); selected = index
        }
        val state = adapter?.state
        video = state?.videoBackdrop()?.takeIf { !it.bitmap.isRecycled }
        val copies = adapter != null && (host.referenceSurfaceCopies || state?.needsSurfaceBackdrop() == true ||
            adapter.delegate.isOpticalSurfaceTab(index) || adapter.delegate.usesDirectOpticalBackdropMode)
        live = next != null && state?.skipLiveBackdrop(index) != true && !copies
        host.liveBackdropActiveForAdapter = live
        if (copies && !pending && SystemClock.uptimeMillis() - lastCopy >= 48L) requestStrip()
        ready = next != null || video != null || strip != null
        host.setPortBackdropReady(ready)
        host.invalidatePortBackdropDrawing()
    }

    fun draw(canvas: Canvas, width: Float, height: Float) {
        if (!enabled || disposed || !ready || !host.portCaptureAllowed()) return
        val point = IntArray(2); host.getLocationOnScreen(point)
        val originX = point[0].toFloat()
        val originY = point[1] + host.height - height
        val source = scene.get()
        val fallback = if (host.portDark()) Color.BLACK else Color.WHITE
        val color = source?.let { adapter?.state?.backdropSceneBaseColor(it, fallback) }
            ?.takeIf { Color.alpha(it) > 0 } ?: findBaseColor(source, fallback)
        canvas.drawColor(color)
        if (live && source != null && canvas.isHardwareAccelerated) {
            val state = adapter?.state
            val safe = (source as? ViewGroup)?.let { state?.backdropSceneViews(it, -1) }
            if (safe != null) safe.forEach { drawSafeScene(canvas, it, originX, originY, 0) }
            else drawSafeScene(canvas, source, originX, originY, 0)
            state?.onLiveBackdropRecorded(selected)
        }
        strip?.takeIf { !it.isRecycled }?.let { bitmap ->
            val destination = RectF(stripScreen).apply { offset(-originX, -originY) }
            if (adapter?.state?.drawSampledBackdrop(canvas, bitmap, destination, paint) != true)
                canvas.drawBitmap(bitmap, null, destination, paint)
        }
        video?.takeIf { !it.bitmap.isRecycled }?.let { frame ->
            val rect = RectF(frame.surfaceLeft-originX, frame.surfaceTop-originY,
                frame.surfaceLeft+frame.surfaceWidth-originX, frame.surfaceTop+frame.surfaceHeight-originY)
            canvas.drawBitmap(frame.bitmap, null, rect, paint)
        }
    }

    private fun findBaseColor(view: View?, fallback: Int): Int {
        var current = view
        repeat(10) {
            val candidate = (current?.background as? ColorDrawable)?.color
            if (candidate != null && Color.alpha(candidate) == 255) return candidate
            current = current?.parent as? View
        }
        return fallback
    }
    private fun containsExcluded(view: View, depth: Int): Boolean {
        if (view === excluded || view is GlassHostLayout || view is AdapterOwnedOverlay) return true
        if (view is ViewGroup && depth < 18) for (i in 0 until view.childCount)
            if (containsExcluded(view.getChildAt(i), depth+1)) return true
        return false
    }
    private fun drawSafeScene(canvas: Canvas, view: View, x: Float, y: Float, depth: Int) {
        if (depth > 18 || view === excluded || view is GlassHostLayout || view is AdapterOwnedOverlay ||
            view.visibility != View.VISIBLE || view.alpha <= 0.001f) return
        if (containsExcluded(view, 0) && view is ViewGroup) {
            for (i in 0 until view.childCount) drawSafeScene(canvas, view.getChildAt(i), x, y, depth+1)
            return
        }
        val node = runCatching { nodeField?.get(view) as? RenderNode }.getOrNull() ?: return
        if (!node.hasDisplayList()) return
        val point = IntArray(2); view.getLocationOnScreen(point)
        val save = canvas.save()
        // RenderNode already includes the view's local left/top transform.
        canvas.translate(point[0]-x-view.left-view.translationX, point[1]-y-view.top-view.translationY)
        runCatching { canvas.drawRenderNode(node) }
        canvas.restoreToCount(save)
    }

    private fun requestStrip() {
        val root = activity.window.decorView
        if (root.width <= 0 || root.height <= 0 || !root.isAttachedToWindow || host.width <= 0) return
        val screen = IntArray(2); val rootScreen = IntArray(2); val rootWindow = IntArray(2)
        host.getLocationOnScreen(screen); root.getLocationOnScreen(rootScreen); root.getLocationInWindow(rootWindow)
        val left = PortBackdropCoordinates.windowCoordinate(screen[0], rootScreen[0], rootWindow[0])
        val top = PortBackdropCoordinates.windowCoordinate(screen[1], rootScreen[1], rootWindow[1])
        val padding = (24f*host.resources.displayMetrics.density).toInt()
        val clipped = PortBackdropCoordinates.clipStrip(left, top, host.width, host.height, padding, root.width, root.height) ?: return
        val custom = adapter?.delegate?.opticalCaptureGeometry(root.width, root.height, left, top, host.width, host.height, padding)
        val rect = custom?.sourceRect?.let { Rect(it).apply { intersect(0,0,root.width,root.height) } }
            ?: Rect(clipped[0],clipped[1],clipped[2],clipped[3])
        if (rect.isEmpty) return
        val target = Bitmap.createBitmap(custom?.targetWidth?.coerceAtLeast(1) ?: (rect.width()/3).coerceAtLeast(1),
            custom?.targetHeight?.coerceAtLeast(1) ?: (rect.height()/3).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val ticket = generation
        pending = true; lastCopy = SystemClock.uptimeMillis()
        val copy: () -> Unit = {
            if (disposed || ticket != generation || !enabled) { pending = false; target.recycle() }
            else runCatching {
                PixelCopy.request(activity.window, rect, target, { result ->
                    host.post {
                        pending = false
                        if (!disposed && ticket == generation && enabled && host.portCaptureAllowed() && result == PixelCopy.SUCCESS) {
                            strip = target
                            stripScreen = RectF(rect).apply { offset((rootScreen[0]-rootWindow[0]).toFloat(), (rootScreen[1]-rootWindow[1]).toFloat()) }
                            adapter?.state?.onOpticalBackdropCaptured(custom?.barSource)
                            host.setPortBackdropReady(true); host.invalidatePortBackdropDrawing()
                            adapter?.state?.onOpticalSurfaceCopyCompleted(root) { host.postInvalidateOnAnimation() }
                        } else target.recycle()
                    }
                }, adapter?.state?.opticalSurfaceCopyHandler() ?: main)
            }.onFailure { pending = false; target.recycle() }
            Unit
        }
        if (adapter?.state?.scheduleOpticalSurfaceCopy(root, copy) != true) copy()
    }
    fun dispose() { disposed = true; generation++; scene.clear(); video = null; strip = null; enabled = false }
}
