// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Mayak: server number buttons, split into rows of five so ten servers fit a phone width.

package com.csqtt.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ServerNumberPicker(
    count: Int,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    perRow: Int = 5,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (0 until count).chunked(perRow).forEach { row ->
            CsqttSegmentedControl(
                options = row.map { index -> index to "${index + 1}" },
                selected = selected,
                onSelected = onSelected,
                modifier = Modifier.height(44.dp),
                enabled = enabled,
            )
        }
    }
}
