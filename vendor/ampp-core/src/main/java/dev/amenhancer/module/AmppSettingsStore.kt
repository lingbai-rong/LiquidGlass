package dev.amenhancer.module

import android.content.Context
import android.net.Uri
import dev.amenhancer.module.config.EmbeddedConfigurationSession
import dev.amenhancer.module.config.ProviderEmbeddedStorage
import dev.amenhancer.module.config.SharedCurrentSong
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsOnlineImportResult
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsUpdateProgress
import dev.amenhancer.module.lyrics.CustomLyricsUpdateResult
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.ui.EmbeddedActionResult
import dev.amenhancer.module.ui.EmbeddedOnlineSource
import dev.amenhancer.module.ui.EmbeddedRuntimeSettingsController

data class AmppOperationResult(val success: Boolean, val message: String)

enum class AmppLyricsSource { AMLL, AM_LYRICS, LUNABEAT }

data class AmppLyricsImport(
    val ttml: String? = null,
    val source: String? = null,
    val reformatted: Boolean = false,
    val error: String? = null,
)

/** UI-neutral access to the same transactions used by Apple Music's embedded editor. */
class AmppSettingsStore(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val storage = ProviderEmbeddedStorage(app)
    private val controller = EmbeddedRuntimeSettingsController(app,
        EmbeddedConfigurationSession(storage), currentSong = { SharedCurrentSong.read(app) })

    init { AmppSettingsEditor.grantHostAccess(app) }

    fun settings(): ModuleSettings = controller.currentSettings()
    fun saveSettings(settings: ModuleSettings): Boolean = controller.saveOrdinarySettings(settings)
    fun lyrics(): List<CustomLyricsEntry> = controller.lyricsEntries()
    fun readLyrics(id: Long): String? = controller.readLyrics(id)
    fun readTtml(uri: Uri): String? = controller.readTtml(uri)
    fun currentSong(): CurrentSongDetails? = controller.currentSongDetails()

    fun saveLyrics(draft: CustomLyricsMultiIdDraft, replacingIds: List<Long>): AmppOperationResult =
        controller.saveLyrics(draft, replacingIds).publicResult()
    fun setLyricsEnabled(ids: List<Long>, enabled: Boolean): AmppOperationResult =
        controller.setLyricsEnabled(ids, enabled).publicResult()
    fun deleteLyrics(ids: List<Long>): AmppOperationResult = controller.deleteLyrics(ids).publicResult()
    fun importFont(uri: Uri): AmppOperationResult = controller.importFont(uri).publicResult()
    fun clearFont(): AmppOperationResult = controller.clearFont().publicResult()
    fun backupLyrics(uri: Uri): AmppOperationResult = controller.backupLyrics(uri).publicResult()
    fun restoreLyrics(uri: Uri, policy: CustomLyricsRestorePolicy): AmppOperationResult =
        controller.restoreLyrics(uri, policy).publicResult()

    /** Fetches an editable draft; nothing is persisted until the user selects Save. */
    fun fetchLyrics(source: AmppLyricsSource, id: Long): AmppLyricsImport =
        when (val result = controller.fetchOnlineLyrics(EmbeddedOnlineSource.valueOf(source.name), id)) {
            is CustomLyricsOnlineImportResult.Imported ->
                AmppLyricsImport(result.ttml, result.source, result.reformatted)
            is CustomLyricsOnlineImportResult.Failed -> AmppLyricsImport(error = result.message)
        }

    fun updateLyrics(cancelled: () -> Boolean, progress: (CustomLyricsUpdateProgress) -> Unit): AmppOperationResult =
        when (val result = controller.updateLyrics(cancelled, progress)) {
            is CustomLyricsUpdateResult.Updated -> AmppOperationResult(true,
                "歌词更新完成：检查 ${result.checked} 条，更新 ${result.updated} 条，" +
                    "无变化 ${result.unchanged} 条，跳过 ${result.skipped} 条，失败 ${result.failed} 条")
            is CustomLyricsUpdateResult.Failed -> AmppOperationResult(false, result.message)
            CustomLyricsUpdateResult.Cancelled -> AmppOperationResult(false, "歌词更新已取消")
        }

    override fun close() = storage.close()
}

private fun EmbeddedActionResult.publicResult(): AmppOperationResult = when (this) {
    is EmbeddedActionResult.Done -> AmppOperationResult(true, message)
    is EmbeddedActionResult.Failed -> AmppOperationResult(false, message)
}
