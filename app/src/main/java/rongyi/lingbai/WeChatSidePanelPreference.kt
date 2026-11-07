// SPDX-License-Identifier: GPL-3.0-or-later
package rongyi.lingbai

import android.content.Context

/** Module-owned master switch for the injected WeChat minus-one screen. */
object WeChatSidePanelPreference {
    private const val KEY_ENABLED = "wechat_side_panel_enabled"
    @Volatile private var lastHookValue: Boolean? = null

    @JvmStatic
    fun loadLocal(context: Context): Boolean {
        val device = HookPreferenceTransport.open(
            GlassSettings.storageContext(context),
            TargetAppSettings.PREF_NAME,
        ).getBoolean(KEY_ENABLED, true)
        return try {
            if (context.isDeviceProtectedStorage) device else HookPreferenceTransport.open(
                context,
                TargetAppSettings.PREF_NAME,
            ).getBoolean(KEY_ENABLED, device)
        } catch (_: Throwable) {
            device
        }
    }

    @JvmStatic
    fun saveLocal(context: Context, enabled: Boolean): Boolean {
        val deviceSaved = HookPreferenceTransport.open(
            GlassSettings.storageContext(context),
            TargetAppSettings.PREF_NAME,
        ).edit().putBoolean(KEY_ENABLED, enabled).commit()
        try {
            if (!context.isDeviceProtectedStorage) {
                HookPreferenceTransport.open(context, TargetAppSettings.PREF_NAME)
                    .edit().putBoolean(KEY_ENABLED, enabled).commit()
            }
        } catch (_: Throwable) {
        }
        if (deviceSaved) GlassSettings.notifyChanged(context)
        return deviceSaved
    }

    @JvmStatic
    fun loadForHook(): Boolean = try {
        XposedBridge.getRemotePreferences(TargetAppSettings.PREF_NAME)
            .getBoolean(KEY_ENABLED, true)
            .also { lastHookValue = it }
    } catch (_: Throwable) {
        // A transient LSPosed preference read failure must not undo a switch change that this
        // process has already observed.
        lastHookValue ?: true
    }
}
