package rongyi.lingbai

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.amenhancer.module.AmppLyricsSource
import dev.amenhancer.module.config.TitleCorrectionMode
import dev.amenhancer.module.lyrics.CustomLyricsRestorePolicy
import dev.amenhancer.module.model.CustomLyricsSources
import dev.amenhancer.module.model.ModuleSettings
import dev.amenhancer.module.ui.CustomLyricsListState
import dev.amenhancer.module.ui.CustomLyricsUiGroup
import rongyi.lingbai.moduleui.*
import rongyi.lingbai.moduleStringResource as stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

internal data class AppleMusicDocuments(
    val font: () -> Unit,
    val ttml: () -> Unit,
    val backup: () -> Unit,
    val restore: () -> Unit,
)

/** Register once at the Activity root, so covered pages and back previews own no SAF launchers. */
@Composable
internal fun rememberAppleMusicDocuments(model: AppleMusicSettingsModel): AppleMusicDocuments {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), model::receiveDocument)
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) model.backup(uri)
    }
    val context = LocalContext.current
    LaunchedEffect(model.notice) {
        model.notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); model.consumeNotice() }
    }
    return remember(model, picker, backup) {
        AppleMusicDocuments(
            font = { model.prepareDocument(AppleMusicDocument.FONT) { picker.launch(arrayOf("*/*")) } },
            ttml = { model.prepareDocument(AppleMusicDocument.TTML) {
                picker.launch(arrayOf("application/ttml+xml", "application/xml", "text/xml", "text/plain"))
            } },
            backup = { if (!model.busy) backup.launch("LiquidGlass-AppleMusic-lyrics.zip") },
            restore = { model.prepareDocument(AppleMusicDocument.RESTORE) { picker.launch(arrayOf("*/*")) } },
        )
    }
}

