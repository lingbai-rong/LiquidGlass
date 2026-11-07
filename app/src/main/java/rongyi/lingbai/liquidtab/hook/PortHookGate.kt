package rongyi.lingbai.liquidtab.hook

import android.os.SystemClock
import rongyi.lingbai.TargetApp
import rongyi.lingbai.TargetAppHookSettingsReader

object PortHookGate {
    private var app: TargetApp? = null
    @Volatile private var checkedAt = 0L
    @Volatile private var allowed = false
    @Synchronized fun configure(value: TargetApp) { app = value; checkedAt = 0L }
    @Synchronized fun enabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (checkedAt == 0L || now - checkedAt >= 500L) {
            allowed = app?.let { TargetAppHookSettingsReader.load(null, it)?.enabled } == true
            checkedAt = now
        }
        return allowed
    }
}
