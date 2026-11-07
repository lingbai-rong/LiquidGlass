package rongyi.lingbai

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import rongyi.lingbai.liquidtab.hook.*
import rongyi.lingbai.liquidtab.hook.adapters.*
import rongyi.lingbai.liquidtab.hook.adapters.qqmusic.QqMusicNavigationState
import rongyi.lingbai.liquidtab.hook.adapters.qqmusic.QqMusicPlayerGlass
import rongyi.lingbai.liquidtab.targets.TargetSpecRegistry
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Native discovery, page state, artwork and input from LiquidTab, with LiquidGlass material. */
class LiquidTabPortAdapter private constructor(activity: Activity, app: TargetApp, settings: TargetAppSettings) :
    UniversalHostAdapter(activity, app, settings) {
    internal val spec = TargetSpecRegistry.byKey.getValue(app.key)
    internal val delegate = TargetAdapterRegistry.forSpec(spec)!!
    internal var state: AppNavigationState? = null
        private set
    private var chrome: AppNativeChromeController? = null
    private var source: ViewGroup? = null
    private var nativeCount = app.labels.size
    private var indices = PortNavigationModel.visibleIndices(nativeCount, settings.hiddenMask, delegate.reverseVisualSlotOrder)
    private var displayLabels = app.labels
    private var nativeSelected = 0
    private var host: MiuixGlassNavView? = null
    private var icons: Array<Bitmap?> = emptyArray()
    private var lastModelAt = 0L
    private var lastArtworkAt = 0L
    private var artworkHash = 0L
    private var pageAllowed = true
    private var disposed = false
    private var generation = 0
    private var observer: ViewTreeObserver? = null
    private val tick = Runnable { frame() }
    private var previousCallback: Window.Callback? = null
    private var observingCallback: Window.Callback? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener { frame(); true }
    private val scroll = ViewTreeObserver.OnScrollChangedListener {
        state?.onContentScroll()
        host?.let { if (it.referenceIdleDeadline > 0L) { it.referenceIdleDeadline = SystemClock.uptimeMillis() + delegate.scrollStopHideDelayMs(nativeSelected); it.msgScrolledToStop = false } }
    }

    override fun getBottomBarRoot(): ViewGroup? {
        val old = source
        if (old != null && old.isAttachedToWindow && old.visibility == View.VISIBLE && delegate.acceptsNavigationSource(old)) {
            updateModel(old)
            return old
        }
        delegate.beforeNavigationScan(activity)
        val root = activity.window.decorView
        val candidate = NavigationFinder.find(root, spec) ?: return null
        val nested = delegate.resolveNestedNavigation(candidate.view)
        val next = nested?.first ?: candidate.view
        if (!delegate.acceptsNavigationSource(next)) return null
        source = next
        lastModelAt = 0L
        nativeCount = nested?.second ?: candidate.slotCount
        delegate.prepareNavigationSource(next, next.resources.displayMetrics.density)
        state?.prepare(next, ::schedule)
        updateModel(next)
        return next
    }

    private fun nativeCells(root: ViewGroup): List<View> {
        if (app == TargetApp.QQMUSIC) return QqMusicNavigationState.cells(root)
        if (app == TargetApp.XIANYU) {
            val shell = rongyi.lingbai.liquidtab.hook.adapters.xianyu.XianyuNavigationState.shell(root)
            val row = rongyi.lingbai.liquidtab.hook.adapters.xianyu.XianyuNavigationState.find(shell, "indicator_itmes") as? ViewGroup
            if (row != null && row.childCount == 5) return (0 until 5).map(row::getChildAt)
        }
        if (app == TargetApp.ZHIHU || app == TargetApp.NETEASE) {
            val count = runCatching { XposedHelpers.callMethod(root, "getTabCount") as? Int }.getOrNull()
                ?: delegate.estimateSlotCount(root, spec)
            if (count != null && count in 2..7) {
                val cells = (0 until count).mapNotNull { i -> runCatching {
                    val tab = XposedHelpers.callMethod(root, "getTabAt", i) ?: return@runCatching null
                    XposedHelpers.callMethod(tab, "getCustomView") as? View
                }.getOrNull() }
                if (cells.size == count) return cells
            }
        }
        val exact = ArrayList<View>()
        fun collect(view: View, depth: Int) {
            if (view is GlassHostLayout || view is AdapterOwnedOverlay) return
            val name = view.javaClass.name
            if (name.endsWith("TabItemLayoutV2") || (app == TargetApp.YOUTUBE && name == "android.widget.Button")) {
                exact.add(view); return
            }
            if (view is ViewGroup && depth < 5) for (i in 0 until view.childCount) collect(view.getChildAt(i), depth + 1)
        }
        collect(root, 0)
        if (exact.size in 2..7) return exact.sortedBy { val p = IntArray(2); it.getLocationOnScreen(p); p[0] }
        val children = (0 until root.childCount).map(root::getChildAt).filter { it !is GlassHostLayout && it.visibility != View.GONE }
        if (children.size in 2..7) return children
        if (children.size == 1 && children[0] is ViewGroup) return nativeCells(children[0] as ViewGroup)
        return emptyList()
    }

    private fun label(cell: View): String? {
        if (cell is TextView && cell.text.isNotBlank()) return cell.text.toString().trim()
        if (cell is ViewGroup) for (i in 0 until cell.childCount) label(cell.getChildAt(i))?.let { return it }
        return cell.contentDescription?.toString()?.takeIf { it.length in 1..20 }
    }

    private fun updateModel(root: ViewGroup) {
        val now = SystemClock.uptimeMillis()
        if (now - lastModelAt < 100L && displayLabels.size == indices.size) return
        lastModelAt = now
        val cells = nativeCells(root)
        if (cells.size in 2..7) nativeCount = cells.size
        delegate.estimateSlotCount(root, spec)?.takeIf { it in 2..7 }?.let { nativeCount = it }
        if (nativeCount !in 2..7) nativeCount = spec.preferredSlots.first.coerceIn(2, 7)
        val mask = settings.hiddenMask
        indices = PortNavigationModel.visibleIndices(nativeCount, mask, delegate.reverseVisualSlotOrder)
        displayLabels = indices.map { i ->
            cells.getOrNull(i)?.let(::label) ?: spec.entryLabels.getOrNull(i) ?: app.labels.getOrNull(i) ?: "${i + 1}"
        }.toTypedArray()
        host?.let { current ->
            current.adapterNavigationSource = root
            current.updateAdapterSlotCount(nativeCount)
            if (current.portLabels() != displayLabels.toList()) current.setLabels(displayLabels)
        }
    }

    override fun getVisibleLabels(): Array<String> { getBottomBarRoot(); return displayLabels }
    override fun centerActionTabIndex(): Int {
        val native = delegate.sourceCenterActionIndex ?: delegate.actionOnlyIndices.firstOrNull() ?: app.centerActionLabelIndex
        return indices.indexOf(native)
    }
    override fun getSuppressionPlan(): SuppressionPlan {
        val plan = SuppressionPlan()
        val root = getBottomBarRoot() ?: return plan
        if (!delegate.keepNavigationSourceVisible || root.javaClass.name == "com.netease.cloudmusic.theme.ui.NavigationTabLayout") plan.transparent(root)
        return plan
    }
    override fun captureHardwareBackdrops() = false
    override fun preservePhysicalBackdropCoordinates() = true
    override fun useExactBackdropSource() = true
    override fun getBackdropSourceView(): View? = host?.let { state?.resolveBackdropScene(it) }
        ?: (activity.findViewById<View>(android.R.id.content) as? ViewGroup)?.let { GlassInstaller.findFullWindowContentBranch(it, source ?: it) ?: it }
    override fun getNavigationIconBitmaps(): Array<Bitmap?> = icons
    override fun shouldShowInjectedNavigation(): Boolean = pageAllowed
    override fun isNavigationVisible(): Boolean {
        val root = getBottomBarRoot() ?: return false
        return pageAllowed && root.isAttachedToWindow && root.visibility == View.VISIBLE &&
            (root.isShown || state?.retainNavigationWhenNativeRowHidden(nativeSelected) == true)
    }
    override fun detectSelectedIndex(): Int {
        val root = getBottomBarRoot() ?: return indices.indexOf(nativeSelected).coerceAtLeast(0)
        val found = state?.resolveSelectedIndex(root, nativeCount) ?: runCatching { XposedHelpers.callMethod(root, "getSelectedTabPosition") as? Int }.getOrNull()
            ?: nativeCells(root).indexOfFirst { it.isSelected || it.isActivated }.takeIf { it >= 0 }
        if (found != null && found in 0 until nativeCount && found !in delegate.actionOnlyIndices && found != (delegate.sourceCenterActionIndex ?: app.centerActionLabelIndex)) {
            val now = SystemClock.uptimeMillis()
            if (state?.acceptNativeNavigationSelection(found, now) != false && nativeSelected != found) {
                nativeSelected = found
                state?.onNavigationTabSwitched(found, nativeCount, now)
                lastArtworkAt = 0L
            }
        }
        host?.adapterSelectedIndex = nativeSelected
        return indices.indexOf(nativeSelected).coerceAtLeast(0)
    }
    override fun select(index: Int): Boolean {
        val native = indices.getOrNull(index) ?: return false
        val root = getBottomBarRoot() ?: return false
        val current = host ?: return false
        val now = SystemClock.uptimeMillis()
        state?.onNavigationSelectionRequested(native, nativeCount, now)
        val done = state?.dispatchNativeNavigationTap(current, root, native, nativeCount) == true ||
            delegate.dispatchNavigationTap(root, native, nativeCount) || state?.performTap(current, root, native, nativeCount) == true ||
            nativeCells(root).getOrNull(native)?.let { cell ->
                if (cell.performClick()) true else clickableChild(cell)?.performClick() == true || dispatchCell(root, cell)
            } == true
        if (done) {
            if (native !in delegate.actionOnlyIndices && native != (delegate.sourceCenterActionIndex ?: app.centerActionLabelIndex)) nativeSelected = native
            state?.onTapSucceeded { delay -> schedule(delay) { frame() } }
            schedule(delegate.selectionSettleMs.coerceAtLeast(32L)) { detectSelectedIndex(); lastArtworkAt = 0L; frame() }
        }
        return done
    }
    override fun post(): Boolean = centerActionTabIndex().takeIf { it >= 0 }?.let(::select) ?: false

    private fun clickableChild(view: View): View? {
        if (view.hasOnClickListeners()) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) clickableChild(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun dispatchCell(root: ViewGroup, cell: View): Boolean {
        val a = IntArray(2); val b = IntArray(2); root.getLocationOnScreen(a); cell.getLocationOnScreen(b)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, (b[0]-a[0]+cell.width/2).toFloat(), (b[1]-a[1]+cell.height/2).toFloat(), 0)
        val up = MotionEvent.obtain(down).apply { action = MotionEvent.ACTION_UP }
        return try { val consumed = root.dispatchTouchEvent(down); root.dispatchTouchEvent(up) || consumed } finally { down.recycle(); up.recycle() }
    }
    private fun schedule(delay: Long, block: () -> Unit) {
        val ticket = generation
        host?.postDelayed({ if (!disposed && generation == ticket) block() }, delay)
    }

    internal fun bind(current: MiuixGlassNavView) {
        if (host === current && !disposed) return
        if (host != null) unbind()
        host = current; disposed = false; generation++
        current.adapterSceneRoot = WeakReference(activity.window.decorView)
        current.config = delegate.normalizeConfig(HookConfigReader.from(settings, activity))
        state = delegate.createNavigationState(activity, current)
        current.appNavigationState = state
        chrome = delegate.createNativeChromeController()
        getBottomBarRoot()?.let { state?.prepare(it, ::schedule) }
        current.installPortBackdrop(PortBackdrop(activity, current, this))
        observer = activity.window.decorView.viewTreeObserver.also { it.addOnPreDrawListener(preDraw); it.addOnScrollChangedListener(scroll) }
        previousCallback = activity.window.callback
        previousCallback?.let { original ->
            val callback = object : Window.Callback by original {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (NativeMusicPlayerGlass.dispatchTouch(activity.window.decorView, event)) return true
                    delegate.onWindowTouch(current, event)
                    if (current.referenceIdleDeadline > 0 && event.actionMasked in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE)) {
                        current.msgScrolledToStop = false
                        current.referenceIdleDeadline = SystemClock.uptimeMillis() + delegate.scrollStopHideDelayMs(nativeSelected)
                    }
                    return original.dispatchTouchEvent(event)
                }
                override fun onWindowFocusChanged(focus: Boolean) {
                    original.onWindowFocusChanged(focus); state?.onHostWindowFocusChanged(focus)
                    if (!focus) state?.onHostFocusLost()
                    schedule(0L) { frame() }
                }
            }
            observingCallback = callback; activity.window.callback = callback
        }
        frame()
    }

    internal fun frame() {
        val current = host ?: return
        if (disposed || !current.isAttachedToWindow) return
        current.updatePortMaterialBounds()
        val root = getBottomBarRoot()
        val selected = detectSelectedIndex()
        val now = SystemClock.uptimeMillis()
        current.adapterSelectedIndex = nativeSelected
        current.barVisibilityTarget = current.visibility == View.VISIBLE
        if (current.referenceIdleDeadline > 0 && now >= current.referenceIdleDeadline) current.msgScrolledToStop = true
        state?.onHostFrame(current)
        state?.onHostPreDraw(current, root, { nativeSelected }, settings.enabled, current.resources.displayMetrics.density,
            current.outerRect.height().toInt(), { if (current.portDark()) Color.BLACK else Color.WHITE })
        chrome?.update(current, root)
        root?.let { delegate.suppressNativeBottomChrome(current, it) }
        state?.suppressNativeChrome(root)
        state?.suppressHostNativeChrome(root, activity.window.decorView, nativeSelected)
        state?.enforceContentClipping()
        state?.adjustFloatingActions(current, root)
        val allowed = !activity.isInPictureInPictureMode && activity.window.decorView.hasWindowFocus() &&
            !delegate.isBlockingOverlayVisible() && state?.transientPageAllowsNavigation(activity) != false &&
            state?.hostPageAllowsNavigation(current, activity.window.decorView) != false && state?.beforeHostDraw(current, root) != false
        if (pageAllowed != allowed) { pageAllowed = allowed; current.post { UniversalGlassInstaller.refreshHostVisibility(activity) } }
        state?.updateBackdropRendererMode(current)
        state?.requestVideoBackdrop(current, allowed && current.portCaptureAllowed())
        current.updatePortBackdropFrame(nativeSelected, allowed)
        if (root != null && now - lastArtworkAt >= 250L) {
            lastArtworkAt = now; runCatching { captureArtwork(root, current) }.onFailure { ModuleLog.w("LiquidGlass-Icons", "Native artwork capture failed", it) }
        }
        if (selected >= 0) current.setSelectedIndexFromHost(selected)
        current.removeCallbacks(tick)
        val continuous = delegate.continuousFramePump || delegate.needsContinuousOpticalSurfaceFrames(nativeSelected) || state?.needsContinuousHomeFrames(nativeSelected) == true
        if (continuous) current.postInvalidateOnAnimation()
        current.postDelayed(tick, if (continuous && allowed && current.isShown) 32L else 200L)
    }

    private fun captureArtwork(root: ViewGroup, current: MiuixGlassNavView) {
        val size = (32f * current.resources.displayMetrics.density).toInt().coerceIn(32, 128)
        val row = Bitmap.createBitmap(size * nativeCount, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(row)
        val frameIndices = (0 until nativeCount).toList().let { if (app == TargetApp.AMAP) it.reversed() else it }
        val frame = AdapterNavigationFrame(activity, 1f, nativeCount, row.width, row.height, size.toFloat(),
            current.resources.displayMetrics.density, settings.iconScale, settings.textSizeSp, current.resources.displayMetrics.scaledDensity,
            nativeSelected, frameIndices, true, current.portDark(),
            if (settings.customPostColorEnabled) settings.customPostColor else delegate.accentColor ?: spec.defaultAccentColor)
        if (delegate.ownsNavigationDrawing && state?.readyForDrawing != false) runCatching { state?.draw(canvas, frame); delegate.drawNavigation(canvas, frame) }
        val cells = nativeCells(root)
        val next = indices.map { native ->
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val target = Canvas(bitmap)
            var drawn = runCatching { state?.drawFixedNavigationSlot(target, native, nativeSelected, current.portDark(), size / 2f, size / 2f, size.toFloat()) == true }.getOrDefault(false)
            if (!drawn) {
                target.drawBitmap(row, android.graphics.Rect(native*size, 0, (native+1)*size, size), android.graphics.Rect(0, 0, size, size), null)
                drawn = hasPixels(bitmap)
            }
            if (!drawn) cells.getOrNull(native)?.let { cell ->
                val image = firstImage(cell)
                if (image != null && image.width > 0 && image.height > 0) {
                    val save = target.save(); target.scale(size.toFloat()/image.width, size.toFloat()/image.height)
                    runCatching { image.draw(target) }; target.restoreToCount(save)
                } else if (cell.width > 0 && cell.height > 0) {
                    val save = target.save(); target.scale(size.toFloat()/cell.width, size.toFloat()/cell.width)
                    runCatching { cell.draw(target) }; target.restoreToCount(save)
                }
            }
            bitmap.takeIf(::hasPixels)
        }.toTypedArray()
        row.recycle()
        var hash = 17L
        for (bitmap in next) if (bitmap != null) {
            val pixels = IntArray(size*size); bitmap.getPixels(pixels,0,size,0,0,size,size)
            hash = hash * 31 + pixels.contentHashCode()
        } else hash *= 31
        if (hash != artworkHash || next.size != icons.size) { artworkHash = hash; icons = next; current.setNavigationIconBitmaps(next) }
        else next.forEach { it?.recycle() }
    }
    private fun hasPixels(bitmap: Bitmap): Boolean {
        val p = IntArray(bitmap.width*bitmap.height); bitmap.getPixels(p,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        return p.count { Color.alpha(it) > 40 } > 4
    }
    private fun firstImage(view: View): ImageView? {
        if (view is ImageView && view.drawable != null) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) firstImage(view.getChildAt(i))?.let { return it }
        return null
    }
    internal fun unbind() {
        host?.removeCallbacks(tick)
        disposed = true; generation++
        observer?.takeIf { it.isAlive }?.let { it.removeOnPreDrawListener(preDraw); it.removeOnScrollChangedListener(scroll) }
        observer = null
        if (activity.window.callback === observingCallback) activity.window.callback = previousCallback
        observingCallback = null; previousCallback = null
        state?.onHostDetached(); state?.dispose(); state = null
        QqMusicPlayerGlass.disposeForRoot(activity.window.decorView)
        PortChromeOwnership.restoreForRoot(activity.window.decorView)
        GlassInstaller.restoreNavigationBarImmersion(activity)
        host?.appNavigationState = null
        host?.installPortBackdrop(null)
        host = null; chrome = null
    }

    companion object {
        private val active = WeakHashMap<Activity, WeakReference<LiquidTabPortAdapter>>()
        @JvmStatic fun supports(app: TargetApp?): Boolean = app != null && app.key in TargetSpecRegistry.byKey
        @JvmStatic fun create(activity: Activity, app: TargetApp, settings: TargetAppSettings): LiquidTabPortAdapter {
            active[activity]?.get()?.takeIf { it.app == app && it.settings.revision == settings.revision && !it.disposed }?.let { return it }
            return LiquidTabPortAdapter(activity, app, settings).also { active[activity] = WeakReference(it) }
        }
        @JvmStatic fun acceptsProcess(app: TargetApp, process: String?): Boolean {
            if (process == null || process == app.packageName) return true
            val spec = TargetSpecRegistry.byKey[app.key] ?: return false
            return spec.uiProcessSuffixes.any { process == "${app.packageName}:$it" }
        }
        @JvmStatic fun matchesActivity(activity: Activity, app: TargetApp): Boolean {
            if (activity.packageName != app.packageName) return false
            val spec = TargetSpecRegistry.byKey[app.key] ?: return false
            if (spec.activityHints.isEmpty()) return true
            var type: Class<*>? = activity.javaClass
            while (type != null) {
                val name = type.name
                if (spec.activityHints.any { name == it || name.endsWith(".$it") }) return true
                type = type.superclass
            }
            return app.matchesActivity(activity.javaClass.name)
        }
        @JvmStatic fun installHooks(param: LoadPackageParam, app: TargetApp) {
            PortHookGate.configure(app)
            val spec = TargetSpecRegistry.byKey.getValue(app.key)
            val adapter = TargetAdapterRegistry.forSpec(spec) ?: return
            adapter.installHooks(param, spec)
            for (signal in adapter.hookSignals() + spec.hookSignals) runCatching {
                val type = XposedHelpers.findClass(signal.className, param.classLoader)
                for (method in signal.methods) XposedBridge.hookAllMethods(type, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(hook: MethodHookParam) {
                        if (!PortHookGate.enabled()) return
                        val activity = hook.thisObject as? Activity ?: (hook.thisObject as? View)?.context?.let(ActivityFinder::from) ?: return
                        if (matchesActivity(activity, app)) activity.window.decorView.post { UniversalGlassInstaller.install(activity, app); UniversalGlassInstaller.refreshHostVisibility(activity) }
                    }
                })
            }
        }
    }
}