@Composable
internal fun AppleMusicSettingsContent(
    model: AppleMusicSettingsModel,
    documents: AppleMusicDocuments,
    onLyrics: () -> Unit,
    onBack: () -> Unit,
) {
    var dpiDialog by rememberSaveable { mutableStateOf(false) }
    var clearFont by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    val settings = model.settings
    AppleMusicPageBars()
    ModuleAppPage(stringResource(R.string.am_settings), onBack) { padding, scroll ->
        ModuleAppList(padding, scroll, 28) {
            if (model.busy) item { AppleMusicNote(stringResource(R.string.am_working)) }
            if (settings == null) item { AppleMusicLoading(model) }
            else {
                item {
                    ModuleGroup(stringResource(R.string.section_features)) {
                        SwitchPreference(title = stringResource(R.string.am_dual_pane),
                            summary = stringResource(R.string.am_dual_pane_summary), checked = settings.dualPaneEnabled,
                            enabled = !model.busy, onCheckedChange = { value -> model.changeSettings { it.copy(dualPaneEnabled = value) } })
                        SwitchPreference(title = stringResource(R.string.am_nav_compensation),
                            summary = stringResource(R.string.am_nav_compensation_summary), checked = settings.navigationCompensationEnabled,
                            enabled = !model.busy, onCheckedChange = { value -> model.changeSettings { it.copy(navigationCompensationEnabled = value) } })
                        SwitchPreference(title = stringResource(R.string.am_phone_glass),
                            summary = stringResource(R.string.am_phone_glass_summary), checked = settings.phoneLiquidGlassEnabled,
                            enabled = !model.busy, onCheckedChange = { value -> model.changeSettings { it.copy(phoneLiquidGlassEnabled = value) } })
                        if (settings.phoneLiquidGlassEnabled) {
                            AppleMusicIntSlider(stringResource(R.string.am_bar_gap), settings.phoneLiquidGlassBottomGapDp,
                                ModuleSettings.MIN_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP..ModuleSettings.MAX_PHONE_LIQUID_GLASS_BOTTOM_GAP_DP,
                                ModuleSettings().phoneLiquidGlassBottomGapDp, "dp", !model.busy) { value ->
                                model.changeSettings { it.copy(phoneLiquidGlassBottomGapDp = value) }
                            }
                            AppleMusicIntSlider(stringResource(R.string.am_panel_blur), settings.phoneLiquidGlassPanelBlurDp,
                                ModuleSettings.MIN_PHONE_LIQUID_GLASS_PANEL_BLUR_DP..ModuleSettings.MAX_PHONE_LIQUID_GLASS_PANEL_BLUR_DP,
                                ModuleSettings().phoneLiquidGlassPanelBlurDp, "dp", !model.busy) { value ->
                                model.changeSettings { it.copy(phoneLiquidGlassPanelBlurDp = value) }
                            }
                        }
                        SwitchPreference(title = stringResource(R.string.am_future_blur),
                            summary = stringResource(R.string.am_future_blur_summary), checked = settings.futureBlurEnabled,
                            enabled = !model.busy, onCheckedChange = { value -> model.changeSettings { it.copy(futureBlurEnabled = value) } })
                        SwitchPreference(title = stringResource(R.string.am_cjk_animation),
                            summary = stringResource(R.string.am_cjk_animation_summary), checked = settings.cjkKaraokeAnimationEnabled,
                            enabled = !model.busy, onCheckedChange = { value -> model.changeSettings { it.copy(cjkKaraokeAnimationEnabled = value) } })
                        SwitchPreference(title = stringResource(R.string.am_title_correction),
                            summary = stringResource(R.string.am_title_correction_summary), checked = settings.titleCorrectionEnabled,
                            enabled = !model.busy, onCheckedChange = { value -> model.changeSettings { it.copy(titleCorrectionEnabled = value) } })
                        OverlayDropdownPreference(title = stringResource(R.string.am_title_mode),
                            items = listOf(stringResource(R.string.am_title_original), stringResource(R.string.am_title_china),
                                stringResource(R.string.am_title_japan)), selectedIndex = settings.titleCorrectionMode.ordinal,
                            enabled = !model.busy, showValueOnEnd = true,
                            onSelectedIndexChange = { index -> model.changeSettings { it.copy(titleCorrectionMode = TitleCorrectionMode.entries[index]) } })
                        ArrowPreference(title = stringResource(R.string.am_custom_lyrics),
                            summary = stringResource(R.string.am_custom_lyrics_summary, model.entries.size), onClick = onLyrics)
                    }
                }
                item {
                    ModuleGroup(stringResource(R.string.am_advanced)) {
                        AppleMusicIntSlider(stringResource(R.string.am_lyric_blur_offset), settings.lyricBlurRadiusOffsetPx,
                            ModuleSettings.MIN_LYRIC_BLUR_RADIUS_OFFSET_PX..ModuleSettings.MAX_LYRIC_BLUR_RADIUS_OFFSET_PX,
                            0, "px", !model.busy) { value -> model.changeSettings { it.copy(lyricBlurRadiusOffsetPx = value) } }
                        ArrowPreference(title = stringResource(R.string.am_dpi),
                            summary = if (settings.appleMusicDpiOverrideDpi == 0) stringResource(R.string.am_dpi_system)
                                else "${settings.appleMusicDpiOverrideDpi} dpi",
                            enabled = !model.busy, onClick = { dpiDialog = true })
                    }
                }
                item {
                    ModuleGroup(stringResource(R.string.am_lyrics_font)) {
                        ArrowPreference(title = stringResource(R.string.am_choose_font),
                            summary = if (settings.fontManifest.enabled) settings.fontManifest.displayName else stringResource(R.string.am_font_original),
                            enabled = !model.busy, onClick = documents.font)
                        ArrowPreference(title = stringResource(R.string.am_clear_font),
                            summary = stringResource(R.string.am_font_summary), enabled = settings.fontManifest.enabled && !model.busy,
                            onClick = { clearFont = true })
                    }
                }
                item {
                    ModuleGroup(stringResource(R.string.am_help)) {
                        ArrowPreference(title = stringResource(R.string.am_restart),
                            summary = stringResource(R.string.am_restart_summary), onClick = { help = true })
                    }
                }
            }
        }
        if (LocalModulePageActive.current) {
            if (dpiDialog && settings != null) AppleMusicDpiDialog(settings.appleMusicDpiOverrideDpi,
                onDismiss = { dpiDialog = false }, onSave = { value -> model.changeSettings { it.copy(appleMusicDpiOverrideDpi = value) }; dpiDialog = false })
            if (clearFont) AppleMusicConfirm(stringResource(R.string.am_clear_font), stringResource(R.string.am_clear_font_confirm),
                onDismiss = { clearFont = false }, onConfirm = { clearFont = false; model.clearFont() })
            if (help) AppleMusicInfo(stringResource(R.string.am_scope_help), stringResource(R.string.am_scope_help_summary)) { help = false }
            AppleMusicOperationDialogs(model)
        }
    }
}

