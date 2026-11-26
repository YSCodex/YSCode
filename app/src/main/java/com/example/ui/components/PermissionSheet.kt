// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agent.PendingPermissionRequest
import com.example.agent.PermissionReply
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.theme.YSCodeStrings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PermissionSheet(
    request: PendingPermissionRequest,
    strings: YSCodeStrings,
    onRespond: (PermissionReply) -> Unit
) {
    val colors = LocalYSCodeColors.current
    val haptic = LocalHapticFeedback.current
    val riskColor = if (request.isDangerous) colors.danger else colors.warn

    ModalBottomSheet(
        onDismissRequest = { onRespond(PermissionReply.DENY) },
        containerColor = colors.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = riskColor.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, riskColor.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = if (request.isDangerous) Icons.Outlined.WarningAmber else Icons.Outlined.Shield,
                        contentDescription = null,
                        tint = riskColor,
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (request.isDangerous) "High-Risk Action Approval" else "Permission Required",
                        style = MaterialTheme.typography.titleMedium,
                        color = riskColor
                    )
                    Text(
                        text = request.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface2)
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = request.previewDiff ?: request.detail,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 12.sp,
                    color = colors.text
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onRespond(PermissionReply.DENY)
                    },
                    border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.6f)),
                    modifier = Modifier.testTag("permission_deny_button")
                ) {
                    Text(strings.deny, color = colors.danger)
                }

                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRespond(PermissionReply.ALLOW_ONCE)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    modifier = Modifier.testTag("permission_allow_once_button")
                ) {
                    Text(strings.allowOnce)
                }

                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRespond(PermissionReply.ALLOW_SESSION)
                    },
                    modifier = Modifier.testTag("permission_allow_session_button")
                ) {
                    Text(strings.allowSession)
                }

                if (!request.isDangerous) {
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onRespond(PermissionReply.ALWAYS_ALLOW)
                        },
                        modifier = Modifier.testTag("permission_always_allow_button")
                    ) {
                        Text("${strings.alwaysAllow} ${request.suggestedRulePattern}")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
