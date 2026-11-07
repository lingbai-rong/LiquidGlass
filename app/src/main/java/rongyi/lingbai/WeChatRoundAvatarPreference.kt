// SPDX-License-Identifier: GPL-3.0-only
package rongyi.lingbai

import android.content.Context

/** Separate from the WeChat navigation and side-panel switches. Published by API 102. */
object WeChatRoundAvatarPreference {
    private const val KEY_ENABLED = "wechat_round_avatars_enabled"
    @Volatile private var lastHookValue = false

    @JvmStatic
    fun loadLocal(context: Context): Boolean = try {
        HookPreferenceTransport.open(context, TargetAppSettings.PREF_NAME)
            .getBoolean(KEY_ENABLED, false)
    } catch (_: Throwable) {
        false
    }

    @JvmStatic
    fun saveLocal(context: Context, enabled: Boolean): Boolean = try {
        // HookPreferenceTransport already uses the canonical device-protected store and
        // publishes its changes. Editing just this key preserves every other host setting.
        val saved = HookPreferenceTransport.open(context, TargetAppSettings.PREF_NAME)
            .edit().putBoolean(KEY_ENABLED, enabled).commit()
        if (saved) GlassSettings.notifyChanged(context)
        saved
    } catch (_: Throwable) {
        false
    }

    @JvmStatic
    fun loadForHook(): Boolean = try {
        XposedBridge.getRemotePreferences(TargetAppSettings.PREF_NAME)
            .getBoolean(KEY_ENABLED, false)
            .also { lastHookValue = it }
    } catch (_: Throwable) {
        // A temporary binder failure must not undo an already-observed setting.
        lastHookValue
    }
}
