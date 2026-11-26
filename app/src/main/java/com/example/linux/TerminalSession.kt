// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.linux

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class AnsiSpan(
    val text: String,
    val color: Color? = null,
    val isBold: Boolean = false,
    val isDim: Boolean = false
)

data class TerminalLine(
    val id: Long,
    val spans: List<AnsiSpan>
)

object AnsiParser {
    private val escRegex = Regex("\u001B\\[([0-9;]*)m")

    fun parse(line: String, defaultColor: Color? = null): List<AnsiSpan> {
        val cleaned = line.replace(Regex("\u001B\\[[0-9;]*[A-HJKSTf]"), "")
        val spans = mutableListOf<AnsiSpan>()
        var lastEnd = 0
        var currentColor: Color? = defaultColor
        var bold = false
        var dim = false

        for (match in escRegex.findAll(cleaned)) {
            if (match.range.first > lastEnd) {
                spans += AnsiSpan(cleaned.substring(lastEnd, match.range.first), currentColor, bold, dim)
            }
            val codes = match.groupValues[1].split(";").mapNotNull { it.toIntOrNull() }
            if (codes.isEmpty() || 0 in codes) {
                currentColor = defaultColor
                bold = false
                dim = false
            } else {
                for (c in codes) {
                    when (c) {
                        1 -> bold = true
                        2 -> dim = true
                        22 -> { bold = false; dim = false }
                        30 -> currentColor = Color(0xFF4F545C)
                        31, 91 -> currentColor = Color(0xFFE5534B)
                        32, 92 -> currentColor = Color(0xFF4CAF7A)
                        33, 93 -> currentColor = Color(0xFFE0A93B)
                        34, 94 -> currentColor = Color(0xFF7C9CFF)
                        35, 95 -> currentColor = Color(0xFFC678DD)
                        36, 96 -> currentColor = Color(0xFF56B6C2)
                        37, 97 -> currentColor = Color(0xFFE8EAED)
                        39 -> currentColor = defaultColor
                    }
                }
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < cleaned.length) {
            spans += AnsiSpan(cleaned.substring(lastEnd), currentColor, bold, dim)
        }
        return if (spans.isEmpty()) listOf(AnsiSpan("", defaultColor)) else spans
    }
}

data class TerminalTabState(
    val id: String,
    val title: String,
    val isAgentMirror: Boolean = false,
    val cwdPath: String = "/root",
    val lines: List<TerminalLine> = emptyList(),
    val isRunning: Boolean = false,
    val history: List<String> = emptyList()
)

class TerminalManager(
    private val env: LinuxEnvironment,
    private val runner: ProotRunner
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lineSeq = 1L

    private val sessionCwds = mutableMapOf<String, File>()
    private val sessionEnvs = mutableMapOf<String, MutableMap<String, String>>()
    private val activeJobs = mutableMapOf<String, Job>()

    private val _tabs = MutableStateFlow<List<TerminalTabState>>(emptyList())
    val tabs: StateFlow<List<TerminalTabState>> = _tabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow("")
    val selectedTabId: StateFlow<String> = _selectedTabId.asStateFlow()

    init {
        val agentTab = TerminalTabState(
            id = "agent_mirror",
            title = "Agent Terminal",
            isAgentMirror = true,
            cwdPath = "/root",
            lines = listOf(
                nextLine("YSCode Agent Terminal — live mirror of agent bash & background jobs", Color(0xFF7C9CFF), bold = true)
            )
        )
        val firstShellId = createShellTabInternal(initialTitle = "Shell 1", initialDir = env.projectsDir)
        _tabs.value = listOf(agentTab) + _tabs.value
        _selectedTabId.value = firstShellId

        scope.launch {
            runner.agentTerminalLines.collect { evt ->
                val color = when {
                    evt.isCommandHeader -> Color(0xFFD97757)
                    evt.isStderr -> Color(0xFFE5534B)
                    else -> null
                }
                appendLinesToTab("agent_mirror", listOf(evt.text), overrideColor = color, bold = evt.isCommandHeader)
            }
        }
    }

    fun selectTab(id: String) {
        _selectedTabId.value = id
    }

    fun newTab(projectDir: File? = null): String {
        val shellCount = _tabs.value.count { !it.isAgentMirror } + 1
        val id = createShellTabInternal("Shell $shellCount", projectDir ?: env.projectsDir)
        _selectedTabId.value = id
        return id
    }

    fun closeTab(id: String) {
        if (id == "agent_mirror") return
        activeJobs.remove(id)?.cancel()
        sessionCwds.remove(id)
        sessionEnvs.remove(id)
        val updated = _tabs.value.filterNot { it.id == id }
        _tabs.value = updated
        if (_selectedTabId.value == id) {
            _selectedTabId.value = updated.firstOrNull { !it.isAgentMirror }?.id ?: "agent_mirror"
        }
    }

    fun setWorkingDir(tabId: String, dir: File) {
        if (dir.exists() && dir.isDirectory) {
            sessionCwds[tabId] = dir
            updateTab(tabId) { it.copy(cwdPath = env.toVirtualPath(dir)) }
        }
    }

    fun runCommand(tabId: String, rawCommand: String) {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) return
        val tab = _tabs.value.find { it.id == tabId } ?: return
        if (tab.isAgentMirror) return

        val cwd = sessionCwds[tabId] ?: env.projectsDir.apply { mkdirs() }
        val customEnv = sessionEnvs.getOrPut(tabId) { mutableMapOf() }

        updateTab(tabId) {
            it.copy(history = (it.history + cmd).takeLast(100))
        }
        appendLinesToTab(tabId, listOf("${env.toVirtualPath(cwd)} $ $cmd"), overrideColor = Color(0xFFD97757), bold = true)

        if (cmd == "clear") {
            updateTab(tabId) { it.copy(lines = emptyList()) }
            return
        }

        // Handle persistent cd
        if (cmd == "cd" || cmd.startsWith("cd ")) {
            val targetArg = cmd.removePrefix("cd").trim()
            val nextDir = if (targetArg.isEmpty() || targetArg == "~") {
                env.rootHomeDir.apply { mkdirs() }
            } else {
                env.resolveProjectPath(targetArg, cwd)
            }
            if (nextDir.exists() && nextDir.isDirectory) {
                sessionCwds[tabId] = nextDir
                updateTab(tabId) { it.copy(cwdPath = env.toVirtualPath(nextDir)) }
            } else {
                appendLinesToTab(tabId, listOf("cd: $targetArg: No such directory"), overrideColor = Color(0xFFE5534B))
            }
            return
        }

        // Handle persistent export KEY=VALUE
        if (cmd.startsWith("export ") && !cmd.contains(";") && !cmd.contains("&&")) {
            val assignment = cmd.removePrefix("export ").trim()
            val eqIdx = assignment.indexOf('=')
            if (eqIdx > 0) {
                val key = assignment.substring(0, eqIdx).trim()
                val value = assignment.substring(eqIdx + 1).trim().trim('"', '\'')
                customEnv[key] = value
                appendLinesToTab(tabId, listOf("export $key=$value"), overrideColor = Color(0xFF9AA0A6))
                return
            }
        }

        activeJobs[tabId]?.cancel()
        updateTab(tabId) { it.copy(isRunning = true) }

        activeJobs[tabId] = scope.launch {
            try {
                val res = runner.exec(
                    command = cmd,
                    cwd = cwd,
                    extraEnv = customEnv,
                    timeoutMs = 120_000L,
                    mirrorToAgentTerminal = false,
                    onLine = { line ->
                        appendLinesToTab(tabId, listOf(line))
                    }
                )
                if (res.exitCode != 0 && res.stderr.isNotBlank()) {
                    appendLinesToTab(tabId, listOf("[exit ${res.exitCode}]"), overrideColor = Color(0xFFE5534B))
                }
            } finally {
                updateTab(tabId) { it.copy(isRunning = false) }
            }
        }
    }

