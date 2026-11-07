package rongyi.lingbai.liquidtab.hook

import rongyi.lingbai.ModuleLog

/** Diagnostics share the module's default-off LSPosed/Logcat gate. */
object PortLog {
    fun d(tag: String, message: String): Int { ModuleLog.i(tag, message); return 0 }
    fun i(tag: String, message: String): Int { ModuleLog.i(tag, message); return 0 }
    fun v(tag: String, message: String): Int = d(tag, message)
    fun w(tag: String, message: String): Int { ModuleLog.w(tag, message); return 0 }
    fun w(tag: String, message: String, error: Throwable): Int { ModuleLog.w(tag, message, error); return 0 }
    fun e(tag: String, message: String): Int { ModuleLog.w(tag, message); return 0 }
    fun e(tag: String, message: String, error: Throwable): Int { ModuleLog.e(tag, message, error); return 0 }
}
