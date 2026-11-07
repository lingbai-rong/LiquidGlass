// SPDX-License-Identifier: GPL-3.0-or-later
package rongyi.lingbai

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.luckypray.dexkit.DexKitBridge
import rongyi.lingbai.XC_MethodHook.MethodHookParam
import rongyi.lingbai.wechatpanel.*
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * WeChat minus-one screen ported from WeKit's HomeSidePanel (GPL-3.0).
 *
 * WeKit discovers host structures through DexKit and binds them through its own hook framework.
 * LiquidGlass already has an exact WeChat 8.0.72 adapter, so this port keeps the visual/gesture
 * behavior while attaching through the same verified LauncherUI/MainTabUI/WxViewPager route.
 */
object WeChatSidePanel {
    private const val TAG = "[LiquidGlass][26.96][WeChatSidePanel]"
    private const val CUSTOM_VIEW_PAGER = "com.tencent.mm.ui.base.CustomViewPager"

    private val sessionsByPager = WeakHashMap<View, WeakReference<PanelSession>>()
    private val sessionsByActivity = WeakHashMap<Activity, WeakReference<PanelSession>>()
    private val sessionsByTabsAdapter = WeakHashMap<Any, WeakReference<PanelSession>>()
    private val pendingEdgeToEdgeAttachListeners =
        WeakHashMap<View, View.OnAttachStateChangeListener>()
    private var lastLauncher = WeakReference<Activity>(null)
    private var lastMainTabUi = WeakReference<Any>(null)
    private val pendingHostCancel = ThreadLocal<PendingHostCancel?>()
    @Volatile private var installed = false

