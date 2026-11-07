package dev.amenhancer.module.hook

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule

/** Share LiquidGlass's default-off diagnostics switch with the integrated AM++ hooks. */
internal object IntegratedDiagnostics {
    @Volatile private var enabled = false
    private var preferences: SharedPreferences? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
        if (key == null || key == "enabled") refresh(changed)
    }

    @Synchronized
    fun attach(module: XposedModule) {
        if (preferences != null) return
        runCatching { module.getRemotePreferences("module_logging") }
            .onSuccess { remote ->
                remote.registerOnSharedPreferenceChangeListener(listener)
                preferences = remote
                refresh(remote)
            }
            .onFailure { enabled = false }
    }

    fun isEnabled(): Boolean = enabled

    private fun refresh(remote: SharedPreferences) {
        enabled = runCatching { remote.getBoolean("enabled", false) }.getOrDefault(false)
    }
}

/** Avoid Logcat output from upstream hook helpers until diagnostics is enabled. */
internal object IntegratedLog {
    fun d(tag: String, message: String): Int =
        if (IntegratedDiagnostics.isEnabled()) Log.d(tag, message) else 0

    fun i(tag: String, message: String): Int =
        if (IntegratedDiagnostics.isEnabled()) Log.i(tag, message) else 0

    fun w(tag: String, message: String): Int =
        if (IntegratedDiagnostics.isEnabled()) Log.w(tag, message) else 0

    fun w(tag: String, message: String, error: Throwable): Int =
        if (IntegratedDiagnostics.isEnabled()) Log.w(tag, message, error) else 0

    fun e(tag: String, message: String, error: Throwable): Int =
        if (IntegratedDiagnostics.isEnabled()) Log.e(tag, message, error) else 0

    fun e(tag: String, message: String): Int =
        if (IntegratedDiagnostics.isEnabled()) Log.e(tag, message) else 0
}
