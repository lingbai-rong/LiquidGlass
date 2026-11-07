package dev.amenhancer.module.config

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Descriptor-based transport; cached preferences keep IPC out of player animation frames. */
internal class ProviderEmbeddedStorage(context: Context) : EmbeddedConfigurationStorage, AutoCloseable {
    private val resolver = context.applicationContext.contentResolver
    private val base = AmppSettingsProvider.BASE_URI
    private val revision = AtomicLong()
    private var loadedRevision = -1L
    @Volatile private var snapshot: Map<String, Any>? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { revision.incrementAndGet() }
    }

    init { resolver.registerContentObserver(base, true, observer) }

    @Synchronized
    override fun values(): Map<String, *> {
        val version = revision.get()
        if (loadedRevision == version) snapshot?.let { return it }
        try {
            val input = resolver.openInputStream(uri("snapshot"))
                ?: error("Apple Music settings store unavailable")
            val values = input.bufferedReader().use { SharedSettingsValuesCodec.decode(it.readText()) }
            snapshot = values
            loadedRevision = version
            return values
        } catch (error: Exception) {
            // A provider restart should not interrupt an already configured player.
            return snapshot ?: throw error
        }
    }

    override fun writeValues(values: Map<String, Any>, synchronous: Boolean): Boolean =
        sendValues("write_values", JSONObject().put("values", SharedSettingsValuesCodec.encode(values)))

    override fun publishIndexPointer(expected: CustomLyricsIndexState,
        pointer: CustomLyricsIndexPointer): Boolean = sendValues("publish_index", JSONObject().apply {
        put("values", SharedSettingsValuesCodec.encode(ModuleSettingsSchema.encodeIndexPointer(pointer)))
        put("pointer", SharedSettingsValuesCodec.encode(expected.pointer?.let(
            ModuleSettingsSchema::encodeIndexPointer).orEmpty()))
        put("manifest", CustomLyricsManifestCodec.encode(expected.manifest))
        put("canCommit", expected.canCommit)
    })

    private fun sendValues(method: String, request: JSONObject): Boolean = withStagedFile(
        ByteArrayInputStream(request.toString().toByteArray()),
    ) { id -> call(method, id) }

    override fun removeValues(keys: Set<String>, synchronous: Boolean): Boolean =
        call("remove", extras = Bundle().apply { putStringArrayList("keys", ArrayList(keys)) })

    override fun openFile(name: String): InputStream? = openFileDescriptor(name)
        ?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }

    override fun openFileDescriptor(name: String): ParcelFileDescriptor? = runCatching {
        require(SharedSettingsAccessPolicy.validFileId(name))
        resolver.openFileDescriptor(uri("files", name), "r")
    }.getOrNull()

    override fun writeFile(name: String, bytes: ByteArray): Boolean = copyFile(
        name, ByteArrayInputStream(bytes), bytes.size.toLong(), CustomLyricsFilePolicy.sha256(bytes),
    )

    override fun copyFile(name: String, input: InputStream, expectedSizeBytes: Long,
        expectedSha256: String): Boolean {
        if (!SharedSettingsAccessPolicy.validFileId(name) || expectedSizeBytes < 0) {
            input.close(); return false
        }
        return withStagedFile(input, expectedSizeBytes, expectedSha256) { id ->
            call("commit_file", id, Bundle().apply {
                putString("name", name)
                putLong("size", expectedSizeBytes)
                putString("sha256", expectedSha256)
            })
        }
    }

    /** Close and flush the writer before publishing, including on slow SAF document providers. */
    private fun withStagedFile(input: InputStream, expectedSize: Long? = null,
        expectedSha: String? = null, commit: (String) -> Boolean): Boolean {
        val id = "transfer_" + UUID.randomUUID().toString().replace("-", "")
        return try {
            input.use { source ->
                val descriptor = resolver.openFileDescriptor(uri("staging", id), "wt")
                    ?: return false
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (expectedSize != null && size > expectedSize) return false
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
                if (expectedSize != null && size != expectedSize) return false
                if (expectedSha != null && !hex(digest).equals(expectedSha, true)) return false
            }
            commit(id)
        } catch (_: Exception) {
            false
        } finally { call("discard", id) }
    }

    override fun fileMatches(name: String, expectedSizeBytes: Long,
        expectedSha256: String): Boolean = runCatching {
        val input = openFile(name) ?: return@runCatching false
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        input.use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                size += count
                if (size > expectedSizeBytes) return@runCatching false
                digest.update(buffer, 0, count)
            }
        }
        size == expectedSizeBytes && hex(digest).equals(expectedSha256, true)
    }.getOrDefault(false)

    override fun deleteFile(name: String): Boolean = call("delete", name)
    override fun hasAnyFiles(): Boolean = requireNotNull(
        resolver.call(base, "has_files", null, null),
    ).getBoolean("ok")
    override fun close() { resolver.unregisterContentObserver(observer) }

    private fun call(method: String, arg: String? = null, extras: Bundle? = null): Boolean =
        runCatching {
            val result = resolver.call(base, method, arg, extras)?.getBoolean("ok") == true
            if (result && method !in setOf("has_files", "discard")) revision.incrementAndGet()
            result
        }.getOrDefault(false)

    private fun uri(vararg parts: String): Uri = base.buildUpon().apply {
        parts.forEach(::appendPath)
    }.build()

    private fun hex(digest: MessageDigest): String = digest.digest().joinToString("") {
        "%02x".format(it)
    }
}
