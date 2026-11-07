package dev.amenhancer.module.config

import dev.amenhancer.module.lyrics.CustomLyricsFilePolicy
import dev.amenhancer.module.model.CustomLyricsManifest
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class SharedIndexPublicationTest {
    @Test fun `a stale editor cannot replace the index published by the other process`() {
        val bytes = CustomLyricsManifestCodec.encode(CustomLyricsManifest.empty()).toByteArray()
        val pointer = CustomLyricsIndexPointer("index_current", 4L,
            CustomLyricsFilePolicy.sha256(bytes), bytes.size.toLong())
        val preferences = ModuleSettingsSchema.encodeIndexPointer(pointer).toMutableMap()
        val files = mutableMapOf("index_current" to bytes)
        val staleState = CustomLyricsIndexState(null, CustomLyricsManifest.empty())
        val repository = CustomLyricsIndexRepository(
            newIndexFileId = { "index_stale" },
            openFile = { files[it]?.let(::ByteArrayInputStream) },
            writeRemoteFile = { name, body -> files[name] = body; true },
            publishPointer = { error("shared editor must use conditional publication") },
            deleteRemoteFile = { files.remove(it) },
            publishPointerIfUnchanged = { expected, next ->
                val current = CustomLyricsIndexRepository.state(preferences) {
                    files[it]?.let(::ByteArrayInputStream)
                }
                if (expected != current) false else {
                    preferences.putAll(ModuleSettingsSchema.encodeIndexPointer(next)); true
                }
            },
        )
        assertTrue(repository.commit(staleState, CustomLyricsManifest.empty()) is
            CustomLyricsIndexCommitResult.Failed)
        assertEquals(pointer, ModuleSettingsSchema.decodeIndexPointer(preferences))
        assertTrue(files.containsKey("index_current"))
        assertFalse(files.containsKey("index_stale"))
        val current = repository.state(preferences)
        assertTrue(repository.commit(current, CustomLyricsManifest.empty()) is
            CustomLyricsIndexCommitResult.Committed)
        assertEquals(5L, ModuleSettingsSchema.decodeIndexPointer(preferences)?.generation)
    }
}