    @JvmStatic
    fun install(lpparam: LoadPackageParam) {
        if (installed) return
        val classLoader = lpparam.classLoader

        findClass("com.tencent.mm.ui.LauncherUI", classLoader)?.let { launcher ->
            findMethod(launcher, "enableEdge2Edge")?.let { enableEdgeToEdge ->
                hookMethod(enableEdgeToEdge, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!launcher.isInstance(activity)) return
                        if (isEnabled(activity)) param.setResult(true)
                    }
                })
            }
            hookExact(
                launcher,
                "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!launcher.isInstance(activity)) return
                        lastLauncher = WeakReference(activity)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!launcher.isInstance(activity)) return
                        lastLauncher = WeakReference(activity)
                        if (isEnabled(activity)) {
                            ensureLauncherEdgeToEdge(activity)
                        }
                    }
                },
                Bundle::class.java,
            )
            hookExact(launcher, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!launcher.isInstance(activity)) return
                    lastLauncher = WeakReference(activity)
                    reloadSettings(activity)
                    sessionsByActivity[activity]?.get()?.onLauncherResumed()
                }
            })
            hookExact(launcher, "onDestroy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!launcher.isInstance(activity)) return
                    removePendingEdgeToEdgeAttachListener(activity)
                    detachForActivity(activity)
                }
            })
            hookExact(
                launcher,
                "moveTaskToBack",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!launcher.isInstance(activity)) return
                        val session = sessionsByActivity[activity]?.get() ?: return
                        if (session.consumeBack()) param.setResult(true)
                    }
                },
                Boolean::class.javaPrimitiveType!!,
            )
            val chatTransitionHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!launcher.isInstance(activity)) return
                    sessionsByActivity[activity]?.get()?.onChatTransition()
                }
            }
            hookExact(
                launcher,
                "startChatting",
                chatTransitionHook,
                String::class.java,
                Bundle::class.java,
                Boolean::class.javaPrimitiveType!!,
            )
            hookExact(
                launcher,
                "closeChatting",
                chatTransitionHook,
                Boolean::class.javaPrimitiveType!!,
            )
        }

        resolveMainTabDoOnCreate(classLoader)?.let { doOnCreate ->
            hookMethod(doOnCreate, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    attachFromMainTab(param.thisObject)
                }
            })
        } ?: XposedBridge.log("$TAG MainTabUI.doOnCreate hook target unavailable")

        findClass(CUSTOM_VIEW_PAGER, classLoader)?.let { customPager ->
            hookExact(
                customPager,
                "dispatchTouchEvent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        pendingHostCancel.remove()
                        val pager = param.thisObject as? View ?: return
                        val session = sessionsByPager[pager]?.get() ?: return
                        val event = param.args.firstOrNull() as? MotionEvent ?: return
                        when (session.onPagerTouch(event)) {
                            PagerTouchResult.PASS -> Unit
                            PagerTouchResult.CANCEL_HOST -> {
                                pendingHostCancel.set(PendingHostCancel(event, event.action))
                                event.action = MotionEvent.ACTION_CANCEL
                            }

                            PagerTouchResult.CONSUME -> param.setResult(true)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val event = param.args.firstOrNull() as? MotionEvent ?: return
                        val pending = pendingHostCancel.get() ?: return
                        if (pending.event !== event) return
                        event.action = pending.originalAction
                        param.setResult(true)
                        pendingHostCancel.remove()
                    }
                },
                MotionEvent::class.java,
            )
        }

        findClass("com.tencent.mm.wallet_core.model.m1", classLoader)?.let { walletCache ->
            walletCache.declaredMethods.singleOrNull {
                it.name == "j" && it.parameterCount == 2 && it.returnType == Void.TYPE
            }?.apply { isAccessible = true }?.let { walletCacheWrite ->
                hookMethod(walletCacheWrite, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        HomeSidePanelWalletBalanceSource.onCacheWrite(
                            param.args.getOrNull(0),
                            param.args.getOrNull(1),
                        )
                    }
                })
            } ?: XposedBridge.log("$TAG exact wallet cache write method unavailable")
        }

        installed = true
        XposedBridge.log("$TAG hooks installed after original WeChat glass hooks")
    }

    @JvmStatic
    fun reloadSettings(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (isEnabled(activity)) {
            val current = sessionsByActivity[activity]?.get()
            if (current == null || !current.isAttached()) {
                lastMainTabUi.get()?.let(::attachFromMainTab)
            }
        } else {
            detachForActivity(activity)
        }
    }

    /** Exact WeKit attach chain: MainTabUI.doOnCreate -> mViewPager/mTabsAdapter -> FrameLayout. */
    private fun attachFromMainTab(mainTabUi: Any?) {
        if (mainTabUi == null) return
        lastMainTabUi = WeakReference(mainTabUi)
        val activity = findActivityOwner(mainTabUi) ?: lastLauncher.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        lastLauncher = WeakReference(activity)
        if (!isEnabled(activity)) {
            detachForActivity(activity)
            return
        }
        ensureLauncherEdgeToEdge(activity)
        val pager = readField(mainTabUi, "mViewPager") as? View ?: run {
            XposedBridge.log("$TAG MainTabUI.mViewPager unavailable")
            return
        }
        val tabsAdapter = readField(mainTabUi, "mTabsAdapter") ?: run {
            XposedBridge.log("$TAG MainTabUI.mTabsAdapter unavailable")
            return
        }
        val parent = pager.parent as? FrameLayout ?: run {
            XposedBridge.log("$TAG MainTabUI.mViewPager parent is not FrameLayout")
            return
        }
        val current = sessionsByPager[pager]?.get()
        if (current != null && current.isAttached()) {
            sessionsByActivity[activity] = WeakReference(current)
            installTabsAdapterHooks(tabsAdapter, current)
            return
        }
        val staleSessions = listOfNotNull(current, sessionsByActivity.remove(activity)?.get()).distinct()
        staleSessions.forEach { stale ->
            sessionsByPager.entries.removeAll {
                it.value.get() == null || it.value.get() === stale
            }
            sessionsByTabsAdapter.entries.removeAll {
                it.value.get() == null || it.value.get() === stale
            }
            runCatching { stale.detach() }
        }
        val session = attachSessionSafely(activity, parent, pager, "MainTabUI.doOnCreate") ?: return
        session.setSelectedTab(session.currentPagerItem())
        sessionsByPager[pager] = WeakReference(session)
        sessionsByActivity[activity] = WeakReference(session)
        installTabsAdapterHooks(tabsAdapter, session)
        XposedBridge.log("$TAG attached through MainTabUI.doOnCreate (WeChat 8.0.72: d)")
    }

    private fun attachSessionSafely(
        activity: Activity,
        parent: FrameLayout,
        pager: View,
        source: String,
    ): PanelSession? {
        var session: PanelSession? = null
        return try {
            PanelSession(activity, parent, pager).also {
                session = it
                it.attach()
            }
        } catch (error: Throwable) {
            runCatching { session?.detach() }
            XposedBridge.log(
                "$TAG optional attach failed at $source; original WeChat UI remains active: $error",
            )
            XposedBridge.log(error)
            null
        }
    }

    private val hookedTabsAdapterClasses = linkedSetOf<Class<*>>()

    private fun installTabsAdapterHooks(adapter: Any, session: PanelSession) {
        sessionsByTabsAdapter[adapter] = WeakReference(session)
        val type = adapter.javaClass
        synchronized(hookedTabsAdapterClasses) {
            if (!hookedTabsAdapterClasses.add(type)) return
        }
        hookExact(
            type,
            "onPageSelected",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = sessionsByTabsAdapter[param.thisObject]?.get() ?: return
                    val index = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                    target.setSelectedTab(index)
                }
            },
            Int::class.javaPrimitiveType!!,
        )
        hookExact(
            type,
            "onPageScrolled",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = sessionsByTabsAdapter[param.thisObject]?.get() ?: return
                    val position = (param.args.getOrNull(0) as? Number)?.toInt() ?: return
                    val offset = (param.args.getOrNull(1) as? Number)?.toFloat() ?: 0f
                    if (position != HOME_TAB_INDEX || offset > 0.001f) {
                        target.setSelectedTab(-1)
                    } else {
                        target.setSelectedTab(target.currentPagerItem())
                    }
                }
            },
            Int::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        )
    }

    private fun findActivityOwner(instance: Any): Activity? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val currentType = type
            for (field in currentType.declaredFields) {
                if (
                    Activity::class.java.isAssignableFrom(field.type) ||
                    field.type.name == "com.tencent.mm.ui.MMFragmentActivity"
                ) {
                    runCatching {
                        field.isAccessible = true
                        field.get(instance) as? Activity
                    }.getOrNull()?.let { return it }
                }
            }
            type = currentType.superclass
        }
        return null
    }

    private fun readField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val currentType = type
            val field = runCatching { currentType.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(instance)
                }.getOrNull()
            }
            type = currentType.superclass
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun ensureLauncherEdgeToEdge(activity: Activity) {
        val window = activity.window
        val decor = window.decorView
        if (decor.isAttachedToWindow) {
            applyLauncherEdgeToEdge(window)
            return
        }
        if (pendingEdgeToEdgeAttachListeners[decor] != null) return
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                pendingEdgeToEdgeAttachListeners.remove(view)
                view.removeOnAttachStateChangeListener(this)
                view.post { applyLauncherEdgeToEdge(activity.window) }
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        pendingEdgeToEdgeAttachListeners[decor] = listener
        decor.addOnAttachStateChangeListener(listener)
    }

    private fun removePendingEdgeToEdgeAttachListener(activity: Activity) {
        val decor = activity.window.decorView
        val listener = pendingEdgeToEdgeAttachListeners.remove(decor) ?: return
        decor.removeOnAttachStateChangeListener(listener)
    }

    @Suppress("DEPRECATION")
    private fun applyLauncherEdgeToEdge(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.requestApplyInsets()
    }

    private fun detachForActivity(activity: Activity) {
        val session = sessionsByActivity.remove(activity)?.get() ?: return
        sessionsByPager.entries.removeAll { it.value.get() == null || it.value.get() === session }
        sessionsByTabsAdapter.entries.removeAll {
            it.value.get() == null || it.value.get() === session
        }
        runCatching { session.detach() }
            .onFailure { XposedBridge.log("$TAG optional detach failed: $it") }
    }

    private fun isEnabled(activity: Activity): Boolean =
        if (activity.packageName == TargetApp.WECHAT.packageName) {
            WeChatSidePanelPreference.loadForHook()
        } else {
            false
        }

    private fun findClass(name: String, classLoader: ClassLoader): Class<*>? =
        runCatching { XposedHelpers.findClass(name, classLoader) }.getOrNull()

    private fun findMethod(
        type: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>,
    ): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val currentType = current
            runCatching { currentType.getDeclaredMethod(name, *parameterTypes) }
                .getOrNull()
                ?.let { return it.apply { isAccessible = true } }
            current = currentType.superclass
        }
        return null
    }

    /**
     * WeKit resolves this API by the two exact strings below. The method is named d() in the
     * supplied WeChat 8.0.72 build, so that verified symbol is retained only as a fallback when
     * DexKit is unavailable; the primary path is the same source-level resolver as WeKit.
     */
    private fun resolveMainTabDoOnCreate(classLoader: ClassLoader): Method? {
        if (PanelNativeLoader.ensureDexKitLoaded()) {
            val bridge = runCatching { DexKitBridge.create(classLoader, true) }
                .onFailure { WeLogger.w(TAG, "failed to open DexKit for MainTabUI", it) }
                .getOrNull()
            if (bridge != null) {
                try {
                    bridge.findMethod {
                        matcher {
                            declaredClass = "com.tencent.mm.ui.MainTabUI"
                            usingEqStrings("MicroMsg.LauncherUI.MainTabUI", "doOnCreate")
                        }
                    }.singleOrNull()?.getMethodInstance(classLoader)?.let { method ->
                        method.isAccessible = true
                        XposedBridge.log("$TAG resolved MainTabUI.doOnCreate with WeKit matcher")
                        return method
                    }
                } catch (error: Throwable) {
                    WeLogger.w(TAG, "WeKit MainTabUI matcher failed", error)
                } finally {
                    runCatching(bridge::close)
                }
            }
        }

        return findClass("com.tencent.mm.ui.MainTabUI", classLoader)
            ?.declaredMethods
            ?.singleOrNull {
                it.name == "d" && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
            }
            ?.apply {
                isAccessible = true
                XposedBridge.log("$TAG using verified WeChat 8.0.72 MainTabUI.d fallback")
            }
    }

    private fun hookExact(
        type: Class<*>,
        name: String,
        hook: XC_MethodHook,
        vararg parameterTypes: Class<*>,
    ) {
        val method = findMethod(type, name, *parameterTypes)
        if (method == null) {
            val signature = parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
            XposedBridge.log("$TAG exact method unavailable: ${type.name}#$name$signature")
            return
        }
        hookMethod(method, hook)
    }

    private fun hookMethod(method: Method, hook: XC_MethodHook) {
        runCatching { XposedBridge.hookMethod(method, hook) }
            .onFailure { XposedBridge.log("$TAG failed to hook $method: $it") }
    }

    private data class PendingHostCancel(val event: MotionEvent, val originalAction: Int)

    private enum class PagerTouchResult {
        PASS,
        CANCEL_HOST,
        CONSUME,
    }

    private data class TransformSnapshot(
        val originalPivotX: Float,
        val originalPivotY: Float,
        val transformPivotX: Float,
        val transformPivotY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
    )

    private data class ToolbarProfileBinding(
        val host: RelativeLayout,
        val nativeTitle: TextView,
        val composeView: ComposeView,
    )

    private class PanelSession(
        private val activity: Activity,
        private val parent: FrameLayout,
        private val pager: View,
    ) {
        private val decor = activity.window.decorView as FrameLayout
        private val moduleContext = resolveModuleContext(activity)
        private val panelContext = PanelResourceContext(activity, moduleContext)
        init {
            PanelHostContext.initialize(activity, moduleContext)
        }
        private val contentWrapper = FrameLayout(activity)
        private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val cityIndex = HomeSidePanelCityIndex(panelContext)
        private val weather = HomeSidePanelWeather(cityIndex, homeSidePanelHttpClient)
        private val hitokoto = HomeSidePanelHitokoto(homeSidePanelHttpClient)
        private val runtimeStore = HomeSidePanelRuntimeStore(
            weather = weather,
            hitokoto = hitokoto,
            walletBalance = HomeSidePanelWalletBalanceSource,
            parentScope = stateScope,
            cacheStore = HomeSidePanelLayoutStore,
        )
        private val panelState = HomeSidePanelState(
            activity = activity,
            profile = HomeSidePanelProfileLoader(cityIndex),
            weather = weather,
            hitokoto = hitokoto,
            runtimeStore = runtimeStore,
            location = HomeSidePanelLocation(cityIndex),
            scope = stateScope,
            layoutStore = HomeSidePanelLayoutStore,
            closePanel = { afterClosed ->
                close(animated = true, oneShot = true, afterClosed = afterClosed)
            },
        )
        private val gesture = HomeSidePanelGestureState(
            homeSidePanelGestureConfig(activity.resources.displayMetrics.density),
        )
        private val overlay = PanelOverlayLayout(activity).also { it.session = this }
        private val dimView = View(activity)
        private val outlineProvider = ProgressOutlineProvider()
        private val transformSnapshots = linkedMapOf<View, TransformSnapshot>()
        private lateinit var panelHost: PanelComposeHost
        private var animator: ValueAnimator? = null
        private var drawerWidthPx = 1
        private var renderedProgress = 0f
        private var selectedTabIndex = 0
        private var dragging = false
        private var attached = false
        private var wasVisible = false
        private var chattingVisible = false
        private var parentClipChildren = true
        private var parentClipToPadding = true
        private var toolbarSyncPosted = false
        private var pendingTransitionLayoutListener: View.OnLayoutChangeListener? = null
        private val toolbarProfileBindings = linkedMapOf<RelativeLayout, ToolbarProfileBinding>()
        private val homeToolbarHosts = linkedSetOf<RelativeLayout>()
        private val chattingToolbarHosts = linkedSetOf<RelativeLayout>()
        private val nativeTitleVisibilities = linkedMapOf<TextView, Int>()
        private val toolbarSyncRunnable = Runnable {
            toolbarSyncPosted = false
            if (attached) syncToolbarProfileBindings()
        }
        private val parentLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            absorbStrayChildren()
            if (updateDrawerWidth()) applyProgress(renderedProgress)
            requestToolbarProfileSync()
        }
        private val decorLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (updateDrawerWidth()) applyProgress(renderedProgress)
            ensureOverlayOnTop()
            requestToolbarProfileSync()
        }

        fun attach() {
            if (attached) return
            check(activity.installPanelViewTreeLifecycle()) {
                "LauncherUI DecorView owners are unavailable; skip optional WeChat side panel"
            }
            attached = true
            PanelHostContext.initialize(activity, moduleContext)
            HomeSidePanelWalletBalanceSource.install(
                reader = PanelWeChatResolver::readWalletBalance,
                requester = PanelWeChatResolver::requestWalletBalanceRefresh,
            )
            PanelWeChatResolver.warmUpAsync(stateScope)
            parentClipChildren = parent.clipChildren
            parentClipToPadding = parent.clipToPadding
            parent.clipChildren = false
            parent.clipToPadding = false
            moveExistingChildrenIntoWrapper()
            contentWrapper.clipChildren = false
            contentWrapper.clipToPadding = false
            contentWrapper.outlineProvider = outlineProvider
            contentWrapper.clipToOutline = true
            panelHost = PanelComposeHost(
                activity = activity,
                moduleContext = moduleContext,
            ) {
                InjectedUiTheme {
                    LaunchedEffect(panelState) {
                        val messagesJob = launch(start = CoroutineStart.UNDISPATCHED) {
                            panelState.messages.collect { message ->
                                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                        panelState.start()
                        messagesJob.join()
                    }
                    val state by panelState.uiState.collectAsStateWithLifecycle()
                    HomeSidePanelContent(state, panelState)
                }
            }
            dimView.setBackgroundColor(Color.BLACK)
            dimView.alpha = 0f
            dimView.isClickable = true
            dimView.setOnClickListener {
                if (renderedProgress > CLOSED_EPSILON) close(animated = true, oneShot = true)
            }

            overlay.clipChildren = false
            overlay.clipToPadding = false
            overlay.visibility = View.GONE
            overlay.addView(
                dimView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            overlay.addView(
                panelHost,
                FrameLayout.LayoutParams(drawerWidthPx, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            parent.addView(
                contentWrapper,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            decor.addView(
                overlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            ensureOverlayOnTop()
            parent.addOnLayoutChangeListener(parentLayoutListener)
            decor.addOnLayoutChangeListener(decorLayoutListener)
            stateScope.launch {
                panelState.uiState
                    .map { it.showToolbarProfile to it.hideWeChatTitle }
                    .distinctUntilChanged()
                    .collect { syncToolbarProfileVisibility() }
            }
            decor.post {
                updateDrawerWidth()
                applyProgress(0f)
                requestToolbarProfileSync()
            }
        }

        fun detach() {
            if (!attached) return
            attached = false
            animator?.cancel()
            animator = null
            decor.removeCallbacks(toolbarSyncRunnable)
            toolbarSyncPosted = false
            pendingTransitionLayoutListener?.let(parent::removeOnLayoutChangeListener)
            pendingTransitionLayoutListener = null
            parent.removeOnLayoutChangeListener(parentLayoutListener)
            decor.removeOnLayoutChangeListener(decorLayoutListener)
            restoreTransforms()
            clearToolbarProfileBindings()
            if (::panelHost.isInitialized) panelHost.dispose()
            if (overlay.parent === decor) decor.removeView(overlay)
            panelState.close()
            restoreContent()
            contentWrapper.clipToOutline = false
            if (contentWrapper.parent === parent) parent.removeView(contentWrapper)
            parent.clipChildren = parentClipChildren
            parent.clipToPadding = parentClipToPadding
            activity.clearPanelViewTreeLifecycle()
        }

        fun isAttached(): Boolean =
            attached && overlay.parent === decor && contentWrapper.parent === parent

        fun currentPagerItem(): Int =
            (UniversalHostAdapter.call(pager, "getCurrentItem") as? Number)?.toInt() ?: 0

        fun setSelectedTab(index: Int) {
            selectedTabIndex = index
            gesture.setSelectedTab(index)
            syncToolbarProfileVisibility()
            requestToolbarProfileSync()
            if (index != HOME_TAB_INDEX && renderedProgress > CLOSED_EPSILON) close(true)
        }

        fun onLauncherResumed() {
            panelState.onLauncherResumed()
            requestToolbarProfileSync()
        }

        fun onChatTransition() {
            if (renderedProgress > CLOSED_EPSILON || dragging) close(true)
            chattingVisible = isChattingVisible()
            syncToolbarProfileVisibility()
            requestToolbarProfileSync()
            pendingTransitionLayoutListener?.let(parent::removeOnLayoutChangeListener)
            val listener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    parent.removeOnLayoutChangeListener(this)
                    if (pendingTransitionLayoutListener === this) {
                        pendingTransitionLayoutListener = null
                    }
                    requestToolbarProfileSync()
                }
            }
            pendingTransitionLayoutListener = listener
            parent.addOnLayoutChangeListener(listener)
        }

        private fun open() {
            if (selectedTabIndex != HOME_TAB_INDEX || isChattingVisible()) return
            val from = renderedProgress
            animator?.cancel()
            animator = null
            dragging = false
            parent.requestDisallowInterceptTouchEvent(false)
            gesture.close()
            gesture.snapTo(from)
            animateTo(1f, from)
        }

        fun consumeBack(): Boolean {
            if (panelState.consumeSettingsBack()) return true
            if (renderedProgress <= CLOSED_EPSILON && !dragging && !gesture.isTracking) return false
            close(animated = true)
            return true
        }

        fun onPagerTouch(event: MotionEvent): PagerTouchResult = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(event)
                PagerTouchResult.PASS
            }

            MotionEvent.ACTION_MOVE -> when (gesture.onMove(event.x, event.y, event.eventTime)) {
                HomeSidePanelGestureDecision.PASS,
                HomeSidePanelGestureDecision.TRACKING,
                -> PagerTouchResult.PASS

                HomeSidePanelGestureDecision.CONSUME -> {
                    applyProgress(gesture.progress)
                    if (!dragging) {
                        dragging = true
                        parent.requestDisallowInterceptTouchEvent(true)
                        PagerTouchResult.CANCEL_HOST
                    } else {
                        PagerTouchResult.CONSUME
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    gesture.onCancel()
                    PagerTouchResult.PASS
                } else {
                    settleGesture(gesture.onUp(event.eventTime))
                    PagerTouchResult.CONSUME
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    gesture.onCancel()
                    PagerTouchResult.PASS
                } else {
                    settleGesture(gesture.onCancel())
                    PagerTouchResult.CONSUME
                }
            }

            else -> if (dragging) PagerTouchResult.CONSUME else PagerTouchResult.PASS
        }

        fun onOverlayInterceptTouch(event: MotionEvent): Boolean {
            if (
                event.x <= drawerWidthPx &&
                renderedProgress >= 0.98f &&
                homeSidePanelShouldPassFullyOpenTouchToChild(event.actionMasked)
            ) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) beginGesture(event)
                else if (!dragging) gesture.onCancel()
                return false
            }
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginGesture(event)
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val decision = gesture.onMove(event.x, event.y, event.eventTime)
                    if (decision == HomeSidePanelGestureDecision.CONSUME) {
                        dragging = true
                        parent.requestDisallowInterceptTouchEvent(true)
                        applyProgress(gesture.progress)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    if (gesture.isTracking && !dragging) applyProgress(gesture.onCancel())
                    false
                }

                else -> false
            }
        }

        fun onOverlayTouch(event: MotionEvent): Boolean = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(event)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val decision = gesture.onMove(event.x, event.y, event.eventTime)
                if (decision != HomeSidePanelGestureDecision.PASS) {
                    dragging = decision == HomeSidePanelGestureDecision.CONSUME
                    applyProgress(gesture.progress)
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                settleGesture(gesture.onUp(event.eventTime))
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                settleGesture(gesture.onCancel())
                true
            }

            else -> renderedProgress > CLOSED_EPSILON
        }

        private fun beginGesture(event: MotionEvent) {
            animator?.cancel()
            animator = null
            gesture.setSelectedTab(
                if (selectedTabIndex == HOME_TAB_INDEX && !isChattingVisible()) {
                    HOME_TAB_INDEX
                } else {
                    -1
                },
            )
            gesture.snapTo(renderedProgress)
            gesture.onDown(
                x = event.x,
                y = event.y,
                widthPx = parent.width.toFloat(),
                timeMs = event.eventTime,
            )
            dragging = false
            if (renderedProgress > CLOSED_EPSILON) overlay.visibility = View.VISIBLE
        }

        private fun settleGesture(target: Float) {
            val from = renderedProgress
            dragging = false
            parent.requestDisallowInterceptTouchEvent(false)
            animateTo(target, from, oneShot = target == 0f)
        }

        private fun close(
            animated: Boolean,
            oneShot: Boolean = false,
            afterClosed: (() -> Unit)? = null,
        ) {
            val from = renderedProgress
            animator?.cancel()
            animator = null
            dragging = false
            parent.requestDisallowInterceptTouchEvent(false)
            gesture.close()
            if (animated) animateTo(0f, from, oneShot, afterClosed) else {
                applyProgress(0f)
                afterClosed?.invoke()
            }
        }

        private fun animateTo(
            target: Float,
            from: Float = renderedProgress,
            oneShot: Boolean = false,
            afterClosed: (() -> Unit)? = null,
        ) {
            animator?.cancel()
            animator = null
            if (abs(from - target) < 0.001f) {
                gesture.snapTo(target)
                applyProgress(target)
                if (target == 0f) afterClosed?.invoke()
                return
            }
            overlay.visibility = View.VISIBLE
            ensureOverlayOnTop()
            var canceled = false
            animator = ValueAnimator.ofFloat(from, target).apply {
                duration = if (oneShot && target == 0f) {
                    (120L + 120L * abs(from - target)).roundToInt().toLong()
                } else {
                    (180L + 180L * abs(from - target)).roundToInt().toLong()
                }
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    gesture.snapTo(progress)
                    applyProgress(progress)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationCancel(animation: Animator) {
                        canceled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (canceled) return
                        gesture.snapTo(target)
                        applyProgress(target)
                        if (animator === animation) animator = null
                        if (target == 0f) afterClosed?.invoke()
                    }
                })
                start()
            }
        }

        private fun applyProgress(value: Float) {
            val progress = value.coerceIn(0f, 1f)
            renderedProgress = progress
            val visible = progress > CLOSED_EPSILON
            if (visible && !wasVisible) {
                refreshTransformTargets()
                panelState.onPanelOpened()
            }
            if (!visible && wasVisible) panelState.onPanelClosed()
            wasVisible = visible
            val eased = (1f - (1f - progress).toDouble().pow(1.35).toFloat()).coerceIn(0f, 1f)
            val scale = 1f - 0.05f * eased
            val density = activity.resources.displayMetrics.density
            val translationX = 7f * density * eased
            val translationY = 8f * density * eased
            transformSnapshots.forEach { (view, snapshot) ->
                view.pivotX = snapshot.transformPivotX
                view.pivotY = snapshot.transformPivotY
                view.scaleX = snapshot.scaleX * scale
                view.scaleY = snapshot.scaleY * scale
                view.translationX = snapshot.translationX + translationX
                view.translationY = snapshot.translationY + translationY
            }
            contentWrapper.outlineProvider = outlineProvider
            contentWrapper.clipToOutline = progress > CLOSED_EPSILON
            outlineProvider.radiusPx = 28f * density * eased
            contentWrapper.invalidateOutline()
            dimView.alpha = 0.52f * eased
            panelHost.translationX = -drawerWidthPx * (1f - progress)
            overlay.isClickable = visible || dragging
            overlay.visibility = if (visible || dragging) View.VISIBLE else View.GONE
            if (visible || dragging) ensureOverlayOnTop()
            if (!visible && !dragging) restoreTransforms()
        }

        private fun ensureOverlayOnTop() {
            if (overlay.parent !== decor) return
            val z = PANEL_OVERLAY_Z_DP * activity.resources.displayMetrics.density
            if (overlay.elevation != z) overlay.elevation = z
            if (overlay.translationZ != z) overlay.translationZ = z
        }

        private fun requestToolbarProfileSync() {
            if (!attached || toolbarSyncPosted) return
            toolbarSyncPosted = true
            decor.post(toolbarSyncRunnable)
        }

        private fun syncToolbarProfileBindings() {
            chattingVisible = isChattingVisible()
            val hosts = linkedMapOf<RelativeLayout, TextView>()
            collectToolbarProfileHosts(decor, hosts)

            homeToolbarHosts.removeAll { it.parent == null }
            chattingToolbarHosts.removeAll { it.parent == null }
            if (chattingVisible) {
                chattingToolbarHosts += hosts.keys.filterNot { it in toolbarProfileBindings }
            }

            val iterator = toolbarProfileBindings.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val currentTitle = hosts[entry.key]
                if (
                    currentTitle == null ||
                    currentTitle !== entry.value.nativeTitle ||
                    entry.value.composeView.parent !== entry.key
                ) {
                    disposeToolbarProfileBinding(entry.value)
                    iterator.remove()
                }
            }

            hosts.forEach { (host, nativeTitle) ->
                if (
                    !chattingVisible &&
                    (host in homeToolbarHosts || host !in chattingToolbarHosts) &&
                    host !in toolbarProfileBindings
                ) {
                    homeToolbarHosts += host
                    toolbarProfileBindings[host] = createToolbarProfileBinding(host, nativeTitle)
                }
            }
            syncToolbarProfileVisibility()
        }

        private fun collectToolbarProfileHosts(
            root: View,
            destination: MutableMap<RelativeLayout, TextView>,
        ) {
            collectViews(root) { it is TextView && it.id == android.R.id.text1 }
                .forEach { titleView ->
                    val title = titleView as TextView
                    var customRoot: View = title
                    var ancestor = title.parent
                    while (ancestor is ViewGroup) {
                        if (ancestor.isToolbar()) {
                            (customRoot as? RelativeLayout)?.let { destination[it] = title }
                            break
                        }
                        customRoot = ancestor
                        ancestor = ancestor.parent
                    }
                }
        }

        private fun View.isToolbar(): Boolean {
            var type: Class<*>? = javaClass
            while (type != null) {
                if (type.name == "androidx.appcompat.widget.Toolbar") return true
                type = type.superclass
            }
            return false
        }

        private fun createToolbarProfileBinding(
            host: RelativeLayout,
            nativeTitle: TextView,
        ): ToolbarProfileBinding {
            val composeView = ComposeView(PanelResourceContext(activity, moduleContext)).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setLifecycleOwner(LifecycleOwnerProvider.getOrCreate(activity))
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    InjectedUiTheme {
                        val state by panelState.uiState.collectAsStateWithLifecycle()
                        HomeSidePanelToolbarContent(
                            profile = state.profile,
                            onAvatarClick = { this@PanelSession.open() },
                            onAvatarLongClick = panelState::openAccountSwitcherFromToolbar,
                            onStatusClick = panelState::openStatusEditorFromToolbar,
                        )
                    }
                }
            }
            host.addView(
                composeView,
                RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                },
            )
            return ToolbarProfileBinding(host, nativeTitle, composeView)
        }

        private fun syncToolbarProfileVisibility() {
            val state = panelState.uiState.value
            val showProfile = state.showToolbarProfile &&
                selectedTabIndex == HOME_TAB_INDEX &&
                !chattingVisible
            toolbarProfileBindings.values.forEach { binding ->
                binding.composeView.visibility = if (showProfile) View.VISIBLE else View.GONE
            }
            syncNativeTitleVisibility(
                bindings = toolbarProfileBindings.values,
                hide = showProfile && state.hideWeChatTitle,
            )
        }

        private fun syncNativeTitleVisibility(
            bindings: Collection<ToolbarProfileBinding>,
            hide: Boolean,
        ) {
            val currentTitles = bindings.mapTo(linkedSetOf()) { it.nativeTitle }
            val iterator = nativeTitleVisibilities.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (!hide || entry.key !in currentTitles) {
                    entry.key.visibility = entry.value
                    iterator.remove()
                }
            }
            if (!hide) return
            currentTitles.forEach { title ->
                nativeTitleVisibilities.putIfAbsent(title, title.visibility)
                title.visibility = View.GONE
            }
        }

        private fun clearToolbarProfileBindings() {
            nativeTitleVisibilities.forEach { (title, visibility) ->
                title.visibility = visibility
            }
            nativeTitleVisibilities.clear()
            toolbarProfileBindings.values.forEach(::disposeToolbarProfileBinding)
            toolbarProfileBindings.clear()
            homeToolbarHosts.clear()
            chattingToolbarHosts.clear()
        }

        private fun disposeToolbarProfileBinding(binding: ToolbarProfileBinding) {
            nativeTitleVisibilities.remove(binding.nativeTitle)?.let { visibility ->
                binding.nativeTitle.visibility = visibility
            }
            if (binding.composeView.parent === binding.host) {
                binding.host.removeView(binding.composeView)
            }
            binding.composeView.disposeComposition()
        }

        private fun refreshTransformTargets() {
            restoreTransforms()
            val candidates = linkedSetOf<View>()
            candidates += contentWrapper
            collectViews(decor) { view ->
                val name = view.javaClass.name
                name == "androidx.appcompat.widget.ActionBarContainer" ||
                    view is MiuixGlassNavView
            }.forEach { candidate ->
                if (!candidate.isDescendantOf(contentWrapper)) candidates += candidate
            }
            val parentLocation = IntArray(2)
            parent.getLocationOnScreen(parentLocation)
            candidates.filter { it !== overlay && it.parent != null }.forEach { view ->
                val viewLocation = IntArray(2)
                view.getLocationOnScreen(viewLocation)
                val transformPivotX: Float
                val transformPivotY: Float
                if (view === contentWrapper) {
                    transformPivotX = view.width / 2f
                    transformPivotY = view.height / 2f
                } else {
                    transformPivotX = parentLocation[0] + parent.width / 2f - viewLocation[0]
                    transformPivotY = parentLocation[1] + parent.height / 2f - viewLocation[1]
                }
                transformSnapshots[view] = TransformSnapshot(
                    originalPivotX = view.pivotX,
                    originalPivotY = view.pivotY,
                    transformPivotX = transformPivotX,
                    transformPivotY = transformPivotY,
                    scaleX = view.scaleX,
                    scaleY = view.scaleY,
                    translationX = view.translationX,
                    translationY = view.translationY,
                )
            }
        }

        private fun restoreTransforms() {
            transformSnapshots.forEach { (view, snapshot) ->
                view.pivotX = snapshot.originalPivotX
                view.pivotY = snapshot.originalPivotY
                view.scaleX = snapshot.scaleX
                view.scaleY = snapshot.scaleY
                view.translationX = snapshot.translationX
                view.translationY = snapshot.translationY
            }
            transformSnapshots.clear()
            contentWrapper.clipToOutline = false
            contentWrapper.invalidateOutline()
        }

        private fun updateDrawerWidth(): Boolean {
            val width = parent.width
            if (width <= 0) return false
            val next = (width * DRAWER_WIDTH_FRACTION).roundToInt().coerceAtLeast(1)
            if (next == drawerWidthPx) return false
            drawerWidthPx = next
            if (::panelHost.isInitialized) {
                val params = panelHost.layoutParams as? FrameLayout.LayoutParams
                    ?: FrameLayout.LayoutParams(next, FrameLayout.LayoutParams.MATCH_PARENT)
                params.width = next
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                panelHost.layoutParams = params
            }
            return true
        }

        private fun isChattingVisible(): Boolean =
            UniversalHostAdapter.call(activity, "getCurrentFragmet") != null

        private fun moveExistingChildrenIntoWrapper() {
            val children = buildList {
                for (index in 0 until parent.childCount) {
                    val child = parent.getChildAt(index)
                    if (child !== contentWrapper) add(child to child.layoutParams)
                }
            }
            children.forEach { (child, params) ->
                parent.removeView(child)
                contentWrapper.addView(child, params)
            }
        }

        private fun restoreContent() {
            while (contentWrapper.childCount > 0) {
                val child = contentWrapper.getChildAt(0)
                val params = child.layoutParams
                contentWrapper.removeViewAt(0)
                parent.addView(child, params)
            }
            contentWrapper.scaleX = 1f
            contentWrapper.scaleY = 1f
            contentWrapper.translationX = 0f
            contentWrapper.translationY = 0f
        }

        private fun absorbStrayChildren() {
            var index = 0
            while (index < parent.childCount) {
                val child = parent.getChildAt(index)
                if (child === contentWrapper) {
                    index++
                } else {
                    val params = child.layoutParams
                    parent.removeViewAt(index)
                    contentWrapper.addView(child, params)
                }
            }
        }

        private fun View.isDescendantOf(candidateParent: View): Boolean {
            var current = parent
            while (current is View) {
                if (current === candidateParent) return true
                current = current.parent
            }
            return false
        }

        private fun collectViews(root: View, predicate: (View) -> Boolean): List<View> {
            val result = mutableListOf<View>()
            val queue = ArrayDeque<View>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 4_000) {
                val view = queue.removeFirst()
                if (predicate(view)) result += view
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) queue.addLast(view.getChildAt(index))
                }
            }
            return result
        }
    }

    private class PanelOverlayLayout(context: Context) : FrameLayout(context) {
        var session: PanelSession? = null

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
            session?.onOverlayInterceptTouch(event) == true || super.onInterceptTouchEvent(event)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean =
            session?.onOverlayTouch(event) == true || super.onTouchEvent(event)
    }

    private class ProgressOutlineProvider : ViewOutlineProvider() {
        var radiusPx = 0f

        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
        }
    }

    private const val HOME_TAB_INDEX = 0
    private const val DRAWER_WIDTH_FRACTION = 0.84f
    private const val CLOSED_EPSILON = 0.001f
    private const val PANEL_OVERLAY_Z_DP = 128f
}

