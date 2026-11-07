package dev.amenhancer.module

import android.app.Activity
import android.content.Context
import android.content.Intent
import dev.amenhancer.module.config.AmppSettingsProvider
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.ProviderEmbeddedStorage
import dev.amenhancer.module.config.HostPrivateEmbeddedStorage
import dev.amenhancer.module.config.ModuleSettingsSchema
import dev.amenhancer.module.ui.EmbeddedRuntimeSettingsController
import dev.amenhancer.module.ui.EmbeddedSettingsHost

/** Opens the original complete editor inside the module, including its SAF/network workflows. */
class AmppSettingsEditor private constructor(
    private val host: EmbeddedSettingsHost,
    private val storage: ProviderEmbeddedStorage,
) : AutoCloseable {
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int,
        data: Intent?): Boolean = host.onActivityResult(activity, requestCode, resultCode, data)

    override fun close() { host.uninstall(); storage.close() }

    fun refreshLayout() { host.refreshInModule() }

    companion object {
        fun hasEnabledFeatures(context: Context): Boolean {
            val settings = ModuleSettingsSchema.decode(HostPrivateEmbeddedStorage(context).values())
            return settings.dualPaneEnabled || settings.phoneLiquidGlassEnabled ||
                settings.futureBlurEnabled || settings.cjkKaraokeAnimationEnabled ||
                settings.navigationCompensationEnabled || settings.titleCorrectionEnabled ||
                settings.customLyricsEnabled || settings.fontManifest.enabled ||
                settings.appleMusicDpiOverrideDpi != 0
        }

        /** URI grants make the module visible to a host whose manifest cannot declare our provider. */
        @JvmStatic
        fun grantHostAccess(context: Context) {
            runCatching {
                context.grantUriPermission(ModuleConstants.TARGET_PACKAGE, AmppSettingsProvider.BASE_URI,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
        }

        fun open(activity: Activity, onClose: () -> Unit): AmppSettingsEditor {
            grantHostAccess(activity)
            val storage = ProviderEmbeddedStorage(activity)
            try {
                val controller = EmbeddedRuntimeSettingsController(activity,
                    EmbeddedConfigurationSession(storage), currentSong = { null })
                val host = EmbeddedSettingsHost.install(activity.application, controller)
                host.showInModule(activity, onClose)
                return AmppSettingsEditor(host, storage)
            } catch (error: Exception) { storage.close(); throw error }
        }
    }
}
