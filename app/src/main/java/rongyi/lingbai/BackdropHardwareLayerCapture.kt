package rongyi.lingbai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.ArrayDeque

/** Screen-space description of the small strip recorded behind the liquid-glass view. */
internal data class BackdropStrip(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val scale: Float,
)

/**
 * Optional compositor for pixels that View.draw(Canvas) cannot see.
 *
 * The normal capture path deliberately knows nothing about TextureView, SurfaceView or
 * PixelCopy. Keeping those asynchronous details here prevents a stale video/Flutter surface from
 * changing the source view, coordinates or lifecycle of ordinary View/Compose pages.
 */
internal class BackdropHardwareLayerCapture {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var generation = 0L
    private var surfaceCopyInFlight = false

    private var textureRef: WeakReference<TextureView>? = null
    private var textureBitmap: Bitmap? = null
    private var textureBitmapWidth = 0
    private var textureBitmapHeight = 0

    private var surfaceRef: WeakReference<SurfaceView>? = null
    private var surfaceBitmap: Bitmap? = null
    private var surfaceBitmapWidth = 0
    private var surfaceBitmapHeight = 0

    private val viewLocation = IntArray(2)
    private val sourceRect = Rect()
    private val destinationRect = RectF()

    fun beginFrame(): Long = ++generation

    fun invalidate() {
        generation++
        textureRef = null
        surfaceRef = null
    }

    fun clear() {
        invalidate()
        textureBitmap = null
        textureBitmapWidth = 0
        textureBitmapHeight = 0
        surfaceBitmap = null
        surfaceBitmapWidth = 0
        surfaceBitmapHeight = 0
        // An active PixelCopy callback still owns its destination. The generation check turns the
        // callback into a no-op; the bitmap is intentionally left to GC instead of being recycled.
    }

    fun hasSurfaceLayer(root: View, strip: BackdropStrip): Boolean =
        findSurface(root, strip) != null

    /**
     * A Surface PixelCopy owns its destination bitmap until the framework callback returns.
     * Callers must keep displaying the last completed frame instead of rotating that pending
     * destination into their front/work pair.
     */
    fun isSurfaceCopyInFlight(): Boolean = surfaceCopyInFlight

    fun compositeTexture(root: View, target: Bitmap, strip: BackdropStrip): Boolean {
        val texture = findTexture(root, strip) ?: return false
        if (!texture.isAvailable || texture.width <= 0 || texture.height <= 0) return false

        texture.getLocationOnScreen(viewLocation)
        val intersection = intersection(strip, viewLocation[0], viewLocation[1], texture.width, texture.height)
            ?: return false
        val snapshotWidth = (texture.width * strip.scale).toInt().coerceAtLeast(1)
        val snapshotHeight = (texture.height * strip.scale).toInt().coerceAtLeast(1)
        val snapshot = obtainTextureBitmap(snapshotWidth, snapshotHeight)
        val captured = try { texture.getBitmap(snapshot) } catch (_: Throwable) { null } ?: return false

        val sourceScaleX = captured.width.toFloat() / texture.width.toFloat()
        val sourceScaleY = captured.height.toFloat() / texture.height.toFloat()
        sourceRect.set(
            ((intersection.left - viewLocation[0]) * sourceScaleX).toInt().coerceIn(0, captured.width),
            ((intersection.top - viewLocation[1]) * sourceScaleY).toInt().coerceIn(0, captured.height),
            ((intersection.right - viewLocation[0]) * sourceScaleX).toInt().coerceIn(0, captured.width),
            ((intersection.bottom - viewLocation[1]) * sourceScaleY).toInt().coerceIn(0, captured.height),
        )
        if (sourceRect.width() <= 0 || sourceRect.height() <= 0) return false

        destinationFor(target, strip, intersection, destinationRect)
        Canvas(target).drawBitmap(captured, sourceRect, destinationRect, paint)
        return true
    }

