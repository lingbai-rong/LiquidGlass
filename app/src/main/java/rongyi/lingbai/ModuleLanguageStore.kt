package rongyi.lingbai

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class ModuleLanguagePack(
    val id: String,
    val name: String,
    val creator: String,
    val locale: String,
    val json: String,
)

/** HyperChanger-compatible JSON metadata with LiquidGlass resource names / source text as keys. */
object ModuleLanguageStore {
    const val MAX_BYTES = 128 * 1024
    private const val PREFS = "module_languages"
    private const val PACK_PREFIX = "pack:"
    var revision by mutableIntStateOf(0)
        private set
    private var cachedSelection: String? = null
    private var cachedStrings: Map<String, String> = emptyMap()
    private var cachedRegistry: String? = null
    private var cachedPacks: List<ModuleLanguagePack> = emptyList()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun parse(json: String, id: String = UUID.randomUUID().toString()): ModuleLanguagePack {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(json)
        val name = root.optString("languageName", root.optString("name")).trim()
        require(name.isNotEmpty() && name.length <= 80)
        val requestedLocale = root.optString("locale", "").trim()
        val locale = if (requestedLocale.isEmpty()) "" else Locale.forLanguageTag(requestedLocale).toLanguageTag()
        require(requestedLocale.isEmpty() || Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*").matches(requestedLocale))
        require(locale != "und")
        root.put("locale", locale)
        val strings = root.getJSONObject("strings")
        require(strings.length() <= 4000)
        strings.keys().forEach { key ->
            require(key.length <= 512 && strings.get(key) is String)
            require(strings.getString(key).length <= 8000)
        }
        return ModuleLanguagePack(id, name, root.optString("creator", "").take(120), locale, root.toString(2))
    }

    fun packs(context: Context): List<ModuleLanguagePack> {
        val raw = prefs(context).getString("packs", "[]").orEmpty()
        if (raw == cachedRegistry) return cachedPacks
        cachedPacks = runCatching {
            val items = JSONArray(raw)
            (0 until items.length()).mapNotNull { index ->
                runCatching {
                    val item = items.getJSONObject(index)
                    parse(item.getJSONObject("content").toString(), item.getString("id"))
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        cachedRegistry = raw
        return cachedPacks
    }

    fun selection(context: Context): String {
        val selected = prefs(context).getString("selected", "").orEmpty()
        if (selected.startsWith(PACK_PREFIX)) {
            val pack = packs(context).firstOrNull { PACK_PREFIX + it.id == selected }
            // An explicit change in Android's app-language settings wins over an old pack.
            if (pack != null && ModuleUiPreferences.currentLanguage(context) == pack.locale) return selected
        }
        return ModuleUiPreferences.currentLanguage(context)
    }

    fun packSelection(pack: ModuleLanguagePack): String = PACK_PREFIX + pack.id

    fun select(context: Context, selection: String): Boolean {
        val pack = if (selection.startsWith(PACK_PREFIX)) {
            packs(context).firstOrNull { packSelection(it) == selection } ?: return false
        } else null
        val previous = prefs(context).getString("selected", "").orEmpty()
        if (!prefs(context).edit().putString("selected", selection).commit()) return false
        if (!ModuleUiPreferences.setLanguage(context, pack?.locale ?: selection)) {
            prefs(context).edit().putString("selected", previous).commit()
            return false
        }
        invalidate()
        return true
    }

    fun save(context: Context, json: String, editingId: String? = null): ModuleLanguagePack {
        val existing = packs(context)
        val parsed = parse(json, editingId ?: UUID.randomUUID().toString())
        val pack = if (editingId == null) {
            existing.firstOrNull { it.name == parsed.name && it.locale == parsed.locale }
                ?.let { parsed.copy(id = it.id) } ?: parsed
        } else parsed
        val wasSelected = prefs(context).getString("selected", "") == packSelection(pack)
        val updated = existing.filterNot { it.id == pack.id } + pack
        require(updated.size <= 24)
        require(updated.sumOf { it.json.toByteArray(Charsets.UTF_8).size } <= 2 * 1024 * 1024)
        val array = JSONArray()
        updated.forEach { array.put(JSONObject().put("id", it.id).put("content", JSONObject(it.json))) }
        check(prefs(context).edit().putString("packs", array.toString()).commit())
        if (wasSelected) select(context, packSelection(pack))
        invalidate()
        return pack
    }

    fun delete(context: Context, pack: ModuleLanguagePack): Boolean {
        val isSelected = prefs(context).getString("selected", "") == packSelection(pack)
        if (isSelected && !select(context, ModuleUiPreferences.LANGUAGE_SYSTEM)) return false
        val array = JSONArray()
        packs(context).filterNot { it.id == pack.id }.forEach {
            array.put(JSONObject().put("id", it.id).put("content", JSONObject(it.json)))
        }
        val saved = prefs(context).edit().putString("packs", array.toString()).commit()
        if (saved) invalidate()
        return saved
    }

    private fun invalidate() {
        cachedSelection = null
        revision++
    }

    fun template(context: Context, locale: String = "en"): String {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(locale.ifBlank { "zh-CN" }))
        }
        val resources = context.createConfigurationContext(configuration).resources
        val keys = JSONObject(context.assets.open("languages/example.json").bufferedReader().use { it.readText() })
            .getJSONObject("strings")
        val strings = JSONObject()
        keys.keys().forEach { key ->
            val id = resources.getIdentifier(key, "string", context.packageName)
            if (id != 0) strings.put(key, resources.getString(id))
        }
        val name = when (locale) {
            "zh-CN" -> "简体中文"
            "zh-TW" -> "繁體中文"
            "ja" -> "日本語"
            "en" -> "English"
            else -> "LiquidGlass"
        }
        return JSONObject().put("languageName", name).put("creator", "LiquidGlass")
            .put("locale", locale).put("strings", strings).toString(2)
    }

    fun text(context: Context, id: Int, vararg args: Any): String {
        val fallback = context.getString(id)
        val selected = selection(context)
        if (selected != cachedSelection) {
            val pack = packs(context).firstOrNull { packSelection(it) == selected }
            cachedStrings = pack?.let {
                val strings = JSONObject(it.json).getJSONObject("strings")
                buildMap { strings.keys().forEach { key -> put(key, strings.getString(key)) } }
            } ?: emptyMap()
            cachedSelection = selected
        }
        val translated = if (cachedStrings.isEmpty()) fallback else {
            val key = context.resources.getResourceEntryName(id)
            val chinese = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }
            ).getString(id)
            cachedStrings[key] ?: cachedStrings[chinese] ?: cachedStrings[fallback] ?: fallback
        }
        if (args.isEmpty()) return translated
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return runCatching { String.format(locale, translated, *args) }
            .getOrElse { context.getString(id, *args) }
    }
}

@Composable
fun moduleStringResource(id: Int, vararg formatArgs: Any): String {
    // Both dependencies are read so imported packs and Android locale changes recompose callers.
    val revision = ModuleLanguageStore.revision
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    return remember(context, configuration, revision, id, formatArgs.toList()) {
        ModuleLanguageStore.text(context, id, *formatArgs)
    }
}
