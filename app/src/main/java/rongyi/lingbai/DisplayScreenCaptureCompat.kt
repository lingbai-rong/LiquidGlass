package rongyi.lingbai

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import java.lang.reflect.InvocationTargetException

/**
 * Reflection-only bridge to the hidden android.window.ScreenCapture API.
 *
 * This intentionally carries no compile-time dependency on hidden framework classes. The caller
 * keeps a working PixelCopy fallback because captureDisplay is privileged on AOSP and OEM builds
 * can also change the hidden signatures.
 */
internal object DisplayScreenCaptureCompat {
    private const val TAG = "LiquidGlassScreenCapture"

    internal data class CaptureResult(
        val bitmap: Bitmap?,
        val error: Throwable? = null,
    )

    /** Resolve the SurfaceControl of the separate glass panel so the display capture can exclude it. */
    fun resolveSurfaceControl(view: View): Any? {
        return try {
            val root = view.rootView ?: view
            val getViewRootImpl = findNoArgMethod(root.javaClass, "getViewRootImpl")
                ?: findNoArgMethod(View::class.java, "getViewRootImpl")
                ?: return null
            val viewRootImpl = getViewRootImpl.invoke(root) ?: return null
            val getSurfaceControl = findNoArgMethod(viewRootImpl.javaClass, "getSurfaceControl")
                ?: return null
            val surfaceControl = getSurfaceControl.invoke(viewRootImpl) ?: return null
            val isValid = findNoArgMethod(surfaceControl.javaClass, "isValid")
            if (isValid != null && isValid.invoke(surfaceControl) == false) null else surfaceControl
        } catch (t: Throwable) {
            ModuleLog.w(TAG, "Unable to resolve overlay SurfaceControl", unwrap(t))
            null
        }
    }

    /**
     * Capture a display crop through IWindowManager.captureDisplay using a synchronous
     * ScreenCapture listener. Run this off the UI thread: getBuffer() can block waiting for SF.
     */
    fun captureDisplay(
        activity: Activity,
        sourceCrop: Rect,
        frameScale: Float,
        excludeSurfaceControl: Any,
    ): CaptureResult {
        return try {
            val screenCaptureClass = Class.forName("android.window.ScreenCapture")
            val builderClass = Class.forName("android.window.ScreenCapture\$CaptureArgs\$Builder")
            val builder = builderClass.getDeclaredConstructor().apply { isAccessible = true }
                .newInstance()

            invokeOneArg(builder, "setSourceCrop", Rect(sourceCrop))
            invokeOneArg(builder, "setFrameScale", frameScale)
            applyExcludeLayer(builder, excludeSurfaceControl)

            // Xiaomi/HyperOS builds may expose additional capture policy knobs. Do not depend on
            // them; the standard CaptureArgs fields above are sufficient for this experiment.
            val build = findNoArgMethod(builder.javaClass, "build")
                ?: throw NoSuchMethodException("ScreenCapture CaptureArgs.Builder.build")
            val captureArgs = build.invoke(builder)
                ?: throw IllegalStateException("ScreenCapture CaptureArgs is null")

            val createListener = findNoArgStaticMethod(screenCaptureClass, "createSyncCaptureListener")
                ?: throw NoSuchMethodException("ScreenCapture.createSyncCaptureListener")
            val listener = createListener.invoke(null)
                ?: throw IllegalStateException("ScreenCapture listener is null")

            val windowManagerGlobal = Class.forName("android.view.WindowManagerGlobal")
            val getWm = findNoArgStaticMethod(windowManagerGlobal, "getWindowManagerService")
                ?: throw NoSuchMethodException("WindowManagerGlobal.getWindowManagerService")
            val wm = getWm.invoke(null)
                ?: throw IllegalStateException("IWindowManager is null")

            val captureMethod = allMethods(wm.javaClass).firstOrNull { method ->
                method.name == "captureDisplay" && method.parameterTypes.size == 3 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: throw NoSuchMethodException("IWindowManager.captureDisplay(int, CaptureArgs, listener)")
            captureMethod.isAccessible = true
            val displayId = activity.display?.displayId ?: 0
            captureMethod.invoke(wm, displayId, captureArgs, listener)

            val getBuffer = findNoArgMethod(listener.javaClass, "getBuffer")
                ?: throw NoSuchMethodException("SynchronousScreenCaptureListener.getBuffer")
            val screenshotBuffer = getBuffer.invoke(listener)
                ?: return CaptureResult(null, IllegalStateException("ScreenCapture returned null buffer"))
            val asBitmap = findNoArgMethod(screenshotBuffer.javaClass, "asBitmap")
                ?: throw NoSuchMethodException("ScreenshotHardwareBuffer.asBitmap")
            val bitmap = asBitmap.invoke(screenshotBuffer) as? Bitmap
            if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
                CaptureResult(null, IllegalStateException("ScreenCapture returned null/empty bitmap"))
            } else {
                CaptureResult(bitmap, null)
            }
        } catch (t: Throwable) {
            CaptureResult(null, unwrap(t))
        }
    }

    private fun applyExcludeLayer(builder: Any, surfaceControl: Any) {
        val surfaceControlClass = Class.forName("android.view.SurfaceControl")
        if (!surfaceControlClass.isInstance(surfaceControl)) {
            throw IllegalArgumentException("Unexpected SurfaceControl class: ${surfaceControl.javaClass.name}")
        }
        val excludeArray = java.lang.reflect.Array.newInstance(surfaceControlClass, 1)
        java.lang.reflect.Array.set(excludeArray, 0, surfaceControl)
        val method = allMethods(builder.javaClass).firstOrNull { m ->
            m.name == "setExcludeLayers" && m.parameterTypes.size == 1 &&
                m.parameterTypes[0].isArray
        } ?: throw NoSuchMethodException("CaptureArgs.Builder.setExcludeLayers")
        method.isAccessible = true
        method.invoke(builder, excludeArray)
    }

    private fun invokeOneArg(receiver: Any, name: String, argument: Any) {
        val method = allMethods(receiver.javaClass).firstOrNull { m ->
            if (m.name != name || m.parameterTypes.size != 1) return@firstOrNull false
            val p = m.parameterTypes[0]
            when {
                p.isPrimitive && p == Float::class.javaPrimitiveType && argument is Float -> true
                else -> p.isAssignableFrom(argument.javaClass)
            }
        } ?: throw NoSuchMethodException("${receiver.javaClass.name}.$name")
        method.isAccessible = true
        method.invoke(receiver, argument)
    }

    private fun findNoArgMethod(clazz: Class<*>, name: String) =
        allMethods(clazz).firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            ?.apply { isAccessible = true }

    private fun findNoArgStaticMethod(clazz: Class<*>, name: String) =
        allMethods(clazz).firstOrNull {
            it.name == name && it.parameterTypes.isEmpty() &&
                java.lang.reflect.Modifier.isStatic(it.modifiers)
        }?.apply { isAccessible = true }

    private fun allMethods(clazz: Class<*>): List<java.lang.reflect.Method> {
        val result = ArrayList<java.lang.reflect.Method>()
        var current: Class<*>? = clazz
        while (current != null) {
            try { result.addAll(current.declaredMethods) } catch (_: Throwable) {}
            current = current.superclass
        }
        try { result.addAll(clazz.methods) } catch (_: Throwable) {}
        return result
    }

    private fun unwrap(t: Throwable): Throwable {
        var current = t
        while (current is InvocationTargetException && current.targetException != null) {
            current = current.targetException
        }
        return current
    }
}
