// Adapted from user-supplied LiquidTab 2.0 (GPL-3.0); LiquidGlass retains its renderer.
package rongyi.lingbai.liquidtab.hook.adapters.qqmusic

import android.app.Activity
import android.view.View
import rongyi.lingbai.XC_MethodHook
import rongyi.lingbai.XposedBridge
import rongyi.lingbai.XposedHelpers
import rongyi.lingbai.liquidtab.hook.HookConfigReader
import org.json.JSONObject

/** APK 20.9: the two top-tip factories inflate b9l/m6a and b9m/m6n above the player. */
internal object QqMusicPromoHooks {
    private const val TIP_PACKAGE = "com.tencent.qqmusic.minibarviptips"
    private val viewNames = setOf("$TIP_PACKAGE.view.MinibarTopTipView", "$TIP_PACKAGE.view.MinibarTopVipCommTipView")

    fun suppress(view: View): Boolean {
        if (view.javaClass.name !in viewNames || !HookConfigReader.read(view.context, targetSpec.packageName).enabled) return false
        view.animate().cancel()
        if (view.visibility != View.GONE) rongyi.lingbai.liquidtab.hook.PortChromeOwnership.hide(view)
        if (view.background != null) rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearBackground(view)
        if (view.foreground != null) rongyi.lingbai.liquidtab.hook.PortChromeOwnership.clearForeground(view)
        return true
    }

    fun install(loader: ClassLoader) {
        installSongMembershipPopup(loader)
        runCatching {
            val manager = XposedHelpers.findClass("$TIP_PACKAGE.MinibarTipManager", loader)
            // These lazy factories already accept null when native suppression is active.
            // Reject creation before the banner's artwork, animations or observers run.
            for (method in listOf("d0", "e0")) {
                XposedBridge.hookAllMethods(manager, method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
 if (!rongyi.lingbai.liquidtab.hook.PortHookGate.enabled()) return
                        val owner = param.args.firstOrNull() ?: return
                        val root = manager.getDeclaredField("d").apply { isAccessible = true }.get(owner) as? View ?: return
                        if (HookConfigReader.read(root.context, targetSpec.packageName).enabled) param.setResult(null)
                    }
                }).also { check(it.isNotEmpty()) { "missing top-tip factory $method" } }
            }
        }.onFailure { XposedBridge.log("[OfflineGlass][QQMusic] promo factory hook failed: $it") }
        for (name in viewNames) runCatching {
            val type = XposedHelpers.findClass(name, loader)
            // Both implement the APK's tip interface: d(data), f(song,data) are show entry points.
            for (method in listOf("d", "f")) {
                XposedBridge.hookAllMethods(type, method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
 if (!rongyi.lingbai.liquidtab.hook.PortHookGate.enabled()) return
                        val view = param.thisObject as? View ?: return
                        if (suppress(view)) param.setResult(null)
                    }
                }).also { check(it.isNotEmpty()) { "missing top-tip display $name.$method" } }
            }
        }.onFailure { XposedBridge.log("[OfflineGlass][QQMusic] promo display hook failed: $it") }
    }

    private fun installSongMembershipPopup(loader: ClassLoader) {
        runCatching {
            val companion = XposedHelpers.findClass(
                "com.tme.qqmusic.knative.kuikly.container.KuiklyRenderFragment\$a", loader)
            // Both nullable open entry points e(Activity, info) / f(Activity, url)
            // converge on d BEFORE creating the dialog window and dim layer.
            XposedBridge.hookAllMethods(companion, "d", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
 if (!rongyi.lingbai.liquidtab.hook.PortHookGate.enabled()) return
                    val activity = param.args.getOrNull(0) as? Activity ?: return
                    val info = param.args.getOrNull(2) ?: return
                    if (!HookConfigReader.read(activity, targetSpec.packageName).enabled) return
                    val membershipRecommendation = runCatching {
                        if (XposedHelpers.callMethod(info, "l") != "dialog_song_popup") return@runCatching false
                        val data = XposedHelpers.callMethod(info, "m") ?: return@runCatching false
                        val params = JSONObject(data.toString())
                        params.optString("__alert_id") == "21" ||
                            params.optString("aid").contains(".vip.")
                    }.getOrDefault(false)
                    if (membershipRecommendation) {
                        param.setResult(null)
                        XposedBridge.log("[OfflineGlass][QQMusic] suppressed song membership popup")
                    }
                }
            }).also { check(it.isNotEmpty()) { "missing Kuikly dialog creation method" } }
        }.onFailure { XposedBridge.log("[OfflineGlass][QQMusic] membership popup hook failed: $it") }
    }
}
