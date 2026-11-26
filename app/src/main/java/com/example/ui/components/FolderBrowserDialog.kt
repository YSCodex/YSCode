// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.components

import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import java.io.File

/** In-app folder picker for real device storage (needs All files access). Browse, create, or type a path. */
@Composable
fun FolderBrowserDialog(
    hasAccess: Boolean,
    onGrantAccess: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    val root = remember { runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: File("/sdcard") }
    var current by remember { mutableStateOf(root) }
    var refresh by remember { mutableStateOf(0) }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    val dirs = remember(current, refresh) {
        current.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open device folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!hasAccess) {
                    Text(
                        "All files access is off, so folders cannot be opened in place. Grant it first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warn
                    )
                    TextButton(onClick = onGrantAccess) { Text("Grant All files access") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { current.parentFile?.takeIf { current != root }?.let { current = it } },
                        enabled = current != root
                    ) { Icon(Icons.Outlined.ArrowUpward, "Up", modifier = Modifier.size(18.dp)) }
                    Text(
                        current.absolutePath,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    if (dirs.isEmpty()) {
                        item { Text("No subfolders", style = MaterialTheme.typography.bodySmall, color = colors.textDim) }
                    }
                    items(dirs, key = { it.absolutePath }) { dir ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { current = dir }.padding(vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.Folder, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(dir.name, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (creating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("New folder name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val n = newName.trim().replace("/", "")
                            if (n.isNotEmpty()) {
                                val d = File(current, n)
                                if (d.mkdirs() || d.isDirectory) current = d
                            }
                            newName = ""; creating = false; refresh++
                        }) { Text("Create") }
                    }
                } else {
                    TextButton(onClick = { creating = true }) { Text("+ New folder here") }
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("Or type a path") },
                    placeholder = { Text("/sdcard/Download/my-project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPick(typed.trim().ifEmpty { current.absolutePath })
            }) { Text(if (typed.isBlank()) "Use this folder" else "Open path") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
