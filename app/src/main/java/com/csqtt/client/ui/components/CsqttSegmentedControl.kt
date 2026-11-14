// SPDX-FileCopyrightText: 2026 amurcanov
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.csqtt.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import com.csqtt.client.ui.design.CsqttShapes
import com.csqtt.client.ui.design.CsqttSizes
import com.csqtt.client.ui.design.CsqttSpacing

@Composable
fun <T> CsqttSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showCheck: Boolean = true,
    // Mayak Admin: small second line under a label (the server's ping), by option value.
    subLabels: Map<T, String> = emptyMap(),
    // Mayak: colour of a label (green = server answers, red = it does not), by option value.
    labelColors: Map<T, androidx.compose.ui.graphics.Color> = emptyMap(),
    // Mayak Admin: options whose label starts with a flag drawn inside a small TV ("🇸🇪 3").
    tvFramed: Set<T> = emptySet(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(CsqttSpacing.Xs),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                shape = CsqttShapes.Control,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = CsqttSizes.MinimumTouchTarget)
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelected(value) },
                    ),
            ) {
                // Status colour (green/red) is shown on every server except the selected one, whose
                // background already highlights it and can be low-contrast for coloured text.
                val labelColor = if (isSelected) androidx.compose.ui.graphics.Color.Unspecified
                    else labelColors[value] ?: androidx.compose.ui.graphics.Color.Unspecified
                val subLabel = subLabels[value]
                if (subLabels.isNotEmpty()) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(horizontal = CsqttSpacing.Xxs, vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        SegmentLabel(label, value in tvFramed, labelColor)
                        Text(
                            text = subLabel ?: " ",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = androidx.compose.ui.graphics.Color.Unspecified,
                            modifier = Modifier.alpha(0.75f),
                        )
                    }
                    return@Surface
                }
                Row(
                    modifier = Modifier.padding(horizontal = CsqttSpacing.Xs, vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isSelected && showCheck) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(CsqttSizes.IconSmall),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(CsqttSpacing.Xxs))
                    }
                    SegmentLabel(label, value in tvFramed, labelColor)
                }
            }
        }
    }
}

/** A segment label; with [tv] its first word (the flag) sits on the screen of a small TV. */
@Composable
private fun SegmentLabel(label: String, tv: Boolean, color: androidx.compose.ui.graphics.Color) {
    val split = label.indexOf(' ')
    if (!tv || split <= 0) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = color)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TvFlag(label.substring(0, split))
        androidx.compose.foundation.layout.Spacer(Modifier.width(3.dp))
        Text(text = label.substring(split + 1), style = MaterialTheme.typography.labelMedium, maxLines = 1, color = color)
    }
}

/** A tiny TV: a rounded frame with the flag on its screen and a short stand under it. */
@Composable
private fun TvFlag(flag: String) {
    val frame = androidx.compose.material3.LocalContentColor.current
    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .border(1.5.dp, frame, androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                .padding(horizontal = 2.dp, vertical = 0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = flag, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
        }
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .width(8.dp)
                .height(1.5.dp)
                .background(frame),
        )
    }
}
