// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mcp.McpServerState
import com.example.mcp.McpStatus
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.viewmodel.ConfigViewModel
import com.example.ui.viewmodel.MainViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    mainViewModel: MainViewModel,
    configViewModel: ConfigViewModel,
    onBack: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    val activeProject by mainViewModel.activeProject.collectAsStateWithLifecycle()
    val servers by configViewModel.mcpServers.collectAsStateWithLifecycle()
    val projectDir = remember(activeProject) { activeProject?.path?.let { File(it) } }

    LaunchedEffect(projectDir) {
        configViewModel.container.mcpRegistry.refreshFromDb(projectDir)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showImportJsonDialog by remember { mutableStateOf(false) }
    var inspectedServerId by remember { mutableStateOf<String?>(null) }
    val inspectedServer = remember(servers, inspectedServerId) {
        servers.find { it.serverId == inspectedServerId }
    }

    Scaffold(
        containerColor = colors.bg,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("mcp_back_btn")) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text)
                    }
                },
                title = { Text("MCP Servers", color = colors.text) },
                actions = {
                    IconButton(onClick = { showImportJsonDialog = true }, modifier = Modifier.testTag("mcp_import_json_btn")) {
                        Icon(Icons.Outlined.Code, "Import mcpServers JSON", tint = colors.textDim)
                    }
                    IconButton(onClick = { showAddDialog = true }, modifier = Modifier.testTag("mcp_add_btn")) {
                        Icon(Icons.Outlined.Add, "Add MCP server", tint = colors.accent)
                    }
                }
            )
        }
    ) { padding ->
        if (servers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Hub, null, tint = colors.accent, modifier = Modifier.size(48.dp))
                    Text("No MCP servers configured", style = MaterialTheme.typography.titleMedium, color = colors.text)
                    Text(
                        "Connect stdio tools running inside YSCode or remote HTTP/SSE MCP servers to extend the agent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) {
                            Text("Add Server")
                        }
                        OutlinedButton(onClick = { showImportJsonDialog = true }) {
                            Text("Paste JSON")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(servers, key = { it.serverId }) { srv ->
                    McpServerCard(
                        server = srv,
                        onClick = { inspectedServerId = srv.serverId },
                        onToggle = { enabled -> configViewModel.toggleMcpServer(srv.serverId, enabled, projectDir) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            hasProject = projectDir != null,
            onDismiss = { showAddDialog = false },
            onAdd = { name, transport, cmd, args, url, kvPairs, scopeChoice ->
                val envMap = if (transport == "stdio") kvPairs else emptyMap()
                val hdrMap = if (transport != "stdio") kvPairs else emptyMap()
                configViewModel.addMcpServer(
                    name = name,
                    transport = transport,
                    command = cmd,
                    argsText = args,
                    url = url,
                    envMap = envMap,
                    headersMap = hdrMap,
                    scope = scopeChoice,
                    projectDir = projectDir
                )
                showAddDialog = false
            }
        )
    }

    if (showImportJsonDialog) {
        var jsonInput by remember {
            mutableStateOf(
                """
                {
                  "mcpServers": {
                    "filesystem": {
                      "command": "npx",
                      "args": ["-y", "@modelcontextprotocol/server-filesystem", "."]
                    }
                  }
                }
                """.trimIndent()
            )
        }
        var scopeChoice by remember { mutableStateOf("global") }
        AlertDialog(
            onDismissRequest = { showImportJsonDialog = false },
            title = { Text("Import mcpServers JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = scopeChoice == "global", onClick = { scopeChoice = "global" }, label = { Text("Global") })
                        FilterChip(selected = scopeChoice == "project", enabled = projectDir != null, onClick = { scopeChoice = "project" }, label = { Text("Project") })
                    }
                    OutlinedTextField(
                        value = jsonInput,
                        onValueChange = { jsonInput = it },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    configViewModel.importMcpJson(jsonInput, scopeChoice, projectDir)
                    showImportJsonDialog = false
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImportJsonDialog = false }) { Text("Cancel") }
            }
        )
    }

    inspectedServer?.let { srv ->
        AlertDialog(
            onDismissRequest = { inspectedServerId = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(srv.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row {
                        IconButton(onClick = { configViewModel.restartMcpServer(srv.serverId, projectDir) }) {
                            Icon(Icons.Outlined.Refresh, "Restart server", tint = colors.accent)
                        }
                        IconButton(onClick = {
                            configViewModel.removeMcpServer(srv.serverId)
                            inspectedServerId = null
                        }) {
                            Icon(Icons.Outlined.Delete, "Remove server", tint = colors.danger)
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Status: ${srv.status.name} • Transport: ${srv.transport} • Scope: ${srv.scope}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textDim
                    )
                    if (!srv.lastError.isNullOrBlank()) {
                        Surface(color = colors.danger.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = srv.lastError,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.danger,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = colors.border)
                    Text("Discovered Tools (${srv.tools.size})", style = MaterialTheme.typography.titleSmall, color = colors.text)
                    if (srv.tools.isEmpty()) {
                        Text("No tools discovered yet.", style = MaterialTheme.typography.bodySmall, color = colors.textDim)
                    } else {
                        srv.tools.forEach { tool ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tool.qualifiedName, fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp, color = colors.text)
                                    if (tool.description.isNotBlank()) {
                                        Text(tool.description, style = MaterialTheme.typography.labelSmall, color = colors.textDim, maxLines = 2)
                                    }
                                }
                                Switch(
                                    checked = tool.enabled,
                                    onCheckedChange = { configViewModel.toggleMcpTool(srv.serverId, tool.toolName, it) }
                                )
                            }
                        }
                    }

                    if (srv.logs.isNotEmpty()) {
                        HorizontalDivider(color = colors.border)
                        Text("Recent Logs", style = MaterialTheme.typography.titleSmall, color = colors.text)
                        Surface(color = colors.surface2, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = srv.logs.takeLast(20).joinToString("\n"),
                                fontFamily = JetBrainsMonoFontFamily,
                                fontSize = 10.sp,
                                color = colors.textDim,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { inspectedServerId = null }) { Text("Done") }
            }
        )
    }
}

@Composable
private fun McpServerCard(
    server: McpServerState,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val colors = LocalYSCodeColors.current
    val statusColor = when (server.status) {
        McpStatus.CONNECTED -> colors.success
        McpStatus.CONNECTING -> colors.warn
        McpStatus.ERROR -> colors.danger
        McpStatus.DISCONNECTED -> colors.textDim.copy(alpha = 0.75f)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(server.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.text)
                Text(
                    text = "${server.transport.uppercase()} • ${server.scope} • ${server.tools.size} tools • ${server.status.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim
                )
                server.lastError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = colors.danger, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Switch(checked = server.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun AddMcpServerDialog(
    hasProject: Boolean,
    onDismiss: () -> Unit,
    onAdd: (
        name: String,
        transport: String,
        command: String,
        args: String,
        url: String,
        kvPairs: Map<String, String>,
        scope: String
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("stdio") }
    var scopeChoice by remember { mutableStateOf("global") }
    var command by remember { mutableStateOf("npx") }
    var args by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var kvText by remember { mutableStateOf("") } // KEY=VALUE lines

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP Server") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("stdio", "http", "sse").forEach { t ->
                        FilterChip(selected = transport == t, onClick = { transport = t }, label = { Text(t.uppercase()) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = scopeChoice == "global", onClick = { scopeChoice = "global" }, label = { Text("Global") })
                    FilterChip(selected = scopeChoice == "project", enabled = hasProject, onClick = { scopeChoice = "project" }, label = { Text("Project") })
                }
                if (transport == "stdio") {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Command (e.g. npx, python3)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = args,
                        onValueChange = { args = it },
                        label = { Text("Args (space-separated)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Server URL (https://...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = kvText,
                    onValueChange = { kvText = it },
                    label = { Text(if (transport == "stdio") "Env Vars (KEY=VALUE per line)" else "Headers (Header=Value per line)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val valid = name.isNotBlank() && ((transport == "stdio" && command.isNotBlank()) || (transport != "stdio" && url.isNotBlank()))
            Button(
                onClick = {
                    val map = kvText.lines().mapNotNull { line ->
                        val idx = line.indexOf('=')
                        if (idx > 0) line.substring(0, idx).trim() to line.substring(idx + 1).trim() else null
                    }.toMap()
                    onAdd(name, transport, command, args, url, map, scopeChoice)
                },
                enabled = valid
            ) { Text("Add & Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
