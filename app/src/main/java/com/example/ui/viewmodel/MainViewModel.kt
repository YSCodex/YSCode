// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.agent.BackgroundAgentJob
import com.example.agent.HookDefinition
import com.example.agent.PermissionReply
import com.example.agent.SlashCommandDef
import com.example.data.AppSettings
import com.example.data.CheckpointEntity
import com.example.data.PermissionMode
import com.example.data.ProjectEntity
import com.example.data.ProjectTemplate
import com.example.data.ProviderEntity
import com.example.data.SessionEntity
import com.example.git.GitStatusResult
import com.example.linux.LinuxDiagnostics
import com.example.linux.SelfTestItem
import com.example.llm.LlmContentPart
import com.example.llm.LlmMessage
import com.example.tools.PlanAction
import com.example.util.HunkStatus
import com.example.util.JsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(
    val container: AppContainer
) : ViewModel() {

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toastEvents = _toastEvents.asSharedFlow()

    val settings: StateFlow<AppSettings> = container.settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val isOnline: StateFlow<Boolean> = container.networkMonitor.isOnline

    val projects: StateFlow<List<ProjectEntity>> = container.database.projectDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val providers: StateFlow<List<ProviderEntity>> = container.database.providerDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeProjectId = MutableStateFlow("")
    val activeProject: StateFlow<ProjectEntity?> = combine(projects, _activeProjectId) { list, id ->
        list.find { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val projectSessions: StateFlow<List<SessionEntity>> = activeProject
        .flatMapLatest { proj ->
            if (proj == null) flowOf(emptyList())
            else container.database.sessionDao().observeForProject(proj.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeSessionId = MutableStateFlow("")
    val activeSession: StateFlow<SessionEntity?> = combine(projectSessions, _activeSessionId) { list, id ->
        list.find { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val messages: StateFlow<List<LlmMessage>> = activeSession
        .distinctUntilChangedBy { it?.id }
        .flatMapLatest { sess ->
            if (sess == null) flowOf(emptyList())
            else container.database.messageDao().observeForSession(sess.id).map { entities ->
                entities.mapNotNull { e ->
                    runCatching { JsonHelper.json.decodeFromString<LlmMessage>(e.contentJson) }.getOrNull()
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val checkpoints: StateFlow<List<CheckpointEntity>> = activeSession
        .distinctUntilChangedBy { it?.id }
        .flatMapLatest { sess ->
            if (sess == null) flowOf(emptyList())
            else container.database.checkpointDao().observeForSession(sess.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyUsage: StateFlow<List<com.example.data.DailyUsage>> = container.database.usageDao()
        .observeDaily(java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun syncLinkedFolder(dir: java.io.File, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val msg = runCatching { container.projectManager.syncLinked(dir) }.getOrElse { "Sync failed: ${it.message}" }
            onDone(msg)
        }
    }

    suspend fun buildWorkLog(sessionId: String): com.example.agent.WorkLog =
        com.example.agent.WorkLogBuilder.build(container.agentLoop.loadFullHistory(sessionId))

    val agentActivity = container.agentLoop.activity
    val todoItems = container.agentLoop.todoState
    val pendingPermission = container.permissionEngine.pendingRequest
    val pendingQuestion = container.interactionBridge.pendingQuestion
    val pendingPlan = container.interactionBridge.pendingPlan
    val changes = container.changesRepository.changes
    val setupState = container.rootfsManager.setupState
    val terminalTabs = container.terminalManager.tabs
    val selectedTerminalTabId = container.terminalManager.selectedTabId
    val backgroundJobs = container.prootRunner.backgroundJobs
    val activeJobCount = container.prootRunner.activeJobCount
    val backgroundAgentJobs = container.backgroundAgentManager.jobs

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _attachments = MutableStateFlow<List<LlmContentPart.ImageBase64>>(emptyList())
    val attachments: StateFlow<List<LlmContentPart.ImageBase64>> = _attachments.asStateFlow()

    private val _projectFiles = MutableStateFlow<List<String>>(emptyList())
    val projectFiles: StateFlow<List<String>> = _projectFiles.asStateFlow()

    private val _gitStatus = MutableStateFlow(GitStatusResult(false, "local", emptyMap(), false))
    val gitStatus: StateFlow<GitStatusResult> = _gitStatus.asStateFlow()

    private val _slashCommands = MutableStateFlow<List<SlashCommandDef>>(emptyList())
    val slashCommands: StateFlow<List<SlashCommandDef>> = _slashCommands.asStateFlow()

    private val _selfTests = MutableStateFlow<List<SelfTestItem>>(emptyList())
    val selfTests: StateFlow<List<SelfTestItem>> = _selfTests.asStateFlow()

    private val _linuxDiagnostics = MutableStateFlow<LinuxDiagnostics?>(null)
    val linuxDiagnostics: StateFlow<LinuxDiagnostics?> = _linuxDiagnostics.asStateFlow()

    private val _allFilesAccess = MutableStateFlow(false)
    val allFilesAccess: StateFlow<Boolean> = _allFilesAccess.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { container.linuxEnv.syncSharedStorageBinds() }
            _allFilesAccess.value = runCatching { container.linuxEnv.hasAllFilesAccess() }.getOrDefault(false)
            val savedProj = container.settingsStore.current().activeProjectId
            if (savedProj.isNotBlank()) _activeProjectId.value = savedProj
            _slashCommands.value = container.slashCommands.loadAllCommands(null)
            refreshLinuxDiagnostics()
        }
    }

    fun emitToast(msg: String) {
        _toastEvents.tryEmit(msg)
    }

    fun updateComposerText(text: String) {
        _composerText.value = text
    }

    fun openProject(project: ProjectEntity) {
        _activeProjectId.value = project.id
        viewModelScope.launch {
            container.database.projectDao().upsert(project.copy(lastOpenedAt = System.currentTimeMillis()))
            container.settingsStore.update { it.copy(activeProjectId = project.id) }
            val sessions = container.database.sessionDao().getAll().filter { it.projectId == project.id }
            if (sessions.isEmpty()) {
                createSession(project)
            } else {
                _activeSessionId.value = sessions.first().id
            }
            val dir = if (project.storageMode == ProjectEntity.STORAGE_LOCAL_PATH) {
                File(project.localPath ?: project.path).also { container.linuxEnv.registerProjectDir(it) }
            } else {
                File(project.path)
            }
            container.terminalManager.selectedTabId.value.let { tabId ->
                if (tabId.isNotBlank() && tabId != "agent_mirror") {
                    container.terminalManager.setWorkingDir(tabId, dir)
                }
            }
            refreshProjectContext(dir)
            container.skillRepository.rescan(dir)
        }
    }

    fun refreshProjectContext(projectDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!projectDir.exists()) return@launch
            val files = projectDir.walkTopDown()
                .onEnter { d -> d == projectDir || d.name !in setOf(".git", "node_modules", "build", ".gradle") }
                .filter { it.isFile }
                .map { it.relativeTo(projectDir).path.replace(File.separatorChar, '/') }
                .take(400)
                .toList()
            _projectFiles.value = files
            _gitStatus.value = container.gitHelper.getStatus(projectDir)
            _slashCommands.value = container.slashCommands.loadAllCommands(projectDir)
        }
    }

    fun createProjectFromTemplate(name: String, template: ProjectTemplate, onCreated: (ProjectEntity) -> Unit) {
        viewModelScope.launch {
            container.projectManager.createFromTemplate(name, template).fold(
                onSuccess = { proj ->
                    openProject(proj)
                    onCreated(proj)
                },
                onFailure = { emitToast("Create failed: ${it.message}") }
            )
        }
    }

    fun cloneProjectFromGit(url: String, customName: String, onCreated: (ProjectEntity) -> Unit) {
        viewModelScope.launch {
            emitToast("Cloning repository...")
            container.projectManager.cloneFromGit(url, customName).fold(
                onSuccess = { proj ->
                    openProject(proj)
                    emitToast("Cloned ${proj.name}")
                    onCreated(proj)
                },
                onFailure = { emitToast("Clone failed: ${it.message}") }
            )
        }
    }

    fun importProjectZip(uri: Uri, nameHint: String, onCreated: (ProjectEntity) -> Unit) {
        viewModelScope.launch {
            container.projectManager.importFromZip(uri, nameHint).fold(
                onSuccess = { proj ->
                    openProject(proj)
                    emitToast("Imported ${proj.name}")
                    onCreated(proj)
                },
                onFailure = { emitToast("Import ZIP failed: ${it.message}") }
            )
        }
    }

    fun importProjectFolder(treeUri: Uri, onCreated: (ProjectEntity) -> Unit) {
        viewModelScope.launch {
            container.projectManager.importFromSafFolder(treeUri).fold(
                onSuccess = { proj ->
                    openProject(proj)
                    emitToast(
                        if (proj.storageMode == ProjectEntity.STORAGE_LOCAL_PATH) "Opened ${proj.name} in place"
                        else "Imported ${proj.name} as a synced copy (grant All files access to work in place)"
                    )
                    onCreated(proj)
                },
                onFailure = { emitToast("Open folder failed: ${it.message}") }
            )
        }
    }

    fun openLocalFolder(path: String, onCreated: (ProjectEntity) -> Unit) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            emitToast("Enter a folder path")
            return
        }
        viewModelScope.launch {
            val dir = container.linuxEnv.resolveProjectPath(trimmed, container.linuxEnv.rootHomeDir)
            container.projectManager.addLocalFolder(dir).fold(
                onSuccess = { proj ->
                    openProject(proj)
                    emitToast("Opened ${proj.name}")
                    onCreated(proj)
                },
                onFailure = { emitToast("Open folder failed: ${it.message}") }
            )
        }
    }

    fun togglePinProject(project: ProjectEntity) {
        viewModelScope.launch {
            container.database.projectDao().upsert(project.copy(pinned = !project.pinned))
        }
    }

    fun renameProject(project: ProjectEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            container.database.projectDao().upsert(project.copy(name = newName.trim()))
        }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch {
            container.projectManager.deleteProject(project)
            emitToast(
                if (project.storageMode == ProjectEntity.STORAGE_LOCAL_PATH) "Removed ${project.name} (folder kept)"
                else "Deleted ${project.name}"
            )
        }
    }

    fun shareProjectZip(context: Context, project: ProjectEntity) {
        viewModelScope.launch {
            container.projectManager.createShareableProjectZip(project).fold(
                onSuccess = { uri ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share ${project.name}.zip"))
                },
                onFailure = { emitToast("Export failed: ${it.message}") }
            )
        }
    }

    fun createSession(project: ProjectEntity? = activeProject.value) {
        val proj = project ?: return
        viewModelScope.launch {
            val s = settings.value
            val sess = SessionEntity(
                id = "sess_" + UUID.randomUUID().toString().take(8),
                projectId = proj.id,
                title = "New Session",
                providerId = s.activeProviderId,
                mode = s.defaultMode.id
            )
            container.database.sessionDao().upsert(sess)
            _activeSessionId.value = sess.id
            container.permissionEngine.resetSessionPermissions()
            container.agentLoop.todoState.value = emptyList()
        }
    }

    fun selectSession(session: SessionEntity) {
        _activeSessionId.value = session.id
        container.permissionEngine.resetSessionPermissions()
    }

    fun renameSession(session: SessionEntity, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            container.database.sessionDao().upsert(session.copy(title = newTitle.trim(), updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteSession(session: SessionEntity) {
        viewModelScope.launch {
            container.database.sessionDao().deleteById(session.id)
            if (_activeSessionId.value == session.id) {
                _activeSessionId.value = ""
            }
        }
    }

    fun forkSessionFromMessage(session: SessionEntity, messageId: String) {
        viewModelScope.launch {
            val existing = container.database.messageDao().getForSession(session.id)
            val cutIdx = existing.indexOfFirst { it.id == messageId }
            val slice = if (cutIdx >= 0) existing.take(cutIdx + 1) else existing
            val newSessionId = "sess_" + UUID.randomUUID().toString().take(8)
            val forked = session.copy(
                id = newSessionId,
                title = "${session.title} (Fork)",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            container.database.sessionDao().upsert(forked)
            container.database.messageDao().upsertAll(
                slice.map { it.copy(id = "msg_" + UUID.randomUUID().toString().take(10), sessionId = newSessionId) }
            )
            _activeSessionId.value = newSessionId
            emitToast("Forked session")
        }
    }

    fun exportSessionMarkdown(session: SessionEntity): String {
        val msgs = messages.value
        return buildString {
            appendLine("# ${session.title}")
            appendLine("Model: ${session.model.ifBlank { "default" }} | Tokens: ${session.tokensIn} in / ${session.tokensOut} out")
            appendLine()
            for (m in msgs) {
                appendLine("## ${m.role.uppercase()}")
                m.parts.forEach { p ->
                    when (p) {
                        is LlmContentPart.Text -> appendLine(p.text)
                        is LlmContentPart.ToolUse -> appendLine("Tool call: `${p.name}` `${p.argumentsJson}`")
                        is LlmContentPart.ToolResult -> appendLine("Result (${p.toolName}):\n```\n${p.output.take(1500)}\n```")
                        is LlmContentPart.Reasoning -> appendLine("> Thinking: ${p.text.take(600)}")
                        is LlmContentPart.ImageBase64 -> appendLine("[Image: ${p.fileName}]")
                    }
                }
                appendLine()
            }
        }
    }

    fun setSessionModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(activeProviderId = providerId) }
            activeSession.value?.let { sess ->
                container.database.sessionDao().upsert(
                    sess.copy(providerId = providerId, model = modelId, updatedAt = System.currentTimeMillis())
                )
            }
        }
    }

    fun selectOrAddCustomModel(providerId: String, customModelId: String, saveToProvider: Boolean = true) {
        val cleanId = customModelId.trim()
        if (cleanId.isBlank()) return
        viewModelScope.launch {
            val targetProv = container.database.providerDao().getById(providerId)
                ?: container.database.providerDao().getAll().firstOrNull()
                ?: run {
                    emitToast("Add a provider first")
                    return@launch
                }
            if (saveToProvider) {
                val existing = runCatching {
                    JsonHelper.json.decodeFromString<List<com.example.llm.ModelConfig>>(targetProv.modelsJson)
                }.getOrDefault(emptyList())
                if (existing.none { it.id == cleanId }) {
                    val updated = listOf(com.example.llm.ModelConfig(id = cleanId, displayName = cleanId)) + existing
                    container.database.providerDao().upsert(
                        targetProv.copy(
                            modelsJson = JsonHelper.json.encodeToString(updated),
                            defaultModel = targetProv.defaultModel.ifBlank { cleanId }
                        )
                    )
                }
            }
            setSessionModel(targetProv.id, cleanId)
            emitToast("Switched model to $cleanId")
        }
    }

    fun setPermissionMode(mode: PermissionMode) {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(defaultMode = mode) }
            activeSession.value?.let { sess ->
                container.database.sessionDao().upsert(sess.copy(mode = mode.id))
            }
        }
    }

    fun confirmYoloFirstTime() {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(yoloConfirmedOnce = true) }
        }
    }

    fun attachImageUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val tmp = java.io.File(context.cacheDir, "attach_${System.nanoTime()}")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: run { emitToast("Cannot open the image"); return@launch }
                if (tmp.length() > 40L * 1024L * 1024L) { emitToast("Image is larger than 40 MB"); return@launch }
                if (com.example.util.ImageLoader.sniffMime(tmp) == null) {
                    emitToast("Unsupported file: use a PNG, JPEG, GIF or WebP image")
                    return@launch
                }
                // Rotate by EXIF, downscale to <= 1568 px and re-encode: smaller, faster and cheaper for the model.
                val loaded = com.example.util.ImageLoader.load(tmp)
                _attachments.value = _attachments.value +
                    LlmContentPart.ImageBase64(loaded.mime, loaded.base64, "image_${_attachments.value.size + 1}")
            } catch (t: Throwable) {
                emitToast("Failed to attach image: ${t.message}")
            } finally {
                tmp.delete()
            }
        }
    }

    fun attachFileContent(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@runCatching
                val snippet = "\n```\n${text.take(6000)}\n```\n"
                _composerText.value = (_composerText.value + snippet).trimStart()
            }.onFailure { emitToast("Failed to read file: ${it.message}") }
        }
    }

    fun removeAttachment(idx: Int) {
        _attachments.value = _attachments.value.filterIndexed { i, _ -> i != idx }
    }

    fun sendCurrentMessage() {
        val raw = _composerText.value.trim()
        val atts = _attachments.value
        if (raw.isEmpty() && atts.isEmpty()) return
        val proj = activeProject.value ?: return
        val sess = activeSession.value ?: return
        val projDir = File(proj.path)

        // `!` shell shortcut in composer
        if (raw.startsWith("!")) {
            val cmd = raw.removePrefix("!").trim()
            _composerText.value = ""
            if (cmd.isNotEmpty()) {
                val tabId = selectedTerminalTabId.value.takeIf { it.isNotBlank() && it != "agent_mirror" }
                    ?: container.terminalManager.newTab(projDir)
                container.terminalManager.runCommand(tabId, cmd)
                emitToast("Ran in Terminal: $cmd")
            }
            return
        }

        // `/bg <task>` background agent shortcut
        if (raw.startsWith("/bg ", ignoreCase = true)) {
            val bgPrompt = raw.removeRange(0, 3).trim()
            _composerText.value = ""
            _attachments.value = emptyList()
            if (bgPrompt.isNotEmpty()) {
                val job = container.agentLoop.launchBackgroundTask(
                    session = sess,
                    projectDir = projDir,
                    role = "coder",
                    prompt = bgPrompt,
                    mode = settings.value.defaultMode
                )
                emitToast("Started background agent ${job.id}")
            }
            return
        }

        // Custom slash command expansion
        val expanded = if (raw.startsWith("/")) {
            container.slashCommands.expandCustomCommand(raw, projDir) ?: raw
        } else raw

        _composerText.value = ""
        _attachments.value = emptyList()
        container.agentLoop.enqueueOrRun(
            session = sess,
            projectDir = projDir,
            userText = expanded,
            attachments = atts,
            mode = settings.value.defaultMode
        )
    }

    fun launchBackgroundAgent(role: String, prompt: String) {
        val proj = activeProject.value ?: return
        val sess = activeSession.value ?: return
        val clean = prompt.trim()
        if (clean.isEmpty()) return
        val job = container.agentLoop.launchBackgroundTask(
            session = sess,
            projectDir = File(proj.path),
            role = role,
            prompt = clean,
            mode = settings.value.defaultMode
        )
        emitToast("Started background [$role] ${job.id}")
    }

    fun cancelBackgroundAgent(jobId: String) {
        container.backgroundAgentManager.cancelJob(jobId)
    }

    fun dismissBackgroundAgent(jobId: String) {
        container.backgroundAgentManager.dismissJob(jobId)
    }

    fun clearFinishedBackgroundAgents() {
        container.backgroundAgentManager.clearFinished()
    }

    fun injectBackgroundResult(job: BackgroundAgentJob) {
        val summary = "[Background ${job.role} result (${job.id})]:\n${job.output.take(3000)}"
        _composerText.value = summary
    }

    fun runProjectAutoTestNow(onResult: (String) -> Unit = {}) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            val dir = File(proj.path)
            val s = settings.value
            val cmd = container.autoTestRunner.detectTestCommand(dir, s.customTestCommand)
            if (cmd == null) {
                emitToast("No test framework detected")
                onResult("No test framework detected (Node, Python, Gradle, Rust, Go).")
                return@launch
            }
            emitToast("Running $cmd...")
            val res = container.autoTestRunner.runDetectedTests(dir, cmd, s.customEnvVars)
            val msg = if (res.passed) "Tests passed ($cmd in ${res.durationMs}ms)" else "Tests failed (exit ${res.exitCode})"
            emitToast(msg)
            onResult("$msg\n\n${res.output}")
        }
    }

    fun buildRepoMapPreview(onReady: (String) -> Unit) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            val map = container.repoMapIndex.formatRepoMap(
                projectDir = File(proj.path),
                maxChars = 6000,
                useTreeSitterIfAvailable = settings.value.useTreeSitterRepoMap
            )
            onReady(map)
        }
    }

    suspend fun loadProjectHooks(): List<HookDefinition> {
        val proj = activeProject.value ?: return emptyList()
        return container.hookEngine.loadProjectHooks(File(proj.path))
    }

    fun saveProjectHooks(hooks: List<HookDefinition>) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            container.hookEngine.saveProjectHooks(File(proj.path), hooks)
            emitToast("Saved ${hooks.size} project hook(s)")
        }
    }

    fun readProjectMemoryText(): String {
        val proj = activeProject.value ?: return ""
        return container.memoryManager.loadProjectMemory(File(proj.path))
    }

    fun stopAgent() {
        container.agentLoop.stop()
    }

    fun killAllProcesses() {
        container.agentLoop.stop()
        container.backgroundAgentManager.cancelAll()
        container.prootRunner.killAll()
        emitToast("All running agent & shell jobs stopped")
    }

    fun respondPermission(reply: PermissionReply) {
        container.permissionEngine.respond(reply)
    }

    fun submitUserAnswer(answer: String) {
        container.interactionBridge.submitAnswer(answer)
    }

    fun submitPlanDecision(action: PlanAction, feedback: String = "") {
        container.interactionBridge.submitPlanDecision(action, feedback)
    }

    fun undoLastCheckpoint() {
        val proj = activeProject.value ?: return
        val sess = activeSession.value ?: return
        viewModelScope.launch {
            container.checkpointManager.undoLast(sess.id, File(proj.path)).fold(
                onSuccess = {
                    refreshProjectContext(File(proj.path))
                    emitToast("Restored checkpoint: ${it.label}")
                },
                onFailure = { emitToast("Undo failed: ${it.message}") }
            )
        }
    }

    fun restoreCheckpoint(checkpoint: CheckpointEntity) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            container.checkpointManager.restoreCheckpoint(checkpoint, File(proj.path)).fold(
                onSuccess = {
                    refreshProjectContext(File(proj.path))
                    emitToast("Restored to '${checkpoint.label}'")
                },
                onFailure = { emitToast("Restore failed: ${it.message}") }
            )
        }
    }

    fun initProjectMemory() {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            val f = container.projectManager.generateYsCodeMd(File(proj.path))
            refreshProjectContext(File(proj.path))
            emitToast("Generated ${f.name}")
        }
    }

    fun clearCurrentSession() {
        val sess = activeSession.value ?: return
        viewModelScope.launch {
            container.database.messageDao().deleteForSession(sess.id)
            emitToast("Cleared session messages")
        }
    }

    fun testVision() {
        val sess = activeSession.value ?: return
        emitToast("Testing vision, please wait...")
        viewModelScope.launch {
            val result = runCatching { container.agentLoop.testVision(sess.id) }.getOrElse { "Vision test failed: ${it.message}" }
            emitToast(result)
        }
    }

    fun compactCurrentSession() {
        val sess = activeSession.value ?: return
        viewModelScope.launch {
            container.agentLoop.compactSessionManually(sess.id).fold(
                onSuccess = { emitToast(it) },
                onFailure = { emitToast("Compact failed: ${it.message}") }
            )
        }
    }

    fun setHunkStatus(relativePath: String, hunkId: Int, status: HunkStatus) {
        container.changesRepository.setHunkStatus(relativePath, hunkId, status)
        activeProject.value?.let { refreshProjectContext(File(it.path)) }
    }

    fun acceptAllChanges() {
        container.changesRepository.acceptAll()
        emitToast("Accepted all pending changes")
    }

    fun revertAllChanges() {
        container.changesRepository.revertAll()
        activeProject.value?.let { refreshProjectContext(File(it.path)) }
        emitToast("Reverted all pending changes")
    }

    fun commitChanges(message: String, push: Boolean) {
        val proj = activeProject.value ?: return
        viewModelScope.launch {
            val dir = File(proj.path)
            container.gitHelper.commit(dir, message).fold(
                onSuccess = { out ->
                    if (push) {
                        container.gitHelper.pushOrPull(dir, push = true).fold(
                            onSuccess = { emitToast("$out & pushed") },
                            onFailure = { emitToast("$out (push failed: ${it.message})") }
                        )
                    } else {
                        emitToast(out)
                    }
                    refreshProjectContext(dir)
                },
                onFailure = { emitToast("Commit failed: ${it.message}") }
            )
        }
    }

    suspend fun generateAiCommitMessage(): String {
        val proj = activeProject.value ?: return "chore: update files"
        val diffText = changes.value.joinToString("\n") {
            "File: ${it.relativePath} (+${it.diff.addedCount} -${it.diff.removedCount})"
        }.ifBlank { "Updated project files in ${proj.name}" }
        return container.agentLoop.generateCommitMessage(File(proj.path), diffText)
            .getOrElse { "chore: update ${proj.name}" }
    }

    fun initializeLinuxEnvironment(installPackages: Boolean = true, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            container.rootfsManager.initializeEnvironment(installPackages).onSuccess {
                container.settingsStore.update { it.copy(rootfsInitialized = true) }
                container.skillRepository.ensureStarterSkillsInstalled()
                refreshLinuxDiagnostics()
                onDone()
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            container.settingsStore.update { it.copy(onboardingCompleted = true, rootfsInitialized = true) }
        }
    }

    fun runLinuxSelfTest() {
        viewModelScope.launch {
            _selfTests.value = container.rootfsManager.runSelfTest(container.prootRunner)
        }
    }

    fun refreshLinuxDiagnostics() {
        viewModelScope.launch {
            _linuxDiagnostics.value = container.rootfsManager.getDiagnostics()
            _allFilesAccess.value = runCatching { container.linuxEnv.syncSharedStorageBinds() }.getOrDefault(false)
        }
    }

    fun repairLinux() {
        viewModelScope.launch {
            container.rootfsManager.repairEnvironment().fold(
                onSuccess = { refreshLinuxDiagnostics(); emitToast("Linux directories repaired") },
                onFailure = { emitToast("Repair failed: ${it.message}") }
            )
        }
    }

    fun resetLinux() {
        viewModelScope.launch {
            container.rootfsManager.resetRootfs(preserveProjects = true).fold(
                onSuccess = {
                    container.skillRepository.ensureStarterSkillsInstalled()
                    refreshLinuxDiagnostics()
                    emitToast("Rootfs reset (projects preserved)")
                },
                onFailure = { emitToast("Reset failed: ${it.message}") }
            )
        }
    }

    fun importRootfsTarGz(uri: Uri) {
        viewModelScope.launch {
            container.rootfsManager.importRootfsTarGz(uri).fold(
                onSuccess = { count ->
                    refreshLinuxDiagnostics()
                    emitToast("Imported rootfs ($count entries)")
                },
                onFailure = { emitToast("Rootfs import failed: ${it.message}") }
            )
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(container) as T
        }
    }
}
