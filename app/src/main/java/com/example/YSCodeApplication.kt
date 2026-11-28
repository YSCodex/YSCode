// SPDX-License-Identifier: GPL-3.0-or-later
package com.example

import android.app.Application
import android.content.Context
import com.example.agent.AgentLoop
import com.example.agent.AutoTestRunner
import com.example.agent.BackgroundAgentManager
import com.example.agent.ContextManager
import com.example.agent.HookEngine
import com.example.agent.MemoryManager
import com.example.agent.PermissionEngine
import com.example.agent.PromptBuilder
import com.example.agent.RepoMapIndex
import com.example.agent.SlashCommandProcessor
import com.example.data.BackupManager
import com.example.data.ProjectManager
import com.example.data.SecretStore
import com.example.data.SettingsStore
import com.example.data.ToolchainManager
import com.example.data.YSCodeDatabase
import com.example.git.ChangesRepository
import com.example.git.CheckpointManager
import com.example.git.GitHelper
import com.example.linux.LinuxEnvironment
import com.example.linux.ProotRunner
import com.example.linux.RootfsManager
import com.example.linux.TerminalManager
import com.example.llm.LlmClientFactory
import com.example.mcp.McpRegistry
import com.example.skills.SkillInstaller
import com.example.skills.SkillRepository
import com.example.tools.AddMcpServerTool
import com.example.tools.AgentInteractionBridge
import com.example.tools.AskUserTool
import com.example.tools.BashOutputTool
import com.example.tools.BashTool
import com.example.tools.CreateSkillTool
import com.example.tools.EditFileTool
import com.example.tools.ExitPlanModeTool
import com.example.tools.GlobTool
import com.example.tools.GrepTool
import com.example.tools.InstallSkillTool
import com.example.tools.KillJobTool
import com.example.tools.ListDirTool
import com.example.tools.MemoryWriteTool
import com.example.tools.MultiEditTool
import com.example.tools.ParallelTasksTool
import com.example.tools.ReadFileTool
import com.example.tools.RepoMapTool
import com.example.tools.TaskSubagentTool
import com.example.tools.TodoWriteTool
import com.example.tools.ToolchainTool
import com.example.tools.ToolRegistry
import com.example.tools.UseSkillTool
import com.example.tools.WebFetchTool
import com.example.tools.WebSearchTool
import com.example.tools.WriteFileTool
import com.example.util.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val database: YSCodeDatabase = YSCodeDatabase.build(appContext)
    val secretStore = SecretStore(appContext)
    val settingsStore = SettingsStore(appContext)
    val networkMonitor = NetworkMonitor(appContext)

    val linuxEnv = LinuxEnvironment(appContext)
    val rootfsManager = RootfsManager(appContext, linuxEnv)
    val prootRunner = ProotRunner(linuxEnv)
    val terminalManager = TerminalManager(linuxEnv, prootRunner)
    val toolchainManager = ToolchainManager(linuxEnv, database.toolchainDao())

    val changesRepository = ChangesRepository()
    val checkpointManager = CheckpointManager(linuxEnv, database.checkpointDao(), changesRepository)
    val gitHelper = GitHelper(prootRunner, settingsStore, secretStore, changesRepository)

    val skillRepository = SkillRepository(linuxEnv, database.skillMetaDao())
    val skillInstaller = SkillInstaller(appContext, linuxEnv, skillRepository)
    val mcpRegistry = McpRegistry(database.mcpServerDao(), secretStore, settingsStore, linuxEnv, prootRunner)

    val repoMapIndex = RepoMapIndex(prootRunner)
    val memoryManager = MemoryManager(linuxEnv)
    val hookEngine = HookEngine(settingsStore, prootRunner)
    val autoTestRunner = AutoTestRunner(prootRunner)
    val backgroundAgentManager = BackgroundAgentManager(appContext)

    val codexAuth = com.example.llm.CodexAuth(secretStore)
    val agyManager = com.example.llm.AgyManager(appContext, prootRunner, linuxEnv)
    val clientFactory = LlmClientFactory(secretStore, codexAuth, agyManager)
    val interactionBridge = AgentInteractionBridge()
    val permissionEngine = PermissionEngine(settingsStore)
    val promptBuilder = PromptBuilder(
        env = linuxEnv,
        skillRepository = skillRepository,
        gitHelper = gitHelper,
        repoMapIndex = repoMapIndex,
        memoryManager = memoryManager,
        hookEngine = hookEngine,
        toolchainManager = toolchainManager
    )
    val contextManager = ContextManager()
    val slashCommands = SlashCommandProcessor(linuxEnv)

    val projectManager = ProjectManager(
        context = appContext,
        env = linuxEnv,
        runner = prootRunner,
        projectDao = database.projectDao(),
        sessionDao = database.sessionDao()
    )
    val backupManager = BackupManager(appContext, database, settingsStore, linuxEnv)

    lateinit var agentLoop: AgentLoop
        private set

    val toolRegistry: ToolRegistry = ToolRegistry(
        builtInTools = listOf(
            RepoMapTool(repoMapIndex),
            ReadFileTool(),
            com.example.tools.ViewImageTool(),
            WriteFileTool(),
            EditFileTool(),
            MultiEditTool(),
            ListDirTool(),
            GlobTool(),
            GrepTool(),
            BashTool(),
            BashOutputTool(),
            KillJobTool(),
            WebSearchTool(settingsStore),
            WebFetchTool(settingsStore),
            com.example.tools.BrowserTool(appContext, settingsStore),
            com.example.tools.RecallTool(database.messageDao(), database.sessionDao()),
            MemoryWriteTool(memoryManager),
            ToolchainTool(toolchainManager),
            TodoWriteTool(),
            AskUserTool(interactionBridge),
            ExitPlanModeTool(interactionBridge) {
                settingsStore.update { it.copy(defaultMode = com.example.data.PermissionMode.AUTO_EDIT) }
            },
            UseSkillTool(skillRepository),
            CreateSkillTool(skillRepository),
            InstallSkillTool(skillInstaller),
            AddMcpServerTool(mcpRegistry),
            TaskSubagentTool { prompt, role, allowWrite, background, modelOverride, ctx ->
                agentLoop.runSubagent(prompt, role, allowWrite, background, modelOverride, ctx)
            },
            ParallelTasksTool { role, prompt, modelOverride, ctx ->
                agentLoop.runSubagent(prompt, role, role == "coder", false, modelOverride, ctx)
            }
        ),
        mcpRegistry = mcpRegistry,
        settingsStore = settingsStore
    )

    init {
        agentLoop = AgentLoop(
            context = appContext,
            settingsStore = settingsStore,
            providerDao = database.providerDao(),
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            clientFactory = clientFactory,
            toolRegistry = toolRegistry,
            permissionEngine = permissionEngine,
            promptBuilder = promptBuilder,
            contextManager = contextManager,
            checkpointManager = checkpointManager,
            changesRepository = changesRepository,
            env = linuxEnv,
            runner = prootRunner,
            interactionBridge = interactionBridge,
            hookEngine = hookEngine,
            autoTestRunner = autoTestRunner,
            memoryManager = memoryManager,
            backgroundAgentManager = backgroundAgentManager,
            usageDao = database.usageDao()
        )
    }
}

class YSCodeApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        com.example.util.CrashReporter.install(this)
        container = AppContainer(this)
        com.example.util.AppForeground.register(this)
        appScope.launch {
            container.rootfsManager.ensureBaseDirs()
            container.toolchainManager.rescan()
            container.skillRepository.ensureStarterSkillsInstalled()
            container.mcpRegistry.refreshFromDb()

            runCatching { com.example.linux.BuiltinShellCommands(container.linuxEnv).reprobeModeIfStale() }
            runCatching { com.example.update.UpdateManager(this@YSCodeApplication).checkIfDue() }
            runCatching { com.example.update.Telemetry(this@YSCodeApplication).pingIfDue() }

            // Auto-setup Linux on first launch / after update if PRoot is bundled but rootfs is missing.
            val env = container.linuxEnv
            val st = container.settingsStore.settingsFlow.first()
            if (st.onboardingCompleted && env.prootBinary.exists() && !env.rawProotAndRootfs()) {
                container.rootfsManager.initializeEnvironment(installPackages = true)
            }
        }
    }
}
