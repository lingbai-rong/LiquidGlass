// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from Miuix 0.9.3 NavigationBarItem to accept host bitmap / Canvas icons.
package rongyi.lingbai.moduleui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RowScope.HostNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    showLabels: Boolean,
    enabled: Boolean = true,
    icon: @Composable (Color, Modifier) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> onSurfaceContainerColor.copy(alpha = if (selected) {
            NavigationBarDefaults.SelectedPressedAlpha
        } else NavigationBarDefaults.UnselectedPressedAlpha)
        selected -> onSurfaceContainerColor
        else -> onSurfaceContainerColor.copy(alpha = NavigationBarDefaults.UnselectedAlpha)
    }
    Column(
        modifier = Modifier.height(NavigationBarDefaults.ItemHeight).weight(1f)
            .selectable(selected = selected, onClick = onClick, enabled = enabled,
                role = Role.Tab, interactionSource = interactionSource, indication = null)
            .then(if (!showLabels) Modifier.semantics { contentDescription = label } else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (showLabels) Arrangement.Top else Arrangement.Center,
    ) {
        if (showLabels) {
            Box(Modifier.padding(top = NavigationBarDefaults.IconTopPadding)) {
                icon(tint, Modifier.size(NavigationBarDefaults.IconSize))
            }
            Text(
                modifier = Modifier.padding(bottom = NavigationBarDefaults.BottomPadding),
                text = label, color = tint, textAlign = TextAlign.Center,
                fontSize = NavigationBarDefaults.LabelFontSize,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        } else icon(tint, Modifier.size(NavigationBarDefaults.IconSize))
    }
}
