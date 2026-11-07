// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.cainiao

import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import rongyi.lingbai.XC_MethodHook
import rongyi.lingbai.XposedBridge
import java.util.WeakHashMap

/** Strip only navigation fitting on the decor windows managed by this adapter. */
internal object CainiaoInsets {
    private val windows = WeakHashMap<View, Boolean>()
    private var installed = false

    fun configure(decor: View, enabled: Boolean) {
        if (enabled) windows[decor] = true else windows.remove(decor)
        if (!enabled || installed) return
        XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchApplyWindowInsets", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
 if (!rongyi.lingbai.liquidtab.hook.PortHookGate.enabled()) return
                if (windows[param.thisObject] != true) return
                val original = param.args.firstOrNull() as? WindowInsets ?: return
                val type = WindowInsets.Type.navigationBars()
                val visible = original.getInsets(type)
                val stable = original.getInsetsIgnoringVisibility(type)
                if (visible.bottom == 0 && stable.bottom == 0) return
                param.args[0] = WindowInsets.Builder(original)
                    .setInsets(type, Insets.of(visible.left, visible.top, visible.right, 0))
                    .setInsetsIgnoringVisibility(type, Insets.of(stable.left, stable.top, stable.right, 0))
                    .build()
            }
        })
        installed = true
    }
}
