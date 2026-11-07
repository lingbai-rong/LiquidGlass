package dev.amenhancer.module.config

import dev.amenhancer.module.model.ModuleSettings
import org.json.JSONObject

/** Typed, streamed preferences: large legacy lyric indexes never travel in a Binder Bundle. */
internal object SharedSettingsValuesCodec {
    private val defaults = ModuleSettingsSchema.encode(ModuleSettings()) +
        ModuleSettingsSchema.encodeIndexPointer(CustomLyricsIndexPointer("index", 1, "", 1)) +
        mapOf(
            EmbeddedConfigurationMigration.MIGRATION_MARKER_KEY to "",
            "title_correction_target_language" to "",
            "online_lyric_replacement_enabled" to false,
        )

    fun encode(values: Map<String, *>): String = JSONObject().apply {
        values.forEach { (key, value) ->
            require(isAllowed(key, value)) { "Invalid Apple Music setting: $key" }
            put(key, JSONObject().apply {
                put("type", when (value) {
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Long -> "long"
                    else -> "string"
                })
                put("value", value)
            })
        }
    }.toString()

    fun decode(json: String): Map<String, Any> {
        val root = JSONObject(json)
        return buildMap {
            root.keys().forEach { key ->
                val item = root.getJSONObject(key)
                val value: Any = when (item.getString("type")) {
                    "boolean" -> item.get("value").also { require(it is Boolean) }
                    "int" -> item.get("value").also { require(it is Int || it is Long) }
                        .let { (it as Number).toLong() }.also {
                        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                    }.toInt()
                    "long" -> item.get("value").also { require(it is Int || it is Long) }
                        .let { (it as Number).toLong() }
                    "string" -> item.get("value").also { require(it is String) }
                    else -> error("Unknown setting type")
                }
                require(isAllowed(key, value)) { "Invalid Apple Music setting: $key" }
                put(key, value)
            }
        }
    }

    fun isAllowedKey(key: String): Boolean = key in defaults

    private fun isAllowed(key: String, value: Any?): Boolean = when (defaults[key]) {
        is Boolean -> value is Boolean
        is Int -> value is Int
        is Long -> value is Long
        is String -> value is String
        else -> false
    }
}

internal object SharedSettingsAccessPolicy {
    fun allows(callingUid: Int, moduleUid: Int, packages: Collection<String>): Boolean =
        callingUid == moduleUid || dev.amenhancer.module.ModuleConstants.TARGET_PACKAGE in packages

    fun validFileId(name: String): Boolean = Regex("[A-Za-z0-9_-]{1,128}").matches(name)
}

/** A migration must not publish over an edit made while its payloads were being copied. */
internal object SharedSettingsMigrationPolicy {
    fun allowsWrite(current: Map<String, *>, incoming: Map<String, *>): Boolean {
        val key = EmbeddedConfigurationMigration.MIGRATION_MARKER_KEY
        return when (incoming[key]) {
            EmbeddedConfigurationMigration.MIGRATION_IN_PROGRESS ->
                current.isEmpty() || (current.size == 1 &&
                    current[key] == EmbeddedConfigurationMigration.MIGRATION_IN_PROGRESS)
            EmbeddedConfigurationMigration.MIGRATION_COMPLETE ->
                current.size == 1 && current[key] == EmbeddedConfigurationMigration.MIGRATION_IN_PROGRESS
            else -> true
        }
    }
}
