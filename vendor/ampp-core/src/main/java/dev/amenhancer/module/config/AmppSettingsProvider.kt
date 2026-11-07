package dev.amenhancer.module.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import dev.amenhancer.module.ModuleConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import org.json.JSONObject

/** The module owns the one settings store used by both editors and the hooked player. */
class AmppSettingsProvider : ContentProvider() {
    private lateinit var storage: HostPrivateEmbeddedStorage
    private lateinit var preferences: SharedPreferences
    private lateinit var staging: File
    private val mutationLock = Any()
    @Volatile private var currentSong: dev.amenhancer.module.CurrentSongDetails? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        context?.contentResolver?.notifyChange(BASE_URI, null)
    }

    override fun onCreate(): Boolean {
        val app = context ?: return false
        storage = HostPrivateEmbeddedStorage(app)
        preferences = app.getSharedPreferences(HostPrivateEmbeddedStorage.PREFERENCES_NAME, 0)
        preferences.registerOnSharedPreferenceChangeListener(listener)
        staging = File(app.cacheDir, "ampp-settings-transfer").apply { mkdirs() }
        // Recover abandoned transfers after a process crash. Published payloads are elsewhere.
        staging.listFiles()?.forEach { it.delete() }
        return true
    }

    private fun checkCaller() {
        val app = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (!SharedSettingsAccessPolicy.allows(
            uid, app.applicationInfo.uid,
            app.packageManager.getPackagesForUid(uid)?.toList().orEmpty(),
        )) throw SecurityException("Apple Music settings access denied")
    }

    private fun stageFile(id: String): File {
        require(SharedSettingsAccessPolicy.validFileId(id))
        return File(staging, "${Binder.getCallingUid()}_$id")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        require(uri.authority == BASE_URI.authority)
        val parts = uri.pathSegments
        if (parts == listOf("snapshot") && mode == "r") {
            val temporary = File.createTempFile("snapshot_", ".json", staging)
            try {
                FileOutputStream(temporary).use {
                    it.write(SharedSettingsValuesCodec.encode(storage.values()).toByteArray())
                }
                return ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            } finally {
                // The opened descriptor retains the inode without exposing a reusable path.
                temporary.delete()
            }
        }
        if (parts.size != 2 || !SharedSettingsAccessPolicy.validFileId(parts[1])) {
            throw FileNotFoundException("Invalid settings file")
        }
        return when {
            parts[0] == "files" && mode == "r" -> storage.openFileDescriptor(parts[1])
                ?: throw FileNotFoundException(parts[1])
            parts[0] == "staging" && mode in setOf("w", "wt") ->
                ParcelFileDescriptor.open(stageFile(parts[1]),
                    ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE)
            else -> throw FileNotFoundException("Unsupported settings file operation")
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        checkCaller()
        // Metadata is transient and kept outside the settings snapshot/index notifications.
        if (method == "current_song") return SharedCurrentSong.encode(currentSong)
        if (method == "publish_song") {
            currentSong = SharedCurrentSong.decode(extras)
            return Bundle().apply { putBoolean("ok", true) }
        }
        val result = synchronized(mutationLock) {
            when (method) {
                "has_files" -> storage.hasAnyFiles()
                "remove" -> {
                    val keys = extras?.getStringArrayList("keys")?.toSet().orEmpty()
                    require(keys.all(SharedSettingsValuesCodec::isAllowedKey))
                    storage.removeValues(keys)
                }
                "delete" -> storage.deleteFile(requireNotNull(arg))
                "discard" -> stageFile(requireNotNull(arg)).delete()
                "commit_file" -> {
                    val file = stageFile(requireNotNull(arg))
                    try {
                        val data = requireNotNull(extras)
                        FileInputStream(file).use { input ->
                            storage.copyFile(requireNotNull(data.getString("name")), input,
                                data.getLong("size"), requireNotNull(data.getString("sha256")))
                        }
                    } finally { file.delete() }
                }
                "write_values", "publish_index" -> {
                    val file = stageFile(requireNotNull(arg))
                    try {
                        // A v1 manifest may be larger than Binder's transaction limit.
                        require(file.length() <= MAX_SETTINGS_TRANSFER_BYTES)
                        val request = JSONObject(file.readText())
                        val values = SharedSettingsValuesCodec.decode(request.getString("values"))
                        val expected = if (method == "publish_index") {
                            CustomLyricsIndexState(
                                pointer = ModuleSettingsSchema.decodeIndexPointer(
                                    SharedSettingsValuesCodec.decode(request.getString("pointer"))),
                                manifest = requireNotNull(CustomLyricsManifestCodec.decodeIndexFile(
                                    request.getString("manifest"))),
                                canCommit = request.getBoolean("canCommit"),
                            )
                        } else null
                        val current = if (expected != null) {
                            CustomLyricsIndexRepository.state(storage.values(), storage::openFile)
                        } else null
                        if (expected != current ||
                            !SharedSettingsMigrationPolicy.allowsWrite(storage.values(), values)
                        ) false else storage.writeValues(values, true)
                    } finally { file.delete() }
                }
                else -> error("Unknown Apple Music settings operation")
            }
        }
        if (result && method !in setOf("has_files", "discard")) {
            context?.contentResolver?.notifyChange(BASE_URI, null)
        }
        return Bundle().apply { putBoolean("ok", result) }
    }

    override fun getType(uri: Uri): String { checkCaller(); return "application/octet-stream" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        checkCaller(); return null
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? { checkCaller(); return null }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        checkCaller(); return 0
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?): Int { checkCaller(); return 0 }

    companion object {
        internal val BASE_URI: Uri = Uri.parse("content://${ModuleConstants.MODULE_PACKAGE}.apple_music_settings")
        // Two full 8 MiB indexes plus JSON escaping and ordinary settings.
        private const val MAX_SETTINGS_TRANSFER_BYTES = 64L * 1024 * 1024
    }
}
