// SPDX-License-Identifier: GPL-3.0-only
// Avatar matchers and radius behavior adapted from WeKit's contacts/RoundAvatars.kt,
// by Ujhhgtg and contributors. LiquidGlass supplies its own API 102 lifecycle/preferences.
package rongyi.lingbai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import io.github.libxposed.api.XposedInterface
import org.luckypray.dexkit.DexKitBridge
import rongyi.lingbai.XC_MethodHook.MethodHookParam
import rongyi.lingbai.wechatpanel.PanelNativeLoader
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Narrow avatar-loader hooks; ordinary images and the original bitmap are untouched. */
object WeChatRoundAvatars {
    private const val TAG = "[LiquidGlass][WeChatRoundAvatars]"
    // WeKit's default: half the avatar side, producing a circle for a square avatar.
    private const val RADIUS_FACTOR = 0.5f
    private val lock = Any()
    private var hostLoader: ClassLoader? = null
    private var resolving = false
    private var attempted = false
    @Volatile private var enabled = false
    @Volatile private var hooksReady = false
    @Volatile private var notificationHookReady = false
    @Volatile private var notificationErrorLogged = false
    private var installUiAvatars = true

    @JvmStatic
    fun install(lpparam: LoadPackageParam) {
        if (lpparam.packageName != TargetApp.WECHAT.packageName) return
        val mainProcess = lpparam.processName == null || lpparam.processName == lpparam.packageName
        if (!mainProcess && lpparam.processName != "${lpparam.packageName}:push") return
        synchronized(lock) {
            hostLoader = lpparam.classLoader
            installUiAvatars = mainProcess
        }
        reloadSettings()
    }

    /** Called on remote updates and LauncherUI resume; never reads preferences per avatar. */
    @JvmStatic
    fun reloadSettings() {
        val nextEnabled = WeChatRoundAvatarPreference.loadForHook()
        val loader = synchronized(lock) {
            enabled = nextEnabled
            if (!nextEnabled) {
                attempted = false
                return
            }
            if (((hooksReady || !installUiAvatars) && notificationHookReady) || resolving || attempted) return
            val currentLoader = hostLoader ?: return
            attempted = true
            resolving = true
            currentLoader
        }
        // The feature is optional. A full DEX scan must not stall the host's UI thread.
        try {
            Thread({
                try {
                    installTargets(loader)
                } catch (error: Throwable) {
                    XposedBridge.log("$TAG avatar adaptation unavailable; other features remain active: $error")
                    XposedBridge.log(error)
                } finally {
                    synchronized(lock) { resolving = false }
                }
            }, "LiquidGlass-WeChatAvatars").apply { isDaemon = true }.start()
        } catch (error: Throwable) {
            synchronized(lock) { resolving = false }
            XposedBridge.log("$TAG could not start avatar resolution: $error")
        }
    }

    private data class Target(
        val member: Executable,
        val radiusIndex: Int,
        val defaultMaskIndex: Int = -1,
    )

    private fun avatarTarget(member: Executable, radiusIndex: Int): Target {
        val types = member.parameterTypes
        check(types.getOrNull(radiusIndex - 1) == String::class.java &&
            types.getOrNull(radiusIndex) == Float::class.javaPrimitiveType &&
            types.getOrNull(radiusIndex + 1) == Boolean::class.javaPrimitiveType &&
            types.getOrNull(radiusIndex + 2) == String::class.java) {
            "Unexpected avatar parameter layout: ${member.toGenericString()}"
        }
        if (types.size == radiusIndex + 3) return Target(member, radiusIndex)
        // Verified in 8.0.72: the constructor and static updater both consume bit 0x4
        // of a trailing Kotlin default-argument mask and overwrite radius with 0.1f.
        // Select the verified structure, not an obfuscated member name or version number.
        val markerMatches = when (member) {
            is Constructor<*> -> types.last().name.startsWith("kotlin.jvm.internal.")
            is Method -> Modifier.isStatic(member.modifiers) &&
                types.first() == member.declaringClass && types.last() == Any::class.java
            else -> false
        }
        check(types.size == radiusIndex + 5 &&
            types[radiusIndex + 3] == Int::class.javaPrimitiveType && markerMatches) {
            "Unsupported avatar default-argument layout: ${member.toGenericString()}"
        }
        return Target(member, radiusIndex, radiusIndex + 3)
    }

    private fun installTargets(loader: ClassLoader) {
        check(PanelNativeLoader.ensureDexKitLoaded()) { "DexKit native library unavailable" }
        val bridge = DexKitBridge.create(loader, true)
        try {
            // These features share one switch but adapt independently: a changed UI avatar
            // signature must not prevent notification avatars from being installed.
            if (installUiAvatars && !hooksReady) try {
                installUiAvatarTargets(bridge, loader)
            } catch (error: Throwable) {
                XposedBridge.log("$TAG UI avatar adaptation unavailable: $error")
                XposedBridge.log(error)
            }
            if (!notificationHookReady) try {
                installNotificationAvatarTarget(bridge, loader)
            } catch (error: Throwable) {
                XposedBridge.log("$TAG notification avatar adaptation unavailable: $error")
                XposedBridge.log(error)
            }
        } finally {
            bridge.close()
        }
    }

