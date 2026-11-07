package rongyi.lingbai

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference

/**
 * Captures the real screen strip behind an injected liquid-glass navigation view.
 *
 * Source selection and frame scheduling are intentionally independent:
 * - ordinary hosts use `android.R.id.content` unless an adapter exposes the exact active page;
 * - direct-window hosts sample the glass rectangle at its physical screen coordinates;
 * - exact page roots are reserved for hosts whose page coordinate space is actually stable;
 * - while visible, the tiny backdrop strip keeps refreshing continuously at the adaptive interval;
 * - TextureView/SurfaceView handling lives in [BackdropHardwareLayerCapture].
 *
 * This is the same small-strip model used by the supplied WeChat reference implementation. It
 * avoids the old promotion/coverage state machine: adapters bind one exact source and capture()
 * maps that source with its real screen coordinates, without guessing or re-parenting it.
 */
internal class BitmapBackdropManager(
    private val glassView: View,
    private val onBitmapUpdated: (Bitmap?) -> Unit,
) {
    companion object {
        private const val DEFAULT_CAPTURE_HEIGHT_DP = 84f
        private const val DEFAULT_DOWNSAMPLE = 0.30f
        private const val DEFAULT_MOVING_INTERVAL_MS = 28L
        private const val MIN_MOVING_INTERVAL_MS = 21L
        private const val LOW_LATENCY_MIN_MOVING_INTERVAL_MS = 12L
        private const val LOW_LATENCY_DEFAULT_MOVING_INTERVAL_MS = 16L
        private const val FORCED_CAPTURE_MIN_GAP_MS = 16L
        private const val DEFAULT_SETTLE_DELAY_MS = 104L
        private const val FAILED_CAPTURE_RETRY_MS = 50L
        private const val EXACT_SOURCE_MIN_OVERLAP_DP = 64f
        private const val EXACT_SOURCE_BOTTOM_TOLERANCE_DP = 8f
        private const val EXACT_SOURCE_MIN_WIDTH_FRACTION = 0.90f
        private const val EXACT_SOURCE_MIN_CAPTURE_HEIGHT_FRACTION = 0.70f
    }

    private var activityRef: WeakReference<Activity>? = null
    private var sourceRef: WeakReference<View>? = null
    private var listenerRootRef: WeakReference<View>? = null
    // Most hosts are safest when sampled from the Activity content root. A small number of hosts
    // expose a stable page container whose coordinate space is more precise; those adapters opt in.
    private var exactSourceEnabled = false

    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    private var running = false
    private var captureHeightDp = DEFAULT_CAPTURE_HEIGHT_DP
    private var capturePending = true
    private var forceCapture = true
    private var capturing = false
    private var moving = false
    private var transitionFrozen = false
    private var delayedFrameScheduled = false
    private var delayedFrameDueAt = 0L
    private var lastCaptureAt = 0L
    private var movingIntervalMs = DEFAULT_MOVING_INTERVAL_MS
    private var adaptiveMovingIntervalMs = DEFAULT_MOVING_INTERVAL_MS
    private var captureCostEmaMs = 0f
    private var settleDelayMs = DEFAULT_SETTLE_DELAY_MS
    private var includeHardwareLayers = true
    private var preserveLastFrameOnBlank = false
    // QQ can redraw the small 0.30x bottom strip without janking on current builds. In this
    // dedicated mode the capture scheduler may follow one 120 Hz frame behind (12-16 ms) instead
    // of being clamped to the generic 24 ms floor used by heavier hosts.
    private var lowLatencyCaptureMode = false
    // HyperOS native blur already follows every compositor frame. In assist mode this manager
    // keeps only a low-frequency pixel texture for the 3.42 refraction/lens layers: motion signals
    // reset the quiet window, but no View.draw() is performed until scrolling has actually settled.
    private var nativeAssistMode = false
    // Two tiny strip buffers avoid mutating the bitmap currently consumed by Compose and make the
    // blank-frame guard allocation-free. No bitmap is recycled while a render thread may own it.
    private var frontBitmap: Bitmap? = null
    private var workBitmap: Bitmap? = null
    // SurfaceView pixels arrive later than View.draw(). Keep their destination outside the
    // published double buffer until PixelCopy has produced one complete frame.
    private var pendingSurfaceTarget: Bitmap? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0

    private val glassLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private val hardwareCapture = BackdropHardwareLayerCapture()

    private val delayedFrame = Runnable {
        delayedFrameScheduled = false
        delayedFrameDueAt = 0L
        if (running && capturePending && !transitionFrozen) requestHostFrame()
    }

    private val transitionEnd = Runnable {
        if (!running) return@Runnable
        transitionFrozen = false
        setMoving(false)
        hardwareCapture.invalidate()
        capturePending = true
        forceCapture = true
        attachListeners()
        requestHostFrame()
    }

    fun setCaptureHeightDp(heightDp: Float) {
        val next = heightDp.coerceIn(48f, 160f)
        if (kotlin.math.abs(captureHeightDp - next) < 0.01f) return
        captureHeightDp = next
        resetBuffer()
    }

    fun setCaptureProfile(
        movingIntervalMs: Long,
        settleDelayMs: Long,
        includeHardwareLayers: Boolean,
    ) {
        val minimumInterval = if (lowLatencyCaptureMode) {
            LOW_LATENCY_MIN_MOVING_INTERVAL_MS
        } else {
            MIN_MOVING_INTERVAL_MS
        }
        this.movingIntervalMs = movingIntervalMs.coerceIn(minimumInterval, 180L)
        adaptiveMovingIntervalMs = this.movingIntervalMs
        this.settleDelayMs = settleDelayMs.coerceIn(72L, 320L)
        if (this.includeHardwareLayers != includeHardwareLayers) {
            this.includeHardwareLayers = includeHardwareLayers
            hardwareCapture.invalidate()
            markDirty()
        }
    }

    fun setLowLatencyCaptureMode(enabled: Boolean) {
        if (lowLatencyCaptureMode == enabled) return
        lowLatencyCaptureMode = enabled
        val minimumInterval = if (enabled) {
            LOW_LATENCY_MIN_MOVING_INTERVAL_MS
        } else {
            MIN_MOVING_INTERVAL_MS
        }
        val preferredInterval = if (enabled) {
            LOW_LATENCY_DEFAULT_MOVING_INTERVAL_MS
        } else {
            DEFAULT_MOVING_INTERVAL_MS
        }
        movingIntervalMs = if (enabled) {
            minOf(movingIntervalMs, preferredInterval).coerceAtLeast(minimumInterval)
        } else {
            movingIntervalMs.coerceAtLeast(minimumInterval)
        }
        adaptiveMovingIntervalMs = movingIntervalMs
        captureCostEmaMs = 0f
        glassView.removeCallbacks(delayedFrame)
        delayedFrameScheduled = false
        delayedFrameDueAt = 0L
        if (running && !transitionFrozen) {
            capturePending = true
            forceCapture = true
            attachListeners(force = true)
            requestHostFrame()
        }
    }

    fun setPreserveLastFrameOnBlank(enabled: Boolean) {
        preserveLastFrameOnBlank = enabled
    }

    fun setNativeAssistMode(enabled: Boolean) {
        if (nativeAssistMode == enabled) return
        nativeAssistMode = enabled
        detachListeners()
        glassView.removeCallbacks(delayedFrame)
        delayedFrameScheduled = false
        if (enabled) {
            capturePending = frontBitmap == null
            forceCapture = capturePending
        } else {
            capturePending = true
            forceCapture = true
        }
        if (running) {
            attachListeners(force = true)
            if (capturePending) requestHostFrame()
        }
    }

    fun start(activity: Activity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        val sameActivity = activityRef?.get() === activity
        activityRef = WeakReference(activity)
        if (running && sameActivity) {
            attachListeners()
            if (frontBitmap == null) markDirty()
            return
        }
        running = true
        transitionFrozen = false
        capturing = false
        capturePending = true
        forceCapture = true
        lastCaptureAt = 0L
        adaptiveMovingIntervalMs = movingIntervalMs
        captureCostEmaMs = 0f
        setMoving(false)
        attachListeners(force = true)
        requestHostFrame()
    }

    fun resume() {
        if (running) {
            attachListeners()
            if (frontBitmap == null) markDirty()
            return
        }
        val activity = activityRef?.get() ?: ActivityFinder.from(glassView.context) ?: return
        start(activity)
    }

    fun rebind(activity: Activity?) {
        if (activity != null) activityRef = WeakReference(activity)
        detachListeners()
        cancelCallbacks()
        hardwareCapture.clear()
        clearBuffers(clearConsumer = true)
        running = true
        transitionFrozen = false
        capturing = false
        capturePending = true
        forceCapture = true
        lastCaptureAt = 0L
        adaptiveMovingIntervalMs = movingIntervalMs
        captureCostEmaMs = 0f
        setMoving(false)
        attachListeners(force = true)
        requestHostFrame()
    }

    fun pause() {
        running = false
        capturePending = false
        forceCapture = false
        capturing = false
        transitionFrozen = false
        lastCaptureAt = 0L
        setMoving(false)
        cancelCallbacks()
        hardwareCapture.invalidate()
        detachListeners()
    }

    fun suspendAndClear() {
        pause()
        clearBuffers(clearConsumer = true)
    }

    fun stop() {
        pause()
        activityRef = null
        hardwareCapture.clear()
        clearBuffers(clearConsumer = true)
    }

    /** Track the visible page as a change hint while capture remains in Activity coordinates. */
    fun setSource(view: View?) {
        val previous = sourceRef?.get()
        if (previous === view) {
            if (running) markDirty()
            return
        }
        sourceRef = view?.let(::WeakReference)
        detachListeners()
        hardwareCapture.invalidate()
        capturePending = true
        forceCapture = true
        if (running) {
            attachListeners(force = true)
            requestHostFrame()
        }
    }

    fun setExactSourceEnabled(enabled: Boolean) {
        if (exactSourceEnabled == enabled) return
        exactSourceEnabled = enabled
        detachListeners()
        hardwareCapture.invalidate()
        capturePending = true
        forceCapture = true
        if (running) {
            attachListeners(force = true)
            requestHostFrame()
        }
    }

    fun resetBuffer() {
        clearBuffers(clearConsumer = true)
        hardwareCapture.clear()
        markDirty()
    }

    fun notifyContentTransition(settleDelayMs: Long = 120L) {
        if (!running) return
        transitionFrozen = true
        capturePending = false
        forceCapture = false
        setMoving(true)
        hardwareCapture.invalidate()
        glassView.removeCallbacks(transitionEnd)
        glassView.postDelayed(transitionEnd, settleDelayMs.coerceIn(48L, 420L))
    }

    fun finishContentTransition() {
        if (!running) return
        glassView.removeCallbacks(transitionEnd)
        transitionFrozen = false
        setMoving(false)
        hardwareCapture.invalidate()
        markDirty()
    }

    fun markDirty() {
        if (!running) return
        capturePending = true
        forceCapture = true
        attachListeners()
        requestHostFrame()
    }

    fun markMoving() {
        if (!running || transitionFrozen || glassView.visibility != View.VISIBLE) return
        setMoving(true)
        if (nativeAssistMode) {
            capturePending = false
            forceCapture = false
            glassView.removeCallbacks(delayedFrame)
            delayedFrameScheduled = false
            attachListeners()
            return
        }
        capturePending = true
        forceCapture = false

        attachListeners()
        val elapsed = if (lastCaptureAt == 0L) Long.MAX_VALUE else
            SystemClock.uptimeMillis() - lastCaptureAt
        val remaining = adaptiveMovingIntervalMs - elapsed
        if (remaining > 0L) scheduleDelayedFrame(remaining) else requestHostFrame()
    }

    fun markIdle() {
        if (!running || transitionFrozen) return
        setMoving(false)
        if (nativeAssistMode) return
        // Do not stop the capture loop when the finger/fling stops. Keeping the tiny 0.30x strip
        // alive prevents delayed animations, video frames and late Compose commits from leaving
        // the glass one frame behind until the next user interaction.
        capturePending = true
        forceCapture = false
        attachListeners()
        scheduleDelayedFrame(1L)
    }



    private fun setMoving(value: Boolean) {
        if (moving == value) return
        moving = value
    }

    private fun resolveCaptureRoot(): View? {
        val activity = activityRef?.get() ?: ActivityFinder.from(glassView.context) ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        activityRef = WeakReference(activity)
        return try {
            val exact = sourceRef?.get()
            if (exactSourceEnabled) {
                // Exact hosts (Cainiao/Meituan) must never fall back to the Activity root while
                // their Fragment/pager is being replaced. That root still contains the collapsed
                // native bar and late Mach layers, so one transient null/short page can repaint
                // the glass with the very mask this mode is meant to exclude. Keep the last valid
                // bitmap and wait for the adapter to publish a usable page source instead.
                if (isUsableExactSource(exact)) exact else null
            } else {
                activity.findViewById<View>(android.R.id.content) ?: activity.window?.decorView
            }
        } catch (_: Throwable) {
            if (exactSourceEnabled) null
            else try { activity.window?.decorView } catch (_: Throwable) { null }
        }
    }

    private fun isUsableExactSource(source: View?): Boolean {
        if (source == null || source === glassView || !source.isAttachedToWindow ||
            source.visibility != View.VISIBLE || source.alpha <= 0.01f ||
            source.width <= 0 || source.height <= 0
        ) return false
        return try {
            val sourceRect = Rect()
            val glassRect = Rect()
            if (!source.getGlobalVisibleRect(sourceRect) || sourceRect.isEmpty) return false
            if (!glassView.getGlobalVisibleRect(glassRect) || glassRect.isEmpty) return false
            val overlap = Rect()
            if (!overlap.setIntersect(sourceRect, glassRect) || overlap.isEmpty) return false
            val density = source.resources.displayMetrics.density.coerceAtLeast(0.5f)
            val requiredWidth = (minOf(sourceRect.width(), glassRect.width()) *
                EXACT_SOURCE_MIN_WIDTH_FRACTION).toInt().coerceAtLeast(1)
            // capture() samples the requested bottom strip of the glass view. Requiring most of it
            // keeps a page with an un-cleared bottom margin from being accepted and then clamped
            // upward, which is what turns the stale page edge into a visible mask while scrolling.
            val captureHeight = (captureHeightDp * density).toInt().coerceAtLeast(1)
            val requiredHeight = maxOf(
                (EXACT_SOURCE_MIN_OVERLAP_DP * density).toInt(),
                (captureHeight * EXACT_SOURCE_MIN_CAPTURE_HEIGHT_FRACTION).toInt(),
            ).coerceAtMost(glassRect.height()).coerceAtLeast(1)
            val bottomTolerance = (EXACT_SOURCE_BOTTOM_TOLERANCE_DP * density)
                .toInt().coerceAtLeast(2)
            val reachesGlassBottom = sourceRect.bottom >= glassRect.bottom - bottomTolerance
            reachesGlassBottom &&
                overlap.width() >= requiredWidth &&
                overlap.height() >= requiredHeight
        } catch (_: Throwable) {
            false
        }
    }

    private fun attachListeners(force: Boolean = false) {
        if (!running) return
        val root = resolveCaptureRoot() ?: return
        val attached = listenerRootRef?.get()
        val alive = try { root.viewTreeObserver.isAlive } catch (_: Throwable) { false }
        if (!force && alive && attached === root && preDrawListener != null &&
            scrollListener != null
        ) return

        detachListeners()

        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (!running || !capturePending || capturing || transitionFrozen ||
                glassView.visibility != View.VISIBLE || !glassView.isAttachedToWindow
            ) return@OnPreDrawListener true

            val now = SystemClock.uptimeMillis()
            // Motion frames use the adaptive host budget. QQ's dedicated low-latency mode may
            // run at 12-16 ms while the generic path keeps its 24 ms floor. Explicit page changes
            // and the final quiet frame use the same fast lane so queued slower work cannot leave
            // the glass visibly behind the host.
            val requestedForce = forceCapture
            val forcedGapMs = if (lowLatencyCaptureMode) 12L else FORCED_CAPTURE_MIN_GAP_MS
            val interval = if (requestedForce) forcedGapMs else adaptiveMovingIntervalMs
            if (lastCaptureAt != 0L) {
                val remaining = interval - (now - lastCaptureAt)
                if (remaining > 0L) {
                    scheduleDelayedFrame(remaining)
                    return@OnPreDrawListener true
                }
            }

            capturePending = false
            forceCapture = false
            val captureStartedNs = SystemClock.elapsedRealtimeNanos()
            if (capture(root)) {
                lastCaptureAt = now
                recordCaptureCost(
                    (SystemClock.elapsedRealtimeNanos() - captureStartedNs) / 1_000_000f,
                )
                if (!nativeAssistMode && running && !transitionFrozen) {
                    capturePending = true
                    forceCapture = false
                    val elapsedSinceCaptureStart = SystemClock.uptimeMillis() - lastCaptureAt
                    scheduleDelayedFrame((adaptiveMovingIntervalMs - elapsedSinceCaptureStart).coerceAtLeast(1L))
                }
            } else {
                capturePending = true
                forceCapture = requestedForce
                scheduleDelayedFrame(if (requestedForce) forcedGapMs else FAILED_CAPTURE_RETRY_MS)
            }
            true
        }

        val onScroll = ViewTreeObserver.OnScrollChangedListener {
            if (running && !capturing && !transitionFrozen &&
                glassView.visibility == View.VISIBLE
            ) {
                markMoving()
            }
        }

        try {
            val observer = root.viewTreeObserver
            observer.addOnPreDrawListener(preDraw)
            observer.addOnScrollChangedListener(onScroll)
            preDrawListener = preDraw
            scrollListener = onScroll
            listenerRootRef = WeakReference(root)
        } catch (_: Throwable) {
            preDrawListener = null
            scrollListener = null
            listenerRootRef = null
        }
    }

    private fun detachListeners() {
        val root = listenerRootRef?.get()
        val preDraw = preDrawListener
        val onScroll = scrollListener
        preDrawListener = null
        scrollListener = null
        listenerRootRef = null
        if (root == null) return
        try {
            val observer = root.viewTreeObserver
            if (observer.isAlive) {
                if (preDraw != null) observer.removeOnPreDrawListener(preDraw)
                if (onScroll != null) observer.removeOnScrollChangedListener(onScroll)
            }
        } catch (_: Throwable) {}
    }

    private fun requestHostFrame() {
        val root = listenerRootRef?.get() ?: resolveCaptureRoot() ?: return
        try { root.postInvalidateOnAnimation() } catch (_: Throwable) {}
    }

    private fun scheduleDelayedFrame(delayMs: Long) {
        val delay = delayMs.coerceAtLeast(1L)
        val dueAt = SystemClock.uptimeMillis() + delay
        if (delayedFrameScheduled && delayedFrameDueAt != 0L && delayedFrameDueAt <= dueAt + 1L) return
        if (delayedFrameScheduled) glassView.removeCallbacks(delayedFrame)
        delayedFrameScheduled = true
        delayedFrameDueAt = dueAt
        glassView.postDelayed(delayedFrame, delay)
    }

    private fun cancelCallbacks() {
        glassView.removeCallbacks(delayedFrame)
        glassView.removeCallbacks(transitionEnd)
        delayedFrameScheduled = false
        delayedFrameDueAt = 0L
    }

    private fun capture(root: View): Boolean {
        if (capturing || !running || !root.isAttachedToWindow ||
            root.width <= 0 || root.height <= 0 ||
            glassView.width <= 0 || glassView.height <= 0
        ) return false
        val activity = activityRef?.get() ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false

        capturing = true
        try {
            glassView.getLocationOnScreen(glassLocation)
            root.getLocationOnScreen(rootLocation)
            val density = root.resources.displayMetrics.density.coerceAtLeast(0.5f)
            val requestedHeight = (captureHeightDp * density).toInt().coerceAtLeast(1)
            val desiredLeft = glassLocation[0]
            val desiredTop = glassLocation[1] +
                (glassView.height - requestedHeight).coerceAtLeast(0)
            val rootRight = rootLocation[0] + root.width
            val rootBottom = rootLocation[1] + root.height
            val sampleWidth = minOf(glassView.width, root.width).coerceAtLeast(1)
            val sampleHeight = minOf(requestedHeight, root.height).coerceAtLeast(1)
            val sampleLeft = desiredLeft.coerceIn(
                rootLocation[0],
                maxOf(rootLocation[0], rootRight - sampleWidth),
            )
            val sampleTop = desiredTop.coerceIn(
                rootLocation[1],
                maxOf(rootLocation[1], rootBottom - sampleHeight),
            )
            val strip = BackdropStrip(
                left = sampleLeft,
                top = sampleTop,
                width = sampleWidth,
                height = sampleHeight,
                scale = DEFAULT_DOWNSAMPLE,
            )
            val targetWidth = (strip.width * strip.scale).toInt().coerceAtLeast(1)
            val targetHeight = (strip.height * strip.scale).toInt().coerceAtLeast(1)
            // The previously resolved Surface may detach while its request is in flight. Guard
            // before resolving the next layer so that transition cannot repaint its destination.
            if (includeHardwareLayers && hardwareCapture.isSurfaceCopyInFlight()) return false
            val hasSurfaceLayer = includeHardwareLayers && hardwareCapture.hasSurfaceLayer(root, strip)
            // Never rotate/repaint the destination of an outstanding PixelCopy. Doing so can make
            // the previous callback stale on every 24 ms capture tick and expose SurfaceView's
            // checker/placeholder layer as the glass texture.
            val target = obtainWorkBitmap(targetWidth, targetHeight)
            target.eraseColor(Color.TRANSPARENT)

            val canvas = Canvas(target)
            val saveCount = canvas.save()
            val hideGlass = isAncestorOrSelf(root, glassView)
            val previousAlpha = glassView.alpha
            try {
                if (hideGlass) glassView.alpha = 0f
                // Root local (0,0) is mapped into the target using screen coordinates. If an exact
                // page stops above the glass, sampleTop clamps to that page's own bottom strip.
                canvas.scale(strip.scale, strip.scale)
                canvas.translate(
                    (rootLocation[0] - strip.left).toFloat(),
                    (rootLocation[1] - strip.top).toFloat(),
                )
                root.draw(canvas)
            } finally {
                canvas.restoreToCount(saveCount)
                if (hideGlass) glassView.alpha = previousAlpha
            }

            val frameGeneration = if (includeHardwareLayers) hardwareCapture.beginFrame() else 0L
            if (includeHardwareLayers) {
                hardwareCapture.compositeTexture(root, target, strip)
            }

            val blank = NativeCore.isViewDrawBlank(
                target,
                preserveLastFrameOnBlank,
            )
            if (blank && !hasSurfaceLayer) return false

            if (hasSurfaceLayer) {
                // Do not publish the View.draw() intermediate. SurfaceView can leave a colorful
                // compositor placeholder rather than a transparent/black hole, so a blank-only
                // test is insufficient. Commit this bitmap only after PixelCopy succeeds.
                pendingSurfaceTarget = target
                val started = hardwareCapture.compositeSurfaceAsync(
                    root = root,
                    target = target,
                    strip = strip,
                    frameGeneration = frameGeneration,
                    isTargetCurrent = { bitmap -> running && pendingSurfaceTarget === bitmap },
                    onReady = { bitmap ->
                        if (pendingSurfaceTarget === bitmap) {
                            pendingSurfaceTarget = null
                        }
                        if (running &&
                            (!preserveLastFrameOnBlank ||
                                !NativeCore.isViewDrawOpaqueBlack(bitmap))
                        ) {
                            publish(bitmap, notifyConsumer = true)
                        }
                        requestNextSurfaceFrame()
                    },
                    onDropped = {
                        pendingSurfaceTarget = null
                        requestNextSurfaceFrame()
                    },
                )
                if (!started) {
                    pendingSurfaceTarget = null
                    return false
                }
            } else {
                publish(target, notifyConsumer = true)
            }
            return true
        } catch (_: Throwable) {
            return false
        } finally {
            capturing = false
        }
    }

    private fun requestNextSurfaceFrame() {
        if (!running || transitionFrozen || nativeAssistMode ||
            glassView.visibility != View.VISIBLE
        ) return
        capturePending = true
        forceCapture = false
        // Pull a retry that may have been parked behind FAILED_CAPTURE_RETRY_MS forward as soon as
        // the single in-flight Surface copy releases its destination.
        scheduleDelayedFrame(1L)
    }


    private fun recordCaptureCost(costMs: Float) {
        if (!costMs.isFinite() || costMs <= 0f) return
        captureCostEmaMs = NativeCore.updateEma(captureCostEmaMs, costMs)
        adaptiveMovingIntervalMs = NativeCore.adaptiveInterval(
            if (lowLatencyCaptureMode) NativeCore.PROFILE_VIEW_LOW_LATENCY
            else NativeCore.PROFILE_VIEW_NORMAL,
            captureCostEmaMs,
            movingIntervalMs,
            adaptiveMovingIntervalMs,
        )
    }

    private fun publish(target: Bitmap, notifyConsumer: Boolean = true) {
        val previousFront = frontBitmap
        frontBitmap = target
        workBitmap = previousFront?.takeIf {
            !it.isRecycled && it.width == target.width && it.height == target.height && it !== target
        }
        bitmapWidth = target.width
        bitmapHeight = target.height
        if (notifyConsumer) {
            try { onBitmapUpdated(target) } catch (_: Throwable) {}
        }
    }

    private fun obtainWorkBitmap(width: Int, height: Int): Bitmap {
        val reusable = workBitmap
        if (reusable != null && reusable !== frontBitmap && !reusable.isRecycled &&
            reusable.width == width && reusable.height == height
        ) return reusable
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setHasAlpha(true)
            workBitmap = it
        }
    }

    private fun clearBuffers(clearConsumer: Boolean) {
        if (clearConsumer) {
            try { onBitmapUpdated(null) } catch (_: Throwable) {}
        }
        frontBitmap = null
        workBitmap = null
        pendingSurfaceTarget = null
        bitmapWidth = 0
        bitmapHeight = 0
    }

    private fun isAncestorOrSelf(ancestor: View, child: View): Boolean {
        var current: View? = child
        var depth = 0
        while (current != null && depth++ < 24) {
            if (current === ancestor) return true
            current = try { current.parent as? View } catch (_: Throwable) { null }
        }
        return false
    }

}
