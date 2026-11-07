// SPDX-License-Identifier: Apache-2.0
// Language page layout and glass controls adapted from HyperChanger by btm_m.
package rongyi.lingbai

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import rongyi.lingbai.moduleui.*
import rongyi.lingbai.moduleStringResource as stringResource

@Composable
internal fun ModuleLanguageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision = ModuleLanguageStore.revision
    val packs = remember(revision) { ModuleLanguageStore.packs(context) }
    val selection = ModuleLanguageStore.selection(context)
    val pageActive = LocalModulePageActive.current
    val suppressParentBack = LocalSuppressParentBack.current
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportJson by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOriginal by rememberSaveable { mutableStateOf("") }
    var editorText by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val editorTransition = remember { MutableTransitionState(editorOpen) }
    editorTransition.targetState = editorOpen
    val editorPresent = editorOpen || editorTransition.currentState || !editorTransition.isIdle
    DisposableEffect(editorPresent, suppressParentBack) {
        suppressParentBack(editorPresent)
        onDispose { suppressParentBack(false) }
    }
    fun notify(id: Int) = Toast.makeText(context, ModuleLanguageStore.text(context, id), Toast.LENGTH_SHORT).show()
    fun select(value: String) {
        if (!ModuleLanguageStore.select(context, value)) notify(R.string.language_change_failed)
    }
    fun openEditor(json: String, id: String?) {
        editingId = id
        editorOriginal = json
        editorText = json
        editorOpen = true
    }
    fun requestEditorClose() {
        if (editorText != editorOriginal) discard = true else editorOpen = false
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = exportJson
        exportJson = null
        if (uri != null && text != null) scope.launch {
            val succeeded = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
                        ?: error("No output stream")
                }.isSuccess
            }
            notify(if (succeeded) R.string.language_exported else R.string.language_export_failed)
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var count = input.read(buffer)
                        while (count != -1) {
                            require(bytes.size() + count <= ModuleLanguageStore.MAX_BYTES)
                            bytes.write(buffer, 0, count)
                            count = input.read(buffer)
                        }
                        bytes.toString("UTF-8")
                    } ?: error("No input stream")
                }
            }
            result.onSuccess { text ->
                runCatching { ModuleLanguageStore.save(context, text) }
                    .onSuccess { notify(R.string.language_imported) }
                    .onFailure { notify(R.string.language_invalid) }
            }.onFailure { notify(R.string.language_invalid) }
        }
    }
    val editorBack = rememberModuleBackState(
        enabled = pageActive && editorOpen && !discard,
        resetAfterCommit = editorText != editorOriginal,
    ) { requestEditorClose() }
    LaunchedEffect(editorOpen) { if (editorOpen) editorBack.progress.snapTo(0f) }
    val builtins = listOf(
        "" to stringResource(R.string.language_system),
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "en" to "English",
        "ja" to "日本語",
    )
    val author = stringResource(R.string.language_builtin)
    val unnamed = stringResource(R.string.language_custom)
    Box(Modifier.fillMaxSize()) {
        // Keep this exact list alive underneath the editor, including its LazyListState.
        Box(Modifier.fillMaxSize().modulePageInput(pageActive && !editorPresent)) {
            ModuleAppPage(stringResource(R.string.language_title), onBack) { padding, scroll ->
                ModuleAppList(padding, scroll, 28) {
                    builtins.forEach { (tag, label) ->
                        item(key = "builtin:$tag") {
                            LanguageCard(label, author, selected = selection == tag,
                                onSelect = { select(tag) }, onOptions = { menuId = "builtin:$tag" })
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                            SmallTitle(stringResource(R.string.language_actions),
                                insideMargin = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp))
                            ArrowPreference(title = stringResource(R.string.language_import),
                                onClick = { importer.launch(arrayOf("application/json", "text/json", "text/plain")) })
                            ArrowPreference(title = stringResource(R.string.language_new),
                                summary = stringResource(R.string.language_new_summary), onClick = {
                                    val json = JSONObject(ModuleLanguageStore.template(context))
                                        .put("languageName", "New Language").put("creator", "").toString(2)
                                    openEditor(json, null)
                                })
                        }
                    }
                    packs.forEach { pack ->
                        item(key = pack.id) {
                            LanguageCard(pack.name, pack.creator.ifBlank { unnamed },
                                selected = selection == ModuleLanguageStore.packSelection(pack),
                                onSelect = { select(ModuleLanguageStore.packSelection(pack)) },
                                onOptions = { menuId = pack.id })
                        }
                    }
                }
                menuId?.let { id ->
                    val builtin = id.startsWith("builtin:")
                    val pack = packs.firstOrNull { it.id == id }
                    val label = if (builtin) builtins.firstOrNull { it.first == id.removePrefix("builtin:") }?.second else pack?.name
                    WindowDialog(show = true, onDismissRequest = { menuId = null }) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(label.orEmpty(), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                            Text(pack?.creator?.ifBlank { unnamed } ?: author, style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (pack != null) {
                                GlassDialogButton({ menuId = null; openEditor(pack.json, pack.id) }, Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(stringResource(R.string.language_edit)) }
                                GlassDialogButton({ menuId = null; deleteId = pack.id }, Modifier.fillMaxWidth()) {
                                    Text(stringResource(R.string.language_delete), color = Color(0xFFD32F2F))
                                }
                            }
                            GlassDialogButton({
                                exportJson = pack?.json ?: ModuleLanguageStore.template(context,
                                    id.removePrefix("builtin:").ifBlank { context.resources.configuration.locales[0].toLanguageTag() })
                                menuId = null
                                exporter.launch("LiquidGlass-language.json")
                            }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.language_export)) }
                            GlassDialogButton({ menuId = null }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_cancel)) }
                        }
                    }
                }
                deleteId?.let { id ->
                    WindowDialog(show = true, onDismissRequest = { deleteId = null }) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.language_delete_title), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.language_delete_summary), style = MiuixTheme.textStyles.body2)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                GlassDialogButton({ deleteId = null }, Modifier.weight(1f)) { Text(stringResource(R.string.account_cancel)) }
                                GlassDialogButton({
                                    packs.firstOrNull { it.id == id }?.let {
                                        if (!ModuleLanguageStore.delete(context, it)) notify(R.string.settings_save_failed)
                                    }
                                    deleteId = null
                                }, Modifier.weight(1f)) { Text(stringResource(R.string.language_delete), color = Color(0xFFD32F2F)) }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visibleState = editorTransition, modifier = Modifier.fillMaxSize(),
            enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 5 } +
                fadeIn(tween(260)) + scaleIn(tween(300), initialScale = .97f),
            exit = slideOutHorizontally(tween(260)) { it / 8 } + fadeOut(tween(220)) +
                scaleOut(tween(260), targetScale = .985f),
        ) {
            Box(Modifier.fillMaxSize().moduleBackTransform(editorBack)
                .background(MiuixTheme.colorScheme.surface).modulePageInput(pageActive && editorOpen)) {
                ModuleAppPage(stringResource(R.string.language_edit), onBack = { requestEditorClose() }, actions = {
                    GlassToolbarIconButton(MiuixIcons.Regular.Download, stringResource(R.string.language_export)) {
                        exportJson = editorText
                        exporter.launch("LiquidGlass-language.json")
                    }
                    GlassToolbarIconButton(MiuixIcons.Regular.Ok, stringResource(R.string.action_save)) {
                        runCatching { ModuleLanguageStore.save(context, editorText, editingId) }
                            .onSuccess { editorOriginal = editorText; editorOpen = false; notify(R.string.language_saved) }
                            .onFailure { notify(R.string.language_invalid) }
                    }
                }) { padding, scroll ->
                    ModuleAppList(padding, scroll, 28) {
                        item {
                            val original = remember(editorOriginal) { runCatching { JSONObject(editorOriginal) }.getOrNull() }
                            ModuleGroup(original?.optString("languageName").orEmpty()) {
                                val creator = original?.optString("creator").orEmpty()
                                if (creator.isNotBlank()) Text(creator, style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(12.dp)) {
                                TextField(value = editorText,
                                    onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= ModuleLanguageStore.MAX_BYTES) editorText = it },
                                    modifier = Modifier.fillMaxWidth(), singleLine = false, label = stringResource(R.string.language_json))
                            }
                        }
                    }
                    if (discard) {
                        WindowDialog(show = true, onDismissRequest = { discard = false }) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.language_discard_title), style = MiuixTheme.textStyles.title3)
                                Text(stringResource(R.string.language_discard_summary), style = MiuixTheme.textStyles.body2)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    GlassDialogButton({ discard = false }, Modifier.weight(1f)) { Text(stringResource(R.string.account_cancel)) }
                                    GlassDialogButton({ discard = false; editorOpen = false }, Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColorsPrimary()) { Text(stringResource(R.string.language_discard)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LanguageCard(name: String, creator: String, selected: Boolean, onSelect: () -> Unit, onOptions: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.5.dp))
            .combinedClickable(onClick = onSelect, onLongClick = onOptions),
        cornerRadius = 22.5.dp, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                Text(creator, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 3.dp))
            }
            Checkbox(state = if (selected) ToggleableState.On else ToggleableState.Off, onClick = onSelect)
        }
    }
}
