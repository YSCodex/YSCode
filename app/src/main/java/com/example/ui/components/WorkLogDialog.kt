// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.agent.WorkLog
import com.example.ui.theme.LocalYSCodeColors

@Composable
fun WorkLogDialog(log: WorkLog, onDismiss: () -> Unit) {
    val colors = LocalYSCodeColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Work log (whole session)") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${log.totalMessages} messages - ${log.files.size} files - ${log.commands.size} commands - " +
                        "${log.failedCalls} failed calls" +
                        if (log.compactions > 0) " - ${log.compactions} compaction(s)" else "",
                    style = MaterialTheme.typography.labelMedium, color = colors.textDim
                )
                if (log.files.isNotEmpty()) {
                    Text("Files changed", style = MaterialTheme.typography.titleSmall, color = colors.text)
                    log.files.take(200).forEach { f ->
                        val tags = buildList {
                            if (f.writes > 0) add("written x${f.writes}")
                            if (f.edits > 0) add("edited x${f.edits}")
                        }.joinToString(", ")
                        Text("${f.path}\n   $tags", style = MaterialTheme.typography.bodySmall, color = colors.text)
                    }
                }
                if (log.commands.isNotEmpty()) {
                    Text("Commands run (latest ${minOf(60, log.commands.size)})", style = MaterialTheme.typography.titleSmall, color = colors.text)
                    log.commands.takeLast(60).forEach { c ->
                        Text(
                            (if (c.failed) "FAILED  " else "ok  ") + c.command.lines().first().take(160),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.failed) colors.danger else colors.textDim
                        )
                    }
                }
                if (log.skills.isNotEmpty() || log.mcpTools.isNotEmpty()) {
                    Text("Skills and MCP used", style = MaterialTheme.typography.titleSmall, color = colors.text)
                    if (log.skills.isNotEmpty()) Text("Skills: " + log.skills.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = colors.text)
                    if (log.mcpTools.isNotEmpty()) Text("MCP: " + log.mcpTools.take(30).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = colors.text)
                }
                if (log.toolCounts.isNotEmpty()) {
                    Text("Tool usage", style = MaterialTheme.typography.titleSmall, color = colors.text)
                    Text(log.toolCounts.take(15).joinToString("  ") { "${it.first}:${it.second}" }, style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                }
                if (log.files.isEmpty() && log.commands.isEmpty()) {
                    Text("Nothing done yet in this session.", style = MaterialTheme.typography.bodySmall, color = colors.textDim, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
