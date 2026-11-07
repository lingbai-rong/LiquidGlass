// SPDX-License-Identifier: GPL-3.0-or-later
package rongyi.lingbai.wechatpanel

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.util.WeakHashMap

/** WeKit's lifecycle owner used by Compose views injected into WeChat. */
internal class XposedLifecycleOwner private constructor() :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    override val viewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    fun onResume() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onPause() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onStop() = lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
    }

    companion object {
        fun create(): XposedLifecycleOwner =
            XposedLifecycleOwner().apply {
                onCreate()
                onStart()
                onResume()
            }
    }
}

/** Activity-scoped owner provider copied from WeKit's injected UI lifecycle. */
internal object LifecycleOwnerProvider {
    private val lifecycleMap = WeakHashMap<Activity, XposedLifecycleOwner>()

    fun getOrCreate(activity: Activity): XposedLifecycleOwner =
        lifecycleMap.getOrPut(activity) {
            XposedLifecycleOwner.create().also { customOwner ->
                activity.application.registerActivityLifecycleCallbacks(
                    object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityPaused(act: Activity) {
                            if (act === activity) customOwner.onPause()
                        }

                        override fun onActivityStopped(act: Activity) {
                            if (act === activity) customOwner.onStop()
                        }

                        override fun onActivityDestroyed(act: Activity) {
                            if (act === activity) {
                                clearFallbackOwners(activity, customOwner)
                                customOwner.onDestroy()
                                activity.application.unregisterActivityLifecycleCallbacks(this)
                                lifecycleMap.remove(activity)
                            }
                        }

                        override fun onActivityCreated(act: Activity, state: Bundle?) = Unit

                        override fun onActivityStarted(act: Activity) {
                            if (act === activity) customOwner.onStart()
                        }

                        override fun onActivityResumed(act: Activity) {
                            if (act === activity) customOwner.onResume()
                        }

                        override fun onActivitySaveInstanceState(act: Activity, state: Bundle) = Unit
                    },
                )
            }
        }

    fun installFallbackOwners(activity: Activity) {
        val owner = getOrCreate(activity)
        val roots = viewTreeRoots(activity)

        // MainTabUI.d() runs before LauncherUI.onCreate() returns. At that point WeChat 8.0.72
        // has not published owners on either root, and the side-panel ComposeView is attached
        // directly below DecorView rather than below android.R.id.content. Snapshot missing roots
        // before writing anything so both branches receive fallbacks without replacing owners
        // that WeChat already supplied.
        roots.filter { it.findViewTreeLifecycleOwner() == null }
            .forEach { it.setViewTreeLifecycleOwner(owner) }
        roots.filter { it.findViewTreeViewModelStoreOwner() == null }
            .forEach { it.setViewTreeViewModelStoreOwner(owner) }
        roots.filter { it.findViewTreeSavedStateRegistryOwner() == null }
            .forEach { it.setViewTreeSavedStateRegistryOwner(owner) }
    }

    fun clearFallbackOwners(activity: Activity, expectedOwner: XposedLifecycleOwner? = null) {
        val owner = expectedOwner ?: lifecycleMap[activity] ?: return
        viewTreeRoots(activity).forEach { root ->
            if (root.findViewTreeLifecycleOwner() === owner) {
                root.setViewTreeLifecycleOwner(null)
            }
            if (root.findViewTreeViewModelStoreOwner() === owner) {
                root.setViewTreeViewModelStoreOwner(null)
            }
            if (root.findViewTreeSavedStateRegistryOwner() === owner) {
                root.setViewTreeSavedStateRegistryOwner(null)
            }
        }
    }

    private fun viewTreeRoots(activity: Activity): List<View> = buildList {
        activity.window?.decorView?.let(::add)
        activity.findViewById<View>(android.R.id.content)
            ?.takeUnless { it === firstOrNull() }
            ?.let(::add)
    }
}

internal fun View.setLifecycleOwner(lifecycleOwner: XposedLifecycleOwner) {
    setViewTreeLifecycleOwner(lifecycleOwner)
    setViewTreeViewModelStoreOwner(lifecycleOwner)
    setViewTreeSavedStateRegistryOwner(lifecycleOwner)
}

/** Installs only missing window-root owners and never replaces WeChat's own lifecycle tree. */
internal fun Activity.installPanelViewTreeLifecycle(): Boolean {
    LifecycleOwnerProvider.installFallbackOwners(this)
    val decor = window?.decorView ?: return false
    return decor.findViewTreeLifecycleOwner() != null &&
        decor.findViewTreeViewModelStoreOwner() != null &&
        decor.findViewTreeSavedStateRegistryOwner() != null
}

internal fun Activity.clearPanelViewTreeLifecycle() {
    LifecycleOwnerProvider.clearFallbackOwners(this)
}
