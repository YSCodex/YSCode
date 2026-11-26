// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import android.content.Context
import com.example.data.MessageDao
import com.example.data.MessageEntity
import com.example.data.PermissionMode
import com.example.data.ProviderDao
import com.example.data.SessionDao
import com.example.data.SessionEntity
import com.example.data.UsageDao
import com.example.data.UsageEventEntity
import com.example.data.SettingsStore
import com.example.git.ChangesRepository
import com.example.git.CheckpointManager
import com.example.linux.LinuxEnvironment
import com.example.linux.ProotRunner
import com.example.linux.ShellService
import com.example.llm.ChatRequest
import com.example.llm.LlmClient
import com.example.llm.LlmClientFactory
import com.example.llm.LlmContentPart
import com.example.llm.LlmEvent
import com.example.llm.LlmMessage
import com.example.tools.AgentInteractionBridge
import com.example.tools.ReadTracker
import com.example.tools.Risk
import com.example.tools.TodoItem
import com.example.tools.ToolContext
import com.example.tools.ToolDiffInfo
import com.example.tools.ToolRegistry
import com.example.util.JsonHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class StreamingToolState(
    val id: String,
    val name: String,
    val argsPreview: String,
    val status: String, // "running", "done", "error", "denied"
    val outputPreview: String = "",
    val fullOutput: String = "",
    val summary: String = "",
    val durationMs: Long = 0L,
    val diff: ToolDiffInfo? = null
)

data class AgentActivityState(
    val isRunning: Boolean = false,
    val currentPhase: String = "",
    val currentToolName: String? = null,
    val startedAtMs: Long = 0L,
    val stepCount: Int = 0,
    val maxSteps: Int = 50,
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val activeTools: List<StreamingToolState> = emptyList(),
    val queuedPrompts: List<String> = emptyList(),
    val lastError: String? = null,
    val contextTokensEstimate: Long = 0L,
    val lastAutoTestResult: AutoTestResult? = null,
    val lastHookNotice: String? = null,
    val lastMemoryUpdate: MemoryUpdateResult? = null
)

