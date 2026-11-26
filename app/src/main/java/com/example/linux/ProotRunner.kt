// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.linux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val durationMs: Long = 0L
)

data class BackgroundJobInfo(
    val id: String,
    val command: String,
    val cwd: String,
    val startedAt: Long,
    val isRunning: Boolean,
    val exitCode: Int? = null
)

data class AgentTerminalLine(
    val text: String,
    val isStderr: Boolean = false,
    val isCommandHeader: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class ProotRunner(
    private val env: LinuxEnvironment
) {
    private class ActiveJob(
        val id: String,
        val command: String,
        val cwd: String,
        val process: Process,
        val startedAt: Long = System.currentTimeMillis(),
        val outputBuffer: StringBuffer = StringBuffer(),
        @Volatile var exitCode: Int? = null,
        @Volatile var isRunning: Boolean = true
    )

    private val jobs = ConcurrentHashMap<String, ActiveJob>()
    private val runningForegroundProcesses = ConcurrentHashMap<String, Process>()
    private val builtinCommands = BuiltinShellCommands(env)

    private val _activeJobCount = MutableStateFlow(0)
    val activeJobCount: StateFlow<Int> = _activeJobCount.asStateFlow()

    private val _backgroundJobs = MutableStateFlow<List<BackgroundJobInfo>>(emptyList())
    val backgroundJobs: StateFlow<List<BackgroundJobInfo>> = _backgroundJobs.asStateFlow()

    private val _agentTerminalLines = MutableSharedFlow<AgentTerminalLine>(replay = 120, extraBufferCapacity = 256)
    val agentTerminalLines: SharedFlow<AgentTerminalLine> = _agentTerminalLines.asSharedFlow()

    suspend fun exec(
        command: String,
        cwd: File,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 120_000L,
        mirrorToAgentTerminal: Boolean = true,
        onLine: (String) -> Unit = {}
    ): ExecResult = withContext(Dispatchers.IO) {
        val effectiveTimeout = timeoutMs.coerceIn(1_000L, 600_000L)
        val workingDir = if (cwd.exists() && cwd.isDirectory) cwd else env.rootHomeDir.apply { mkdirs() }
        val start = System.currentTimeMillis()
        val procId = UUID.randomUUID().toString()

        if (mirrorToAgentTerminal) {
            _agentTerminalLines.tryEmit(AgentTerminalLine("$ $command", isCommandHeader = true))
        }

        // Try built-in Kotlin commands first (e.g. git, curl, wget, unzip, pkg, help)
        val builtinResult = builtinCommands.tryExecute(command, workingDir) { line ->
            onLine(line)
            if (mirrorToAgentTerminal) {
                _agentTerminalLines.tryEmit(AgentTerminalLine(line, isStderr = false))
            }
        }
        if (builtinResult != null) {
            if (mirrorToAgentTerminal && builtinResult.stderr.isNotBlank()) {
                builtinResult.stderr.lines().forEach { errLine ->
                    _agentTerminalLines.tryEmit(AgentTerminalLine(errLine, isStderr = true))
                }
            }
            return@withContext builtinResult
        }

        val process = buildProcess(command, workingDir, extraEnv)
        runningForegroundProcesses[procId] = process
        updateActiveCount()

        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()

        try {
            val completed = withTimeoutOrNull(effectiveTimeout) {
                coroutineScope {
                    val outJob = async(Dispatchers.IO) {
                        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val text = line!!
                                synchronized(stdoutBuilder) {
                                    if (stdoutBuilder.length < 250_000) {
                                        stdoutBuilder.appendLine(text)
                                    }
                                }
                                onLine(text)
                                if (mirrorToAgentTerminal) {
                                    _agentTerminalLines.tryEmit(AgentTerminalLine(text, isStderr = false))
                                }
                            }
                        }
                    }
                    val errJob = async(Dispatchers.IO) {
                        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val text = line!!
                                synchronized(stderrBuilder) {
                                    if (stderrBuilder.length < 250_000) {
                                        stderrBuilder.appendLine(text)
                                    }
                                }
                                onLine(text)
                                if (mirrorToAgentTerminal) {
                                    _agentTerminalLines.tryEmit(AgentTerminalLine(text, isStderr = true))
                                }
                            }
                        }
                    }
                    val code = process.waitFor()
                    outJob.await()
                    errJob.await()
                    code
                }
            }

            if (completed == null) {
                destroyProcessTree(process)
                if (mirrorToAgentTerminal) {
                    _agentTerminalLines.tryEmit(AgentTerminalLine("[Timed out after ${effectiveTimeout / 1000}s]", isStderr = true))
                }
                ExecResult(
                    exitCode = 124,
                    stdout = stdoutBuilder.toString().trimEnd(),
                    stderr = (stderrBuilder.toString() + "\nCommand timed out after ${effectiveTimeout / 1000}s").trim(),
                    timedOut = true,
                    durationMs = System.currentTimeMillis() - start
                )
            } else {
                ExecResult(
                    exitCode = completed,
                    stdout = stdoutBuilder.toString().trimEnd(),
                    stderr = stderrBuilder.toString().trimEnd(),
                    timedOut = false,
                    durationMs = System.currentTimeMillis() - start
                )
            }
        } catch (ce: CancellationException) {
            destroyProcessTree(process)
            if (mirrorToAgentTerminal) {
                _agentTerminalLines.tryEmit(AgentTerminalLine("^C [Cancelled]", isStderr = true))
            }
            throw ce
        } catch (t: Throwable) {
            destroyProcessTree(process)
            ExecResult(
                exitCode = 1,
                stdout = stdoutBuilder.toString().trimEnd(),
                stderr = (stderrBuilder.toString() + "\n${t.message}").trim(),
                durationMs = System.currentTimeMillis() - start
            )
        } finally {
            runningForegroundProcesses.remove(procId)
            updateActiveCount()
        }
    }

    fun startBackgroundJob(
        command: String,
        cwd: File,
        extraEnv: Map<String, String> = emptyMap()
    ): BackgroundJobInfo {
        val workingDir = if (cwd.exists() && cwd.isDirectory) cwd else env.rootHomeDir.apply { mkdirs() }
        val id = "job_" + UUID.randomUUID().toString().take(8)
        val process = buildProcess(command, workingDir, extraEnv)
        val job = ActiveJob(id = id, command = command, cwd = workingDir.absolutePath, process = process)
        jobs[id] = job
        _agentTerminalLines.tryEmit(AgentTerminalLine("[bg:$id] $ $command", isCommandHeader = true))

        Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val txt = line!!
                        if (job.outputBuffer.length < 250_000) {
                            job.outputBuffer.append(txt).append('\n')
                        }
                        _agentTerminalLines.tryEmit(AgentTerminalLine("[$id] $txt"))
                    }
                }
            }
        }.start()

        Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val txt = line!!
                        if (job.outputBuffer.length < 250_000) {
                            job.outputBuffer.append(txt).append('\n')
                        }
                        _agentTerminalLines.tryEmit(AgentTerminalLine("[$id] $txt", isStderr = true))
                    }
                }
            }
        }.start()

        Thread {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            job.exitCode = code
            job.isRunning = false
            _agentTerminalLines.tryEmit(AgentTerminalLine("[bg:$id exited with $code]", isCommandHeader = true))
            publishJobs()
        }.start()

        publishJobs()
        return BackgroundJobInfo(id, command, workingDir.absolutePath, job.startedAt, true, null)
    }

    fun getJobOutput(id: String): Pair<BackgroundJobInfo?, String> {
        val job = jobs[id] ?: return null to ""
        val info = BackgroundJobInfo(job.id, job.command, job.cwd, job.startedAt, job.isRunning, job.exitCode)
        return info to job.outputBuffer.toString()
    }

    fun killJob(id: String): Boolean {
        val job = jobs[id] ?: return false
        destroyProcessTree(job.process)
        job.isRunning = false
        job.exitCode = 137
        publishJobs()
        return true
    }

    fun killAll() {
        runningForegroundProcesses.values.forEach { destroyProcessTree(it) }
        runningForegroundProcesses.clear()
        jobs.values.forEach {
            destroyProcessTree(it.process)
            it.isRunning = false
            it.exitCode = 137
        }
        publishJobs()
    }

    fun spawnInteractiveProcess(command: String, cwd: File, extraEnv: Map<String, String> = emptyMap()): Process {
        val workingDir = if (cwd.exists() && cwd.isDirectory) cwd else env.rootHomeDir.apply { mkdirs() }
        return buildProcess(command, workingDir, extraEnv, redirectError = false)
    }

    private fun buildProcess(
        command: String,
        cwd: File,
        extraEnv: Map<String, String>,
        redirectError: Boolean = false
    ): Process {
        val useProot = env.hasProotAndRootfs()
        val cmdList = if (useProot) {
            env.ensureHostPathAccessible(cwd)
            env.prootArgv(
                if (extraEnv.isEmpty()) command else
                    extraEnv.entries.joinToString(" ") { "export ${it.key}='${it.value.replace("'", "'\\''")}';" } + " " + command,
                env.toVirtualPath(cwd)
            )
        } else {
            listOf("/system/bin/sh", "-c", command)
        }

        val pb = ProcessBuilder(cmdList)
            .directory(cwd)
            .redirectErrorStream(redirectError)

        pb.environment().apply {
            clear()
            if (useProot) {
                putAll(env.prootHostEnv())
                // extraEnv (user vars) must be applied INSIDE the chroot: prefix as `env K=V`
            } else {
                putAll(env.buildEnv(extraEnv))
            }
        }
        return pb.start()
    }

    private fun destroyProcessTree(process: Process) {
        runCatching {
            process.destroy()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (process.isAlive) process.destroyForcibly()
            } else {
                process.destroy()
            }
        }
    }

    private fun publishJobs() {
        _backgroundJobs.value = jobs.values.map {
            BackgroundJobInfo(it.id, it.command, it.cwd, it.startedAt, it.isRunning, it.exitCode)
        }.sortedByDescending { it.startedAt }
        updateActiveCount()
    }

    private fun updateActiveCount() {
        val bgRunning = jobs.values.count { it.isRunning }
        _activeJobCount.value = runningForegroundProcesses.size + bgRunning
    }
}
