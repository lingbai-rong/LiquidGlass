package rongyi.lingbai

import android.app.Application
import android.net.Uri
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import dev.amenhancer.module.AmppLyricsSource
import dev.amenhancer.module.AmppOperationResult
import dev.amenhancer.module.AmppSettingsStore
import dev.amenhancer.module.lyrics.CustomLyricsMultiIdDraft
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.lyrics.CustomLyricsUpdateProgress
import dev.amenhancer.module.model.CustomLyricsEntry
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.ui.CustomLyricsIdParser
import dev.amenhancer.module.ui.CustomLyricsUiGroup
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AppleMusicDocument { FONT, TTML, RESTORE }

internal data class AppleMusicLyricsDraft(
    val ids: String = "",
    val name: String = "",
    val ttml: String = "",
    val source: String = CustomLyricsSources.MANUAL,
    val enabled: Boolean = true,
    val replacingIds: List<Long> = emptyList(),
)

/** Activity-scoped state survives navigation, rotation and SAF; all store/network work is off-main. */
internal class AppleMusicSettingsModel(application: Application, private val state: SavedStateHandle) :
    AndroidViewModel(application) {
    private val app = application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val storeDelegate = lazy { AmppSettingsStore(app) }
    private val store by storeDelegate
    private val cancelled = AtomicBoolean(false)
    private val checkpoint = AtomicFile(File(app.cacheDir, "apple-music-lyrics-draft.json"))
    private var checkpointJob: Job? = null
    private var settingsRevision = 0L
    private val initialization: Job

    var settings by mutableStateOf<ModuleSettings?>(null); private set
    var entries by mutableStateOf<List<CustomLyricsEntry>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var progress by mutableStateOf<CustomLyricsUpdateProgress?>(null); private set
    var updating by mutableStateOf(false); private set
    var editor by mutableStateOf<AppleMusicLyricsDraft?>(null); private set
    private var originalEditor: AppleMusicLyricsDraft? = null
    val editorDirty: Boolean get() = editor != originalEditor
    var restoreUri by mutableStateOf(state.get<String>("am_restore")?.let(Uri::parse)); private set

    init {
        initialization = scope.launch {
            try {
                val shouldRestore = state.get<Boolean>("am_editor") == true
                val restored = withContext(Dispatchers.IO) {
                    if (shouldRestore) readCheckpoint() else null
                }
                editor = restored?.first ?: if (shouldRestore) AppleMusicLyricsDraft() else null
                originalEditor = restored?.second ?: editor
                if (shouldRestore && restored == null) error = text(R.string.am_draft_missing)
                refreshNow()
            } catch (failure: Exception) { report(failure) }
            finally { loading = false }
        }
    }

    fun refresh() {
        if (busy || loading) return
        scope.launch { try { refreshNow() } catch (failure: Exception) { report(failure) } }
    }

    private suspend fun refreshNow() {
        val revision = settingsRevision
        val snapshot = operations.withLock { withContext(Dispatchers.IO) { store.settings() to store.lyrics() } }
        if (revision == settingsRevision) settings = snapshot.first
        entries = snapshot.second
    }

    fun changeSettings(transform: (ModuleSettings) -> ModuleSettings) {
        val before = settings ?: return
        val after = transform(before)
        settings = after
        val revision = ++settingsRevision
        scope.launch {
            val saved = runCatching {
                operations.withLock { withContext(Dispatchers.IO) { store.saveSettings(after) } }
            }.getOrDefault(false)
            if (!saved) {
                if (revision == settingsRevision) {
                    val persisted = runCatching {
                        operations.withLock { withContext(Dispatchers.IO) { store.settings() } }
                    }.getOrDefault(before)
                    if (revision == settingsRevision) settings = persisted
                }
                error = text(R.string.settings_save_failed)
            }
        }
    }

    private fun action(work: () -> AmppOperationResult, onSuccess: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = operations.withLock { withContext(Dispatchers.IO) { work() } }
                if (result.success) { notice = result.message; onSuccess(); refreshNow() }
                else error = result.message
            } catch (failure: Exception) { report(failure) }
            finally { busy = false }
        }
    }

    fun setEnabled(group: CustomLyricsUiGroup, enabled: Boolean) =
        action({ store.setLyricsEnabled(group.appleMusicIds, enabled) })
    fun delete(group: CustomLyricsUiGroup) = action({ store.deleteLyrics(group.appleMusicIds) })
    fun clearFont() = action({ store.clearFont() })
    fun backup(uri: Uri) = action({ store.backupLyrics(uri) })
    fun restore(policy: CustomLyricsRestorePolicy) {
        val uri = restoreUri ?: return
        dismissRestore()
        action({ store.restoreLyrics(uri, policy) })
    }
    fun dismissRestore() { restoreUri = null; state["am_restore"] = null }
    fun dismissError() { error = null }
    fun consumeNotice() { notice = null }

    fun openEditor(group: CustomLyricsUiGroup?, onReady: () -> Unit) {
        if (busy) return
        if (group == null) {
            beginEditor(AppleMusicLyricsDraft()); onReady(); return
        }
        busy = true
        scope.launch {
            try {
                val ttml = operations.withLock { withContext(Dispatchers.IO) { store.readLyrics(group.primary.appleMusicId) } }
                    ?: kotlin.error("无法读取歌词文件")
                beginEditor(AppleMusicLyricsDraft(CustomLyricsIdParser.format(group.appleMusicIds),
                    group.primary.displayName, ttml, group.primary.source, group.primary.enabled, group.appleMusicIds))
                onReady()
            } catch (failure: Exception) { report(failure) }
            finally { busy = false }
        }
    }

    private fun beginEditor(draft: AppleMusicLyricsDraft) {
        editor = draft; originalEditor = draft; state["am_editor"] = true
        scheduleCheckpoint()
    }

    fun edit(transform: (AppleMusicLyricsDraft) -> AppleMusicLyricsDraft) {
        if (busy) return
        editor = editor?.let(transform); scheduleCheckpoint()
    }

    fun discardEditor() {
        checkpointJob?.cancel()
        originalEditor = editor; state["am_editor"] = false
        scope.launch { withContext(Dispatchers.IO) { deleteCheckpoint() } }
    }

    fun saveEditor(onSaved: () -> Unit) {
        val draft = editor ?: return
        val ids = CustomLyricsIdParser.parse(draft.ids)
        if (ids == null) { error = text(R.string.am_ids_invalid); return }
        if (draft.ttml.isBlank()) { error = text(R.string.am_ttml_required); return }
        action({ store.saveLyrics(CustomLyricsMultiIdDraft(ids, draft.name, draft.ttml,
            draft.source, draft.enabled), draft.replacingIds) }) {
            originalEditor = editor
            state["am_editor"] = false
            checkpointJob?.cancel()
            // Keep the form visible while the shared page stack animates back.
            onSaved()
        }
    }

    fun getCurrentSong() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val song = withContext(Dispatchers.IO) { store.currentSong() }
                if (song == null) error = text(R.string.am_song_missing)
                else {
                    editor = editor?.copy(ids = song.appleMusicId.toString(),
                        name = listOfNotNull(song.title?.takeIf(String::isNotBlank),
                            song.artist?.takeIf(String::isNotBlank)).joinToString(" - "))
                    scheduleCheckpoint()
                }
            } catch (failure: Exception) { report(failure) }
            finally { busy = false }
        }
    }

    fun fetch(source: AmppLyricsSource) {
        val id = CustomLyricsIdParser.parsePrimary(editor?.ids.orEmpty())
        if (id == null) { error = text(R.string.am_ids_invalid); return }
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { store.fetchLyrics(source, id) }
                val importedTtml = result.ttml
                if (importedTtml == null) error = result.error ?: text(R.string.am_import_failed)
                else {
                    editor = editor?.copy(ttml = importedTtml, source = requireNotNull(result.source))
                    scheduleCheckpoint()
                    notice = text(R.string.am_import_draft_ready)
                }
            } catch (failure: Exception) { report(failure) }
            finally { busy = false }
        }
    }

    fun updateLyrics() {
        if (busy) return
        cancelled.set(false); busy = true; updating = true; progress = null
        scope.launch {
            try {
                val result = operations.withLock { withContext(Dispatchers.IO) {
                    store.updateLyrics({ cancelled.get() || !scope.isActive }) { value ->
                        scope.launch { progress = value }
                    }
                } }
                if (result.success || cancelled.get()) notice = result.message else error = result.message
                refreshNow()
            } catch (failure: Exception) { report(failure) }
            finally { updating = false; progress = null; busy = false }
        }
    }
    fun cancelUpdate() { cancelled.set(true); updating = false }

    fun prepareDocument(operation: AppleMusicDocument, launch: () -> Unit) {
        if (busy) return
        if (state.get<String>("am_document") != null) return
        busy = true
        state["am_document"] = operation.name
        scope.launch {
            try {
                // Flush the draft before handing control to the system document picker.
                checkpointJob?.cancel()
                withContext(Dispatchers.IO) { writeCheckpoint() }
                launch()
            } catch (failure: Exception) { state["am_document"] = null; report(failure) }
            finally { busy = false }
        }
    }

    fun receiveDocument(uri: Uri?) {
        val operation = state.get<String>("am_document")?.let {
            runCatching { AppleMusicDocument.valueOf(it) }.getOrNull()
        }
        state["am_document"] = null
        if (uri == null || operation == null) return
        if (operation == AppleMusicDocument.RESTORE) {
            restoreUri = uri; state["am_restore"] = uri.toString(); return
        }
        if (operation == AppleMusicDocument.FONT) { action({ store.importFont(uri) }); return }
        if (busy) return
        busy = true
        scope.launch {
            try {
                initialization.join()
                val ttml = withContext(Dispatchers.IO) { store.readTtml(uri) }
                if (ttml == null) error = text(R.string.am_import_failed)
                else { editor = editor?.copy(ttml = ttml, source = CustomLyricsSources.MANUAL); scheduleCheckpoint() }
            } catch (failure: Exception) { report(failure) }
            finally { busy = false }
        }
    }

    private fun scheduleCheckpoint() {
        checkpointJob?.cancel()
        checkpointJob = scope.launch {
            try { delay(400); withContext(Dispatchers.IO) { writeCheckpoint() } }
            catch (failure: Exception) { report(failure) }
        }
    }

    @Synchronized private fun writeCheckpoint() {
        val draft = editor ?: return
        val original = originalEditor ?: return
        fun encode(value: AppleMusicLyricsDraft) = JSONObject().put("ids", value.ids).put("name", value.name)
            .put("ttml", value.ttml).put("source", value.source).put("enabled", value.enabled)
            .put("replacing", JSONArray(value.replacingIds))
        val bytes = JSONObject().put("draft", encode(draft)).put("original", encode(original)).toString().toByteArray()
        val output = checkpoint.startWrite()
        try { output.write(bytes); checkpoint.finishWrite(output) }
        catch (failure: Exception) { checkpoint.failWrite(output); throw failure }
    }

    @Synchronized private fun deleteCheckpoint() = checkpoint.delete()

    private fun readCheckpoint(): Pair<AppleMusicLyricsDraft, AppleMusicLyricsDraft>? = runCatching {
        require(checkpoint.baseFile.length() <= 64L * 1024 * 1024)
        fun decode(value: JSONObject): AppleMusicLyricsDraft {
            val ids = value.getJSONArray("replacing")
            return AppleMusicLyricsDraft(value.getString("ids"), value.getString("name"),
                value.getString("ttml"), value.getString("source"), value.getBoolean("enabled"),
                (0 until ids.length()).map(ids::getLong))
        }
        val json = JSONObject(checkpoint.openRead().bufferedReader().use { it.readText() })
        decode(json.getJSONObject("draft")) to decode(json.getJSONObject("original"))
    }.getOrNull()

    private fun report(failure: Exception) {
        if (failure is CancellationException) throw failure
        error = failure.message?.takeIf(String::isNotBlank) ?: text(R.string.settings_save_failed)
    }

    private fun text(id: Int) = ModuleLanguageStore.text(app, id)

    override fun onCleared() {
        cancelled.set(true); scope.cancel()
        // A blocking SAF/network transaction may still be finishing; release after it leaves the lock.
        CoroutineScope(Dispatchers.IO).launch { operations.withLock { if (storeDelegate.isInitialized()) store.close() } }
    }
}