private class PanelComposeHost(
    private val activity: Activity,
    moduleContext: Context,
    content: @Composable () -> Unit,
) : FrameLayout(activity) {
    private val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)
    private val composeView = ComposeView(PanelResourceContext(activity, moduleContext))
    private var disposed = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
        isClickable = true
        setLifecycleOwner(lifecycleOwner)
        composeView.setLifecycleOwner(lifecycleOwner)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setBackgroundColor(Color.TRANSPARENT)
        composeView.setContent(content)
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        // The window recomposer needs owners reachable from the content root. Install fallbacks
        // only when WeChat did not provide its own, while keeping the injected owner on this tree.
        activity.installPanelViewTreeLifecycle()
        setLifecycleOwner(lifecycleOwner)
        composeView.setLifecycleOwner(lifecycleOwner)
        super.onAttachedToWindow()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        composeView.disposeComposition()
    }
}

private class PanelResourceContext(
    base: Context,
    private val moduleContext: Context,
) : ContextWrapper(base) {
    override fun getResources(): Resources = moduleContext.resources
    override fun getAssets(): AssetManager = moduleContext.assets
    override fun getTheme(): Resources.Theme = moduleContext.theme
    override fun getPackageName(): String = moduleContext.packageName
}

private fun resolveModuleContext(host: Context): Context = runCatching {
    host.createPackageContext(
        "rongyi.lingbai",
        Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
    )
}.getOrDefault(host)

private val homeSidePanelHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
}