    fun sendInterrupt(tabId: String) {
        activeJobs.remove(tabId)?.cancel()
        appendLinesToTab(tabId, listOf("^C"), overrideColor = Color(0xFFE0A93B))
        updateTab(tabId) { it.copy(isRunning = false) }
    }

    fun clearTab(tabId: String) {
        updateTab(tabId) { it.copy(lines = emptyList()) }
    }

    fun getTabText(tabId: String): String {
        val tab = _tabs.value.find { it.id == tabId } ?: return ""
        return tab.lines.joinToString("\n") { line -> line.spans.joinToString("") { it.text } }
    }

    fun isProotActive(): Boolean = env.hasProotAndRootfs()

    private fun createShellTabInternal(initialTitle: String, initialDir: File): String {
        val id = "term_" + UUID.randomUUID().toString().take(8)
        val dir = if (initialDir.exists()) initialDir else env.rootHomeDir.apply { mkdirs() }
        sessionCwds[id] = dir
        sessionEnvs[id] = mutableMapOf()
        val hasProot = env.hasProotAndRootfs()
        val modeLabel = if (hasProot) "PRoot arm64 bash" else "Android POSIX + Built-in git/curl/wget/unzip"
        val tipLine = if (hasProot) {
            "Full Linux rootfs active. Type commands or use quick chips below."
        } else {
            "Built-in Kotlin tools ready: git, curl, wget, unzip, pkg. Run 'help' or 'pkg status'."
        }
        val tab = TerminalTabState(
            id = id,
            title = initialTitle,
            isAgentMirror = false,
            cwdPath = env.toVirtualPath(dir),
            lines = listOf(
                nextLine("YSCode Shell ($modeLabel) — ${env.toVirtualPath(dir)}", Color(0xFF4CAF7A), bold = true),
                nextLine(tipLine, Color(0xFF9AA0A6))
            )
        )
        _tabs.value = _tabs.value + tab
        return id
    }

    private fun appendLinesToTab(
        tabId: String,
        newLines: List<String>,
        overrideColor: Color? = null,
        bold: Boolean = false
    ) {
        val parsed = newLines.map { line ->
            if (overrideColor != null) {
                nextLine(line, overrideColor, bold)
            } else {
                TerminalLine(lineSeq++, AnsiParser.parse(line))
            }
        }
        updateTab(tabId) { tab ->
            tab.copy(lines = (tab.lines + parsed).takeLast(1500))
        }
    }

    private fun nextLine(text: String, color: Color? = null, bold: Boolean = false): TerminalLine {
        return TerminalLine(lineSeq++, listOf(AnsiSpan(text, color, isBold = bold)))
    }

    private fun updateTab(tabId: String, transform: (TerminalTabState) -> TerminalTabState) {
        _tabs.value = _tabs.value.map { if (it.id == tabId) transform(it) else it }
    }
}
