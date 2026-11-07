package rongyi.lingbai.liquidtab.hook

import android.graphics.Color
import android.os.Build
import rongyi.lingbai.XposedHelpers
import rongyi.lingbai.liquidtab.hook.adapters.TargetAdapterRegistry
import java.util.WeakHashMap
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import rongyi.lingbai.TargetApp
import rongyi.lingbai.UniversalGlassInstaller
import rongyi.lingbai.liquidtab.hook.adapters.AdapterOwnedOverlay

object GlassInstaller {
    private val navigationImmersiveApplied = WeakHashMap<Activity, Boolean>()
    private val originalNavigationBarState = WeakHashMap<Activity, NavigationBarState>()
    private data class NavigationBarState(val systemUiVisibility: Int, val navigationBarColor: Int,
        val navigationBarDividerColor: Int?, val navigationBarContrastEnforced: Boolean?, val miuiForceImmersive: Boolean?)
    fun ensureNavigationBarImmersion(activity: Activity) { setNavigationBarImmersion(activity, true) }
    fun restoreNavigationBarImmersion(activity: Activity) {
        if (activity in originalNavigationBarState) setNavigationBarImmersion(activity, false)
        originalNavigationBarState.remove(activity); navigationImmersiveApplied.remove(activity)
    }
    @Synchronized
    internal fun setNavigationBarImmersion(activity: Activity, enabled: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        val window = activity.window ?: return
        val decor = window.decorView ?: return
        TargetAdapterRegistry.forPackage(activity.packageName)?.configureNavigationInsets(decor, enabled)
        // Never let a stale chat container or a late WeChat callback lift the
        // already-visible conversation list after the exit animation.
        val requiredFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val immersiveStub = runCatching {
            XposedHelpers.callMethod(decor, "getDecorViewImmersiveStub")
        }.getOrNull()
        val original = originalNavigationBarState.getOrPut(activity) {
            NavigationBarState(
                systemUiVisibility = decor.systemUiVisibility,
                navigationBarColor = window.navigationBarColor,
                navigationBarDividerColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.navigationBarDividerColor
                } else {
                    null
                },
                navigationBarContrastEnforced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced
                } else {
                    null
                },
                miuiForceImmersive = immersiveStub?.let { stub ->
                    runCatching {
                        XposedHelpers.callMethod(stub, "isForceImmersive") as? Boolean
                    }.getOrNull()
                },
            )
        }

        if (!enabled) {
            // Adapters whose embedded pages rewrite window flags may request
            // repeated restoration; the default remains a one-shot restore.
            if (navigationImmersiveApplied[activity] != true &&
                TargetAdapterRegistry.forPackage(activity.packageName)?.repeatImmersionRestoreWhileInactive != true &&
                TargetAdapterRegistry.forPackage(activity.packageName)?.navigationImmersionOverride != false
            ) return
            runCatching {
                immersiveStub?.let { stub ->
                    XposedHelpers.callMethod(
                        stub,
                        "setForceImmersiveNavBar",
                        original.miuiForceImmersive ?: false,
                    )
                    XposedHelpers.callMethod(decor, "updateColorViews", null, false)
                }
            }
            // Preserve unrelated flags that WeChat may have changed while
            // restoring only the two navigation-layout bits LiquidTab owns.
            decor.systemUiVisibility =
                (decor.systemUiVisibility and requiredFlags.inv()) or
                    (original.systemUiVisibility and requiredFlags)
            window.navigationBarColor = original.navigationBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                original.navigationBarDividerColor?.let { window.navigationBarDividerColor = it }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                original.navigationBarContrastEnforced?.let {
                    window.isNavigationBarContrastEnforced = it
                }
            }
            navigationImmersiveApplied[activity] = false
            decor.requestApplyInsets()
            return
        }

        var changed = false

        // On HyperOS, the same force-immersive state used by the framework's
        // navigation policy removes the DecorView's navigation-bar fit without
        // changing status-bar fitting. Keep this best-effort so AOSP/older ROMs
        // continue through the standard flag path below.
        val appImmersionWasReset = TargetAdapterRegistry.forPackage(activity.packageName)
            ?.immersionWasReset(immersiveStub) == true
        if (navigationImmersiveApplied[activity] != true || appImmersionWasReset
        ) {
            runCatching {
                if (immersiveStub != null) {
                    XposedHelpers.callMethod(immersiveStub, "setForceImmersiveNavBar", true)
                    XposedHelpers.callMethod(decor, "updateColorViews", null, false)
                }
            }
            navigationImmersiveApplied[activity] = true
            changed = true
        }

        // Legacy navigation-layout flags affect only the navigation edge. Do
        // not use setDecorFitsSystemWindows(false): it would also opt the status
        // bar out of framework fitting and can pull toolbars under the cutout.
        val currentFlags = decor.systemUiVisibility
        if (currentFlags and requiredFlags != requiredFlags) {
            decor.systemUiVisibility = currentFlags or requiredFlags
            changed = true
        }
        if (window.navigationBarColor != Color.TRANSPARENT) {
            window.navigationBarColor = Color.TRANSPARENT
            changed = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            window.navigationBarDividerColor != Color.TRANSPARENT
        ) {
            window.navigationBarDividerColor = Color.TRANSPARENT
            changed = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && window.isNavigationBarContrastEnforced) {
            window.isNavigationBarContrastEnforced = false
            changed = true
        }
        if (changed) decor.requestApplyInsets()
    }


    fun findViewByClass(root: View, name: String): View? {
        val pending = ArrayDeque<View>(); pending.add(root)
        var count = 0
        while (pending.isNotEmpty() && count++ < 4000) {
            val view = pending.removeFirst()
            if (view.javaClass.name == name) return view
            if (view is ViewGroup && view !is GlassHostLayout && view !is AdapterOwnedOverlay)
                for (i in 0 until view.childCount) pending.add(view.getChildAt(i))
        }
        return null
    }
    fun findFullWindowContentBranch(parent: ViewGroup, excluded: View): View? =
        (0 until parent.childCount).map(parent::getChildAt).filter {
            it !== excluded && it !is GlassHostLayout && it !is AdapterOwnedOverlay && it.visibility == View.VISIBLE
        }.maxByOrNull { it.width.toLong() * it.height }
    fun updateAdapterNavigationBarForPage(activity: Activity) {
        val immersion = TargetAdapterRegistry.forPackage(activity.packageName)?.navigationImmersionOverride ?: return
        setNavigationBarImmersion(activity, immersion && PortHookGate.enabled())
    }
}
