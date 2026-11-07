package rongyi.lingbai

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.ArrayDeque

/**
 * Low-jank backdrop capture for the injected Miuix glass bar.
 *
 * The liquid-glass renderer is identical to the uploaded WeChat module; this class only supplies
 * its background pixels. Redrawing QQ's complete content tree every display frame is much more
 * expensive than doing the same in WeChat, so moving captures target roughly 35-42 fps on light hosts and automatically back off when capture cost rises.
 *
 * The tiny 0.30x backdrop strip keeps refreshing while visible. OnDraw/scroll callbacks only pull
 * the next deadline forward; they no longer decide when the capture loop is allowed to stop.
 */
internal class CoolapkLegacyBitmapBackdropManager(
    private val glassView: View,
    private val onBitmapUpdated: (Bitmap?) -> Unit,
) {
    companion object {
        private const val DEFAULT_CAPTURE_HEIGHT_DP = 84f
        private const val DOWNSAMPLE = 0.30f
        private const val MOVING_CAPTURE_INTERVAL_MS = 24L
        private const val FORCED_CAPTURE_MIN_GAP_MS = 16L
        private const val HOST_DRAW_BURST_WINDOW_MS = 60L
    }

    private var activityRef: WeakReference<Activity>? = null
    private var sourceRef: WeakReference<View>? = null
    private var manualSourceRef: WeakReference<View>? = null
    private var listenerSourceRef: WeakReference<View>? = null
    private var excludedViewRefs: List<WeakReference<View>> = emptyList()
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var hostDrawListener: ViewTreeObserver.OnDrawListener? = null

    private var running = false
    private var captureRequested = true
    private var forceCapture = true
    private var rendering = false
    private var moving = false
    private var lastCaptureAt = 0L
    private var delayedFrameScheduled = false
    private var delayedFrameDueAt = 0L
    private var followUpFrameScheduled = false
    private var suppressNextObservedDraw = false
    private var suppressObservedDrawUntil = 0L
    private var lastObservedHostDrawAt = 0L
    private var adaptiveMovingIntervalMs = MOVING_CAPTURE_INTERVAL_MS
    private var captureCostEmaMs = 0f
    private var hasValidCapture = false
    // Tracks whether the currently published strip contains pixels copied from a SurfaceView.
    // Compass can hide/recreate CameraView2 without changing its bottom-tab index; in that case
    // preserving a "blank" hierarchy frame would otherwise pin the last camera image forever.
    private var publishedFrameContainsSurface = false
    private var preserveLastFrameOnBlank = false
    private var preservePhysicalCoordinates = false
    private var hardwareLayerCaptureEnabled = true
    // Compass 26.56: normal Compass/Level pages do not capture any backdrop at all.
    // The manager stays attached only so it can cheaply notice when the real camera SurfaceView
    // becomes truly visible; View.draw()/PixelCopy start only after that happens.
    private var requireVisibleSurfaceForCapture = false
    // Compass 26.60: CameraView2 process/surface lifetime is NOT the preview state. MIUI keeps the
    // camera streaming behind its normal black Compass/Gradienter UI. The host's private e1 flag
    // follows camera_mask_out/in and is the authoritative visual gate: only true may expose the
    // live Surface to the liquid lens. cameraExitLatched remains a hard close boundary, but process
    // open/start alone never clears it or makes preview visible.
    private var cameraExitLatched = false
    private var cameraPreviewVisible = false
    // Direct visual backup: CompassActivity.v0 is the camera mask ImageView in 17.1.0.1.
    // Cache it so every capture can verify that the normal black mask is actually gone before
    // sampling the still-running camera Surface. This makes the visual UI state stronger than
    // process/surface lifetime and also protects against a missed e1 callback.
    private var cameraMaskRef: WeakReference<View>? = null
    private var captureHeightDp = DEFAULT_CAPTURE_HEIGHT_DP
    // With HyperOS native blur active, observe host motion but capture only the final quiet frame.
    // This preserves the original 3.42 lens texture without redrawing the host during scrolling.
    private var nativeAssistMode = false

    // Compose may still sample the published bitmap on RenderThread after this class returns from
    // onBitmapUpdated(). Never draw the next host frame into that same mutable object.
    private var publishedBitmap: Bitmap? = null
    private var workBitmap: Bitmap? = null
    private var workCanvas: Canvas? = null

    // Hardware-backed host pages (Flutter, UC/Triver, video/coupon/cart containers) are not
    // included in View.draw(Canvas). Composite visible TextureView/SurfaceView pixels over the
    // normal hierarchy capture so each tab can supply the real backdrop.
    private var textureSnapshotBitmap: Bitmap? = null
    private var textureSnapshotWidth = 0
    private var textureSnapshotHeight = 0
    private var textureViewRef: WeakReference<TextureView>? = null
    private val textureLocation = IntArray(2)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textureSrc = Rect()
    private val textureDst = RectF()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceSnapshotBitmap: Bitmap? = null
    private var surfaceSnapshotWidth = 0
    private var surfaceSnapshotHeight = 0
    private var surfaceViewRef: WeakReference<SurfaceView>? = null
    private val surfaceLocation = IntArray(2)
    private val surfaceSrc = Rect()
    private val surfaceDst = RectF()
    private var surfaceCopyInFlight = false
    private var surfaceCopyTarget: Bitmap? = null
    private var surfaceCopyGeneration = 0L
    private var captureGeneration = 0L

    // Page switches in Eleme/NetEase can keep the old Fragment/Surface attached for a short
    // animation window. Keep the last good glass frame during that window, then capture the new
    // page once its layout has settled. This avoids displaying a stretched old Surface on top of
    // a freshly drawn new hierarchy.
    private var contentTransitionActive = false

    private val glassLocation = IntArray(2)
    private val contentLocation = IntArray(2)

    private val delayedFrame = Runnable {
        delayedFrameScheduled = false
        delayedFrameDueAt = 0L
        if (running && captureRequested) requestHostFrame()
    }

    private val followUpFrame = Runnable {
        followUpFrameScheduled = false
        if (running && captureRequested) requestHostFrame()
    }

    private val transitionSettle = Runnable {
        if (!running) return@Runnable
        contentTransitionActive = false
        setMoving(false)
        invalidateHardwareFrame()
        captureRequested = true
        forceCapture = true
        ensureListeners()
        requestHostFrame()
        // A number of host tabs finish their own enter animation after the first stable layout.
        // Two follow-up captures keep the result live without sampling the intermediate page.
        glassView.postDelayed({ if (running && !contentTransitionActive) markDirty() }, 120L)
        glassView.postDelayed({ if (running && !contentTransitionActive) markDirty() }, 300L)
    }

    fun start(activity: Activity?) {
        if (activity == null) return
        val sameActivity = activityRef?.get() === activity
        activityRef = WeakReference(activity)
        if (!sameActivity) cameraMaskRef = null
        if (running && sameActivity) {
            ensureListeners()
            if (!hasValidCapture) {
                captureRequested = true
                forceCapture = true
                requestHostFrame()
            }
            return
        }
        running = true
        captureRequested = true
        forceCapture = true
        rendering = false
        lastCaptureAt = 0L
        lastObservedHostDrawAt = 0L
        adaptiveMovingIntervalMs = MOVING_CAPTURE_INTERVAL_MS
        captureCostEmaMs = 0f
        contentTransitionActive = false
        setMoving(false)
        ensureListeners(force = true)
        requestHostFrame()
    }

    fun resume() {
        if (running) {
            ensureListeners()
            if (!hasValidCapture) {
                captureRequested = true
                forceCapture = true
                requestHostFrame()
            }
            return
        }
        running = true
        captureRequested = true
        forceCapture = true
        rendering = false
        lastCaptureAt = 0L
        lastObservedHostDrawAt = 0L
        adaptiveMovingIntervalMs = MOVING_CAPTURE_INTERVAL_MS
        captureCostEmaMs = 0f
        contentTransitionActive = false
        setMoving(false)
        ensureListeners(force = true)
        requestHostFrame()
    }

    /**
     * QQ may replace android.R.id.content or its render nodes while returning from a mini program.
     * Re-resolve the host source and rebuild the captured backdrop instead of reusing a stale GPU
     * layer that still points at the previous page.
     */
    fun rebind(activity: Activity?) {
        if (activity != null && activityRef?.get() !== activity) cameraMaskRef = null
        if (activity != null) activityRef = WeakReference(activity)
        detachListeners()
        sourceRef = null
        listenerSourceRef = null
        running = true
        captureRequested = true
        forceCapture = true
        rendering = false
        lastCaptureAt = 0L
        lastObservedHostDrawAt = 0L
        adaptiveMovingIntervalMs = MOVING_CAPTURE_INTERVAL_MS
        captureCostEmaMs = 0f
        glassView.removeCallbacks(delayedFrame)
        glassView.removeCallbacks(followUpFrame)
        glassView.removeCallbacks(transitionSettle)
        delayedFrameScheduled = false
        delayedFrameDueAt = 0L
        followUpFrameScheduled = false
        suppressNextObservedDraw = false
        suppressObservedDrawUntil = 0L
        contentTransitionActive = false
        invalidateHardwareFrame()
        releaseCaptureBitmapImmediately(clearConsumer = true)
        ensureListeners(force = true)
        requestHostFrame()
    }

    fun pause() {
        running = false
        captureRequested = false
        forceCapture = false
        rendering = false
        lastCaptureAt = 0L
        lastObservedHostDrawAt = 0L
        setMoving(false)
        glassView.removeCallbacks(delayedFrame)
        glassView.removeCallbacks(followUpFrame)
        glassView.removeCallbacks(transitionSettle)
        delayedFrameScheduled = false
        delayedFrameDueAt = 0L
        followUpFrameScheduled = false
        suppressNextObservedDraw = false
        suppressObservedDrawUntil = 0L
        contentTransitionActive = false
        invalidateHardwareFrame()
        detachListeners()
    }

    fun stop() {
        pause()
        sourceRef = null
        manualSourceRef = null
        activityRef = null
        cameraMaskRef = null
        releaseCaptureBitmapImmediately(clearConsumer = true)
    }

    fun setSource(view: View?) {
        val current = manualSourceRef?.get()
        if (current === view) {
            // Polling resolves the same host root repeatedly. Real host draws and explicit tab
            // transitions already request a new frame, so forcing a capture here creates a visible
            // periodic hitch without improving freshness.
            if (running) ensureListeners()
            return
        }
        manualSourceRef = if (view == null) null else WeakReference(view)
        detachListeners()
        sourceRef = null
        releaseTextureSnapshot()
        captureRequested = true
        forceCapture = true
        if (running) {
            ensureListeners(force = true)
            requestHostFrame()
        }
    }

    fun setPreserveLastFrameOnBlank(enabled: Boolean) {
        preserveLastFrameOnBlank = enabled
    }

    fun setPreservePhysicalCoordinates(enabled: Boolean) {
        if (preservePhysicalCoordinates == enabled) return
        preservePhysicalCoordinates = enabled
        invalidateHardwareFrame()
        resetBuffer()
    }

    fun setNativeAssistMode(enabled: Boolean) {
        if (nativeAssistMode == enabled) return
        nativeAssistMode = enabled
        glassView.removeCallbacks(delayedFrame)
        glassView.removeCallbacks(followUpFrame)
        delayedFrameScheduled = false
        delayedFrameDueAt = 0L
        followUpFrameScheduled = false
        if (enabled) {
            captureRequested = !hasValidCapture
            forceCapture = captureRequested
        } else {
            captureRequested = true
            forceCapture = true
        }
        detachListeners()
        if (running) {
            ensureListeners(force = true)
            if (captureRequested) requestHostFrame()
        }
    }

    fun setHardwareLayerCaptureEnabled(enabled: Boolean) {
        if (hardwareLayerCaptureEnabled == enabled) return
        hardwareLayerCaptureEnabled = enabled
        invalidateHardwareFrame()
        releaseTextureSnapshot()
        if (running) markDirty()
    }

    fun setRequireVisibleSurfaceForCapture(enabled: Boolean) {
        if (requireVisibleSurfaceForCapture == enabled) return
        requireVisibleSurfaceForCapture = enabled
        invalidateHardwareFrame()
        if (enabled && publishedBitmap != null) {
            releaseCaptureBitmapImmediately(clearConsumer = true)
        }
        captureRequested = true
        forceCapture = true
        if (running) {
            ensureListeners(force = true)
            requestHostFrame()
        }
    }

    /**
     * Hard camera-close boundary. Drop the real frame synchronously. 26.60 also clears the visual
     * preview gate because CameraView2 may continue streaming after the user has returned to the
     * normal black page.
     */
    fun forceCameraBackdropBlackUntilReopened() {
        cameraExitLatched = true
        cameraPreviewVisible = false
        clearRealCameraBackdropNow()
    }

    /**
     * Camera process/resource became available. Do not change visual preview ownership here: MIUI
     * may keep CameraView2 alive indefinitely behind camera_mask_in. If the host mask already says
     * preview is visible, simply pull the next capture forward.
     */
    fun noteCameraProcessStarted() {
        if (running && cameraPreviewVisible && !cameraExitLatched) markDirty()
    }

    /**
     * Authoritative 17.1.0.1 visual state from CompassActivity.e1 / camera_mask_out/in.
     * false immediately returns the glass to its synthetic-black liquid texture even while the
     * underlying camera stream remains alive and moving. true is the only path that can re-enable
     * real Surface/PixelCopy capture.
     */
    fun setCameraPreviewVisible(visible: Boolean) {
        val changed = cameraPreviewVisible != visible
        cameraPreviewVisible = visible
        if (!visible) {
            // Do not clear a hard-close latch here. Only a later explicit mask-out/e1=true event
            // may unlock capture again. The preview gate itself is already enough to keep the
            // ordinary Compass/Gradienter page black while CameraView2 continues streaming.
            clearRealCameraBackdropNow()
            return
        }
        // A host mask-out event is stronger than an old close latch: it means the app now intends
        // the live preview to be on-screen again.
        cameraExitLatched = false
        invalidateHardwareFrame()
        releaseCaptureBitmapImmediately(clearConsumer = true)
        captureRequested = true
        forceCapture = true
        if (running) {
            ensureListeners(force = changed)
            requestHostFrame()
        }
    }

    /** Compatibility entry: process-open itself is never allowed to expose the Surface. */
    fun resumeCameraCaptureAfterOpen() {
        noteCameraProcessStarted()
    }

    private fun clearRealCameraBackdropNow() {
        glassView.removeCallbacks(delayedFrame)
        glassView.removeCallbacks(followUpFrame)
        delayedFrameScheduled = false
        delayedFrameDueAt = 0L
        followUpFrameScheduled = false
        captureRequested = true
        forceCapture = true
        invalidateHardwareFrame()
        releaseCaptureBitmapImmediately(clearConsumer = true)
        if (running) {
            ensureListeners(force = true)
            requestHostFrame()
        }
    }


    fun setExcludedViews(views: Array<View>) {
        val next = views.asSequence()
            .filter { it !== glassView }
            .distinctBy { System.identityHashCode(it) }
            .map(::WeakReference)
            .toList()
        val current = excludedViewRefs.mapNotNull { it.get() }
        val incoming = next.mapNotNull { it.get() }
        if (current.size == incoming.size && current.indices.all { current[it] === incoming[it] }) return
        excludedViewRefs = next
        invalidateHardwareFrame()
        captureRequested = true
        forceCapture = true
        if (running) requestHostFrame()
    }

    fun setCaptureHeightDp(heightDp: Float) {
        val next = heightDp.coerceIn(48f, 160f)
        if (kotlin.math.abs(captureHeightDp - next) < 0.01f) return
        captureHeightDp = next
        resetBuffer()
    }

    fun resetBuffer() {
        releaseCaptureBitmapImmediately(clearConsumer = true)
        markDirty()
    }

    /**
     * A host destination changed capture type (for example Compass SurfaceView -> Level View).
     * Drop the published frame immediately and invalidate any asynchronous PixelCopy callback so
     * an outgoing page can never repaint itself over the incoming page after the tab switch.
     */
    fun resetForPageChange() {
        glassView.removeCallbacks(delayedFrame)
        glassView.removeCallbacks(followUpFrame)
        glassView.removeCallbacks(transitionSettle)
        delayedFrameScheduled = false
        delayedFrameDueAt = 0L
        followUpFrameScheduled = false
        contentTransitionActive = false
        setMoving(false)
        lastCaptureAt = 0L
        invalidateHardwareFrame()
        releaseCaptureBitmapImmediately(clearConsumer = true)
        captureRequested = true
        forceCapture = true
        if (running) {
            ensureListeners(force = true)
            requestHostFrame()
        }
    }

    /**
     * Freeze the current good frame while the host replaces its page hierarchy. The bitmap is not
     * cleared, so the liquid-glass appearance remains visible instead of flashing black/white.
     */
    fun notifyContentTransition(settleDelayMs: Long = 120L) {
        if (!running) return
        contentTransitionActive = true
        setMoving(true)
        captureRequested = false
        forceCapture = false
        invalidateHardwareFrame()
        glassView.removeCallbacks(transitionSettle)
        glassView.postDelayed(transitionSettle, settleDelayMs.coerceIn(48L, 420L))
    }

    fun finishContentTransition() {
        if (!running) return
        glassView.removeCallbacks(transitionSettle)
        contentTransitionActive = false
        setMoving(false)
        invalidateHardwareFrame()
        markDirty()
    }

    private fun releaseCaptureBitmapImmediately(clearConsumer: Boolean) {
        if (clearConsumer) {
            try { onBitmapUpdated(null) } catch (_: Throwable) {}
        }
        workCanvas = null
        workBitmap = null
        publishedBitmap = null
        if (!surfaceCopyInFlight) surfaceCopyTarget = null
        hasValidCapture = false
        publishedFrameContainsSurface = false
        // Do not explicitly recycle a bitmap that may still be referenced by Compose's render
        // thread. Dropping the strong reference is safer and the tiny strip is reclaimed by GC.
        releaseTextureSnapshot()
    }

    private fun releaseTextureSnapshot() {
        textureViewRef = null
        surfaceViewRef = null
        // Do not mark an in-flight PixelCopy as idle here. Its callback still owns the destination
        // bitmap. The generation checks below make the result a no-op and schedule a fresh frame.
        surfaceCopyGeneration++
        textureSnapshotBitmap = null
        textureSnapshotWidth = 0
        textureSnapshotHeight = 0
        // TextureView.getBitmap and PixelCopy can finish on a later frame. Avoid recycling their
        // destinations while platform code may still be writing into them.
        surfaceSnapshotBitmap = null
        surfaceSnapshotWidth = 0
        surfaceSnapshotHeight = 0
    }

    private fun invalidateHardwareFrame() {
        captureGeneration++
        textureViewRef = null
        surfaceViewRef = null
        surfaceCopyGeneration++
    }

    /** Page, theme, lifecycle and touch-settle changes bypass the moving-frame limiter. */
    fun markDirty() {
        if (!running) return
        captureRequested = true
        forceCapture = true
        ensureListeners()
        requestHostFrame()
    }

    /** Scroll movement and idle time share one continuous adaptive capture loop. */
    fun markMoving() {
        if (!running || glassView.visibility != View.VISIBLE) return
        setMoving(true)
        if (nativeAssistMode) {
            captureRequested = false
            forceCapture = false
            ensureListeners()
            return
        }
        captureRequested = true
        forceCapture = false
        ensureListeners()
    }

    fun markIdle() {
        if (!running || contentTransitionActive) return
        setMoving(false)
        if (nativeAssistMode) return
        captureRequested = true
        forceCapture = false
        ensureListeners()
        scheduleDelayedFrame(1L)
    }


    private fun recordMotionSignal() {
        setMoving(true)
        if (!nativeAssistMode) {
            captureRequested = true
            forceCapture = false
        }
    }


    private fun setMoving(value: Boolean) {
        if (moving == value) return
        moving = value
    }

    private fun requestHostFrame() {
        try { (sourceRef?.get() ?: resolveSource())?.postInvalidateOnAnimation() } catch (_: Throwable) {}
    }

    private fun resolveSource(): View? {
        val manual = manualSourceRef?.get()
        if (manual != null && manual.isAttachedToWindow && manual.width > 0 && manual.height > 0) {
            sourceRef = WeakReference(manual)
            return manual
        }
        val cached = sourceRef?.get()
        if (cached != null && cached.isAttachedToWindow && cached.width > 0 && cached.height > 0) {
            return cached
        }

        val activity = activityRef?.get() ?: ActivityFinder.from(glassView.context) ?: return null
        if (activity.isDestroyed || activity.isFinishing) return null
        activityRef = WeakReference(activity)

        val source = try {
            activity.findViewById<View>(android.R.id.content)
                ?: activity.window?.decorView
                ?: glassView.rootView
        } catch (_: Throwable) {
            activity.window?.decorView ?: glassView.rootView
        }
        sourceRef = WeakReference(source)
        return source
    }

    private fun ensureListeners(force: Boolean = false) {
        if (!running) return
        val source = resolveSource() ?: return
        val attachedSource = listenerSourceRef?.get()
        if (!force && preDrawListener != null && hostDrawListener != null &&
            attachedSource === source && source.viewTreeObserver.isAlive
        ) return

        detachListeners()

        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (!running || !captureRequested || rendering ||
                contentTransitionActive || glassView.visibility != View.VISIBLE ||
                !glassView.isAttachedToWindow
            ) return@OnPreDrawListener true

            val now = SystemClock.uptimeMillis()
            // Ordinary motion follows the adaptive 32-84 ms budget. Explicit refreshes and the
            // final quiet frame use a 16 ms fast lane, and an earlier queued throttle is replaced
            // rather than allowed to make the settled bitmap visibly trail the host page.
            val requestedForce = forceCapture
            val interval = if (requestedForce) FORCED_CAPTURE_MIN_GAP_MS else adaptiveMovingIntervalMs
            if (lastCaptureAt != 0L) {
                val remaining = interval - (now - lastCaptureAt)
                if (remaining > 0L) {
                    scheduleDelayedFrame(remaining)
                    return@OnPreDrawListener true
                }
            }

            captureRequested = false
            forceCapture = false
            // The capture happens during this traversal's pre-draw. Ignore only the matching
            // onDraw callback, but expire the guard quickly so a cancelled frame cannot swallow
            // the next genuine host update.
            suppressNextObservedDraw = true
            suppressObservedDrawUntil = now + 48L
            if (captureFrame(source)) {
                lastCaptureAt = now
                if (!nativeAssistMode && running && !contentTransitionActive) {
                    captureRequested = true
                    forceCapture = false
                    val elapsedSinceCaptureStart = SystemClock.uptimeMillis() - lastCaptureAt
                    scheduleDelayedFrame((adaptiveMovingIntervalMs - elapsedSinceCaptureStart).coerceAtLeast(1L))
                }
            } else {
                captureRequested = true
                forceCapture = requestedForce
                scheduleDelayedFrame(if (requestedForce) FORCED_CAPTURE_MIN_GAP_MS else adaptiveMovingIntervalMs)
            }
            true
        }

        val onDraw = ViewTreeObserver.OnDrawListener {
            if (!running || rendering || glassView.visibility != View.VISIBLE) return@OnDrawListener
            if (contentTransitionActive) return@OnDrawListener
            val now = SystemClock.uptimeMillis()
            if (suppressNextObservedDraw) {
                val matchesCaptureTraversal = now <= suppressObservedDrawUntil
                suppressNextObservedDraw = false
                suppressObservedDrawUntil = 0L
                if (matchesCaptureTraversal) return@OnDrawListener
            }

            // Consecutive host draws normally mean scrolling, flinging or an animation. Do not
            // bypass the capture limiter on every draw; that old path made 32 ms throttling
            // ineffective and forced an extra full View.draw() on nearly every display frame.
            val delta = if (lastObservedHostDrawAt == 0L) Long.MAX_VALUE
                else now - lastObservedHostDrawAt
            lastObservedHostDrawAt = now
            if (delta !in 1..HOST_DRAW_BURST_WINDOW_MS) {
                invalidateHardwareFrame()
            }
            if (delta in 1..HOST_DRAW_BURST_WINDOW_MS) {
                recordMotionSignal()
            }
            if (nativeAssistMode) return@OnDrawListener
            captureRequested = true
            forceCapture = !moving
            if (!followUpFrameScheduled) {
                followUpFrameScheduled = true
                glassView.postOnAnimation(followUpFrame)
            }
        }

        try {
            val observer = source.viewTreeObserver
            observer.addOnPreDrawListener(preDraw)
            observer.addOnDrawListener(onDraw)
            preDrawListener = preDraw
            hostDrawListener = onDraw
            listenerSourceRef = WeakReference(source)
        } catch (_: Throwable) {
            preDrawListener = null
            hostDrawListener = null
            listenerSourceRef = null
        }
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

    private fun detachListeners() {
        val preDraw = preDrawListener
        val onDraw = hostDrawListener
        val source = listenerSourceRef?.get()
        preDrawListener = null
        hostDrawListener = null
        listenerSourceRef = null
        if (source != null) {
            try {
                val observer = source.viewTreeObserver
                if (observer.isAlive) {
                    if (preDraw != null) observer.removeOnPreDrawListener(preDraw)
                    if (onDraw != null) observer.removeOnDrawListener(onDraw)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun captureFrame(contentView: View): Boolean {
        if (rendering || !running) return false
        if (surfaceCopyInFlight) {
            // PixelCopy still owns the work buffer. Keep the dirty bit and capture the newest host
            // frame once its callback has released that destination.
            captureRequested = true
            return false
        }
        val captureStartedNs = SystemClock.elapsedRealtimeNanos()
        val activity = activityRef?.get() ?: return false
        if (activity.isDestroyed || activity.isFinishing) return false
        if (!glassView.isAttachedToWindow) return false
        if (contentView.width <= 0 || contentView.height <= 0) return false

        rendering = true
        var completed = false
        val frameGeneration = ++captureGeneration
        try {
            glassView.getLocationOnScreen(glassLocation)
            contentView.getLocationOnScreen(contentLocation)

            val density = contentView.resources.displayMetrics.density
            val requestedHeight = (captureHeightDp * density).toInt().coerceAtLeast(1)
            val stripTopInGlass = (glassView.height - requestedHeight).coerceAtLeast(0)

            // Some music applications keep their real page above a mini player or native
            // navigation shell. Legacy hosts retain the old bottom-edge clamp; NetEase opts into
            // exact physical screen-coordinate mapping so unrelated pixels are never shifted into
            // the glass when part of the requested strip lies outside the page root.
            // Keep the bitmap in the glass View's physical screen coordinate space.  NetEase's
            // Activity content root can be narrower than the injected overlay (or shifted by a
            // side inset).  The old code clamped captureStartX to zero and then stretched that
            // narrower root across the full glass width.  Album art and cards were therefore
            // enlarged horizontally, which produced the wide blocks visible on “我的”.
            //
            // Always allocate the full glass width, then draw only the real intersection at its
            // correct offset.  Transparent pixels outside the host root stay transparent instead
            // of being replaced by a stretched copy of the left edge.
            val captureWidthPx = glassView.width.coerceAtLeast(1)
            val captureHeightPx = if (preservePhysicalCoordinates) {
                requestedHeight
            } else {
                requestedHeight.coerceAtMost(contentView.height).coerceAtLeast(1)
            }
            val desiredScreenLeft = glassLocation[0]
            val desiredScreenRight = desiredScreenLeft + captureWidthPx
            val desiredScreenTop = glassLocation[1] + stripTopInGlass
            val desiredScreenBottom = desiredScreenTop + captureHeightPx
            val contentScreenLeft = contentLocation[0]
            val contentScreenRight = contentScreenLeft + contentView.width
            val contentScreenTop = contentLocation[1]
            val contentScreenBottom = contentScreenTop + contentView.height
            val intersectScreenLeft = maxOf(desiredScreenLeft, contentScreenLeft)
            val intersectScreenRight = minOf(desiredScreenRight, contentScreenRight)
            if (intersectScreenRight <= intersectScreenLeft) return false

            val captureStartY: Int
            val screenStartY: Int
            val intersectScreenTop: Int
            val intersectScreenBottom: Int
            if (preservePhysicalCoordinates) {
                // Keep every pixel in the same physical screen coordinate as the glass. NetEase's
                // page content can stop above the navigation/mini-player shell. Clamping upward
                // copied an unrelated strip into the glass and made cards/album artwork appear
                // stretched or displaced on 我的. Missing host pixels now stay transparent instead
                // of shifting the whole frame.
                captureStartY = desiredScreenTop - contentScreenTop
                screenStartY = desiredScreenTop
                intersectScreenTop = maxOf(desiredScreenTop, contentScreenTop)
                intersectScreenBottom = minOf(desiredScreenBottom, contentScreenBottom)
                if (intersectScreenBottom <= intersectScreenTop) return false
            } else {
                val desiredStartY = desiredScreenTop - contentScreenTop
                captureStartY = desiredStartY.coerceIn(
                    0,
                    (contentView.height - captureHeightPx).coerceAtLeast(0),
                )
                screenStartY = contentScreenTop + captureStartY
                intersectScreenTop = screenStartY
                intersectScreenBottom = screenStartY + captureHeightPx
            }

            val screenStartX = desiredScreenLeft
            val hardwareSearchRoot = if (requireVisibleSurfaceForCapture) {
                try { activity.window?.decorView ?: contentView } catch (_: Throwable) { contentView }
            } else {
                contentView
            }

            // Compass 26.56 camera-only policy. A camera may be opened while either Compass or
            // Level is selected, and CameraView2 may live outside compass_root. Therefore detect a
            // truly visible SurfaceView in the whole Activity window. Until one exists, do not
            // allocate/draw a backdrop Bitmap and do not issue PixelCopy.
            if (requireVisibleSurfaceForCapture) {
                // 26.60: process/surface liveness is not enough. MIUI deliberately keeps the
                // camera stream alive behind the normal black page. The host camera-mask/e1 state
                // must explicitly expose preview before any View.draw()/PixelCopy work is allowed.
                if (!isHostCameraPreviewVisuallyExposed()) {
                    if (surfaceCopyInFlight || publishedBitmap != null || hasValidCapture ||
                        publishedFrameContainsSurface
                    ) {
                        invalidateHardwareFrame()
                        releaseCaptureBitmapImmediately(clearConsumer = true)
                    }
                    return false
                }
                val visibleCameraSurface = hardwareLayerCaptureEnabled &&
                    findVisibleSurfaceView(hardwareSearchRoot) != null
                if (!visibleCameraSurface) {
                    if (surfaceCopyInFlight || publishedBitmap != null || hasValidCapture ||
                        publishedFrameContainsSurface
                    ) {
                        invalidateHardwareFrame()
                        releaseCaptureBitmapImmediately(clearConsumer = true)
                    }
                    return false
                }
            }

            val targetWidth = (captureWidthPx * DOWNSAMPLE).toInt().coerceAtLeast(1)
            val targetHeight = (captureHeightPx * DOWNSAMPLE).toInt().coerceAtLeast(1)
            val target = obtainWorkBitmap(targetWidth, targetHeight)

            // First record the normal Android hierarchy. Hardware-backed children are composited
            // immediately afterwards; this preserves ordinary text/cards while replacing the black
            // or blank Surface/Texture rectangles with their actual pixels.
            val localClipLeft = intersectScreenLeft - contentScreenLeft
            val localClipRight = intersectScreenRight - contentScreenLeft
            val localClipTop = intersectScreenTop - contentScreenTop
            val localClipBottom = intersectScreenBottom - contentScreenTop
            val canvas = canvasForWorkBitmap(target)
            target.eraseColor(0)
            val saveCount = canvas.save()
            val hideGlass = isAncestorOrSelf(contentView, glassView)
            val oldGlassAlpha = glassView.alpha
            val hiddenExcluded = hideExcludedViewsForCapture(contentView)
            try {
                if (hideGlass) glassView.alpha = 0f
                canvas.scale(DOWNSAMPLE, DOWNSAMPLE)
                if (preservePhysicalCoordinates) {
                    canvas.translate(
                        (contentScreenLeft - desiredScreenLeft).toFloat(),
                        (contentScreenTop - desiredScreenTop).toFloat(),
                    )
                } else {
                    canvas.translate(
                        (contentScreenLeft - desiredScreenLeft).toFloat(),
                        -captureStartY.toFloat(),
                    )
                }
                canvas.clipRect(
                    localClipLeft,
                    localClipTop,
                    localClipRight,
                    localClipBottom,
                )
                contentView.draw(canvas)
            } finally {
                canvas.restoreToCount(saveCount)
                if (hideGlass) glassView.alpha = oldGlassAlpha
                restoreExcludedViews(hiddenExcluded)
            }
            completed = true

            if (hardwareLayerCaptureEnabled) {
                captureTextureStrip(
                    contentView,
                    target,
                    screenStartX,
                    screenStartY,
                    captureWidthPx,
                    captureHeightPx,
                )
            }

            if (completed) {
                // SurfaceView pixels are not present in View.draw(Canvas). Start their copy before
                // rejecting a blank hierarchy frame so a camera-only strip can still complete the
                // same Bitmap. The requested rectangle uses the exact screenStart coordinates.
                val awaitingSurface = hardwareLayerCaptureEnabled && scheduleSurfaceStrip(
                    hardwareSearchRoot,
                    target,
                    frameGeneration,
                    screenStartX,
                    screenStartY,
                    captureWidthPx,
                    captureHeightPx,
                )
                val blank = NativeCore.isLegacyDrawBlank(
                    target,
                    preserveLastFrameOnBlank && hasValidCapture,
                )
                // CameraView2 may disappear while Compass stays on logical tab 0. If the last
                // published frame came from its SurfaceView, a blank ordinary hierarchy is not a
                // transient frame to preserve: it is the new non-camera page. Publish it once so
                // the lens can never remain stuck on the old camera image.
                val clearingStaleSurfaceFrame = !awaitingSurface && publishedFrameContainsSurface
                if (blank && !awaitingSurface && !clearingStaleSurfaceFrame) {
                    captureRequested = true
                    completed = false
                } else if (!awaitingSurface) {
                    hasValidCapture = true
                    publishedFrameContainsSurface = false
                    publishBitmap(target)
                }
            }
        } catch (_: Throwable) {
            captureRequested = true
        } finally {
            rendering = false
            if (completed) {
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - captureStartedNs) / 1_000_000f
                recordCaptureCost(elapsedMs)
            }
        }
        return completed
    }


    private data class HiddenViewAlpha(val view: View, val alpha: Float)

    private fun hideExcludedViewsForCapture(contentRoot: View): List<HiddenViewAlpha> {
        if (excludedViewRefs.isEmpty()) return emptyList()
        val hidden = ArrayList<HiddenViewAlpha>(excludedViewRefs.size)
        for (reference in excludedViewRefs) {
            val view = reference.get() ?: continue
            if (!view.isAttachedToWindow || !isAncestorOrSelf(contentRoot, view)) continue
            val alpha = view.alpha
            if (alpha <= 0.001f) continue
            hidden.add(HiddenViewAlpha(view, alpha))
            try { view.alpha = 0f } catch (_: Throwable) {}
        }
        return hidden
    }

    private fun restoreExcludedViews(hidden: List<HiddenViewAlpha>) {
        for (index in hidden.indices.reversed()) {
            val item = hidden[index]
            try { item.view.alpha = item.alpha } catch (_: Throwable) {}
        }
    }

    private fun isInsideExcludedSubtree(view: View): Boolean {
        if (excludedViewRefs.isEmpty()) return false
        for (reference in excludedViewRefs) {
            val excludedRoot = reference.get() ?: continue
            if (view === excludedRoot || isAncestorOrSelf(excludedRoot, view)) return true
        }
        return false
    }

    private fun isAncestorOrSelf(root: View, child: View): Boolean {
        if (root === child) return true
        var current: android.view.ViewParent? = child.parent
        while (current is View) {
            if (current === root) return true
            current = current.parent
        }
        return false
    }

    private fun recordCaptureCost(costMs: Float) {
        if (!costMs.isFinite() || costMs <= 0f) return
        captureCostEmaMs = NativeCore.updateEma(captureCostEmaMs, costMs)
        adaptiveMovingIntervalMs = NativeCore.adaptiveInterval(
            NativeCore.PROFILE_COOLAPK,
            captureCostEmaMs,
            MOVING_CAPTURE_INTERVAL_MS,
            adaptiveMovingIntervalMs,
        )
    }

    private fun obtainWorkBitmap(width: Int, height: Int): Bitmap {
        val reusable = workBitmap
        if (
            reusable != null && !reusable.isRecycled && reusable !== publishedBitmap &&
            reusable !== surfaceCopyTarget && reusable.width == width && reusable.height == height
        ) return reusable

        workCanvas = null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setHasAlpha(true)
            workBitmap = it
            workCanvas = Canvas(it)
        }
    }

    private fun canvasForWorkBitmap(bitmap: Bitmap): Canvas {
        if (bitmap === workBitmap) {
            return workCanvas ?: Canvas(bitmap).also { workCanvas = it }
        }
        return Canvas(bitmap)
    }

    private fun publishBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val previous = publishedBitmap
        publishedBitmap = bitmap

        // Rotate the previous published frame into the work slot only after the consumer has been
        // handed the replacement. PixelCopy destinations stay quarantined until their callback.
        if (previous != null && previous !== bitmap && previous !== surfaceCopyTarget &&
            !previous.isRecycled && previous.width == bitmap.width && previous.height == bitmap.height
        ) {
            workBitmap = previous
            workCanvas = Canvas(previous)
        } else {
            workBitmap = null
            workCanvas = null
        }
        try { onBitmapUpdated(bitmap) } catch (_: Throwable) {}
    }

    private fun captureTextureStrip(
        source: View,
        target: Bitmap,
        screenStartX: Int,
        screenStartY: Int,
        captureWidthPx: Int,
        captureHeightPx: Int,
    ): Boolean {
        val texture = resolveTextureView(source, screenStartX, screenStartY, captureWidthPx, captureHeightPx)
            ?: return false
        if (!texture.isAvailable || texture.width <= 0 || texture.height <= 0) return false

        texture.getLocationOnScreen(textureLocation)
        val screenEndX = screenStartX + captureWidthPx
        val screenEndY = screenStartY + captureHeightPx
        val textureEndX = textureLocation[0] + texture.width
        val textureEndY = textureLocation[1] + texture.height

        val intersectLeft = maxOf(screenStartX, textureLocation[0])
        val intersectTop = maxOf(screenStartY, textureLocation[1])
        val intersectRight = minOf(screenEndX, textureEndX)
        val intersectBottom = minOf(screenEndY, textureEndY)
        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) return false

        val snapshotWidth = (texture.width * DOWNSAMPLE).toInt().coerceAtLeast(1)
        val snapshotHeight = (texture.height * DOWNSAMPLE).toInt().coerceAtLeast(1)
        val snapshot = obtainTextureSnapshot(snapshotWidth, snapshotHeight)
        val captured = try { texture.getBitmap(snapshot) } catch (_: Throwable) { null }
            ?: return false

        val scaleX = captured.width.toFloat() / texture.width.toFloat()
        val scaleY = captured.height.toFloat() / texture.height.toFloat()
        textureSrc.set(
            ((intersectLeft - textureLocation[0]) * scaleX).toInt().coerceIn(0, captured.width),
            ((intersectTop - textureLocation[1]) * scaleY).toInt().coerceIn(0, captured.height),
            ((intersectRight - textureLocation[0]) * scaleX).toInt().coerceIn(0, captured.width),
            ((intersectBottom - textureLocation[1]) * scaleY).toInt().coerceIn(0, captured.height),
        )
        if (textureSrc.width() <= 0 || textureSrc.height() <= 0) return false

        val targetScaleX = target.width.toFloat() / captureWidthPx.toFloat()
        val targetScaleY = target.height.toFloat() / captureHeightPx.toFloat()
        textureDst.set(
            (intersectLeft - screenStartX) * targetScaleX,
            (intersectTop - screenStartY) * targetScaleY,
            (intersectRight - screenStartX) * targetScaleX,
            (intersectBottom - screenStartY) * targetScaleY,
        )

        val canvas = canvasForWorkBitmap(target)
        canvas.drawBitmap(captured, textureSrc, textureDst, bitmapPaint)
        return true
    }

    private fun scheduleSurfaceStrip(
        source: View,
        target: Bitmap,
        frameGeneration: Long,
        screenStartX: Int,
        screenStartY: Int,
        captureWidthPx: Int,
        captureHeightPx: Int,
    ): Boolean {
        if (surfaceCopyInFlight) return false
        val surface = resolveSurfaceView(source, screenStartX, screenStartY, captureWidthPx, captureHeightPx)
            ?: return false
        if (!surface.isAttachedToWindow || surface.width <= 0 || surface.height <= 0) return false

        surface.getLocationOnScreen(surfaceLocation)
        val screenEndX = screenStartX + captureWidthPx
        val screenEndY = screenStartY + captureHeightPx
        val intersectLeft = maxOf(screenStartX, surfaceLocation[0])
        val intersectTop = maxOf(screenStartY, surfaceLocation[1])
        val intersectRight = minOf(screenEndX, surfaceLocation[0] + surface.width)
        val intersectBottom = minOf(screenEndY, surfaceLocation[1] + surface.height)
        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) return false

        surfaceSrc.set(
            intersectLeft - surfaceLocation[0],
            intersectTop - surfaceLocation[1],
            intersectRight - surfaceLocation[0],
            intersectBottom - surfaceLocation[1],
        )
        val targetScaleX = target.width.toFloat() / captureWidthPx.toFloat()
        val targetScaleY = target.height.toFloat() / captureHeightPx.toFloat()
        surfaceDst.set(
            (intersectLeft - screenStartX) * targetScaleX,
            (intersectTop - screenStartY) * targetScaleY,
            (intersectRight - screenStartX) * targetScaleX,
            (intersectBottom - screenStartY) * targetScaleY,
        )
        val copyWidth = surfaceDst.width().toInt().coerceAtLeast(1)
        val copyHeight = surfaceDst.height().toInt().coerceAtLeast(1)
        val snapshot = obtainSurfaceSnapshot(copyWidth, copyHeight)
        val destination = RectF(surfaceDst)
        val generation = ++surfaceCopyGeneration
        surfaceCopyInFlight = true
        surfaceCopyTarget = target
        try {
            PixelCopy.request(
                surface,
                Rect(surfaceSrc),
                snapshot,
                { result: Int ->
                    surfaceCopyInFlight = false
                    val surfaceStillVisible = try {
                        isCameraSurfaceFrontVisible(surface, hardwareRootForCameraCheck()) &&
                            intersectionArea(
                                surface, screenStartX, screenStartY, captureWidthPx, captureHeightPx,
                            ) > 0L
                    } catch (_: Throwable) {
                        false
                    }
                    val valid = result == PixelCopy.SUCCESS &&
                        generation == surfaceCopyGeneration &&
                        frameGeneration == captureGeneration &&
                        running && target === workBitmap && target === surfaceCopyTarget &&
                        !target.isRecycled && !snapshot.isRecycled
                    if (valid && surfaceStillVisible) {
                        try {
                            if (NativeCore.isSurfacePixelCopyPlaceholder(snapshot)) {
                                // A placeholder can mean CameraView2 is being hidden/recreated. If
                                // the visible lens still contains an older real camera frame,
                                // publish the hierarchy-only target now instead of preserving that
                                // stale camera image indefinitely. A follow-up capture will replace
                                // it again if the camera Surface becomes real on the next frame.
                                surfaceCopyTarget = null
                                if (publishedFrameContainsSurface) {
                                    publishedFrameContainsSurface = false
                                    hasValidCapture = true
                                    publishBitmap(target)
                                }
                                if (running && !contentTransitionActive) markDirty()
                            } else {
                                val canvas = canvasForWorkBitmap(target)
                                canvas.drawBitmap(snapshot, null, destination, bitmapPaint)
                                surfaceCopyTarget = null
                                hasValidCapture = true
                                publishedFrameContainsSurface = true
                                publishBitmap(target)
                            }
                        } catch (_: Throwable) {
                            surfaceCopyTarget = null
                            if (running && !contentTransitionActive) markDirty()
                        }
                    } else if (valid && !surfaceStillVisible) {
                        // CameraView2 was hidden while PixelCopy was in flight. Never publish that
                        // late camera result, and do not publish the hierarchy target either: it was
                        // recorded before the hide and can belong to the outgoing camera sub-page.
                        // Clear immediately, then capture the now-visible Compass hierarchy.
                        surfaceCopyTarget = null
                        releaseCaptureBitmapImmediately(clearConsumer = true)
                        if (running && !contentTransitionActive) markDirty()
                    } else if (running && !contentTransitionActive) {
                        surfaceCopyTarget = null
                        // The host changed page while PixelCopy was running. Capture the current
                        // Surface now that the stale request has completed.
                        markDirty()
                    } else {
                        surfaceCopyTarget = null
                    }
                    if (running && captureRequested && !contentTransitionActive) requestHostFrame()
                },
                mainHandler,
            )
            return true
        } catch (_: Throwable) {
            surfaceCopyInFlight = false
            surfaceCopyTarget = null
            return false
        }
    }

    private fun obtainSurfaceSnapshot(width: Int, height: Int): Bitmap {
        val reusable = surfaceSnapshotBitmap
        if (reusable != null && !reusable.isRecycled &&
            surfaceSnapshotWidth == width && surfaceSnapshotHeight == height
        ) return reusable
        // Async destinations are released by GC rather than recycled while platform code may own them.
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setHasAlpha(false)
            surfaceSnapshotBitmap = it
            surfaceSnapshotWidth = width
            surfaceSnapshotHeight = height
        }
    }

    private fun obtainTextureSnapshot(width: Int, height: Int): Bitmap {
        val reusable = textureSnapshotBitmap
        if (
            reusable != null && !reusable.isRecycled &&
            textureSnapshotWidth == width && textureSnapshotHeight == height
        ) return reusable

        // TextureView may still be filling the previous destination on the render thread.
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setHasAlpha(false)
            textureSnapshotBitmap = it
            textureSnapshotWidth = width
            textureSnapshotHeight = height
        }
    }

    private fun resolveTextureView(
        root: View,
        screenStartX: Int,
        screenStartY: Int,
        width: Int,
        height: Int,
    ): TextureView? {
        val cached = textureViewRef?.get()
        if (cached != null && !isInsideExcludedSubtree(cached) &&
            cached.isAttachedToWindow && cached.isAvailable &&
            cached.visibility == View.VISIBLE && cached.isShown && cached.alpha > 0.01f &&
            intersectionArea(cached, screenStartX, screenStartY, width, height) > 0L
        ) return cached
        val best = findBestHardwareView(root, TextureView::class.java, screenStartX, screenStartY, width, height)
        if (best != null) textureViewRef = WeakReference(best)
        return best
    }

    private fun findVisibleSurfaceView(root: View): SurfaceView? {
        val cached = surfaceViewRef?.get()
        if (cached != null && !isInsideExcludedSubtree(cached) &&
            isCameraSurfaceFrontVisible(cached, root)
        ) return cached

        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1600) {
            val view = queue.removeFirst()
            if (isInsideExcludedSubtree(view)) continue
            if (view is SurfaceView && isCameraSurfaceFrontVisible(view, root)) {
                surfaceViewRef = WeakReference(view)
                return view
            }
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) queue.addLast(view.getChildAt(index))
            }
        }
        return null
    }

    private fun resolveSurfaceView(
        root: View,
        screenStartX: Int,
        screenStartY: Int,
        width: Int,
        height: Int,
    ): SurfaceView? {
        val cached = surfaceViewRef?.get()
        if (cached != null && !isInsideExcludedSubtree(cached) &&
            isCameraSurfaceFrontVisible(cached, root) &&
            intersectionArea(cached, screenStartX, screenStartY, width, height) > 0L
        ) return cached
        val best = findBestHardwareView(root, SurfaceView::class.java, screenStartX, screenStartY, width, height)
        if (best != null) surfaceViewRef = WeakReference(best)
        return best
    }

    private fun <T : View> findBestHardwareView(
        root: View,
        type: Class<T>,
        screenStartX: Int,
        screenStartY: Int,
        width: Int,
        height: Int,
    ): T? {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        var best: T? = null
        var bestArea = 0L
        while (queue.isNotEmpty() && visited++ < 1600) {
            val view = queue.removeFirst()
            if (isInsideExcludedSubtree(view)) continue
            val candidateVisible = if (view is SurfaceView && requireVisibleSurfaceForCapture) {
                isCameraSurfaceFrontVisible(view, root)
            } else {
                isEffectivelyVisibleHardwareView(view)
            }
            if (type.isInstance(view) && candidateVisible) {
                val area = intersectionArea(view, screenStartX, screenStartY, width, height)
                if (area > bestArea) {
                    @Suppress("UNCHECKED_CAST")
                    val candidate = view as T
                    best = candidate
                    bestArea = area
                }
            }
            if (view is ViewGroup) {
                // Android draws later children on top. Visit them first so two full-size
                // TextureView/SurfaceView pages during a tab transition resolve to the incoming
                // page instead of the still-attached outgoing page.
                for (index in view.childCount - 1 downTo 0) queue.addLast(view.getChildAt(index))
            }
        }
        return best
    }

    /**
     * Compass 26.59: a SurfaceView may remain attached, VISIBLE and globally inside the window
     * after CameraView2 has already moved behind the normal Compass/Gradienter page. Android's
     * isShown()/getGlobalVisibleRect() do not account for sibling occlusion, so process/surface
     * liveness cannot be used as the camera-page state. Treat the Surface as camera-visible only
     * when no normal Compass screen is actually drawn above its branch.
     */
    private fun isCameraSurfaceFrontVisible(surface: SurfaceView, root: View): Boolean {
        if (!isEffectivelyVisibleHardwareView(surface)) return false
        if (!requireVisibleSurfaceForCapture) return true
        // 26.60: SurfaceView's process/Z state is deliberately ignored as preview ownership.
        // cameraPreviewVisible comes from the host's camera_mask/e1 state and therefore remains
        // false on the normal black screen even while this Surface is alive and updating.
        return isHostCameraPreviewVisuallyExposed()
    }

    /**
     * Final visual gate for MIUI Compass 17.1.0.1. The camera process can remain alive and its
     * Surface can keep producing moving frames underneath the ordinary black UI. Capture is
     * allowed only when BOTH the host e1 signal says camera_mask_out and the actual v0 mask view
     * is no longer visible. If v0 cannot be resolved, fall back to the already-hooked e1 state.
     */
    private fun isHostCameraPreviewVisuallyExposed(): Boolean {
        if (cameraExitLatched || !cameraPreviewVisible) return false
        val activity = activityRef?.get() ?: return false
        val mask = resolveCompassCameraMask(activity) ?: return true
        return try {
            mask.visibility != View.VISIBLE || !mask.isShown || mask.alpha <= 0.01f
        } catch (_: Throwable) {
            false
        }
    }

    private fun resolveCompassCameraMask(activity: Activity): View? {
        cameraMaskRef?.get()?.let { cached ->
            if (cached.isAttachedToWindow) return cached
        }
        var owner: Class<*>? = activity.javaClass
        var depth = 0
        while (owner != null && depth++ < 8) {
            try {
                val field = owner.getDeclaredField("v0")
                field.isAccessible = true
                val value = field.get(activity)
                if (value is View) {
                    cameraMaskRef = WeakReference(value)
                    return value
                }
            } catch (_: Throwable) {
                // Continue through the Activity class hierarchy.
            }
            owner = owner.superclass
        }
        return null
    }

    private fun hardwareRootForCameraCheck(): View {
        val activity = activityRef?.get()
        return try { activity?.window?.decorView ?: sourceRef?.get() ?: glassView.rootView }
        catch (_: Throwable) { sourceRef?.get() ?: glassView.rootView }
    }

    private fun isCoveredByVisibleCompassPage(surface: SurfaceView, root: View): Boolean {
        val surfaceRect = Rect()
        if (!try { surface.getGlobalVisibleRect(surfaceRect) } catch (_: Throwable) { false }) return true
        if (surfaceRect.width() <= 0 || surfaceRect.height() <= 0) return true
        val surfaceArea = surfaceRect.width().toLong() * surfaceRect.height().toLong()
        if (surfaceArea <= 0L) return true

        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1800) {
            val candidate = queue.removeFirst()
            if (candidate === surface || isInsideExcludedSubtree(candidate)) continue
            val name = candidate.javaClass.name
            val normalCompassPage = name == "com.miui.compass.CompassScreen" ||
                name == "com.miui.compass.GradienterScreen"
            if (normalCompassPage && isEffectivelyVisibleHardwareView(candidate)) {
                val pageRect = Rect()
                val pageVisible = try { candidate.getGlobalVisibleRect(pageRect) } catch (_: Throwable) { false }
                if (pageVisible) {
                    val overlap = Rect(surfaceRect)
                    if (overlap.intersect(pageRect)) {
                        val overlapArea = overlap.width().toLong() * overlap.height().toLong()
                        // Ignore tiny transition slivers. A returned normal page normally covers
                        // almost the complete camera frame; 45% is low enough to catch split/slide
                        // animations before a frozen camera frame becomes noticeable.
                        if (overlapArea * 100L >= surfaceArea * 45L &&
                            isViewDrawnAbove(candidate, surface)
                        ) return true
                    }
                }
            }
            if (candidate is ViewGroup) {
                for (index in candidate.childCount - 1 downTo 0) {
                    queue.addLast(candidate.getChildAt(index))
                }
            }
        }
        return false
    }

    /** Compare two branches at their nearest common ViewGroup using Android Z then child order. */
    private fun isViewDrawnAbove(front: View, back: View): Boolean {
        if (front === back) return false
        val frontAncestors = HashMap<View, View?>()
        var node: View? = front
        var child: View? = null
        var depth = 0
        while (node != null && depth++ < 32) {
            frontAncestors[node] = child
            child = node
            node = node.parent as? View
        }

        node = back
        var backChild: View? = null
        depth = 0
        while (node != null && depth++ < 32) {
            if (frontAncestors.containsKey(node)) {
                val common = node as? ViewGroup ?: return false
                val frontChild = frontAncestors[node] ?: return false
                val backBranch = backChild ?: return false
                if (frontChild === backBranch) return false
                val frontZ = try { frontChild.z } catch (_: Throwable) { 0f }
                val backZ = try { backBranch.z } catch (_: Throwable) { 0f }
                if (kotlin.math.abs(frontZ - backZ) > 0.01f) return frontZ > backZ
                val frontIndex = try { common.indexOfChild(frontChild) } catch (_: Throwable) { -1 }
                val backIndex = try { common.indexOfChild(backBranch) } catch (_: Throwable) { -1 }
                return frontIndex >= 0 && backIndex >= 0 && frontIndex > backIndex
            }
            backChild = node
            node = node.parent as? View
        }
        return false
    }

    /**
     * Re-evaluate camera foreground ownership immediately after a Compass/Gradienter page commit.
     * This is deliberately independent of CameraView2 process lifetime.
     */
    fun recheckCameraFrontVisibilityNow() {
        if (!requireVisibleSurfaceForCapture) return
        if (!isHostCameraPreviewVisuallyExposed()) {
            clearRealCameraBackdropNow()
            return
        }
        val root = hardwareRootForCameraCheck()
        val visible = findVisibleSurfaceView(root) != null
        if (!visible) {
            invalidateHardwareFrame()
            releaseCaptureBitmapImmediately(clearConsumer = true)
            captureRequested = true
            forceCapture = true
        } else if (running) {
            markDirty()
        }
    }

    private fun isEffectivelyVisibleHardwareView(view: View): Boolean {
        if (!view.isAttachedToWindow || view.visibility != View.VISIBLE || !view.isShown ||
            view.alpha <= 0.01f || view.width <= 0 || view.height <= 0
        ) return false
        var ancestor = view.parent
        var depth = 0
        while (ancestor is View && depth++ < 24) {
            if (ancestor.visibility != View.VISIBLE || ancestor.alpha <= 0.01f) return false
            ancestor = ancestor.parent
        }
        val visible = Rect()
        return try { view.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0 }
        catch (_: Throwable) { false }
    }

    private fun intersectionArea(view: View, x: Int, y: Int, width: Int, height: Int): Long {
        if (view.width <= 0 || view.height <= 0) return 0L
        val location = IntArray(2)
        try { view.getLocationOnScreen(location) } catch (_: Throwable) { return 0L }
        val left = maxOf(x, location[0])
        val top = maxOf(y, location[1])
        val right = minOf(x + width, location[0] + view.width)
        val bottom = minOf(y + height, location[1] + view.height)
        if (right <= left || bottom <= top) return 0L
        return (right - left).toLong() * (bottom - top).toLong()
    }

}
