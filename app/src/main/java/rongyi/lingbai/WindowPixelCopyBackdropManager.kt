package rongyi.lingbai

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Backdrop source for glass hosted in a separate application-panel window.
 *
 * The glass is hosted in a separate TYPE_APPLICATION_PANEL window, so PixelCopy of the original
 * Activity window includes hardware-composed page pixels but can never copy the glass itself.
 * The source rectangle is derived solely from the glass view's physical screen coordinates.
 */
internal class WindowPixelCopyBackdropManager(
    private val glassView: View,
    private val onBitmapUpdated: (Bitmap?) -> Unit,
) {
    companion object {
        private const val DEFAULT_CAPTURE_HEIGHT_DP = 72f
        private const val DOWNSAMPLE = 0.22f
        private const val DOUYIN_SCREEN_CAPTURE_DOWNSAMPLE = 0.20f
        private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        private const val SCREEN_CAPTURE_DISABLE_AFTER_FAILURES = 2
        private const val TAG = "LiquidGlassWindowCapture"
        private val SCREEN_CAPTURE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "LiquidGlass-ScreenCapture").apply { isDaemon = true }
        }
        private const val DEFAULT_MOVING_INTERVAL_MS = 28L
        private const val MIN_MOVING_INTERVAL_MS = 24L
        private const val FORCED_CAPTURE_MIN_GAP_MS = 16L
        private const val DEFAULT_SETTLE_DELAY_MS = 88L
        private const val FAILED_RETRY_MS = 50L
        private const val HOST_DRAW_BURST_MS = 72L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var activityRef: WeakReference<Activity>? = null
    private var listenerRootRef: WeakReference<View>? = null
    private var drawListener: ViewTreeObserver.OnDrawListener? = null
    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    private var running = false
    private var capturePending = true
    private var forceCapture = true
    private var captureInFlight = false
    private var captureScheduled = false
    private var captureDueAt = 0L
    private var moving = false
    private var transitionFrozen = false
    private var preserveLastFrameOnBlank = true
    private var blankFrameFallbackColor: Int = 0
    private var movingIntervalMs = DEFAULT_MOVING_INTERVAL_MS
    private var adaptiveMovingIntervalMs = DEFAULT_MOVING_INTERVAL_MS
    private var captureLatencyEmaMs = 0f
    private var settleDelayMs = DEFAULT_SETTLE_DELAY_MS
    private var captureHeightDp = DEFAULT_CAPTURE_HEIGHT_DP
    // Native compositor blur supplies the live background. PixelCopy is retained only as an
    // occasional settled-frame texture for the 3.42 lens/refraction overlay.
    private var nativeAssistMode = false
    private var lastCaptureRequestAt = 0L
    private var lastHostDrawAt = 0L
    private var generation = 0L
    private var screenCaptureFailureCount = 0
    private var screenCaptureDisabled = false
    private var douyinScreenCaptureEnabled = true
    private var screenCaptureSuccessLogged = false
    private var douyinFallbackPublished = false
    private var douyinWindowFallbackLogged = false

    private var frontBitmap: Bitmap? = null
    private var workBitmap: Bitmap? = null
    private val glassLocation = IntArray(2)
    private val decorLocation = IntArray(2)

    private val captureRunnable = Runnable {
        captureScheduled = false
        captureDueAt = 0L
        if (running && !transitionFrozen && capturePending && !captureInFlight) captureNow()
    }

    private val transitionEndRunnable = Runnable {
        if (!running) return@Runnable
        transitionFrozen = false
        moving = false
        capturePending = true
        forceCapture = true
        attachListeners(force = true)
        scheduleCapture(0L)
    }

    @Suppress("UNUSED_PARAMETER")
    fun setCaptureProfile(
        movingIntervalMs: Long,
        settleDelayMs: Long,
        includeHardwareLayers: Boolean,
    ) {
        this.movingIntervalMs = movingIntervalMs.coerceIn(MIN_MOVING_INTERVAL_MS, 180L)
        adaptiveMovingIntervalMs = this.movingIntervalMs
        this.settleDelayMs = settleDelayMs.coerceIn(72L, 320L)
    }

    fun setPreserveLastFrameOnBlank(enabled: Boolean) {
        preserveLastFrameOnBlank = enabled
    }

    fun setNativeAssistMode(enabled: Boolean) {
        if (nativeAssistMode == enabled) return
        nativeAssistMode = enabled
        detachListeners()
        glassView.removeCallbacks(captureRunnable)
        captureScheduled = false
        captureDueAt = 0L
        if (enabled) {
            capturePending = frontBitmap == null
            forceCapture = capturePending
        } else {
            capturePending = true
            forceCapture = true
        }
        if (running) {
            attachListeners(force = true)
            if (capturePending) scheduleCapture(0L)
        }
    }

    fun setBlankFrameFallbackColor(color: Int) {
        blankFrameFallbackColor = color
    }


    /**
     * 26.66 press-only transparent mode deliberately uses Window PixelCopy for Douyin.
     * The experimental hidden ScreenCapture path can return vendor-tinted frames on some ROMs;
     * keeping it disabled in this mode makes the pressed lens sample the same composed window that
     * the user actually sees.
     */
    fun setDouyinScreenCaptureEnabled(enabled: Boolean) {
        if (douyinScreenCaptureEnabled == enabled) return
        douyinScreenCaptureEnabled = enabled
        if (!enabled) {
            screenCaptureDisabled = true
            screenCaptureFailureCount = 0
            generation++
            captureInFlight = false
            capturePending = true
            forceCapture = true
            clearBuffers(clearConsumer = false)
            if (running) scheduleCapture(0L)
        } else {
            screenCaptureDisabled = false
            screenCaptureFailureCount = 0
            screenCaptureSuccessLogged = false
            capturePending = true
            forceCapture = true
            if (running) scheduleCapture(0L)
        }
    }

    fun setCaptureHeightDp(heightDp: Float, referenceNavigation: Boolean = false) {
        // Keep the existing liquid-glass budget. Reference HyperOS bars also cover the system
        // inset and need a taller strip; never stretch a 72dp capture over that larger region.
        val next = heightDp.coerceIn(56f, if (referenceNavigation) 160f else 72f)
        if (kotlin.math.abs(captureHeightDp - next) < 0.01f) return
        captureHeightDp = next
        resetBuffer()
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
        generation++
        running = true
        transitionFrozen = false
        captureInFlight = false
        capturePending = true
        forceCapture = true
        moving = false
        lastCaptureRequestAt = 0L
        adaptiveMovingIntervalMs = movingIntervalMs
        captureLatencyEmaMs = 0f
        lastHostDrawAt = 0L
        screenCaptureFailureCount = 0
        screenCaptureDisabled = !douyinScreenCaptureEnabled && activity.packageName == DOUYIN_PACKAGE
        screenCaptureSuccessLogged = false
        douyinFallbackPublished = false
        douyinWindowFallbackLogged = false
        attachListeners(force = true)
        scheduleCapture(0L)
    }

    fun resume() {
        if (running) {
            attachListeners()
            if (frontBitmap == null) markDirty()
            return
        }
        start(activityRef?.get() ?: ActivityFinder.from(glassView.context))
    }

    fun rebind(activity: Activity?) {
        if (activity != null) activityRef = WeakReference(activity)
        generation++
        detachListeners()
        cancelCallbacks()
        captureInFlight = false
        running = true
        transitionFrozen = false
        capturePending = true
        forceCapture = true
        moving = false
        lastCaptureRequestAt = 0L
        adaptiveMovingIntervalMs = movingIntervalMs
        captureLatencyEmaMs = 0f
        lastHostDrawAt = 0L
        screenCaptureFailureCount = 0
        screenCaptureDisabled = !douyinScreenCaptureEnabled &&
            activityRef?.get()?.packageName == DOUYIN_PACKAGE
        screenCaptureSuccessLogged = false
        douyinFallbackPublished = false
        douyinWindowFallbackLogged = false
        clearBuffers(clearConsumer = true)
        attachListeners(force = true)
        scheduleCapture(0L)
    }

    fun pause() {
        generation++
        running = false
        transitionFrozen = false
        capturePending = false
        forceCapture = false
        captureInFlight = false
        moving = false
        lastCaptureRequestAt = 0L
        adaptiveMovingIntervalMs = movingIntervalMs
        captureLatencyEmaMs = 0f
        lastHostDrawAt = 0L
        cancelCallbacks()
        detachListeners()
    }

    fun stop() {
        pause()
        activityRef = null
        clearBuffers(clearConsumer = true)
    }

    fun suspendAndClear() {
        pause()
        clearBuffers(clearConsumer = true)
    }

    fun resetBuffer() {
        clearBuffers(clearConsumer = true)
        markDirty()
    }

    fun markDirty() {
        if (!running) return
        capturePending = true
        forceCapture = true
        scheduleCapture(0L)
    }

    fun markMoving() {
        if (!running || glassView.visibility != View.VISIBLE) return
        moving = true
        if (nativeAssistMode) {
            capturePending = false
            forceCapture = false
            return
        }
        capturePending = true
        forceCapture = false
        scheduleCapture(0L)
    }

    fun markIdle() {
        if (!running || transitionFrozen) return
        moving = false
        if (nativeAssistMode) return
        capturePending = true
        forceCapture = false
        scheduleCapture(0L)
    }


    private fun recordMotionSignal() {
        moving = true
        if (!nativeAssistMode) {
            capturePending = true
            forceCapture = false
            scheduleCapture(0L)
        }
    }


    fun notifyContentTransition(settleDelayMs: Long = 120L) {
        if (!running) return
        transitionFrozen = true
        moving = true
        capturePending = false
        forceCapture = false
        glassView.removeCallbacks(transitionEndRunnable)
        glassView.postDelayed(transitionEndRunnable, settleDelayMs.coerceIn(48L, 420L))
    }

    fun finishContentTransition() {
        if (!running) return
        glassView.removeCallbacks(transitionEndRunnable)
        transitionFrozen = false
        moving = false
        capturePending = true
        forceCapture = true
        scheduleCapture(0L)
    }

    private fun resolveDecor(): View? {
        val activity = activityRef?.get() ?: ActivityFinder.from(glassView.context) ?: return null
        if (activity.isFinishing || activity.isDestroyed) return null
        activityRef = WeakReference(activity)
        return try { activity.window.decorView } catch (_: Throwable) { null }
    }

    private fun attachListeners(force: Boolean = false) {
        if (!running) return
        val decor = resolveDecor() ?: return
        val current = listenerRootRef?.get()
        if (!force && current === decor && drawListener != null && scrollListener != null &&
            decor.viewTreeObserver.isAlive
        ) return
        detachListeners()

        val onDraw = ViewTreeObserver.OnDrawListener {
            if (!running || transitionFrozen || glassView.visibility != View.VISIBLE) return@OnDrawListener
            val now = SystemClock.uptimeMillis()
            val delta = if (lastHostDrawAt == 0L) Long.MAX_VALUE else now - lastHostDrawAt
            lastHostDrawAt = now
            if (delta in 1..HOST_DRAW_BURST_MS) {
                recordMotionSignal()
            }
            if (nativeAssistMode) return@OnDrawListener
            // Retain the 3.70 continuous-capture contract. Host draw callbacks are a wake-up
            // signal, while the single-in-flight callback below keeps exactly one newest request
            // alive. This prevents a quiet-window timeout from freezing the glass texture.
            capturePending = true
            forceCapture = false
            scheduleCapture(0L)
        }
        val onScroll = ViewTreeObserver.OnScrollChangedListener { markMoving() }
        try {
            val observer = decor.viewTreeObserver
            observer.addOnDrawListener(onDraw)
            observer.addOnScrollChangedListener(onScroll)
            drawListener = onDraw
            scrollListener = onScroll
            listenerRootRef = WeakReference(decor)
        } catch (_: Throwable) {
            drawListener = null
            scrollListener = null
            listenerRootRef = null
        }
    }

    private fun detachListeners() {
        val root = listenerRootRef?.get()
        val draw = drawListener
        val scroll = scrollListener
        listenerRootRef = null
        drawListener = null
        scrollListener = null
        if (root == null) return
        try {
            val observer = root.viewTreeObserver
            if (observer.isAlive) {
                if (draw != null) observer.removeOnDrawListener(draw)
                if (scroll != null) observer.removeOnScrollChangedListener(scroll)
            }
        } catch (_: Throwable) {}
    }

    private fun scheduleCapture(delayMs: Long) {
        if (!running || transitionFrozen || captureInFlight) return
        var delay = delayMs.coerceAtLeast(0L)
        val interval = if (forceCapture) FORCED_CAPTURE_MIN_GAP_MS else adaptiveMovingIntervalMs
        if (lastCaptureRequestAt != 0L) {
            val remaining = interval - (SystemClock.uptimeMillis() - lastCaptureRequestAt)
            if (remaining > delay) delay = remaining
        }
        val dueAt = SystemClock.uptimeMillis() + delay
        if (captureScheduled && captureDueAt != 0L && captureDueAt <= dueAt + 1L) return
        if (captureScheduled) glassView.removeCallbacks(captureRunnable)
        captureScheduled = true
        captureDueAt = dueAt
        if (delay <= 0L) glassView.postOnAnimation(captureRunnable)
        else glassView.postDelayed(captureRunnable, delay)
    }

    private fun captureNow() {
        if (!running || transitionFrozen || captureInFlight || !capturePending ||
            !glassView.isAttachedToWindow || glassView.width <= 0 || glassView.height <= 0
        ) return
        val activity = activityRef?.get() ?: return
        val decor = resolveDecor() ?: return
        if (!decor.isAttachedToWindow || decor.width <= 0 || decor.height <= 0) return

        val now = SystemClock.uptimeMillis()
        val requestedForce = forceCapture
        val interval = if (requestedForce) FORCED_CAPTURE_MIN_GAP_MS else adaptiveMovingIntervalMs
        if (lastCaptureRequestAt != 0L) {
            val remaining = interval - (now - lastCaptureRequestAt)
            if (remaining > 0L) {
                scheduleCapture(remaining)
                return
            }
        }

        try {
            glassView.getLocationOnScreen(glassLocation)
            decor.getLocationOnScreen(decorLocation)
            val density = glassView.resources.displayMetrics.density.coerceAtLeast(0.5f)
            val requestedHeight = (captureHeightDp * density).toInt().coerceAtLeast(1)
            val desiredScreenLeft = glassLocation[0]
            val desiredScreenTop = glassLocation[1] +
                (glassView.height - requestedHeight).coerceAtLeast(0)

            val sampleWidth = minOf(glassView.width, decor.width).coerceAtLeast(1)
            val sampleHeight = minOf(requestedHeight, decor.height).coerceAtLeast(1)
            val decorScreenRight = decorLocation[0] + decor.width
            val decorScreenBottom = decorLocation[1] + decor.height
            val sampleScreenLeft = desiredScreenLeft.coerceIn(
                decorLocation[0],
                maxOf(decorLocation[0], decorScreenRight - sampleWidth),
            )
            val sampleScreenTop = desiredScreenTop.coerceIn(
                decorLocation[1],
                maxOf(decorLocation[1], decorScreenBottom - sampleHeight),
            )
            val sourceRect = Rect(
                sampleScreenLeft - decorLocation[0],
                sampleScreenTop - decorLocation[1],
                sampleScreenLeft - decorLocation[0] + sampleWidth,
                sampleScreenTop - decorLocation[1] + sampleHeight,
            )
            if (sourceRect.width() <= 0 || sourceRect.height() <= 0) return

            // 26.64: never let the Douyin lens lose its sampling source while the experimental
            // capture path is starting or falling back. This is render-only and is replaced as
            // soon as either ScreenCapture or Window PixelCopy publishes a real frame.
            if (activity.packageName == DOUYIN_PACKAGE && frontBitmap == null) {
                publishDouyinFallback(sampleWidth, sampleHeight)
            }

            // 26.64 experiment: Douyin first tries SurfaceFlinger/WindowManager ScreenCapture.
            // ScreenCapture uses display coordinates, unlike PixelCopy(activity.window), whose crop
            // is relative to the Activity decor. The glass lives in its own application-panel
            // window, and that panel SurfaceControl is excluded so the lens never samples itself.
            if (activity.packageName == DOUYIN_PACKAGE && !screenCaptureDisabled) {
                val displayCrop = Rect(
                    sampleScreenLeft,
                    sampleScreenTop,
                    sampleScreenLeft + sampleWidth,
                    sampleScreenTop + sampleHeight,
                )
                val excludeSurface = DisplayScreenCaptureCompat.resolveSurfaceControl(glassView)
                if (excludeSurface != null) {
                    captureDouyinScreenCapture(
                        activity = activity,
                        displayCrop = displayCrop,
                        excludeSurface = excludeSurface,
                        requestedForce = requestedForce,
                        requestStartedAt = now,
                    )
                    return
                } else {
                    noteScreenCaptureFailure(IllegalStateException("overlay SurfaceControl unavailable"))
                }
            }

            val targetWidth = (sampleWidth * DOWNSAMPLE).toInt().coerceAtLeast(1)
            val targetHeight = (sampleHeight * DOWNSAMPLE).toInt().coerceAtLeast(1)
            val target = obtainWorkBitmap(targetWidth, targetHeight)
            target.eraseColor(Color.TRANSPARENT)

            capturePending = false
            forceCapture = false
            captureInFlight = true
            lastCaptureRequestAt = now
            val requestGeneration = generation
            val requestStartedNs = SystemClock.elapsedRealtimeNanos()
            PixelCopy.request(
                activity.window,
                sourceRect,
                target,
                callback@{ result ->
                    if (!running || requestGeneration != generation) return@callback
                    captureInFlight = false
                    if (result == PixelCopy.SUCCESS) {
                        val blank = NativeCore.isWindowPixelCopyBlank(target)
                        if (blank && blankFrameFallbackColor != 0) {
                            // Gallery's page can stop above its floating native row. Once that row
                            // moves off-screen, PixelCopy legitimately sees the black window floor;
                            // in the light Gallery host this floor is visually meant to be white.
                            target.eraseColor(blankFrameFallbackColor)
                            publish(target)
                        } else if (!blank || !preserveLastFrameOnBlank) {
                            publish(target)
                            if (activity.packageName == DOUYIN_PACKAGE && screenCaptureDisabled &&
                                !douyinWindowFallbackLogged
                            ) {
                                douyinWindowFallbackLogged = true
                                douyinFallbackPublished = false
                                ModuleLog.i(
                                    TAG,
                                    "Douyin Window PixelCopy FALLBACK ACTIVE ${target.width}x${target.height} @0.22x",
                                )
                            }
                        } else {
                            capturePending = true
                            forceCapture = requestedForce
                        }
                    } else {
                        capturePending = true
                        forceCapture = requestedForce
                    }
                    recordCaptureLatency(
                        (SystemClock.elapsedRealtimeNanos() - requestStartedNs) / 1_000_000f,
                    )
                    if (!transitionFrozen && running) {
                        if (!nativeAssistMode && glassView.visibility == View.VISIBLE) {
                            // Continue at the adaptive 3.70 cadence even after the host becomes
                            // idle. Only one PixelCopy can be in flight, so callbacks cannot pile
                            // up and the most recent page frame always replaces the previous one.
                            capturePending = true
                            forceCapture = false
                            scheduleCapture(if (result == PixelCopy.SUCCESS) 0L else FAILED_RETRY_MS)
                        } else if (capturePending) {
                            scheduleCapture(if (result == PixelCopy.SUCCESS) 0L else FAILED_RETRY_MS)
                        }
                    }
                },
                handler,
            )
        } catch (_: Throwable) {
            captureInFlight = false
            capturePending = true
            forceCapture = requestedForce
            scheduleCapture(if (requestedForce) FORCED_CAPTURE_MIN_GAP_MS else FAILED_RETRY_MS)
        }
    }


    private fun captureDouyinScreenCapture(
        activity: Activity,
        displayCrop: Rect,
        excludeSurface: Any,
        requestedForce: Boolean,
        requestStartedAt: Long,
    ) {
        capturePending = false
        forceCapture = false
        captureInFlight = true
        lastCaptureRequestAt = requestStartedAt
        val requestGeneration = generation
        val requestStartedNs = SystemClock.elapsedRealtimeNanos()
        SCREEN_CAPTURE_EXECUTOR.execute {
            val result = DisplayScreenCaptureCompat.captureDisplay(
                activity = activity,
                sourceCrop = displayCrop,
                frameScale = DOUYIN_SCREEN_CAPTURE_DOWNSAMPLE,
                excludeSurfaceControl = excludeSurface,
            )
            handler.post callback@{
                if (!running || requestGeneration != generation) return@callback
                captureInFlight = false
                val bitmap = normalizeDouyinScreenCapture(result.bitmap)
                if (bitmap != null) {
                    screenCaptureFailureCount = 0
                    douyinFallbackPublished = false
                    publish(bitmap)
                    if (!screenCaptureSuccessLogged) {
                        screenCaptureSuccessLogged = true
                        ModuleLog.i(TAG, "Douyin ScreenCapture ACTIVE ${bitmap.width}x${bitmap.height} @0.20x")
                    }
                } else {
                    // A non-null ScreenshotHardwareBuffer can still decode to an all-transparent
                    // or all-black frame on unprivileged app processes. Treat that as a hard
                    // failure instead of publishing it forever and starving the PixelCopy path.
                    val failure = result.error ?: IllegalStateException(
                        "ScreenCapture produced blank/unusable bitmap"
                    )
                    noteScreenCaptureFailure(failure)
                    screenCaptureDisabled = true
                    capturePending = true
                    forceCapture = true
                }
                recordCaptureLatency(
                    (SystemClock.elapsedRealtimeNanos() - requestStartedNs) / 1_000_000f,
                )
                if (!transitionFrozen && running) {
                    if (!nativeAssistMode && glassView.visibility == View.VISIBLE) {
                        capturePending = true
                        forceCapture = false
                        // If hidden ScreenCapture was rejected, the next pass immediately uses the
                        // proven Window PixelCopy path. Otherwise keep a single capture in flight.
                        scheduleCapture(0L)
                    } else if (capturePending) {
                        scheduleCapture(0L)
                    }
                }
            }
        }
    }

    private fun normalizeDouyinScreenCapture(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val software = try {
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            if (NativeCore.isWindowPixelCopyBlank(software)) null else software
        } catch (_: Throwable) {
            software
        }
    }

    private fun publishDouyinFallback(sampleWidth: Int, sampleHeight: Int) {
        if (douyinFallbackPublished || frontBitmap != null) return
        val width = (sampleWidth * DOWNSAMPLE).toInt().coerceAtLeast(2)
        val height = (sampleHeight * DOWNSAMPLE).toInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(
                Color.rgb(5, 6, 8),
                Color.rgb(15, 17, 22),
                Color.rgb(7, 8, 11),
                Color.rgb(17, 19, 25),
                Color.rgb(5, 6, 8),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = Color.argb(28, 58, 68, 84)
        canvas.drawCircle(width * 0.22f, height * 0.34f, height * 0.72f, paint)
        paint.color = Color.argb(18, 34, 42, 56)
        canvas.drawCircle(width * 0.78f, height * 0.70f, height * 0.60f, paint)
        douyinFallbackPublished = true
        publish(bitmap)
        ModuleLog.i(TAG, "Douyin temporary liquid fallback ${width}x${height}")
    }

    private fun noteScreenCaptureFailure(error: Throwable?) {
        screenCaptureFailureCount++
        if (screenCaptureFailureCount >= SCREEN_CAPTURE_DISABLE_AFTER_FAILURES) {
            screenCaptureDisabled = true
            ModuleLog.w(
                TAG,
                "Douyin hidden ScreenCapture disabled after $screenCaptureFailureCount failures; " +
                    "falling back to Window PixelCopy",
                error,
            )
        } else {
            ModuleLog.w(TAG, "Douyin hidden ScreenCapture attempt failed", error)
        }
    }


    private fun recordCaptureLatency(latencyMs: Float) {
        if (!latencyMs.isFinite() || latencyMs <= 0f) return
        captureLatencyEmaMs = NativeCore.updateEma(captureLatencyEmaMs, latencyMs)
        adaptiveMovingIntervalMs = NativeCore.adaptiveInterval(
            NativeCore.PROFILE_PIXEL_COPY,
            captureLatencyEmaMs,
            movingIntervalMs,
            adaptiveMovingIntervalMs,
        )
    }

    private fun publish(target: Bitmap) {
        val previous = frontBitmap
        frontBitmap = target
        // ScreenCapture.asBitmap() is normally an immutable hardware Bitmap. Never feed one back
        // into PixelCopy as a mutable work target if the experimental path falls back later.
        workBitmap = previous?.takeIf {
            it !== target && !it.isRecycled && it.isMutable && it.config != Bitmap.Config.HARDWARE &&
                it.width == target.width && it.height == target.height
        }
        try { onBitmapUpdated(target) } catch (_: Throwable) {}
    }

    private fun obtainWorkBitmap(width: Int, height: Int): Bitmap {
        val reusable = workBitmap
        if (reusable != null && reusable !== frontBitmap && !reusable.isRecycled && reusable.isMutable &&
            reusable.config != Bitmap.Config.HARDWARE &&
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
        douyinFallbackPublished = false
    }

    private fun cancelCallbacks() {
        glassView.removeCallbacks(captureRunnable)
        glassView.removeCallbacks(transitionEndRunnable)
        captureScheduled = false
        captureDueAt = 0L
    }

}
