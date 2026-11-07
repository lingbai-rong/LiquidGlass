package dev.amenhancer.module.config

import dev.amenhancer.module.ModuleConstants
import dev.amenhancer.module.model.ModuleSettings
import org.junit.Assert.*
import org.junit.Test

class SharedSettingsValuesCodecTest {
    @Test fun `round trip preserves all setting types including long index generations`() {
        val values = ModuleSettingsSchema.encode(ModuleSettings(phoneLiquidGlassEnabled = true)) +
            ModuleSettingsSchema.encodeIndexPointer(CustomLyricsIndexPointer(
                "index_1", 3_000_000_000L, "a".repeat(64), 3L))
        val restored = SharedSettingsValuesCodec.decode(SharedSettingsValuesCodec.encode(values))
        assertEquals(values, restored)
        assertEquals(true, ModuleSettingsSchema.decode(restored).phoneLiquidGlassEnabled)
        assertEquals(3_000_000_000L, ModuleSettingsSchema.decodeIndexPointer(restored)?.generation)
    }

    @Test fun `stream format carries a legacy manifest beyond Binder transaction size`() {
        val body = "歌词\"\\\n".repeat(250_000)
        val values = mapOf("custom_lyrics_manifest" to body)
        assertTrue(SharedSettingsValuesCodec.encode(values).toByteArray().size > 1024 * 1024)
        assertEquals(values, SharedSettingsValuesCodec.decode(SharedSettingsValuesCodec.encode(values)))
    }

    @Test fun `rejects unrelated preference keys and wrong boolean types`() {
        assertThrows(IllegalArgumentException::class.java) {
            SharedSettingsValuesCodec.encode(mapOf("other_application_secret" to "value"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SharedSettingsValuesCodec.decode("""{"dual_pane_enabled":{"type":"string","value":"true"}}""")
        }
    }

    @Test fun `integer values cannot be smuggled as strings or fractions`() {
        listOf("\"42\"", "42.5", "2147483648").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                SharedSettingsValuesCodec.decode("""{"schema_version":{"type":"int","value":$value}}""")
            }
        }
    }

    @Test fun `only module and Apple Music callers can access the store`() {
        assertTrue(SharedSettingsAccessPolicy.allows(12001, 12001, emptyList()))
        assertTrue(SharedSettingsAccessPolicy.allows(12002, 12001, listOf(ModuleConstants.TARGET_PACKAGE)))
        assertFalse(SharedSettingsAccessPolicy.allows(12003, 12001, listOf("com.other.app")))
        assertFalse(SharedSettingsAccessPolicy.allows(12003, 12001, emptyList()))
    }

    @Test fun `rejects traversal and URL encoded separators in payload names`() {
        assertTrue(SharedSettingsAccessPolicy.validFileId("font_123-A"))
        listOf("", "../settings", "a/b", "%2Fetc", "a.b", "a".repeat(129)).forEach {
            assertFalse(SharedSettingsAccessPolicy.validFileId(it))
        }
    }
}
