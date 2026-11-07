// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
// Ported from HyperChanger; localized through LiquidGlass resources.
package rongyi.lingbai.moduleui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SliderPreference as MiuixSliderPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import rongyi.lingbai.R
import rongyi.lingbai.moduleStringResource
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun EditableSliderPreference(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    title: String?,
    valueText: String?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    defaultValue: Float = valueRange.start,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showValueDialog by remember { mutableStateOf(false) }
    MiuixSliderPreference(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        title = title,
        valueText = valueText,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier,
        onClick = if (enabled) ({ showValueDialog = true }) else null,
    )
    if (showValueDialog) {
        SliderValueDialog(
            sliderTitle = title.orEmpty(),
            value = value,
            defaultValue = defaultValue,
            valueRange = valueRange,
            steps = steps,
            onDismiss = { showValueDialog = false },
            onSave = { adjustedValue ->
                onValueChange(adjustedValue)
                onValueChangeFinished()
                showValueDialog = false
            },
        )
    }
}

@Composable
private fun SliderValueDialog(
    sliderTitle: String,
    value: Float,
    defaultValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(formatSliderInput(value)) }
    val enteredValue = draft.trim().toFloatOrNull()
    val canSave = enteredValue != null &&
        enteredValue.isFinite() &&
        enteredValue >= valueRange.start &&
        enteredValue <= valueRange.endInclusive

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        imeBottomSafetyFraction = .175f,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(moduleStringResource(R.string.module_adjust_value), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(sliderTitle, style = MiuixTheme.textStyles.body2)
            TextField(
                value = draft,
                onValueChange = { draft = it },
                label = sliderTitle,
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canSave) {
                            onSave(normalizeSliderValue(enteredValue!!, valueRange, steps))
                            onDismiss()
                        }
                    },
                ),
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassDialogButton(
                onClick = { draft = formatSliderInput(defaultValue.coerceIn(valueRange.start, valueRange.endInclusive)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(moduleStringResource(R.string.action_restore_defaults)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(moduleStringResource(R.string.account_cancel)) }
                GlassDialogButton(
                    onClick = { onSave(normalizeSliderValue(enteredValue!!, valueRange, steps)) },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(moduleStringResource(R.string.action_save)) }
            }
        }
    }
}

private fun normalizeSliderValue(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    if (steps <= 0) return value.coerceIn(valueRange.start, valueRange.endInclusive)
    val interval = (valueRange.endInclusive - valueRange.start) / (steps + 1)
    if (interval <= 0f) return valueRange.start
    return (valueRange.start + ((value - valueRange.start) / interval).roundToInt() * interval)
        .coerceIn(valueRange.start, valueRange.endInclusive)
}

private fun formatSliderInput(value: Float): String {
    val rounded = (value * 100_000f).roundToInt() / 100_000f
    return if (rounded == rounded.roundToInt().toFloat()) rounded.roundToInt().toString()
    else "%.5f".format(Locale.US, rounded).trimEnd('0').trimEnd('.')
}