@Composable
internal fun AppleMusicLyricsContent(
    model: AppleMusicSettingsModel,
    documents: AppleMusicDocuments,
    onEdit: (CustomLyricsUiGroup?) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf<CustomLyricsUiGroup?>(null) }
    var deleting by remember { mutableStateOf<CustomLyricsUiGroup?>(null) }
    var backupMenu by rememberSaveable { mutableStateOf(false) }
    var reveal by rememberSaveable { mutableIntStateOf(CustomLyricsListState.DEFAULT_PAGE_SIZE) }
    val listState = remember { CustomLyricsListState() }
    val groups = remember(model.entries, query, reveal) {
        listState.update(model.entries, query)
        if (reveal > listState.visibleCount) listState.loadMore(reveal - listState.visibleCount)
        listState.visibleGroups
    }
    val settings = model.settings
    AppleMusicPageBars()
    ModuleAppPage(stringResource(R.string.am_custom_lyrics), onBack) { padding, scroll ->
        ModuleAppList(padding, scroll, 28) {
            if (model.busy) item { AppleMusicNote(stringResource(R.string.am_working)) }
            if (settings == null) item { AppleMusicLoading(model) }
            else {
                item {
                    ModuleGroup(stringResource(R.string.section_features)) {
                        SwitchPreference(title = stringResource(R.string.am_replace_lyrics),
                            summary = stringResource(R.string.am_replace_lyrics_summary), checked = settings.customLyricsEnabled,
                            enabled = !model.busy, onCheckedChange = { value -> model.changeSettings { it.copy(customLyricsEnabled = value) } })
                        SwitchPreference(title = stringResource(R.string.am_auto_lyrics),
                            summary = stringResource(R.string.am_auto_lyrics_summary), checked = settings.automaticLyricsEnabled,
                            enabled = settings.customLyricsEnabled && !model.busy,
                            onCheckedChange = { value -> model.changeSettings { it.copy(automaticLyricsEnabled = value) } })
                    }
                }
                item {
                    ModuleGroup(stringResource(R.string.am_manage_lyrics)) {
                        ArrowPreference(title = stringResource(R.string.am_add_lyrics), enabled = !model.busy, onClick = { onEdit(null) })
                        ArrowPreference(title = stringResource(R.string.am_import_ttml), enabled = !model.busy,
                            onClick = onImport)
                        ArrowPreference(title = stringResource(R.string.am_update_lyrics), summary = stringResource(R.string.am_update_lyrics_summary),
                            enabled = !model.busy, onClick = model::updateLyrics)
                        ArrowPreference(title = stringResource(R.string.am_backup_restore), enabled = !model.busy, onClick = { backupMenu = true })
                    }
                }
                item {
                    HyperSearchAnchor(query, { query = it }, stringResource(R.string.am_lyrics_search))
                }
                if (groups.isEmpty()) item { AppleMusicNote(stringResource(R.string.am_lyrics_empty)) }
                groups.forEach { group ->
                    item(key = group.primary.appleMusicId) {
                        ModuleGroup(group.primary.displayName.ifBlank { group.primary.appleMusicId.toString() }) {
                            SwitchPreference(title = stringResource(R.string.am_lyrics_enabled),
                                summary = "${group.primary.appleMusicId}" + if (group.appleMusicIds.size > 1)
                                    " · " + stringResource(R.string.am_id_count, group.appleMusicIds.size) else "",
                                checked = group.allEnabled, enabled = !model.busy,
                                onCheckedChange = { model.setEnabled(group, it) })
                            ArrowPreference(title = stringResource(R.string.am_edit_lyrics), summary = appleMusicSourceName(group.primary.source),
                                enabled = !model.busy, onClick = { menu = group })
                        }
                    }
                }
                if (listState.hasMore) item {
                    ArrowPreference(title = stringResource(R.string.am_load_more),
                        summary = "${listState.visibleCount} / ${listState.totalCount}",
                        onClick = { reveal += CustomLyricsListState.DEFAULT_PAGE_SIZE })
                }
            }
        }
        if (LocalModulePageActive.current) {
            if (backupMenu) WindowDialog(show = true, title = stringResource(R.string.am_backup_restore), onDismissRequest = { backupMenu = false }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton({ backupMenu = false; documents.backup() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.am_backup)) }
                    GlassDialogButton({ backupMenu = false; documents.restore() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.am_restore)) }
                    GlassDialogButton({ backupMenu = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_cancel)) }
                }
            }
            menu?.let { group ->
                WindowDialog(show = true, title = group.primary.displayName.ifBlank { group.primary.appleMusicId.toString() }, onDismissRequest = { menu = null }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassDialogButton({ menu = null; onEdit(group) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                            Text(stringResource(R.string.am_edit_lyrics))
                        }
                        GlassDialogButton({ menu = null; deleting = group }, Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.language_delete), color = Color(0xFFD32F2F))
                        }
                        GlassDialogButton({ menu = null }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_cancel)) }
                    }
                }
            }
            deleting?.let { group -> AppleMusicConfirm(stringResource(R.string.am_delete_lyrics),
                stringResource(R.string.am_delete_lyrics_summary, group.appleMusicIds.size),
                onDismiss = { deleting = null }, onConfirm = { deleting = null; model.delete(group) }) }
            model.restoreUri?.let {
                WindowDialog(show = true, title = stringResource(R.string.am_restore), summary = stringResource(R.string.am_restore_conflict), onDismissRequest = model::dismissRestore) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassDialogButton({ model.restore(CustomLyricsRestorePolicy.KEEP_EXISTING) }, Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.am_keep_existing))
                        }
                        GlassDialogButton({ model.restore(CustomLyricsRestorePolicy.OVERWRITE) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                            Text(stringResource(R.string.am_overwrite))
                        }
                        GlassDialogButton(model::dismissRestore, Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_cancel)) }
                    }
                }
            }
            AppleMusicOperationDialogs(model)
        }
    }
}

