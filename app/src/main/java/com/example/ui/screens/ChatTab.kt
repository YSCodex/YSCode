// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ForkRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agent.BackgroundJobStatus
import com.example.agent.HookDefinition
import com.example.agent.TimelineItem
import com.example.agent.TimelineMapper
import com.example.agent.TimelineToolStep
import com.example.data.PermissionMode
import com.example.llm.LlmMessage
import com.example.tools.PlanAction
import com.example.ui.components.BackgroundAgentsDialog
import com.example.ui.components.ChatComposer
import com.example.ui.components.HooksManagerDialog
import com.example.ui.components.MarkdownText
import com.example.ui.components.ModelPickerSheet
import com.example.ui.components.PermissionSheet
import com.example.ui.components.ReadGroupCard
import com.example.ui.components.TextPreviewDialog
import com.example.ui.components.TodoChecklistCard
import com.example.ui.components.ToolCard
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalYSCodeColors
import com.example.ui.theme.stringsFor
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatTab(
    mainViewModel: MainViewModel,
    onNavigateToChanges: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToProviders: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToMcp: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenSessionsDrawer: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val settings by mainViewModel.settings.collectAsStateWithLifecycle()
    val strings = remember(settings.uiLanguage) { stringsFor(settings.uiLanguage) }
    val session by mainViewModel.activeSession.collectAsStateWithLifecycle()
    val providers by mainViewModel.providers.collectAsStateWithLifecycle()
    val messages by mainViewModel.messages.collectAsStateWithLifecycle()
    val checkpoints by mainViewModel.checkpoints.collectAsStateWithLifecycle()
    val activity by mainViewModel.agentActivity.collectAsStateWithLifecycle()
    val todos by mainViewModel.todoItems.collectAsStateWithLifecycle()
    val bgAgentJobs by mainViewModel.backgroundAgentJobs.collectAsStateWithLifecycle()
    val pendingPerm by mainViewModel.pendingPermission.collectAsStateWithLifecycle()
    val pendingQuestion by mainViewModel.pendingQuestion.collectAsStateWithLifecycle()
    val pendingPlan by mainViewModel.pendingPlan.collectAsStateWithLifecycle()
    val composerText by mainViewModel.composerText.collectAsStateWithLifecycle()
    val attachments by mainViewModel.attachments.collectAsStateWithLifecycle()
    val slashCommands by mainViewModel.slashCommands.collectAsStateWithLifecycle()
    val projectFiles by mainViewModel.projectFiles.collectAsStateWithLifecycle()

    var showModelSheet by remember { mutableStateOf(false) }
    var showErrorDetails by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showBgAgentsDialog by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    val activeProjectForPreview by mainViewModel.activeProject.collectAsStateWithLifecycle()
    var workLogData by remember { mutableStateOf<com.example.agent.WorkLog?>(null) }
    var showHooksDialog by remember { mutableStateOf(false) }
    var loadedHooks by remember { mutableStateOf<List<HookDefinition>>(emptyList()) }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewBody by remember { mutableStateOf("") }
    var planEditText by remember { mutableStateOf("") }
    var showPlanEditDialog by remember { mutableStateOf(false) }

    val timelineItems = remember(messages, pendingQuestion, pendingPlan) {
        TimelineMapper.mapMessagesToTimeline(
            messages = messages,
            hasPendingQuestion = pendingQuestion != null,
            hasPendingPlan = pendingPlan != null
        )
    }

    val listState = rememberLazyListState()
    val isUserScrolledUp by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) {
                false
            } else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()
                val lastItemVisible = lastVisible != null && lastVisible.index >= total - 1
                val hiddenBelowPx = if (lastVisible != null) {
                    (lastVisible.offset + lastVisible.size) - info.viewportEndOffset
                } else {
                    Int.MAX_VALUE
                }
                listState.canScrollForward && (!lastItemVisible || hiddenBelowPx > 80)
            }
        }
    }

    // Scroll cleanly to the bottom anchor without pushing earlier messages off-screen
    LaunchedEffect(
        timelineItems.size,
        activity.isRunning,
        activity.streamingText.length,
        activity.activeTools.size,
        pendingQuestion?.id,
        pendingPlan?.planSummary
    ) {
        if (!isUserScrolledUp && (timelineItems.isNotEmpty() || activity.isRunning)) {
            val lastIdx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.scrollToItem(lastIdx, 10_000)
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort notification permission on Android 13+ */ }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) mainViewModel.attachImageUri(context, uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) mainViewModel.attachFileContent(context, uri)
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                mainViewModel.updateComposerText((composerText + " " + spoken).trimStart())
            }
        }
    }

    val activeProvider = remember(providers, session, settings.activeProviderId) {
        providers.find { it.id == (session?.providerId?.ifBlank { settings.activeProviderId }) }
            ?: providers.firstOrNull()
    }
    val activeModelName = remember(activeProvider, session) {
        session?.model?.ifBlank { activeProvider?.defaultModel } ?: activeProvider?.defaultModel ?: "No model"
    }

    val handleSlashCommand: (String) -> Unit = { cmd ->
        mainViewModel.updateComposerText("")
        when (cmd.lowercase()) {
            "/init" -> mainViewModel.initProjectMemory()
            "/bg" -> {
                ensureNotificationPermission()
                mainViewModel.updateComposerText("/bg ")
            }
            "/test" -> mainViewModel.runProjectAutoTestNow { output ->
                previewTitle = "Project Tests"
                previewBody = output
            }
            "/repomap" -> mainViewModel.buildRepoMapPreview { map ->
                previewTitle = "Repo Map Index"
                previewBody = map
            }
            "/hooks" -> scope.launch {
                loadedHooks = mainViewModel.loadProjectHooks()
                showHooksDialog = true
            }
            "/memory" -> {
                previewTitle = "YSCODE.md Memory"
                previewBody = mainViewModel.readProjectMemoryText().ifBlank { "No YSCODE.md yet. Run /init or chat with the agent." }
            }
            "/clear" -> mainViewModel.clearCurrentSession()
            "/compact" -> mainViewModel.compactCurrentSession()
            "/vision" -> mainViewModel.testVision()
            "/model" -> showModelSheet = true
            "/mode" -> {
                val next = PermissionMode.entries[(settings.defaultMode.ordinal + 1) % PermissionMode.entries.size]
                mainViewModel.setPermissionMode(next)
            }
            "/plan" -> mainViewModel.setPermissionMode(PermissionMode.PLAN)
            "/undo" -> mainViewModel.undoLastCheckpoint()
            "/diff", "/commit" -> onNavigateToChanges()
            "/resume", "/sessions" -> onOpenSessionsDrawer()
            "/skills" -> onNavigateToSkills()
            "/mcp" -> onNavigateToMcp()
            "/tools" -> onNavigateToSettings()
            "/export" -> {
                session?.let { s ->
                    clipboard.setText(AnnotatedString(mainViewModel.exportSessionMarkdown(s)))
                    mainViewModel.emitToast("Copied session Markdown to clipboard")
                }
            }
            "/help" -> showHelpDialog = true
            else -> mainViewModel.updateComposerText("$cmd ")
        }
    }

    workLogData?.let { wl ->
        com.example.ui.components.WorkLogDialog(wl) { workLogData = null }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
    ) {
        // Sub-header: Searchable Model chip + Background Agents pill + Tokens/Cost
        Surface(
            color = colors.surface,
            border = BorderStroke(1.dp, colors.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = { showModelSheet = true },
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surface2,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 230.dp)
                        .testTag("chat_model_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = activeModelName.substringAfterLast('/'),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (activeProvider == null) colors.warn else colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(Icons.Outlined.KeyboardArrowDown, "Search & switch model", tint = colors.textDim, modifier = Modifier.size(16.dp))
                    }
                }

                Surface(
                    onClick = { showPreview = true },
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surface2,
                    border = BorderStroke(1.dp, colors.border),
                    modifier = Modifier.testTag("chat_preview_chip")
                ) {
                    Icon(Icons.Outlined.Language, "Preview app", tint = colors.textDim, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp).size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                val runningBg = bgAgentJobs.count { it.status == BackgroundJobStatus.RUNNING }
                val totalBg = bgAgentJobs.size
                Surface(
                    onClick = {
                        ensureNotificationPermission()
                        showBgAgentsDialog = true
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = if (runningBg > 0) colors.accent.copy(alpha = 0.16f) else colors.surface2,
                    border = BorderStroke(1.dp, if (runningBg > 0) colors.accent else colors.border),
                    modifier = Modifier.testTag("chat_bg_agents_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Hub,
                            contentDescription = "Background agents",
                            tint = if (runningBg > 0) colors.accent else colors.textDim,
                            modifier = Modifier.size(14.dp)
                        )
                        if (runningBg > 0 || totalBg > 0) {
                            Text(
                                text = if (runningBg > 0) "$runningBg bg" else "$totalBg done",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (runningBg > 0) colors.accent else colors.textDim
                            )
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))
                Surface(
                    onClick = {
                        val sid = session?.id
                        if (sid != null) scope.launch { workLogData = mainViewModel.buildWorkLog(sid) }
                    },
                    shape = RoundedCornerShape(8.dp),
                    color = colors.surface2,
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Text(
                        "Log",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textDim
                    )
                }

                val tokIn = session?.tokensIn ?: 0L
                val tokOut = session?.tokensOut ?: 0L
                val totalTok = tokIn + tokOut
                val tokLabel = com.example.util.formatTokens(totalTok)
                val costStr = session?.estimatedCostUsd?.takeIf { it > 0.0001 }?.let { " • $${String.format("%.3f", it)}" }.orEmpty()
                Text(
                    text = "$tokLabel$costStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textDim
                )
            }
        }

        // Message timeline
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (timelineItems.isEmpty() && !activity.isRunning) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = colors.accent, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Ready to build", style = MaterialTheme.typography.titleLarge, color = colors.text)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (activeProvider == null) "Configure an LLM provider first, or test Linux commands with !ls"
                        else "Ask YSCode anything — chat naturally, inspect your Repo Map, search the web, or run parallel agents.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textDim,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(18.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val suggestions = listOf(
                            "Explain this repo",
                            "Run & fix tests",
                            "Search latest docs",
                            "Review code quality"
                        )
                        suggestions.forEach { chipText ->
                            Surface(
                                onClick = {
                                    mainViewModel.updateComposerText(chipText)
                                    mainViewModel.sendCurrentMessage()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = colors.surface,
                                border = BorderStroke(1.dp, colors.border)
                            ) {
                                Text(
                                    text = chipText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.text,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                    if (activeProvider == null) {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onNavigateToProviders,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                        ) {
                            Text("Configure Provider")
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (todos.isNotEmpty()) {
                        item(key = "todo_header") {
                            TodoChecklistCard(todos = todos)
                        }
                    }

                    items(timelineItems, key = { it.stableKey }) { entry ->
                        when (entry) {
                            is TimelineItem.UserTurn -> {
                                UserTurnCard(
                                    entry = entry,
                                    hasCheckpoint = checkpoints.any { it.messageId == entry.message.id },
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(entry.text))
                                        mainViewModel.emitToast("Copied message")
                                    },
                                    onFork = { session?.let { s -> mainViewModel.forkSessionFromMessage(s, entry.message.id) } },
                                    onRestoreCheckpoint = {
                                        checkpoints.find { it.messageId == entry.message.id }?.let { chk ->
                                            mainViewModel.restoreCheckpoint(chk)
                                        }
                                    },
                                    onRetry = {
                                        mainViewModel.updateComposerText(entry.text)
                                        mainViewModel.sendCurrentMessage()
                                    }
                                )
                            }
                            is TimelineItem.AssistantTurn -> {
                                AssistantTurnCard(
                                    entry = entry,
                                    codeFontSizeSp = settings.codeFontSize,
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(entry.message.plainText()))
                                        mainViewModel.emitToast("Copied message")
                                    },
                                    onFork = { session?.let { s -> mainViewModel.forkSessionFromMessage(s, entry.message.id) } },
                                    onViewDiff = { onNavigateToChanges() },
                                    onOpenTerminal = onNavigateToTerminal
                                )
                            }
                        }
                    }

                    // Live streaming turn
                    if (activity.isRunning) {
                        item(key = "streaming_turn") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (activity.streamingReasoning.isNotBlank()) {
                                    ReasoningCard(
                                        reasoning = activity.streamingReasoning,
                                        durationLabel = "thinking..."
                                    )
                                }
                                activity.activeTools.forEach { tool ->
                                    ToolCard(
                                        tool = tool,
                                        onViewDiff = { onNavigateToChanges() },
                                        onOpenTerminal = onNavigateToTerminal
                                    )
                                }
                                if (activity.streamingText.isNotBlank()) {
                                    MarkdownText(
                                        markdown = activity.streamingText,
                                        codeFontSizeSp = settings.codeFontSize
                                    )
                                } else if (activity.activeTools.isEmpty()) {
                                    Text(
                                        text = "${activity.currentPhase}…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.accent
                                    )
                                }
                            }
                        }
                    }

                    // Auto-Test, Hook, and YSCODE.md Memory status pills from last turn
                    activity.lastAutoTestResult?.let { testRes ->
                        item(key = "autotest_badge_${testRes.command}_${testRes.durationMs}") {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = (if (testRes.passed) colors.success else colors.danger).copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, (if (testRes.passed) colors.success else colors.danger).copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        previewTitle = "Auto-Test: ${testRes.command}"
                                        previewBody = testRes.output
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        if (testRes.passed) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                        null,
                                        tint = if (testRes.passed) colors.success else colors.danger,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (testRes.passed) {
                                            "Auto-Test passed: ${testRes.command} (${testRes.durationMs}ms)"
                                        } else {
                                            "Auto-Test failed: ${testRes.command} (exit ${testRes.exitCode})"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (testRes.passed) colors.success else colors.danger
                                    )
                                }
                            }
                        }
                    }

                    activity.lastMemoryUpdate?.takeIf { it.updated }?.let { mem ->
                        item(key = "memory_badge") {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = colors.info.copy(alpha = 0.10f),
                                border = BorderStroke(1.dp, colors.info.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        previewTitle = "YSCODE.md Memory"
                                        previewBody = mainViewModel.readProjectMemoryText()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Outlined.BookmarkAdded, null, tint = colors.info, modifier = Modifier.size(15.dp))
                                    Text(
                                        text = "Memory saved to YSCODE.md: ${mem.addedNotes.firstOrNull().orEmpty()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.info,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // Interactive ask_user card
                    pendingQuestion?.let { q ->
                        item(key = "ask_user_${q.id}") {
                            AskUserCard(
                                question = q.question,
                                options = q.options,
                                onAnswer = { mainViewModel.submitUserAnswer(it) }
                            )
                        }
                    }

                    // Interactive Plan Ready card
                    pendingPlan?.let { p ->
                        item(key = "plan_ready") {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = colors.surface,
                                border = BorderStroke(1.dp, colors.success.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth().testTag("plan_ready_card")
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Plan Ready", style = MaterialTheme.typography.titleMedium, color = colors.success)
                                    MarkdownText(markdown = p.planSummary, codeFontSizeSp = settings.codeFontSize)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { mainViewModel.submitPlanDecision(PlanAction.APPROVE_AND_RUN) },
                                            colors = ButtonDefaults.buttonColors(containerColor = colors.success),
                                            modifier = Modifier.testTag("plan_approve_btn")
                                        ) {
                                            Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Approve & run")
                                        }
                                        OutlinedButton(onClick = { showPlanEditDialog = true }) {
                                            Text("Edit plan")
                                        }
                                        OutlinedButton(onClick = { mainViewModel.submitPlanDecision(PlanAction.REJECT) }) {
                                            Icon(Icons.Outlined.Close, null, tint = colors.danger, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Reject", color = colors.danger)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Error recovery card
                    activity.lastError?.let { err ->
                        item(key = "error_card") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = colors.danger.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth().testTag("chat_error_card")
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Outlined.ErrorOutline, null, tint = colors.danger)
                                        Text(
                                            text = err.lines().firstOrNull() ?: err,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colors.danger,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            val lastUser = messages.lastOrNull { it.role == "user" }?.plainText()
                                            if (!lastUser.isNullOrBlank()) {
                                                mainViewModel.updateComposerText(lastUser)
                                                mainViewModel.sendCurrentMessage()
                                            }
                                        }) {
                                            Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(15.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(strings.retry)
                                        }
                                        OutlinedButton(onClick = { showModelSheet = true }) {
                                            Text("Change model")
                                        }
                                        TextButton(onClick = { showErrorDetails = true }) {
                                            Text("Details")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Tiny bottom anchor so auto-scroll never hides prior items off-screen
                    item(key = "timeline_bottom_anchor") {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            if (isUserScrolledUp) {
                SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val idx = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(idx, 10_000)
                        }
                    },
                    shape = CircleShape,
                    containerColor = colors.surface2,
                    contentColor = colors.accent,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp)
                        .testTag("chat_scroll_bottom_fab")
                ) {
                    Icon(
                        Icons.Outlined.ArrowDownward,
                        contentDescription = "Scroll to latest message",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Composer
        ChatComposer(
            text = composerText,
            onTextChange = mainViewModel::updateComposerText,
            isRunning = activity.isRunning,
            mode = settings.defaultMode,
            yoloConfirmedOnce = settings.yoloConfirmedOnce,
            onSelectMode = mainViewModel::setPermissionMode,
            onConfirmYolo = mainViewModel::confirmYoloFirstTime,
            slashCommands = slashCommands,
            projectFiles = projectFiles,
            attachments = attachments,
            onRemoveAttachment = mainViewModel::removeAttachment,
            queuedPrompts = activity.queuedPrompts,
            onClearQueue = { mainViewModel.container.agentLoop.clearQueue() },
            strings = strings,
            onSend = {
                if (composerText.trim().startsWith("/bg ", ignoreCase = true)) {
                    ensureNotificationPermission()
                }
                mainViewModel.sendCurrentMessage()
            },
            onStop = mainViewModel::stopAgent,
            onPickImage = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickFile = {
                filePicker.launch(arrayOf("*/*"))
            },
            onVoiceInput = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak coding instruction")
                }
                runCatching { voiceLauncher.launch(intent) }.onFailure {
                    mainViewModel.emitToast("No speech recognizer installed on this device")
                }
            },
            onExecuteSlashCommand = handleSlashCommand
        )
    }

    if (showModelSheet) {
        ModelPickerSheet(
            providers = providers,
            activeProviderId = activeProvider?.id,
            activeModelId = activeModelName,
            onSelectModel = { provId, modelId -> mainViewModel.setSessionModel(provId, modelId) },
            onUseCustomModelId = { provId, customId -> mainViewModel.selectOrAddCustomModel(provId, customId, true) },
            onManageProviders = onNavigateToProviders,
            onDismiss = { showModelSheet = false }
        )
    }

    if (showPreview) {
        val lastUrl = remember(messages) {
            val rx = Regex("https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0):\\d{2,5}[^\\s)`'\"]*")
            messages.asReversed().firstNotNullOfOrNull { m -> if (m.role == "assistant") rx.findAll(m.plainText()).lastOrNull()?.value else null }
        }
        com.example.ui.components.PreviewDialog(
            initialUrl = (lastUrl ?: "http://localhost:3000").replace("0.0.0.0", "localhost"),
            projectDir = activeProjectForPreview?.let { java.io.File(it.localPath ?: it.path) },
            onDismiss = { showPreview = false }
        )
    }

    if (showBgAgentsDialog) {
        BackgroundAgentsDialog(
            jobs = bgAgentJobs,
            onLaunch = { role, prompt ->
                ensureNotificationPermission()
                mainViewModel.launchBackgroundAgent(role, prompt)
            },
            onCancel = mainViewModel::cancelBackgroundAgent,
            onInjectIntoChat = mainViewModel::injectBackgroundResult,
            onDismissJob = mainViewModel::dismissBackgroundAgent,
            onDismiss = { showBgAgentsDialog = false }
        )
    }

    if (showHooksDialog) {
        HooksManagerDialog(
            initialHooks = loadedHooks,
            onSave = mainViewModel::saveProjectHooks,
            onDismiss = { showHooksDialog = false }
        )
    }

    previewTitle?.let { t ->
        TextPreviewDialog(
            title = t,
            content = previewBody,
            onDismiss = { previewTitle = null }
        )
    }

    pendingPerm?.let { req ->
        PermissionSheet(
            request = req,
            strings = strings,
            onRespond = mainViewModel::respondPermission
        )
    }

    if (showErrorDetails && activity.lastError != null) {
        AlertDialog(
            onDismissRequest = { showErrorDetails = false },
            title = { Text("Error Details") },
            text = { Text(activity.lastError.orEmpty(), fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { showErrorDetails = false }) { Text("Close") } }
        )
    }

    if (showPlanEditDialog) {
        AlertDialog(
            onDismissRequest = { showPlanEditDialog = false },
            title = { Text("Revise Plan") },
            text = {
                OutlinedTextField(
                    value = planEditText,
                    onValueChange = { planEditText = it },
                    label = { Text("Feedback for agent") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    showPlanEditDialog = false
                    mainViewModel.submitPlanDecision(PlanAction.EDIT, planEditText)
                    planEditText = ""
                }) { Text("Send Feedback") }
            },
            dismissButton = {
                TextButton(onClick = { showPlanEditDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("YSCode Slash Commands") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    slashCommands.forEach { cmd ->
                        Text("${cmd.name} — ${cmd.description}", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Tip: Prefix with ! to run a shell command directly in Terminal.", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                }
            },
            confirmButton = { TextButton(onClick = { showHelpDialog = false }) { Text("OK") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserTurnCard(
    entry: TimelineItem.UserTurn,
    hasCheckpoint: Boolean,
    onCopy: () -> Unit,
    onFork: () -> Unit,
    onRestoreCheckpoint: () -> Unit,
    onRetry: () -> Unit
) {
    val colors = LocalYSCodeColors.current
    var showMenu by remember { mutableStateOf(false) }

    if (entry.isCompactedSummary) {
        var open by remember { mutableStateOf(false) }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.surface2,
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { open = !open }, onLongClick = {})
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (open) "Context summary (tap to hide)"
                    else "Context compacted: your earlier messages stay above, but only this summary is sent to the model now. Tap to view.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textDim
                )
                if (open) {
                    Text(
                        text = entry.text.removePrefix("[Compacted Context Summary]").trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.text
                    )
                }
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (entry.isCompactedSummary) colors.surface2 else colors.accent.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, if (entry.isCompactedSummary) colors.border else colors.accent.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
                    .testTag("user_message_bubble")
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = entry.text.ifBlank { "[Attachment]" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.text
                    )
                    if (entry.attachmentCount > 0) {
                        Text(
                            text = "${entry.attachmentCount} image attachment(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.accent
                        )
                    }
                }
            }
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Copy") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { showMenu = false; onCopy() }
            )
            DropdownMenuItem(
                text = { Text("Fork from here") },
                leadingIcon = { Icon(Icons.Outlined.ForkRight, null) },
                onClick = { showMenu = false; onFork() }
            )
            if (hasCheckpoint) {
                DropdownMenuItem(
                    text = { Text("Restore checkpoint") },
                    leadingIcon = { Icon(Icons.Outlined.History, null) },
                    onClick = { showMenu = false; onRestoreCheckpoint() }
                )
            }
            DropdownMenuItem(
                text = { Text("Retry prompt") },
                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                onClick = { showMenu = false; onRetry() }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantTurnCard(
    entry: TimelineItem.AssistantTurn,
    codeFontSizeSp: Int,
    onCopy: () -> Unit,
    onFork: () -> Unit,
    onViewDiff: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true }
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            entry.reasoning?.takeIf { it.isNotBlank() }?.let { r ->
                ReasoningCard(
                    reasoning = r,
                    durationLabel = if (entry.reasoningDurationSec > 0) "Thought for ${entry.reasoningDurationSec}s" else "Reasoning"
                )
            }
            entry.toolSteps.forEach { step ->
                when (step) {
                    is TimelineToolStep.ReadGroup -> {
                        ReadGroupCard(items = step.items)
                    }
                    is TimelineToolStep.SingleCall -> {
                        ToolCard(
                            tool = step.tool,
                            onViewDiff = { onViewDiff() },
                            onOpenTerminal = onOpenTerminal
                        )
                    }
                }
            }
            entry.text?.let { t ->
                MarkdownText(markdown = t, codeFontSizeSp = codeFontSizeSp)
            }
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Copy") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { showMenu = false; onCopy() }
            )
            DropdownMenuItem(
                text = { Text("Fork from here") },
                leadingIcon = { Icon(Icons.Outlined.ForkRight, null) },
                onClick = { showMenu = false; onFork() }
            )
        }
    }
}

@Composable
private fun ReasoningCard(reasoning: String, durationLabel: String) {
    val colors = LocalYSCodeColors.current
    var expanded by remember { mutableStateOf(false) }
    // First heading or line of the thinking text, without markdown marks, as a one-line hint next to "Thought for 6s".
    val preview = remember(reasoning) {
        reasoning.lineSequence().map { it.trim().trim('*', '#', '_', ' ') }.firstOrNull { it.isNotBlank() }.orEmpty().take(70)
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.Psychology, null, tint = colors.info, modifier = Modifier.size(16.dp))
                    Text(durationLabel, style = MaterialTheme.typography.labelMedium, color = colors.info, maxLines = 1)
                    if (!expanded && preview.isNotEmpty()) {
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textDim,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = colors.textDim, modifier = Modifier.size(18.dp))
            }
            AnimatedVisibility(visible = expanded) {
                com.example.ui.components.MarkdownText(
                    markdown = reasoning.trim(),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskUserCard(
    question: String,
    options: List<String>,
    onAnswer: (String) -> Unit
) {
    val colors = LocalYSCodeColors.current
    var customAnswer by remember { mutableStateOf("") }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.info),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Agent Question", style = MaterialTheme.typography.labelLarge, color = colors.info)
            Text(question, style = MaterialTheme.typography.bodyMedium, color = colors.text)
            if (options.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { opt ->
                        OutlinedButton(onClick = { onAnswer(opt) }) { Text(opt) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customAnswer,
                    onValueChange = { customAnswer = it },
                    placeholder = { Text("Or type your answer...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { if (customAnswer.isNotBlank()) onAnswer(customAnswer.trim()) },
                    enabled = customAnswer.isNotBlank()
                ) {
                    Text("Send")
                }
            }
        }
    }
}
