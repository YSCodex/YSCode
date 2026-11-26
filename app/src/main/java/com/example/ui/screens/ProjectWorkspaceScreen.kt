// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.SessionEntity
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.theme.stringsFor
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkspaceScreen(
    mainViewModel: MainViewModel,
    onBackToProjects: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val settings by mainViewModel.settings.collectAsStateWithLifecycle()
    val strings = remember(settings.uiLanguage) { stringsFor(settings.uiLanguage) }
    val project by mainViewModel.activeProject.collectAsStateWithLifecycle()
    val sessions by mainViewModel.projectSessions.collectAsStateWithLifecycle()
    val activeSession by mainViewModel.activeSession.collectAsStateWithLifecycle()
    val changes by mainViewModel.changes.collectAsStateWithLifecycle()
    val gitStatus by mainViewModel.gitStatus.collectAsStateWithLifecycle()
    val isOnline by mainViewModel.isOnline.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) } // 0=Chat, 1=Files, 2=Terminal, 3=Changes, 4=More
    var sessionSearch by remember { mutableStateOf("") }
    var renameSessionTarget by remember { mutableStateOf<SessionEntity?>(null) }

    BackHandler(enabled = drawerState.isOpen || selectedTab != 0) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            selectedTab != 0 -> selectedTab = 0
        }
    }

    val filteredSessions = remember(sessions, sessionSearch) {
        if (sessionSearch.isBlank()) sessions
        else sessions.filter { it.title.contains(sessionSearch, ignoreCase = true) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.surface,
                modifier = Modifier.width(300.dp)
            ) {
                Column(modifier = Modifier.fillMaxHeight().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(project?.name ?: "Workspace", style = MaterialTheme.typography.titleMedium, color = colors.text)
                        IconButton(onClick = {
                            mainViewModel.createSession(project)
                            scope.launch { drawerState.close() }
                        }, modifier = Modifier.testTag("drawer_new_session_btn")) {
                            Icon(Icons.Outlined.Add, "New session", tint = colors.accent)
                        }
                    }

                    OutlinedTextField(
                        value = sessionSearch,
                        onValueChange = { sessionSearch = it },
                        placeholder = { Text("Search sessions...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )

                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filteredSessions, key = { it.id }) { s ->
                            val isSelected = s.id == activeSession?.id
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) colors.accent.copy(alpha = 0.14f) else colors.bg.copy(alpha = 0.4f),
                                border = BorderStroke(1.dp, if (isSelected) colors.accent else colors.border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        mainViewModel.selectSession(s)
                                        scope.launch { drawerState.close() }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = s.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected) colors.accent else colors.text,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${s.tokensIn + s.tokensOut} tok",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.textDim
                                        )
                                    }
                                    IconButton(onClick = { renameSessionTarget = s }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Outlined.DriveFileRenameOutline, "Rename", tint = colors.textDim, modifier = Modifier.size(16.dp))
                                    }
                                    if (sessions.size > 1) {
                                        IconButton(onClick = { mainViewModel.deleteSession(s) }, modifier = Modifier.size(30.dp)) {
                                            Icon(Icons.Outlined.Delete, "Delete", tint = colors.danger, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 8.dp))

                    NavigationDrawerItem(
                        label = { Text("All Projects") },
                        icon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; onBackToProjects() }
                    )
                    NavigationDrawerItem(
                        label = { Text("Skills") },
                        icon = { Icon(Icons.Outlined.Extension, null) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; onOpenSkills() }
                    )
                    NavigationDrawerItem(
                        label = { Text("MCP Servers") },
                        icon = { Icon(Icons.Outlined.Hub, null) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; onOpenMcp() }
                    )
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        icon = { Icon(Icons.Outlined.Settings, null) },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; onOpenSettings() }
                    )
                }
            }
        }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 700.dp
            val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

            val tabs = listOf(
                Triple("Chat", Icons.AutoMirrored.Outlined.Chat, 0),
                Triple("Files", Icons.Outlined.Folder, 0),
                Triple("Terminal", Icons.Outlined.Terminal, 0),
                Triple("Changes", Icons.Outlined.Difference, changes.size),
                Triple("More", Icons.Outlined.MoreHoriz, 0)
            )

            Scaffold(
                containerColor = colors.bg,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surface),
                        navigationIcon = {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.testTag("workspace_drawer_btn")
                            ) {
                                Icon(Icons.Outlined.Menu, "Open drawer", tint = colors.text)
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = project?.name ?: "YSCode",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${activeSession?.title ?: "Session"} • ${gitStatus.branch}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textDim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = onBackToProjects) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Projects", tint = colors.textDim)
                            }
                        }
                    )
                },
                bottomBar = {
                    if (!useRail && !isImeVisible) {
                        NavigationBar(
                            containerColor = colors.surface,
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            tabs.forEachIndexed { idx, (label, icon, badgeCount) ->
                                NavigationBarItem(
                                    selected = selectedTab == idx,
                                    onClick = { selectedTab = idx },
                                    icon = {
                                        if (badgeCount > 0) {
                                            BadgedBox(badge = { Badge { Text("$badgeCount") } }) {
                                                Icon(icon, label)
                                            }
                                        } else {
                                            Icon(icon, label)
                                        }
                                    },
                                    label = { Text(label, maxLines = 1, style = MaterialTheme.typography.bodySmall) },
                                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                        selectedIconColor = colors.accent,
                                        selectedTextColor = colors.accent,
                                        indicatorColor = colors.accent.copy(alpha = 0.16f),
                                        unselectedIconColor = colors.textDim,
                                        unselectedTextColor = colors.textDim
                                    ),
                                    modifier = Modifier.testTag("bottom_tab_${label.lowercase()}")
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (useRail) {
                        NavigationRail(containerColor = colors.surface) {
                            tabs.forEachIndexed { idx, (label, icon, badgeCount) ->
                                NavigationRailItem(
                                    selected = selectedTab == idx,
                                    onClick = { selectedTab = idx },
                                    icon = {
                                        if (badgeCount > 0) {
                                            BadgedBox(badge = { Badge { Text("$badgeCount") } }) {
                                                Icon(icon, label)
                                            }
                                        } else {
                                            Icon(icon, label)
                                        }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (!isOnline) {
                            Surface(color = colors.warn.copy(alpha = 0.16f), modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Outlined.WifiOff, null, tint = colors.warn, modifier = Modifier.size(15.dp))
                                    Text(strings.offlineBanner, style = MaterialTheme.typography.labelSmall, color = colors.warn)
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            when (selectedTab) {
                                0 -> ChatTab(
                                    mainViewModel = mainViewModel,
                                    onNavigateToChanges = { selectedTab = 3 },
                                    onNavigateToTerminal = { selectedTab = 2 },
                                    onNavigateToProviders = onOpenProviders,
                                    onNavigateToSkills = onOpenSkills,
                                    onNavigateToMcp = onOpenMcp,
                                    onNavigateToSettings = onOpenSettings,
                                    onOpenSessionsDrawer = { scope.launch { drawerState.open() } }
                                )
                                1 -> FilesTab(
                                    mainViewModel = mainViewModel,
                                    onAskAgentAboutFile = { relPath ->
                                        mainViewModel.updateComposerText("Inspect @$relPath ")
                                        selectedTab = 0
                                    }
                                )
                                2 -> TerminalTab(mainViewModel = mainViewModel)
                                3 -> ChangesTab(mainViewModel = mainViewModel)
                                4 -> MoreTab(
                                    mainViewModel = mainViewModel,
                                    onOpenSkills = onOpenSkills,
                                    onOpenMcp = onOpenMcp,
                                    onOpenProviders = onOpenProviders,
                                    onOpenSettings = onOpenSettings
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renameSessionTarget?.let { target ->
        var title by remember { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameSessionTarget = null },
            title = { Text("Rename Session") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    mainViewModel.renameSession(target, title)
                    renameSessionTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameSessionTarget = null }) { Text("Cancel") }
            }
        )
    }
}
