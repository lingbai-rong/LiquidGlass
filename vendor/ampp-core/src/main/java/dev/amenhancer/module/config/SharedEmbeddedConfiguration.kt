package dev.amenhancer.module.config

import android.content.Context
import android.content.Intent

internal object SharedEmbeddedConfiguration {
    /** Preserve 27.17/27.18 host data once; never overwrite an already configured module store. */
    fun connect(context: Context, legacy: HostPrivateEmbeddedStorage): EmbeddedConfigurationStorage? {
        val shared = runCatching { ProviderEmbeddedStorage(context) }.getOrNull() ?: return null
        try {
            runCatching {
                context.contentResolver.takePersistableUriPermission(AmppSettingsProvider.BASE_URI,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            shared.values() // Require a real first snapshot, not a successful observer registration.
            if (!EmbeddedConfigurationMigration.destinationAlreadyInitialized(shared)) {
                val result = EmbeddedConfigurationMigration.migrate(
                    remoteValues = legacy.values(), openRemoteFile = legacy::openFile,
                    destination = shared,
                )
                if (result is EmbeddedConfigurationMigrationResult.Failed) {
                    shared.close()
                    return null
                }
            }
            return shared
        } catch (_: Exception) { shared.close(); return null }
    }
}