    fun compositeSurfaceAsync(
        root: View,
        target: Bitmap,
        strip: BackdropStrip,
        frameGeneration: Long,
        isTargetCurrent: (Bitmap) -> Boolean,
        onReady: (Bitmap) -> Unit,
        onDropped: () -> Unit,
    ): Boolean {
        if (surfaceCopyInFlight) return false
        val surface = findSurface(root, strip) ?: return false
        if (!surface.isAttachedToWindow || surface.width <= 0 || surface.height <= 0) return false

        surface.getLocationOnScreen(viewLocation)
        val intersection = intersection(strip, viewLocation[0], viewLocation[1], surface.width, surface.height)
            ?: return false
        sourceRect.set(
            intersection.left - viewLocation[0],
            intersection.top - viewLocation[1],
            intersection.right - viewLocation[0],
            intersection.bottom - viewLocation[1],
        )
        destinationFor(target, strip, intersection, destinationRect)
        val destination = RectF(destinationRect)
        val snapshot = obtainSurfaceBitmap(
            destination.width().toInt().coerceAtLeast(1),
            destination.height().toInt().coerceAtLeast(1),
        )
        val requestGeneration = frameGeneration
        surfaceCopyInFlight = true
        try {
            PixelCopy.request(
                surface,
                Rect(sourceRect),
                snapshot,
                { result ->
                    surfaceCopyInFlight = false
                    val valid = result == PixelCopy.SUCCESS && requestGeneration == generation &&
                        !target.isRecycled && !snapshot.isRecycled && isTargetCurrent(target)
                    if (valid) {
                        try {
                            // PixelCopy can report SUCCESS for the decoder's yellow/neutral tiled
                            // hand-off buffer. Keep the last completed video frame and retry rather
                            // than baking that placeholder underneath otherwise healthy host UI.
                            if (NativeCore.isSurfacePixelCopyPlaceholder(snapshot)) {
                                onDropped()
                                return@request
                            }
                            Canvas(target).drawBitmap(snapshot, null, destination, paint)
                            onReady(target)
                        } catch (_: Throwable) {
                            onDropped()
                        }
                    } else {
                        onDropped()
                    }
                },
                mainHandler,
            )
            return true
        } catch (_: Throwable) {
            surfaceCopyInFlight = false
            onDropped()
            return false
        }
    }

    private fun findTexture(root: View, strip: BackdropStrip): TextureView? {
        val cached = textureRef?.get()
        if (cached != null && cached.isAvailable && usable(cached) && intersectionArea(cached, strip) > 0L) {
            return cached
        }
        val found = findBest(root, TextureView::class.java, strip)
        textureRef = found?.let(::WeakReference)
        return found
    }

    private fun findSurface(root: View, strip: BackdropStrip): SurfaceView? {
        val cached = surfaceRef?.get()
        if (cached != null && usable(cached) && intersectionArea(cached, strip) > 0L) return cached
        val found = findBest(root, SurfaceView::class.java, strip)
        surfaceRef = found?.let(::WeakReference)
        return found
    }

    private fun <T : View> findBest(root: View, type: Class<T>, strip: BackdropStrip): T? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        var best: T? = null
        var bestArea = 0L
        while (queue.isNotEmpty() && visited++ < 1600) {
            val view = queue.removeFirst()
            if (type.isInstance(view) && usable(view)) {
                val area = intersectionArea(view, strip)
                if (area > bestArea) {
                    @Suppress("UNCHECKED_CAST")
                    best = view as T
                    bestArea = area
                }
            }
            if (view is ViewGroup) {
                // Later children are visually above earlier ones; prefer the incoming page while
                // two full-size surfaces coexist during a host transition.
                for (index in view.childCount - 1 downTo 0) queue.addLast(view.getChildAt(index))
            }
        }
        return best
    }

    private fun usable(view: View): Boolean = view.isAttachedToWindow && view.isShown &&
        view.visibility == View.VISIBLE && view.alpha > 0.01f && view.width > 0 && view.height > 0

    private fun intersectionArea(view: View, strip: BackdropStrip): Long {
        try { view.getLocationOnScreen(viewLocation) } catch (_: Throwable) { return 0L }
        val rect = intersection(strip, viewLocation[0], viewLocation[1], view.width, view.height)
            ?: return 0L
        return rect.width().toLong() * rect.height().toLong()
    }

    private fun intersection(strip: BackdropStrip, x: Int, y: Int, width: Int, height: Int): Rect? {
        val left = maxOf(strip.left, x)
        val top = maxOf(strip.top, y)
        val right = minOf(strip.left + strip.width, x + width)
        val bottom = minOf(strip.top + strip.height, y + height)
        if (right <= left || bottom <= top) return null
        return Rect(left, top, right, bottom)
    }

    private fun destinationFor(target: Bitmap, strip: BackdropStrip, intersection: Rect, out: RectF) {
        val scaleX = target.width.toFloat() / strip.width.toFloat()
        val scaleY = target.height.toFloat() / strip.height.toFloat()
        out.set(
            (intersection.left - strip.left) * scaleX,
            (intersection.top - strip.top) * scaleY,
            (intersection.right - strip.left) * scaleX,
            (intersection.bottom - strip.top) * scaleY,
        )
    }

    private fun obtainTextureBitmap(width: Int, height: Int): Bitmap {
        val reusable = textureBitmap
        if (reusable != null && !reusable.isRecycled &&
            textureBitmapWidth == width && textureBitmapHeight == height
        ) return reusable
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setHasAlpha(false)
            textureBitmap = it
            textureBitmapWidth = width
            textureBitmapHeight = height
        }
    }

    private fun obtainSurfaceBitmap(width: Int, height: Int): Bitmap {
        val reusable = surfaceBitmap
        if (reusable != null && !reusable.isRecycled &&
            surfaceBitmapWidth == width && surfaceBitmapHeight == height
        ) return reusable
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setHasAlpha(false)
            surfaceBitmap = it
            surfaceBitmapWidth = width
            surfaceBitmapHeight = height
        }
    }
}