class AgentLoop(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val providerDao: ProviderDao,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val clientFactory: LlmClientFactory,
    private val toolRegistry: ToolRegistry,
    private val permissionEngine: PermissionEngine,
    private val promptBuilder: PromptBuilder,
    private val contextManager: ContextManager,
    private val checkpointManager: CheckpointManager,
    private val changesRepository: ChangesRepository,
    private val env: LinuxEnvironment,
    private val runner: ProotRunner,
    private val interactionBridge: AgentInteractionBridge,
    private val hookEngine: HookEngine,
    private val autoTestRunner: AutoTestRunner,
    private val memoryManager: MemoryManager,
    private val backgroundAgentManager: BackgroundAgentManager,
    private val usageDao: UsageDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    /** Counts near-identical probing calls within one user turn (digits normalised) to break runaway loops. */
    private val loopCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val loopGuardedTools = setOf("bash", "grep", "web_search", "web_fetch")

    /** Images returned by tools this step; forwarded to the model as a user message right after the tool results. */
    private val pendingImages = java.util.concurrent.ConcurrentLinkedQueue<LlmContentPart.ImageBase64>()

    init {
        // Heads-up notifications when the agent needs the user while the app is in the background.
        scope.launch {
            permissionEngine.pendingRequest.collect { req ->
                if (req != null && !com.example.util.AppForeground.isForeground) {
                    com.example.util.AgentNotifier.notify(
                        context, com.example.util.AgentNotifier.ID_APPROVAL,
                        "YSCode needs your approval", req.summary
                    )
                } else if (req == null) {
                    com.example.util.AgentNotifier.cancel(context, com.example.util.AgentNotifier.ID_APPROVAL)
                }
            }
        }
        scope.launch {
            interactionBridge.pendingQuestion.collect { q ->
                if (q != null && !com.example.util.AppForeground.isForeground) {
                    com.example.util.AgentNotifier.notify(
                        context, com.example.util.AgentNotifier.ID_QUESTION,
                        "YSCode has a question", "Open the app to answer the agent."
                    )
                } else if (q == null) {
                    com.example.util.AgentNotifier.cancel(context, com.example.util.AgentNotifier.ID_QUESTION)
                }
            }
        }
    }

    private fun smartTitle(raw: String): String {
        val gh = Regex("github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)").find(raw)
        val base = raw.lines().firstOrNull { it.isNotBlank() }.orEmpty()
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[`*#>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val joined = when {
            gh != null -> (base.trimEnd('-', ':', ' ').ifBlank { "GitHub" } + " " + gh.groupValues[2].removeSuffix(".git")).trim()
            base.isBlank() -> "Session"
            else -> base
        }
        if (joined.length <= 44) return joined
        val cut = joined.take(44)
        return (cut.substringBeforeLast(' ', cut).ifBlank { cut }).trimEnd() + "..."
    }

    private fun notifyRunEnd(projectName: String) {
        if (com.example.util.AppForeground.isForeground) return
        val a = _activity.value
        if (a.currentPhase == "Error" && a.lastError != null) {
            com.example.util.AgentNotifier.notify(
                context, com.example.util.AgentNotifier.ID_DONE,
                "YSCode: error in $projectName", a.lastError.take(160)
            )
        } else {
            com.example.util.AgentNotifier.notify(
                context, com.example.util.AgentNotifier.ID_DONE,
                "YSCode: task finished", "$projectName - tap to review the changes"
            )
        }
    }
    private val monotonicClock = AtomicLong(System.currentTimeMillis())

    private val _activity = MutableStateFlow(AgentActivityState())
    val activity: StateFlow<AgentActivityState> = _activity.asStateFlow()

    val todoState = MutableStateFlow<List<TodoItem>>(emptyList())
    private val readTracker = ReadTracker()

    private fun nextTimestampMs(): Long {
        val now = System.currentTimeMillis()
        while (true) {
            val prev = monotonicClock.get()
            val next = if (now > prev) now else prev + 1L
            if (monotonicClock.compareAndSet(prev, next)) return next
        }
    }

    fun enqueueOrRun(
        session: SessionEntity,
        projectDir: File,
        userText: String,
        attachments: List<LlmContentPart.ImageBase64> = emptyList(),
        mode: PermissionMode
    ) {
        if (_activity.value.isRunning) {
            _activity.value = _activity.value.copy(
                queuedPrompts = _activity.value.queuedPrompts + userText
            )
            return
        }
        startTurn(session, projectDir, userText, attachments, mode)
    }

    fun launchBackgroundTask(
        session: SessionEntity,
        projectDir: File,
        role: String,
        prompt: String,
        mode: PermissionMode
    ): BackgroundAgentJob {
        val dummyCtx = ToolContext(
            projectDir = projectDir,
            env = env,
            runner = runner,
            changesRepository = changesRepository,
            readTracker = readTracker,
            todoState = todoState,
            mode = mode,
            sessionId = session.id,
            isSubagent = true
        )
        return backgroundAgentManager.launchBackgroundAgent(
            sessionId = session.id,
            projectDir = projectDir,
            role = role,
            prompt = prompt,
            useWakeLock = true
        ) { onProgress ->
            runSubagentInternal(
                prompt = prompt,
                role = role,
                allowWrite = role == "coder",
                modelOverride = null,
                parentCtx = dummyCtx,
                onProgress = onProgress
            )
        }
    }

    fun clearQueue() {
        _activity.value = _activity.value.copy(queuedPrompts = emptyList())
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        runner.killAll()
        permissionEngine.cancelPending()
        interactionBridge.cancelAll()
        if (backgroundAgentManager.jobs.value.none { it.status == BackgroundJobStatus.RUNNING }) {
            ShellService.stop(context)
        }
        _activity.value = _activity.value.copy(
            isRunning = false,
            currentPhase = "Stopped",
            currentToolName = null,
            streamingText = "",
            streamingReasoning = "",
            activeTools = emptyList()
        )
    }

    private fun startTurn(
        session: SessionEntity,
        projectDir: File,
        userText: String,
        attachments: List<LlmContentPart.ImageBase64>,
        mode: PermissionMode
    ) {
        currentJob = scope.launch {
            val settings = settingsStore.current()
            if (settings.wakeLockDuringJobs) {
                ShellService.start(context, "YSCode agent working on ${projectDir.name}", true)
            }

            try {
                runLoopInternal(session, projectDir, userText, attachments, mode)
                while (_activity.value.queuedPrompts.isNotEmpty()) {
                    val nextPrompt = _activity.value.queuedPrompts.first()
                    _activity.value = _activity.value.copy(queuedPrompts = _activity.value.queuedPrompts.drop(1))
                    val refreshedSession = sessionDao.getById(session.id) ?: session
                    runLoopInternal(refreshedSession, projectDir, nextPrompt, emptyList(), mode)
                }
                notifyRunEnd(projectDir.name)
            } catch (_: CancellationException) {
                // Handled in stop()
            } catch (t: Throwable) {
                _activity.value = _activity.value.copy(
                    isRunning = false,
                    activeTools = emptyList(),
                    currentPhase = "Error",
                    lastError = t.message ?: "Unexpected agent error"
                )
                notifyRunEnd(projectDir.name)
            } finally {
                // Write the agent's edits and new files back to the user's linked local folder.
                runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        com.example.data.FolderSync(context).push(projectDir)
                    }
                }
                if (backgroundAgentManager.jobs.value.none { it.status == BackgroundJobStatus.RUNNING }) {
                    ShellService.stop(context)
                }
            }
        }
    }

    private suspend fun runLoopInternal(
        session: SessionEntity,
        projectDir: File,
        userText: String,
        attachments: List<LlmContentPart.ImageBase64>,
        mode: PermissionMode
    ) {
        val settings = settingsStore.current()
        val provider = resolveProvider(session.providerId, settings.activeProviderId)
            ?: run {
                _activity.value = AgentActivityState(
                    isRunning = false,
                    lastError = "No LLM provider configured. Add a provider in Settings -> Providers."
                )
                return
            }

        val models = clientFactory.parseModels(provider)
        val modelId = session.model.ifBlank { provider.defaultModel.ifBlank { models.firstOrNull()?.id.orEmpty() } }
        if (modelId.isBlank()) {
            _activity.value = AgentActivityState(
                isRunning = false,
                lastError = "No model selected for ${provider.name}."
            )
            return
        }
        val modelConfig = models.find { it.id == modelId }
        val supportsTools = modelConfig?.supportsTools ?: true
        val contextWindow = modelConfig?.contextWindow ?: 128_000

        val userParts = buildList {
            if (userText.isNotBlank()) add(LlmContentPart.Text(userText))
            addAll(attachments)
        }
        val userMsgId = "msg_" + UUID.randomUUID().toString().take(10)
        val userMsg = LlmMessage(
            id = userMsgId,
            role = "user",
            parts = userParts,
            createdAt = nextTimestampMs()
        )

        checkpointManager.createCheckpoint(session.id, userMsgId, projectDir, userText)
        persistMessage(session.id, userMsg)

        // Auto-title session on first meaningful user prompt
        if (session.title == "New Session" || session.title.isBlank()) {
            val cleanTitle = smartTitle(userText)
            sessionDao.upsert(session.copy(title = cleanTitle, updatedAt = System.currentTimeMillis()))
        }

        val isCasualChat = attachments.isEmpty() && PromptBuilder.isCasualGreetingOrSmallTalk(userText)
        val primaryClient = clientFactory.create(provider)
        var history = loadSessionHistory(session.id)
        var toolSpecs = if (supportsTools && !isCasualChat) toolRegistry.getActiveToolSpecs() else emptyList()
        var systemPrompt = promptBuilder.buildSystemPrompt(
            projectDir = projectDir,
            mode = mode,
            availableTools = toolSpecs.map { it.name },
            isCasualMessage = isCasualChat,
            useTreeSitter = settings.useTreeSitterRepoMap
        )

        _activity.value = _activity.value.copy(
            isRunning = true,
            currentPhase = if (isCasualChat) "Replying" else "Thinking",
            currentToolName = null,
            startedAtMs = System.currentTimeMillis(),
            stepCount = 0,
            maxSteps = settings.maxSteps,
            streamingText = "",
            streamingReasoning = "",
            activeTools = emptyList(),
            lastError = null,
            lastAutoTestResult = null,
            lastHookNotice = null,
            lastMemoryUpdate = null
        )

        loopCounts.clear()
        pendingImages.clear()
        // Keep the app-side copy of a linked local folder fresh before the agent starts working.
        runCatching { com.example.data.FolderSync(context).pull(projectDir) }
        var editedCodeThisTurn = false
        var testRetryCount = 0
        var verifiedTestCommand: String? = null

        val modelLower = modelId.lowercase()
        val visionOk = if (modelConfig != null) com.example.llm.VisionSupport.effective(modelConfig.id, modelConfig.supportsVision)
        else com.example.llm.VisionSupport.inferVision(modelId)
        val isReasoningModel = settings.reasoningEffort.isNotBlank() || listOf("reason", "r1", "think", "o1", "o3", "o4", "gpt-5", "qwq", "mimo").any { modelLower.contains(it) }
        var effectiveMaxTokens = if (isReasoningModel) maxOf(settings.maxTokensPerTurn, 16384) else settings.maxTokensPerTurn
        var truncStreak = 0
        var maxTokErrRetries = 0
        var ctxErrRetries = 0

        for (step in 1..settings.maxSteps) {
            if (contextManager.needsCompaction(systemPrompt, contextManager.slimForRequest(history), contextWindow, settings.autoCompactThreshold)) {
                _activity.value = _activity.value.copy(currentPhase = "Compacting context")
                history = contextManager.compact(history, primaryClient, modelId)
                replaceSessionWithCompacted(session.id, history)
            }

            val slimmed = contextManager.slimForRequest(history)
            val requestMessages = if (visionOk) slimmed else contextManager.stripImages(slimmed)
            val estTokens = contextManager.estimateHistoryTokens(systemPrompt, requestMessages)
            _activity.value = _activity.value.copy(
                stepCount = step,
                currentPhase = if (isCasualChat) "Replying" else "Streaming response",
                streamingText = "",
                streamingReasoning = "",
                activeTools = emptyList(),
                contextTokensEstimate = estTokens
            )

            val chatReq = ChatRequest(
                model = modelId,
                system = systemPrompt,
                messages = requestMessages,
                tools = toolSpecs,
                temperature = settings.temperature,
                maxTokens = effectiveMaxTokens,
                reasoningEffort = settings.reasoningEffort,
                supportsTools = supportsTools && !isCasualChat,
                projectDir = projectDir,
                sessionId = session.id
            )

            val streamOutcome = streamWithRetryAndFallback(primaryClient, chatReq, settings) { a, t ->
                persistMessage(session.id, a)
                persistMessage(session.id, t)
                history = history + a + t
            }
            if (streamOutcome.error != null) {
                val errText = streamOutcome.error
                if (maxTokErrRetries < 3 && effectiveMaxTokens > 4096 &&
                    Regex("(?i)max_tokens|max_completion_tokens|maximum (allowed )?(number of )?(output |completion )?tokens|output token limit").containsMatchIn(errText)
                ) {
                    // Provider rejected the output limit for this model: halve it and retry the same step.
                    maxTokErrRetries++
                    effectiveMaxTokens = (effectiveMaxTokens / 2).coerceAtLeast(4096)
                    continue
                }
                if (ctxErrRetries < 2 &&
                    Regex("(?i)context.?length|maximum context|too many tokens|prompt is too long").containsMatchIn(errText)
                ) {
                    ctxErrRetries++
                    _activity.value = _activity.value.copy(currentPhase = "Compacting context")
                    history = contextManager.compact(history, primaryClient, modelId)
                    replaceSessionWithCompacted(session.id, history)
                    continue
                }
                _activity.value = _activity.value.copy(
                    isRunning = false,
                    currentPhase = "Error",
                    activeTools = emptyList(),
                    lastError = streamOutcome.error
                )
                return
            }

            // Some providers never report usage while streaming: fall back to a local estimate so stats stay meaningful.
            val usageReported = streamOutcome.inputTokens > 0 || streamOutcome.outputTokens > 0
            val outChars = streamOutcome.assistantMessage.parts.sumOf { part ->
                when (part) {
                    is LlmContentPart.Text -> part.text.length
                    is LlmContentPart.Reasoning -> part.text.length
                    is LlmContentPart.ToolUse -> part.argumentsJson.length
                    else -> 0
                }
            }
            val usedIn = if (usageReported) streamOutcome.inputTokens else estTokens.toLong()
            val usedOut = if (usageReported) streamOutcome.outputTokens else (outChars / 4L).coerceAtLeast(if (outChars > 0) 1L else 0L)
            if (usedIn > 0 || usedOut > 0) {
                val cur = sessionDao.getById(session.id) ?: session
                val addCost = ((usedIn / 1_000_000.0) * (modelConfig?.inputPricePer1M ?: 0.0)) +
                    ((usedOut / 1_000_000.0) * (modelConfig?.outputPricePer1M ?: 0.0))
                sessionDao.upsert(
                    cur.copy(
                        tokensIn = cur.tokensIn + usedIn,
                        tokensOut = cur.tokensOut + usedOut,
                        estimatedCostUsd = cur.estimatedCostUsd + addCost,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                runCatching {
                    usageDao.insert(
                        UsageEventEntity(
                            sessionId = session.id,
                            projectId = session.projectId,
                            model = cur.model,
                            ts = System.currentTimeMillis(),
                            tokensIn = usedIn,
                            tokensOut = usedOut,
                            costUsd = addCost,
                            messages = 1
                        )
                    )
                }
            }

            val finish = streamOutcome.finishReason.lowercase()
            val hitLimit = finish == "length" || finish == "max_tokens" || finish == "max_output_tokens"
            val streamBroke = finish == "error"
            val partsAll = streamOutcome.assistantMessage.parts
            val hadCalls = partsAll.any { it is LlmContentPart.ToolUse }
            val hasText = partsAll.any { it is LlmContentPart.Text && it.text.isNotBlank() }
            val emptyReply = !hasText && !hadCalls && !streamOutcome.externalLive
            val needsRecovery = hitLimit || streamBroke || emptyReply

            if (needsRecovery) {
                // Never execute tool calls from a cut-off response (their JSON arguments may be incomplete).
                val keptParts = partsAll.filter { it !is LlmContentPart.ToolUse }
                if (keptParts.isNotEmpty()) {
                    val partial = streamOutcome.assistantMessage.copy(parts = keptParts, createdAt = nextTimestampMs())
                    persistMessage(session.id, partial)
                    history = history + partial
                }
                truncStreak++
                if (truncStreak > 3) {
                    _activity.value = _activity.value.copy(
                        isRunning = false,
                        currentPhase = "Error",
                        activeTools = emptyList(),
                        lastError = if (hitLimit)
                            "The model keeps hitting its output limit before finishing.\n\nRaise 'Max tokens per turn' in Settings > Agent, or choose a model with a larger output window or less reasoning."
                        else
                            "The model returned incomplete or empty responses several times in a row.\n\nTap Retry, or switch to another model/provider."
                    )
                    return
                }
                if (hitLimit) effectiveMaxTokens = minOf(effectiveMaxTokens * 2, 65536)
                val notice = when {
                    hitLimit && hadCalls -> "[Auto-continue] Your last response hit the output token limit while writing a tool call, so that call was NOT executed. Do not repeat your plan. Redo that step in smaller pieces (write files in chunks of at most ~150 lines: create the file, then append the rest) and keep your reasoning short."
                    hitLimit -> "[Auto-continue] Your last response hit the output token limit (mostly thinking). Do not repeat yourself and do not deliberate at length: take the next concrete action now (a tool call or the final answer)."
                    streamBroke -> "[Auto-continue] The connection dropped mid-response. Continue from where you stopped without repeating completed work."
                    else -> "[Auto-continue] Your last response was empty. Continue the task and take the next concrete action."
                }
                val noticeMsg = LlmMessage(
                    id = "auto_" + UUID.randomUUID().toString().take(8),
                    role = "user",
                    parts = listOf(LlmContentPart.Text(notice)),
                    createdAt = nextTimestampMs()
                )
                persistMessage(session.id, noticeMsg)
                history = history + noticeMsg
                _activity.value = _activity.value.copy(currentPhase = "Auto-continue ($truncStreak/3)")
                continue
            }
            truncStreak = 0

            val assistantMsg = streamOutcome.assistantMessage.copy(createdAt = nextTimestampMs())
            if (assistantMsg.parts.isNotEmpty()) {
                persistMessage(session.id, assistantMsg)
                history = history + assistantMsg
            }

            val external = streamOutcome.externalResults
            val allCalls = assistantMsg.toolCalls()
            val externalOnly = external.isNotEmpty() && allCalls.all { it.id in external }
            if (externalOnly) {
                // The provider (e.g. Antigravity) already ran these tools: record the finished cards and end the turn.
                val doneMsg = LlmMessage(
                    id = "tool_" + UUID.randomUUID().toString().take(10),
                    role = "tool",
                    parts = allCalls.map { external.getValue(it.id) },
                    createdAt = nextTimestampMs()
                )
                persistMessage(session.id, doneMsg)
                history = history + doneMsg
            }
            val calls = if (externalOnly) emptyList() else allCalls
            if (calls.isEmpty()) {
                // Auto-Test Loop: if code was modified, verify tests automatically!
                if (editedCodeThisTurn && settings.autoTestAfterEdit && mode != PermissionMode.PLAN && testRetryCount < settings.autoTestMaxRetries) {
                    val detectedCmd = autoTestRunner.detectTestCommand(projectDir, settings.customTestCommand)
                    if (detectedCmd != null) {
                        _activity.value = _activity.value.copy(
                            currentPhase = "Auto-testing ($detectedCmd)",
                            streamingText = "",
                            streamingReasoning = "",
                            activeTools = emptyList()
                        )
                        val testRes = autoTestRunner.runDetectedTests(projectDir, detectedCmd, settings.customEnvVars)
                        _activity.value = _activity.value.copy(lastAutoTestResult = testRes)
                        if (testRes.passed) {
                            verifiedTestCommand = detectedCmd
                        } else {
                            testRetryCount++
                            editedCodeThisTurn = false
                            val failMsg = LlmMessage(
                                id = "autotest_" + UUID.randomUUID().toString().take(8),
                                role = "user",
                                parts = listOf(
                                    LlmContentPart.Text(
                                        if (testRes.output.contains("EADDRINUSE")) {
                                            "[Auto-Test could not run: a port is already in use (probably a background job you started, e.g. a dev server). " +
                                                "Stop that job with kill_job or use a different port, then re-run the tests. Do NOT change the application code for this.]"
                                        } else {
                                            "[Auto-Test Failed: `${testRes.command}` exited with ${testRes.exitCode}]\n```\n${testRes.output.take(4000)}\n```\nPlease fix the failing tests and re-verify."
                                        }
                                    )
                                ),
                                createdAt = nextTimestampMs()
                            )
                            persistMessage(session.id, failMsg)
                            history = history + failMsg
                            continue
                        }
                    }
                }

                // Stop Lifecycle Hooks
                val stopHooks = hookEngine.runMatchingHooks(HookEvent.Stop, "*", null, projectDir, settings.customEnvVars)
                if (stopHooks.isNotEmpty()) {
                    _activity.value = _activity.value.copy(
                        lastHookNotice = stopHooks.joinToString(" | ") { "${it.hookName} (exit ${it.exitCode})" }
                    )
                }

                // Auto-Memory extraction into YSCODE.md
                val memUpdate = if (settings.autoMemoryEnabled) {
                    memoryManager.autoLearnFromTurn(projectDir, userText, verifiedTestCommand)
                } else MemoryUpdateResult(false)

                _activity.value = _activity.value.copy(
                    isRunning = false,
                    currentPhase = "Done",
                    currentToolName = null,
                    streamingText = "",
                    streamingReasoning = "",
                    activeTools = emptyList(),
                    lastMemoryUpdate = memUpdate.takeIf { it.updated }
                )
                return
            }

            if (calls.any { it.name in setOf("write_file", "edit_file", "multi_edit") }) {
                editedCodeThisTurn = true
            }

            val toolCtx = ToolContext(
                projectDir = projectDir,
                env = env,
                runner = runner,
                changesRepository = changesRepository,
                readTracker = readTracker,
                todoState = todoState,
                mode = mode,
                sessionId = session.id,
                customEnvVars = settings.customEnvVars,
                supportsVision = visionOk
            )

            val toolResults = executeToolCalls(calls, toolCtx, mode, settings.customEnvVars)
            if (calls.any { it.name in setOf("install_skill", "create_skill", "add_mcp_server") }) {
                // New skills / MCP tools must be visible to the model on the very next step, not next session.
                if (supportsTools && !isCasualChat) toolSpecs = toolRegistry.getActiveToolSpecs()
                systemPrompt = promptBuilder.buildSystemPrompt(
                    projectDir = projectDir,
                    mode = mode,
                    availableTools = toolSpecs.map { it.name },
                    isCasualMessage = isCasualChat,
                    useTreeSitter = settings.useTreeSitterRepoMap
                )
            }
            val resultMsg = LlmMessage(
                id = "tool_" + UUID.randomUUID().toString().take(10),
                role = "tool",
                parts = toolResults,
                createdAt = nextTimestampMs()
            )
            persistMessage(session.id, resultMsg)
            history = history + resultMsg
            if (pendingImages.isNotEmpty()) {
                val imgs = ArrayList<LlmContentPart.ImageBase64>()
                while (true) { imgs += pendingImages.poll() ?: break }
                // Not persisted (keeps the database small); the model sees it for this turn.
                history = history + LlmMessage(
                    id = "img_" + UUID.randomUUID().toString().take(8),
                    role = "user",
                    parts = listOf(
                        LlmContentPart.Text("[Image(s) from your view_image/read_file call, attached below. Look at them and answer from what you actually see.]")
                    ) + imgs,
                    createdAt = nextTimestampMs()
                )
            }
            if (step == 40 || step == 80 || step == 120) {
                // Transient budget nudge (not persisted): stops expensive rabbit holes on small tasks.
                history = history + LlmMessage(
                    id = "budget_$step",
                    role = "user",
                    parts = listOf(
                        LlmContentPart.Text(
                            "[Budget check] You have used $step steps this turn. Keep going if you are making real progress. If you are " +
                                "repeating yourself or guessing, pause, give a 2-line status and say what is blocking you."
                        )
                    ),
                    createdAt = nextTimestampMs()
                )
            }
            // Clear activeTools once persisted so MessageItemView/TimelineMapper owns the finished cards cleanly
            _activity.value = _activity.value.copy(activeTools = emptyList())
        }

        _activity.value = _activity.value.copy(
            isRunning = false,
            activeTools = emptyList(),
            currentPhase = "Max steps reached (${settings.maxSteps})",
            lastError = "Stopped after reaching max steps (${settings.maxSteps}). Increase in Settings -> Agent."
        )
    }

    private suspend fun executeToolCalls(
        calls: List<LlmContentPart.ToolUse>,
        ctx: ToolContext,
        mode: PermissionMode,
        extraEnv: Map<String, String>
    ): List<LlmContentPart.ToolResult> {
        val allReadOnly = calls.all { toolRegistry.resolveRisk(it.name) == Risk.READ }
        return if (allReadOnly && calls.size > 1) {
            coroutineScope {
                calls.map { call -> async { runSingleTool(call, ctx, mode, extraEnv) } }.awaitAll()
            }
        } else {
            val results = mutableListOf<LlmContentPart.ToolResult>()
            for (call in calls) {
                results += runSingleTool(call, ctx, mode, extraEnv)
            }
            results
        }
    }

    private suspend fun runSingleTool(
        call: LlmContentPart.ToolUse,
        ctx: ToolContext,
        mode: PermissionMode,
        extraEnv: Map<String, String>
    ): LlmContentPart.ToolResult {
        val tStart = System.currentTimeMillis()
        val isInteractiveUi = call.name == "ask_user" || call.name == "exit_plan_mode"
        val argsPreview = TimelineMapper.formatArgsPreview(call.name, call.argumentsJson)

        if (!isInteractiveUi) {
            updateToolCard(
                StreamingToolState(
                    id = call.id,
                    name = call.name,
                    argsPreview = argsPreview,
                    status = "running"
                )
            )
        }
        _activity.value = _activity.value.copy(currentPhase = "Running ${call.name}", currentToolName = call.name)

        val parsedArgs: JsonObject = JsonHelper.parseOrRepairObject(call.argumentsJson).getOrElse { err ->
            val msg = "Malformed JSON arguments for '${call.name}': ${err.message}. Please emit valid JSON."
            updateToolCard(
                StreamingToolState(call.id, call.name, argsPreview, "error", msg, msg, "Invalid JSON", System.currentTimeMillis() - tStart)
            )
            return LlmContentPart.ToolResult(call.id, call.name, msg, isError = true)
        }

        if (call.name in loopGuardedTools) {
            val key = call.name + ":" + parsedArgs.toString().lowercase()
                .replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").take(220)
            val n = loopCounts.merge(key, 1) { a, b -> a + b } ?: 1
            if (n > 8) {
                val msg = "Blocked by loop guard: you have run essentially the same `${call.name}` call $n times " +
                    "(only numbers differ). Stop probing. Read the needed file once with read_file (offset/limit) or use one precise " +
                    "pattern, then act on what you already know. If you are blocked, solve the root cause or tell the user exactly what is missing."
                updateToolCard(
                    StreamingToolState(call.id, call.name, argsPreview, "error", msg, msg, "Loop blocked", System.currentTimeMillis() - tStart)
                )
                return LlmContentPart.ToolResult(call.id, call.name, msg, isError = true)
            }
        }

        val risk = toolRegistry.resolveRisk(call.name)
        val perm = permissionEngine.checkAndAwait(call.name, parsedArgs, risk, mode)
        if (perm is PermissionCheckOutcome.Denied) {
            updateToolCard(
                StreamingToolState(call.id, call.name, argsPreview, "denied", perm.reason, perm.reason, "Denied", System.currentTimeMillis() - tStart)
            )
            return LlmContentPart.ToolResult(call.id, call.name, perm.reason, isError = true)
        }

        // PreToolUse Hooks (exit code 2 blocks execution)
        val preHooks = hookEngine.runMatchingHooks(HookEvent.PreToolUse, call.name, parsedArgs, ctx.projectDir, extraEnv)
        val blocker = preHooks.firstOrNull { it.blocked }
        if (blocker != null) {
            val blockMsg = "Blocked by PreToolUse hook '${blocker.hookName}': ${blocker.output.ifBlank { "exit code 2" }}"
            _activity.value = _activity.value.copy(lastHookNotice = blockMsg)
            updateToolCard(
                StreamingToolState(call.id, call.name, argsPreview, "denied", blockMsg, blockMsg, "Hook blocked", System.currentTimeMillis() - tStart)
            )
            return LlmContentPart.ToolResult(call.id, call.name, blockMsg, isError = true)
        }

        val liveLines = mutableListOf<String>()
        val scopedCtx = ctx.copy(onStreamLine = { line ->
            if (!isInteractiveUi) {
                synchronized(liveLines) {
                    liveLines += line
                    if (liveLines.size > 5) liveLines.removeAt(0)
                    val preview = liveLines.joinToString("\n")
                    updateToolCard(
                        StreamingToolState(call.id, call.name, argsPreview, "running", preview, preview)
                    )
                }
            }
        })

        var res = toolRegistry.execute(call.name, parsedArgs, scopedCtx)
        if (res.images.isNotEmpty()) pendingImages.addAll(res.images)

        // PostToolUse Hooks (auto-format, auto-lint, etc.)
        val postHooks = hookEngine.runMatchingHooks(HookEvent.PostToolUse, call.name, parsedArgs, ctx.projectDir, extraEnv)
        if (postHooks.isNotEmpty()) {
            val hookLog = postHooks.joinToString("\n") { h ->
                "[Hook:${h.hookName} exit=${h.exitCode}] ${h.output}".trim()
            }
            _activity.value = _activity.value.copy(
                lastHookNotice = postHooks.joinToString(" | ") { "${it.hookName} (exit ${it.exitCode})" }
            )
            if (hookLog.isNotBlank()) {
                res = res.copy(output = "${res.output}\n\n$hookLog".trim())
            }
        }

        val elapsed = System.currentTimeMillis() - tStart
        if (!isInteractiveUi) {
            updateToolCard(
                StreamingToolState(
                    id = call.id,
                    name = call.name,
                    argsPreview = argsPreview,
                    status = if (res.isError) "error" else "done",
                    outputPreview = res.output.lines().takeLast(6).joinToString("\n"),
                    fullOutput = res.output,
                    summary = res.summary,
                    durationMs = elapsed,
                    diff = res.diff
                )
            )
        } else {
            removeToolCard(call.id)
        }
        return LlmContentPart.ToolResult(call.id, call.name, res.output, isError = res.isError)
    }

    private fun updateToolCard(state: StreamingToolState) {
        val current = _activity.value.activeTools.toMutableList()
        val idx = current.indexOfFirst { it.id == state.id }
        if (idx >= 0) current[idx] = state else current += state
        _activity.value = _activity.value.copy(activeTools = current)
    }

    private fun removeToolCard(id: String) {
        _activity.value = _activity.value.copy(
            activeTools = _activity.value.activeTools.filterNot { it.id == id }
        )
    }

    private data class StreamOutcome(
        val assistantMessage: LlmMessage,
        val inputTokens: Long = 0L,
        val outputTokens: Long = 0L,
        val error: String? = null,
        val retryable: Boolean = false,
        val finishReason: String = "stop",
        val externalResults: Map<String, LlmContentPart.ToolResult> = emptyMap(),
        /** True when tool cards the provider ran itself were already saved while streaming. */
        val externalLive: Boolean = false
    )

    private suspend fun streamWithRetryAndFallback(
        primaryClient: LlmClient,
        request: ChatRequest,
        settings: com.example.data.AppSettings,
        onExternal: (suspend (LlmMessage, LlmMessage) -> Unit)? = null
    ): StreamOutcome {
        val delays = listOf(0L, 1200L, 2800L)
        var lastError: String? = null
        for (wait in delays) {
            if (wait > 0) delay(wait)
            val attempt = collectStreamOnce(primaryClient, request, onExternal)
            if (attempt.error == null) return attempt
            lastError = attempt.error
            if (!attempt.retryable) break
        }
        if (settings.fallbackProviderId.isNotBlank()) {
            val fbProvider = providerDao.getById(settings.fallbackProviderId)
            if (fbProvider != null) {
                val fbClient = clientFactory.create(fbProvider)
                val fbModel = settings.fallbackModel.ifBlank { fbProvider.defaultModel }
                if (fbModel.isNotBlank()) {
                    val fbAttempt = collectStreamOnce(fbClient, request.copy(model = fbModel), onExternal)
                    if (fbAttempt.error == null) return fbAttempt
                    lastError = "Primary failed ($lastError); Fallback failed (${fbAttempt.error})"
                }
            }
        }
        return StreamOutcome(
            assistantMessage = LlmMessage("err", "assistant", emptyList()),
            error = com.example.util.FriendlyErrors.explain(lastError ?: "LLM request failed")
        )
    }

    private suspend fun collectStreamOnce(
        client: LlmClient,
        request: ChatRequest,
        onExternal: (suspend (LlmMessage, LlmMessage) -> Unit)? = null
    ): StreamOutcome {
        var flushedLive = false
        val textSb = StringBuilder()
        val reasoningSb = StringBuilder()
        var reasoningStart = System.currentTimeMillis()
        val calls = mutableListOf<LlmContentPart.ToolUse>()
        val segments = mutableListOf<LlmContentPart>()
        val externalResults = LinkedHashMap<String, LlmContentPart.ToolResult>()
        var inTok = 0L
        var outTok = 0L
        var errMsg: String? = null
        var retryable = false
        var finish = "stop"

        client.stream(request).collect { event ->
            when (event) {
                is LlmEvent.TextDelta -> {
                    textSb.append(event.text)
                    _activity.value = _activity.value.copy(streamingText = textSb.toString())
                }
                is LlmEvent.ReasoningDelta -> {
                    reasoningSb.append(event.text)
                    _activity.value = _activity.value.copy(streamingReasoning = reasoningSb.toString())
                }
                is LlmEvent.ToolCallStart -> {
                    _activity.value = _activity.value.copy(currentPhase = "Preparing ${event.name}", currentToolName = event.name)
                }
                is LlmEvent.ToolCallDelta -> Unit
                is LlmEvent.ExternalTool -> {
                    if (onExternal != null) {
                        // Show the tool card right now, in the order the provider worked: thinking, text so far, then the tool.
                        val liveParts = mutableListOf<LlmContentPart>()
                        if (reasoningSb.isNotEmpty()) {
                            liveParts += LlmContentPart.Reasoning(reasoningSb.toString(), System.currentTimeMillis() - reasoningStart)
                            reasoningSb.clear(); reasoningStart = System.currentTimeMillis()
                        }
                        if (textSb.isNotEmpty()) { liveParts += LlmContentPart.Text(textSb.toString()); textSb.clear() }
                        liveParts += LlmContentPart.ToolUse(event.id, event.name, event.argumentsJson.ifBlank { "{}" })
                        val a = LlmMessage(id = "msg_" + UUID.randomUUID().toString().take(10), role = "assistant", parts = liveParts, createdAt = nextTimestampMs())
                        val t = LlmMessage(
                            id = "tool_" + UUID.randomUUID().toString().take(10), role = "tool",
                            parts = listOf(LlmContentPart.ToolResult(event.id, event.name, event.output, event.isError)),
                            createdAt = nextTimestampMs()
                        )
                        onExternal(a, t)
                        flushedLive = true
                        _activity.value = _activity.value.copy(currentPhase = "Ran ${event.name}", currentToolName = null, streamingText = "", streamingReasoning = "")
                    } else {
                        if (textSb.isNotEmpty()) { segments += LlmContentPart.Text(textSb.toString()); textSb.clear() }
                        segments += LlmContentPart.ToolUse(event.id, event.name, event.argumentsJson.ifBlank { "{}" })
                        externalResults[event.id] = LlmContentPart.ToolResult(event.id, event.name, event.output, event.isError)
                        _activity.value = _activity.value.copy(currentPhase = "Ran ${event.name}", currentToolName = null, streamingText = "")
                    }
                }
                is LlmEvent.ToolCallEnd -> {
                    calls += LlmContentPart.ToolUse(event.id, event.name, event.argumentsJson.ifBlank { "{}" })
                }
                is LlmEvent.Usage -> {
                    inTok = event.inputTokens
                    outTok = event.outputTokens
                }
                is LlmEvent.Error -> {
                    errMsg = event.message
                    retryable = event.retryable
                }
                is LlmEvent.Done -> finish = event.finishReason
            }
        }

        if (errMsg != null && textSb.isEmpty() && reasoningSb.isEmpty() && calls.isEmpty() && segments.isEmpty() && !flushedLive) {
            return StreamOutcome(LlmMessage("err", "assistant", emptyList()), inTok, outTok, errMsg, retryable)
        }
        val parts = mutableListOf<LlmContentPart>()
        if (reasoningSb.isNotEmpty()) {
            parts += LlmContentPart.Reasoning(reasoningSb.toString(), System.currentTimeMillis() - reasoningStart)
        }
        parts.addAll(segments)
        if (textSb.isNotEmpty()) {
            parts += LlmContentPart.Text(textSb.toString())
        }
        parts.addAll(calls)
        val msg = LlmMessage(
            id = "msg_" + UUID.randomUUID().toString().take(10),
            role = "assistant",
            parts = parts
        )
        // An error after partial output means the stream broke mid-response: flag it so the loop can recover.
        return StreamOutcome(msg, inTok, outTok, null, false, if (errMsg != null) "error" else finish, externalResults, flushedLive)
    }

    /**
     * Role-aware subagent dispatcher supporting foreground & background execution.
     */
    suspend fun runSubagent(
        prompt: String,
        role: String,
        allowWrite: Boolean,
        background: Boolean,
        modelOverride: String?,
        parentCtx: ToolContext
    ): String {
        if (background) {
            val job = backgroundAgentManager.launchBackgroundAgent(
                sessionId = parentCtx.sessionId,
                projectDir = parentCtx.projectDir,
                role = role,
                prompt = prompt,
                useWakeLock = settingsStore.current().wakeLockDuringJobs
            ) { onProgress ->
                runSubagentInternal(prompt, role, allowWrite, modelOverride, parentCtx, onProgress)
            }
            return "Launched background [$role] agent '${job.id}'. You will receive a notification when it finishes; view live progress in More -> Background Agents."
        }
        return runSubagentInternal(prompt, role, allowWrite, modelOverride, parentCtx) { progress ->
            parentCtx.onStreamLine("[$role] $progress")
        }
    }

    private suspend fun runSubagentInternal(
        prompt: String,
        role: String,
        allowWrite: Boolean,
        modelOverride: String?,
        parentCtx: ToolContext,
        onProgress: (String) -> Unit
    ): String {
        val settings = settingsStore.current()
        val provider = resolveProvider("", settings.activeProviderId) ?: return "No provider configured for subagent."
        val modelId = modelOverride?.takeIf { it.isNotBlank() }
            ?: settings.subagentModelOverride.takeIf { it.isNotBlank() }
            ?: provider.defaultModel
        val client = clientFactory.create(provider)
        val effectiveAllowWrite = when (role) {
            "explorer", "reviewer" -> false
            else -> allowWrite
        }
        val specs = toolRegistry.getActiveToolSpecs(readOnlyOnly = !effectiveAllowWrite, includeSubagentTool = false)
        val mode = if (effectiveAllowWrite) parentCtx.mode else PermissionMode.PLAN
        val roleDirective = when (role) {
            "explorer" -> "You are an EXPLORER subagent (read-only). Rapidly map symbols via repo_map, grep, glob, read_file, and web_search, then return exact file paths, line numbers, and findings."
            "coder" -> "You are a CODER subagent. Read target files, apply clean minimal edits, and verify with tests/linters before reporting."
            "reviewer" -> "You are a REVIEWER subagent (read-only). Audit code changes, edge cases, security, and test coverage, reporting concrete issues with path:line."
            else -> "You are a focused subagent. Complete the delegated task and return a concise summary."
        }
        val sys = promptBuilder.buildSystemPrompt(parentCtx.projectDir, mode, specs.map { it.name }) +
            "\n\n# Subagent Role\n$roleDirective"

        var messages = listOf(LlmMessage("sub_u1", "user", listOf(LlmContentPart.Text(prompt))))
        val subCtx = parentCtx.copy(isSubagent = true, mode = mode)

        for (step in 1..15) {
            onProgress("Step $step/15")
            val req = ChatRequest(model = modelId, system = sys, messages = messages, tools = specs, temperature = settings.temperature)
            val outcome = collectStreamOnce(client, req)
            if (outcome.error != null) return "Subagent error: ${outcome.error}"
            val reply = outcome.assistantMessage
            messages = messages + reply
            val calls = reply.toolCalls()
            if (calls.isEmpty()) return reply.plainText().ifBlank { "(subagent finished with empty response)" }

            val results = calls.map { tc ->
                onProgress("Running ${tc.name}")
                val args = JsonHelper.parseOrRepairObject(tc.argumentsJson).getOrElse {
                    return@map LlmContentPart.ToolResult(tc.id, tc.name, "Invalid JSON: ${it.message}", true)
                }
                val risk = toolRegistry.resolveRisk(tc.name)
                if (!effectiveAllowWrite && risk != Risk.READ) {
                    return@map LlmContentPart.ToolResult(tc.id, tc.name, "Subagent role '$role' is read-only", true)
                }
                val r = toolRegistry.execute(tc.name, args, subCtx)
                LlmContentPart.ToolResult(tc.id, tc.name, r.output, r.isError)
            }
            messages = messages + LlmMessage("sub_t_${System.nanoTime()}", "tool", results)
        }
        return messages.lastOrNull()?.plainText()?.ifBlank { "Subagent hit 15-step limit." } ?: "Subagent finished."
    }

    suspend fun generateCommitMessage(projectDir: File, diffSummary: String): Result<String> = runCatching {
        val settings = settingsStore.current()
        val provider = resolveProvider("", settings.activeProviderId) ?: error("No provider configured")
        val model = provider.defaultModel.ifBlank { clientFactory.parseModels(provider).firstOrNull()?.id ?: error("No model") }
        val client = clientFactory.create(provider)
        val sb = StringBuilder()
        client.stream(
            ChatRequest(
                model = model,
                system = "Write a concise single-line conventional git commit message (e.g. feat: ..., fix: ...) for these changes. Output ONLY the commit message.",
                messages = listOf(LlmMessage("cm", "user", listOf(LlmContentPart.Text(diffSummary.take(8000))))),
                maxTokens = 100,
                supportsTools = false
            )
        ).collect { evt ->
            if (evt is LlmEvent.TextDelta) sb.append(evt.text)
        }
        sb.toString().trim().lines().firstOrNull()?.trim('"', '`') ?: "chore: update project files"
    }

    suspend fun compactSessionManually(sessionId: String): Result<String> = runCatching {
        val settings = settingsStore.current()
        val provider = resolveProvider("", settings.activeProviderId)
        val client = provider?.let { clientFactory.create(it) }
        val model = provider?.defaultModel
        val history = loadSessionHistory(sessionId)
        if (history.size <= 4) return@runCatching "Session is already compact (${history.size} messages)."
        val compacted = contextManager.compact(history, client, model)
        replaceSessionWithCompacted(sessionId, compacted)
        "Compacted ${history.size} messages into ${compacted.size} messages."
    }

    /** Sends a generated test picture to the session's model and checks that it really perceives it. */
    suspend fun testVision(sessionId: String): String {
        val settings = settingsStore.current()
        val session = sessionDao.getById(sessionId)
        val provider = resolveProvider(session?.providerId.orEmpty(), settings.activeProviderId) ?: return "No provider configured."
        val modelId = session?.model.orEmpty().ifBlank { provider.defaultModel }
        if (modelId.isBlank()) return "No model selected."
        val client = clientFactory.create(provider)
        val probe = com.example.util.ImageLoader.makeVisionProbe()
        val req = ChatRequest(
            model = modelId,
            system = "You are a vision test. Answer in one short sentence.",
            messages = listOf(
                LlmMessage(
                    id = "vision_probe",
                    role = "user",
                    parts = listOf(
                        LlmContentPart.Text("What color is the large filled circle in this image? Answer with just the color name."),
                        LlmContentPart.ImageBase64(probe.mime, probe.base64, "probe.jpg")
                    ),
                    createdAt = System.currentTimeMillis()
                )
            ),
            maxTokens = 4096,
            supportsTools = false
        )
        val sb = StringBuilder()
        var err: String? = null
        kotlinx.coroutines.withTimeoutOrNull(60_000L) {
            client.stream(req).collect { e ->
                when (e) {
                    is LlmEvent.TextDelta -> sb.append(e.text)
                    is LlmEvent.Error -> err = e.message
                    else -> Unit
                }
            }
        } ?: return "Vision test timed out for $modelId."
        val answer = sb.toString().trim()
        return when {
            err != null -> "Vision test failed for $modelId: ${err!!.take(160)}"
            answer.lowercase().contains("green") -> "Vision works: $modelId sees images correctly."
            answer.isBlank() -> "No answer from $modelId. It may not accept images."
            else -> "$modelId did NOT see the image (it answered: ${answer.take(80)}). Choose a model that supports images."
        }
    }

    suspend fun loadSessionHistory(sessionId: String): List<LlmMessage> {
        val all = messageDao.getForSession(sessionId)
        val lastSummary = all.indexOfLast { it.isCompactedSummary }
        val active = if (lastSummary >= 0) all.drop(lastSummary) else all
        return active.mapNotNull { entity ->
            runCatching { JsonHelper.json.decodeFromString<LlmMessage>(entity.contentJson) }.getOrNull()
        }
    }

    /** Every stored message (including those before a compaction) - for the work log and exports. */
    suspend fun loadFullHistory(sessionId: String): List<LlmMessage> =
        messageDao.getForSession(sessionId).mapNotNull { e ->
            runCatching { JsonHelper.json.decodeFromString<LlmMessage>(e.contentJson) }.getOrNull()
        }

    private suspend fun persistMessage(sessionId: String, msg: LlmMessage) {
        messageDao.upsert(
            MessageEntity(
                id = msg.id,
                sessionId = sessionId,
                role = msg.role,
                contentJson = JsonHelper.json.encodeToString(msg),
                createdAt = msg.createdAt,
                isCompactedSummary = msg.isCompactedSummary
            )
        )
    }

    /**
     * Non-destructive: the full chat stays in the database/UI. The summary is stored as a marker placed right before the
     * kept recent messages; only the marker and what follows it is sent to the model (see loadSessionHistory).
     */
    private suspend fun replaceSessionWithCompacted(sessionId: String, messages: List<LlmMessage>) {
        val summary = messages.firstOrNull { it.isCompactedSummary } ?: return
        val tail = messages.filter { !it.isCompactedSummary }
        val existing = messageDao.getForSession(sessionId)
        val firstTailTs = tail.firstOrNull()
            ?.let { t -> existing.firstOrNull { it.id == t.id }?.createdAt }
            ?: System.currentTimeMillis()
        persistMessage(sessionId, summary.copy(createdAt = firstTailTs - 1))
    }

    private suspend fun resolveProvider(sessionProviderId: String, activeProviderId: String): com.example.data.ProviderEntity? {
        if (sessionProviderId.isNotBlank()) providerDao.getById(sessionProviderId)?.let { return it }
        if (activeProviderId.isNotBlank()) providerDao.getById(activeProviderId)?.let { return it }
        return providerDao.getAll().firstOrNull()
    }
}
