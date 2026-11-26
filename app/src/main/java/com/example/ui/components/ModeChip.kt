// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.PermissionMode
import com.example.ui.theme.LocalYSCodeColors

@Composable
fun ModeChip(
    mode: PermissionMode,
    yoloConfirmedOnce: Boolean,
    onSelectMode: (PermissionMode) -> Unit,
    onConfirmYoloFirstTime: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalYSCodeColors.current
    var showYoloConfirm by remember { mutableStateOf(false) }

    val (tint, icon) = when (mode) {
        PermissionMode.ASK -> colors.warn to Icons.Outlined.Shield
        PermissionMode.AUTO_EDIT -> colors.info to Icons.Outlined.AutoFixHigh
        PermissionMode.PLAN -> colors.success to Icons.Outlined.Description
        PermissionMode.YOLO -> colors.danger to Icons.Outlined.Bolt
    }

    Surface(
        onClick = {
            val next = PermissionMode.entries[(mode.ordinal + 1) % PermissionMode.entries.size]
            if (next == PermissionMode.YOLO && !yoloConfirmedOnce) {
                showYoloConfirm = true
            } else {
                onSelectMode(next)
            }
        },
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.45f)),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag("mode_chip")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "Permission mode ${mode.label}",
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelMedium,
                color = tint
            )
        }
    }

    if (showYoloConfirm) {
        AlertDialog(
            onDismissRequest = { showYoloConfirm = false },
            title = { Text("Enable YOLO Mode?") },
            text = {
                Text("YOLO mode allows file writes and bash commands without asking for confirmation (dangerous system commands and skill/MCP installs still ask). Enable?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showYoloConfirm = false
                        onConfirmYoloFirstTime()
                        onSelectMode(PermissionMode.YOLO)
                    },
                    modifier = Modifier.testTag("confirm_yolo_button")
                ) {
                    Text("Enable YOLO", color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showYoloConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