@Composable
internal fun AppleMusicLyricsEditorContent(model: AppleMusicSettingsModel, documents: AppleMusicDocuments, onBack: () -> Unit) {
    var sources by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val draft = model.editor
    AppleMusicPageBars()
    val active = LocalModulePageActive.current
    val intercept = LocalModuleBackInterceptor.current
    val guarded = model.editorDirty || model.busy
    val close = { if (!model.busy) { if (model.editorDirty) discard = true else { onBack(); model.discardEditor() } } }
    val latestClose by rememberUpdatedState(close)
    DisposableEffect(intercept, active, guarded) {
        intercept(if (active && guarded) ({ latestClose(); true }) else null)
        onDispose { intercept(null) }
    }
    Box(Modifier.fillMaxSize()) {
        ModuleAppPage(stringResource(if (draft?.replacingIds.isNullOrEmpty()) R.string.am_add_lyrics else R.string.am_edit_lyrics), close,
            actions = { GlassToolbarIconButton(MiuixIcons.Regular.Ok, stringResource(R.string.action_save)) { model.saveEditor(onBack) } }) { padding, scroll ->
            ModuleAppList(padding, scroll, 28) {
                if (model.busy) item { AppleMusicNote(stringResource(R.string.am_working)) }
                if (draft == null) item { AppleMusicLoading(model) }
                else {
                    item {
                        ModuleGroup(stringResource(R.string.am_lyrics_details), insideMargin = PaddingValues(16.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextField(value = draft.ids, onValueChange = { text -> model.edit { it.copy(ids = text) } },
                                    label = stringResource(R.string.am_music_ids), useLabelAsPlaceholder = true, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), enabled = !model.busy,
                                    modifier = Modifier.fillMaxWidth())
                                Text(stringResource(R.string.am_music_ids_summary), style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                TextField(value = draft.name, onValueChange = { text -> model.edit { it.copy(name = text) } },
                                    label = stringResource(R.string.am_display_name), useLabelAsPlaceholder = true, singleLine = true,
                                    enabled = !model.busy, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    item {
                        ModuleGroup(stringResource(R.string.am_lyrics_source)) {
                            ArrowPreference(title = stringResource(R.string.am_get_id), enabled = !model.busy, onClick = model::getCurrentSong)
                            ArrowPreference(title = stringResource(R.string.am_import_ttml), enabled = !model.busy, onClick = documents.ttml)
                            ArrowPreference(title = stringResource(R.string.am_online_import), summary = appleMusicSourceName(draft.source),
                                enabled = !model.busy, onClick = { sources = true })
                        }
                    }
                    item {
                        ModuleGroup("TTML", insideMargin = PaddingValues(16.dp)) {
                            TextField(value = draft.ttml, onValueChange = { text -> model.edit { it.copy(ttml = text) } },
                                label = stringResource(R.string.am_ttml_content), useLabelAsPlaceholder = true,
                                singleLine = false, enabled = !model.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 480.dp))
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassDialogButton(close, Modifier.weight(1f), enabled = !model.busy) { Text(stringResource(R.string.account_cancel)) }
                            GlassDialogButton({ model.saveEditor(onBack) }, Modifier.weight(1f), enabled = !model.busy,
                                colors = ButtonDefaults.buttonColorsPrimary()) { Text(stringResource(R.string.action_save)) }
                        }
                    }
                }
            }
            if (active) {
                if (sources) WindowDialog(show = true, title = stringResource(R.string.am_online_import), onDismissRequest = { sources = false }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(AmppLyricsSource.AMLL to "AMLL", AmppLyricsSource.LUNABEAT to "Lunabeat", AmppLyricsSource.AM_LYRICS to "GitHub / AM-Lyrics").forEach { (source, name) ->
                            GlassDialogButton({ sources = false; model.fetch(source) }, Modifier.fillMaxWidth()) { Text(name) }
                        }
                        GlassDialogButton({ sources = false }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_cancel)) }
                    }
                }
                if (discard) AppleMusicConfirm(stringResource(R.string.language_discard_title), stringResource(R.string.language_discard_summary),
                    onDismiss = { discard = false }, onConfirm = { discard = false; onBack(); model.discardEditor() })
                AppleMusicOperationDialogs(model)
            }
        }
    }
}

