// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.zhihu

import rongyi.lingbai.liquidtab.hook.adapterRootView

import android.view.View
import rongyi.lingbai.XC_MethodHook
import rongyi.lingbai.XposedBridge
import rongyi.lingbai.XposedHelpers
import java.util.WeakHashMap

/** Native drawer state and SettingsFragment root, evaluated every host frame. */
internal object ZhihuSettingsVisibility {
    private val settingsRoots = WeakHashMap<View, Boolean>()

    fun install(loader: ClassLoader) {
        val type = runCatching { XposedHelpers.findClass(
            "com.zhihu.android.app.ui.fragment.preference.SettingsFragment", loader)
        }.getOrNull() ?: return
        XposedBridge.hookAllMethods(type, "onViewCreated", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
 if (!rongyi.lingbai.liquidtab.hook.PortHookGate.enabled()) return
                (param.args.firstOrNull() as? View)?.let { settingsRoots[it] = true }
            }
        })
    }

    fun allowsNavigation(root: View?): Boolean {
        root ?: return true
        // MainActivity's i controller owns mine_sidebar_drawer_layout and
        // mine_sidebar_drawer_container. DrawerLayout visibility is independent
        // of the retained native navigation row's visibility.
        val id = root.resources.getIdentifier("mine_sidebar_drawer_layout", "id", "com.zhihu.android")
        val drawer = root.findViewById<View>(id)
        val contentId = root.resources.getIdentifier("mine_sidebar_drawer_container", "id", "com.zhihu.android")
        val content = root.findViewById<View>(contentId)
        if (drawer != null && content != null && drawer.isShown &&
            runCatching { XposedHelpers.callMethod(drawer, "isDrawerVisible", content) as? Boolean }.getOrNull() == true
        ) return false
        return settingsRoots.keys.none { it.isAttachedToWindow && it.isShown && it.adapterRootView === root.adapterRootView }
    }
}
