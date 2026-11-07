package dev.amenhancer.module.config

import org.junit.Assert.*
import org.junit.Test

class SharedSettingsMigrationPolicyTest {
    private val marker = EmbeddedConfigurationMigration.MIGRATION_MARKER_KEY
    private val start = mapOf(marker to EmbeddedConfigurationMigration.MIGRATION_IN_PROGRESS)
    private val finish = mapOf(marker to EmbeddedConfigurationMigration.MIGRATION_COMPLETE,
        "phone_liquid_glass_enabled" to false)

    @Test fun `migration can initialize an empty store and retry an interrupted copy`() {
        assertTrue(SharedSettingsMigrationPolicy.allowsWrite(emptyMap<String, Any>(), start))
        assertTrue(SharedSettingsMigrationPolicy.allowsWrite(start, start))
        assertTrue(SharedSettingsMigrationPolicy.allowsWrite(start, finish))
    }

    @Test fun `an edit in the module during migration prevents stale publication`() {
        val edited = start + mapOf("phone_liquid_glass_enabled" to true)
        assertFalse(SharedSettingsMigrationPolicy.allowsWrite(edited, finish))
        assertFalse(SharedSettingsMigrationPolicy.allowsWrite(edited, start))
        assertFalse(SharedSettingsMigrationPolicy.allowsWrite(finish, start))
        assertFalse(SharedSettingsMigrationPolicy.allowsWrite(finish, finish))
    }

    @Test fun `ordinary settings edits remain writable during and after migration`() {
        val edit = mapOf("phone_liquid_glass_enabled" to true)
        assertTrue(SharedSettingsMigrationPolicy.allowsWrite(start, edit))
        assertTrue(SharedSettingsMigrationPolicy.allowsWrite(finish, edit))
    }
}