@Composable
private fun AppleMusicPageBars() {
    val view = LocalView.current
    val surface = MiuixTheme.colorScheme.surface
    val active = LocalModulePageActive.current
    SideEffect {
        if (active) {
            val activity = view.context as? android.app.Activity
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            activity?.window?.statusBarColor = surface.toArgb()
            activity?.window?.navigationBarColor = surface.toArgb()
            activity?.window?.insetsController?.setSystemBarsAppearance(if (surface.luminance() < .5f) 0 else light, light)
        }
    }
}

@Composable
private fun AppleMusicLoading(model: AppleMusicSettingsModel) {
    ArrowPreference(title = stringResource(if (model.loading) R.string.am_loading else R.string.am_retry),
        enabled = !model.loading, onClick = model::refresh)
}

@Composable
private fun AppleMusicIntSlider(title: String, value: Int, range: IntRange, default: Int, unit: String, enabled: Boolean, save: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    EditableSliderPreference(value = draft, onValueChange = { draft = it }, onValueChangeFinished = { save(draft.roundToInt()) },
        title = title, valueText = "${draft.roundToInt()} $unit", valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0), defaultValue = default.toFloat(), enabled = enabled)
}

@Composable
private fun AppleMusicDpiDialog(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial.toString()) }
    val number = value.trim().toIntOrNull()
    val valid = number != null && ModuleSettings.isValidAppleMusicDpi(number)
    WindowDialog(show = true, title = stringResource(R.string.am_dpi), summary = stringResource(R.string.am_dpi_summary), onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value, { value = it }, label = stringResource(R.string.am_dpi_range), singleLine = true,
                useLabelAsPlaceholder = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            GlassDialogButton({ value = "0" }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.am_dpi_system)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(stringResource(R.string.account_cancel)) }
                GlassDialogButton({ onSave(requireNotNull(number)) }, Modifier.weight(1f), enabled = valid,
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@Composable
private fun AppleMusicOperationDialogs(model: AppleMusicSettingsModel) {
    if (model.updating) WindowDialog(show = true, title = stringResource(R.string.am_update_lyrics), onDismissRequest = model::cancelUpdate) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val progress = model.progress
            Text(if (progress == null) stringResource(R.string.am_checking_lyrics) else
                stringResource(R.string.am_update_progress, progress.checkedEntries, progress.totalEntries,
                    progress.updatedEntries, progress.unchangedEntries, progress.skippedEntries, progress.failedEntries),
                style = MiuixTheme.textStyles.body2)
            GlassDialogButton(model::cancelUpdate, Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_cancel)) }
        }
    }
    model.error?.let { AppleMusicInfo(stringResource(R.string.am_operation_failed), it, model::dismissError) }
}

@Composable
private fun AppleMusicNote(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(16.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2)
}

@Composable
private fun AppleMusicInfo(title: String, summary: String, dismiss: () -> Unit) {
    WindowDialog(show = true, title = title, summary = summary, onDismissRequest = dismiss) {
        GlassDialogButton(dismiss, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(stringResource(R.string.action_ok)) }
    }
}

@Composable
private fun AppleMusicConfirm(title: String, summary: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    WindowDialog(show = true, title = title, summary = summary, onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(stringResource(R.string.account_cancel)) }
            GlassDialogButton(onConfirm, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(stringResource(R.string.action_ok)) }
        }
    }
}

@Composable
private fun appleMusicSourceName(source: String): String = when (source) {
    CustomLyricsSources.AMLL -> "AMLL"
    CustomLyricsSources.AM_LYRICS -> "GitHub / AM-Lyrics"
    CustomLyricsSources.LUNABEAT -> "Lunabeat"
    CustomLyricsSources.AUTO_CACHE -> stringResource(R.string.am_source_cache)
    else -> stringResource(R.string.am_source_manual)
}