    private fun installUiAvatarTargets(bridge: DexKitBridge, loader: ClassLoader) {
        val targets = run {
            val loaders = bridge.findMethod {
                matcher {
                    paramTypes("android.widget.ImageView", "java.lang.String", "float", "boolean")
                    usingEqStrings("MicroMsg.AvatarDrawable")
                }
            }
            check(loaders.size == 1) { "Expected one avatar loader, found ${loaders.size}" }

            val avatarMembers = bridge.findMethod {
                matcher { usingEqStrings("workerScope", "username") }
            }
            val constructors = avatarMembers.filter { it.methodName == "<init>" }
            check(constructors.size == 1) { "Expected one avatar constructor, found ${constructors.size}" }
            val modifiers = avatarMembers.filter { it.methodName != "<init>" }
            // Absence is a supported WeKit path. Ambiguity is a resolution failure, not absence.
            check(modifiers.size <= 1) { "Ambiguous avatar update method: ${modifiers.size} matches" }

            buildList {
                add(Target(loaders.single().getMethodInstance(loader), 2))
                add(avatarTarget(constructors.single().getConstructorInstance(loader), 2))
                modifiers.singleOrNull()?.let { add(avatarTarget(it.getMethodInstance(loader), 3)) }
            }.onEach { target ->
                check(target.member.parameterTypes.getOrNull(target.radiusIndex) == Float::class.javaPrimitiveType) {
                    "Unexpected radius parameter in ${target.member.toGenericString()}"
                }
                target.member.isAccessible = true
            }
        }

        // Resolve and validate every target before changing host behavior. If registration
        // fails, roll back this feature's partial hooks; do not affect navigation or the panel.
        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            for (target in targets) {
                handles += XposedBridge.hookMethod(target.member, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!hooksReady || !enabled) return
                        param.args[target.radiusIndex] = RADIUS_FACTOR
                        if (target.defaultMaskIndex >= 0) {
                            // Leave the boolean/tag defaults intact; only supply radius explicitly.
                            val mask = param.args[target.defaultMaskIndex] as Int
                            param.args[target.defaultMaskIndex] = mask and 0x4.inv()
                        }
                    }
                })
            }
            hooksReady = true
            XposedBridge.log("$TAG installed ${handles.size} avatar hooks")
        } catch (error: Throwable) {
            for (handle in handles.asReversed()) {
                try { handle.unhook() } catch (rollbackError: Throwable) { error.addSuppressed(rollbackError) }
            }
            throw error
        }
    }

    /** WeChat's notification-only bitmap scaler is used by normal and light-push messages. */
    private fun installNotificationAvatarTarget(bridge: DexKitBridge, loader: ClassLoader) {
        // 8.0.72: the method tagged "loadAvatar err" belongs to the same class as
        // a(Context, Bitmap). Do not hook the generic rounded-bitmap utility: music
        // notification covers and mini-program images call that utility as well.
        val anchors = bridge.findMethod {
            matcher { usingEqStrings("loadAvatar err: ", "MicroMsg.NotificationAvatar") }
        }
        check(anchors.size == 1) { "Expected one notification avatar anchor, found ${anchors.size}" }
        val anchor = anchors.single().getMethodInstance(loader)
        check(anchor.returnType == Bitmap::class.java &&
            anchor.parameterTypes.size == 2 &&
            anchor.parameterTypes[0] == String::class.java) {
            "Unexpected notification avatar anchor: ${anchor.toGenericString()}"
        }
        val candidates = anchor.declaringClass.declaredMethods.filter { method ->
            Modifier.isStatic(method.modifiers) && method.returnType == Bitmap::class.java &&
                method.parameterTypes.contentEquals(arrayOf(Context::class.java, Bitmap::class.java))
        }
        check(candidates.size == 1) {
            "Expected one notification avatar scaler, found ${candidates.size}"
        }
        val method = candidates.single().apply { isAccessible = true }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!notificationHookReady || !enabled || param.getThrowable() != null) return
                val bitmap = param.getResult() as? Bitmap ?: return
                val rounded = roundNotificationAvatar(bitmap)
                if (rounded !== bitmap) param.setResult(rounded)
            }
        })
        notificationHookReady = true
        XposedBridge.log("$TAG installed notification avatar hook")
    }

    private fun roundNotificationAvatar(bitmap: Bitmap): Bitmap {
        var output: Bitmap? = null
        try {
            if (bitmap.isRecycled || bitmap.width !in 1..1024 || bitmap.height !in 1..1024) {
                return bitmap
            }
            // Keep WeChat's scaled dimensions and lifecycle. Its notification owner
            // caches/recycles the result; the original bitmap is never modified.
            output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val canvas = Canvas(output)
            val cx = bitmap.width / 2f
            val cy = bitmap.height / 2f
            canvas.drawCircle(cx, cy, minOf(cx, cy), paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            paint.xfermode = null
            return output
        } catch (error: Throwable) {
            output?.recycle()
            if (!notificationErrorLogged) {
                notificationErrorLogged = true
                XposedBridge.log("$TAG notification avatar masking failed: $error")
            }
            return bitmap
        }
    }
}
