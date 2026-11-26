// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.linux

import android.content.Context
import android.net.Uri
import android.os.Build
import com.example.util.TarExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class SetupProgress(
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val stepTitle: String = "",
    val logs: List<String> = emptyList(),
    val error: String? = null,
    val completed: Boolean = false
)

data class SelfTestItem(
    val command: String,
    val passed: Boolean,
    val output: String,
    val note: String = ""
)

data class LinuxDiagnostics(
    val rootfsReady: Boolean,
    val prootAvailable: Boolean,
    val executionMode: String,
    val abi: String,
    val rootfsBytes: Long,
    val projectsBytes: Long,
    val setupInstructions: String
)

class RootfsManager(
    private val context: Context,
    private val env: LinuxEnvironment
) {
    private val _setupState = MutableStateFlow(SetupProgress())
    val setupState: StateFlow<SetupProgress> = _setupState.asStateFlow()

    fun hasBundledRootfsAsset(): Boolean = runCatching {
        context.assets.open("rootfs.tar.gz").use { true }
    }.getOrDefault(false)

    suspend fun initializeEnvironment(installPackages: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        fun log(msg: String, prog: Float, title: String) {
            logs += msg
            _setupState.value = SetupProgress(
                isRunning = true,
                progress = prog,
                stepTitle = title,
                logs = logs.takeLast(80),
                completed = false
            )
        }

        runCatching {
            log("Initializing YSCode Linux workspace...", 0.10f, "Preparing directories")
            ensureBaseDirs()

            if (hasBundledRootfsAsset()) {
                log("Found bundled rootfs.tar.gz in APK assets. Extracting...", 0.25f, "Extracting rootfs")
                context.assets.open("rootfs.tar.gz").use { input ->
                    val count = TarExtractor.extractTarGz(input, env.rootfsDir) { files, entry ->
                        log("Extracted $files files ($entry)", (0.25f + (files % 500) / 1000f).coerceAtMost(0.75f), "Extracting rootfs")
                    }
                    log("Extracted $count rootfs entries.", 0.78f, "Configuring rootfs")
                }
            } else if (installPackages && env.prootBinary.exists() && !env.rawProotAndRootfs()) {
                // First launch: download + install Linux automatically (no user commands needed).
                log("Downloading Alpine Linux (one time, needs internet)...", 0.15f, "Downloading Linux")
                var prog = 0.2f
                val stepRegex = Regex("""\((\d+)/(\d+)\) Installing""")
                val res = BuiltinShellCommands(env).bootstrapAlpine { line ->
                    stepRegex.find(line)?.let { m ->
                        val (n, total) = m.destructured
                        prog = 0.40f + 0.50f * (n.toFloat() / total.toFloat().coerceAtLeast(1f))
                    } ?: run {
                        if (line.contains("Extracted")) prog = 0.35f
                        else if (line.contains("Probing")) prog = 0.38f
                    }
                    val title = if (prog < 0.38f) "Downloading Linux" else "Installing Python, Node, Git"
                    log(line.take(160), prog, title)
                }
                if (!env.rawProotAndRootfs()) {
                    throw IllegalStateException(res.stderr.ifBlank { "Linux download failed. Check internet and tap Retry." })
                }
                if (res.exitCode != 0) log("Finished with warnings. You can run `pkg fix-linux` in Terminal.", 0.92f, "Finishing")
            } else {
                log("No pre-bundled rootfs.tar.gz found; configuring Android POSIX sandbox.", 0.45f, "Configuring local POSIX environment")
            }

            writeBaseConfigFiles()
            log("Wrote /etc/resolv.conf, ~/.bashrc, and setup-packages.sh", 0.88f, "Configuring shell")

            if (installPackages && env.hasProotAndRootfs()) {
                log("PRoot detected — base packages script ready at /root/setup-packages.sh", 0.95f, "Finalizing")
            }

            log("Linux environment ready (${if (env.hasProotAndRootfs()) "PRoot arm64" else "Android POSIX Shell"})", 1.0f, "Complete")
            _setupState.value = SetupProgress(
                isRunning = false,
                progress = 1f,
                stepTitle = "Linux workspace ready",
                logs = logs.takeLast(80),
                completed = true
            )
        }.onFailure { err ->
            logs += "ERROR: ${err.message}"
            _setupState.value = SetupProgress(
                isRunning = false,
                progress = 0f,
                stepTitle = "Setup failed",
                logs = logs.takeLast(80),
                error = err.message ?: "Unknown setup error",
                completed = false
            )
        }
    }

    suspend fun importRootfsTarGz(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val logs = mutableListOf("Opening selected rootfs archive...")
        _setupState.value = SetupProgress(isRunning = true, progress = 0.1f, stepTitle = "Importing rootfs", logs = logs)
        runCatching {
            ensureBaseDirs()
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("Unable to open selected file")
            val count = stream.use { input ->
                TarExtractor.extractTarGz(input, env.rootfsDir) { files, entry ->
                    if (files % 200 == 0) {
                        logs += "Extracted $files entries ($entry)"
                        _setupState.value = SetupProgress(
                            isRunning = true,
                            progress = 0.2f + ((files % 1000) / 1500f),
                            stepTitle = "Extracting rootfs ($files)",
                            logs = logs.takeLast(60)
                        )
                    }
                }
            }
            writeBaseConfigFiles()
            logs += "Imported $count entries successfully."
            _setupState.value = SetupProgress(isRunning = false, progress = 1f, stepTitle = "Import complete", logs = logs, completed = true)
            count
        }.onFailure { err ->
            logs += "Import failed: ${err.message}"
            _setupState.value = SetupProgress(isRunning = false, progress = 0f, stepTitle = "Import failed", logs = logs, error = err.message)
        }
    }

    suspend fun repairEnvironment(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureBaseDirs()
            writeBaseConfigFiles()
        }
    }

    suspend fun resetRootfs(preserveProjects: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val backupProjectsDir = File(context.cacheDir, "tmp_projects_preserve")
            if (preserveProjects && env.projectsDir.exists()) {
                backupProjectsDir.deleteRecursively()
                env.projectsDir.copyRecursively(backupProjectsDir, overwrite = true)
            }
            env.rootfsDir.deleteRecursively()
            ensureBaseDirs()
            writeBaseConfigFiles()
            if (preserveProjects && backupProjectsDir.exists()) {
                backupProjectsDir.copyRecursively(env.projectsDir, overwrite = true)
                backupProjectsDir.deleteRecursively()
            }
            Unit
        }
    }

    suspend fun runSelfTest(runner: ProotRunner): List<SelfTestItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SelfTestItem>()
        // git / ripgrep can be missing when the first bulk `apk add` was cut short; install them one by one.
        runner.exec(
            "for p in git ripgrep; do b=\$p; [ \$p = ripgrep ] && b=rg; command -v \$b >/dev/null 2>&1 || apk add --no-cache \$p >/dev/null 2>&1; done; true",
            env.rootHomeDir, emptyMap(), timeoutMs = 240_000L
        )
        val checks = listOf(
            "ls -la" to "Workspace directory listing",
            "pwd" to "Working directory resolution",
            "uname -a" to "Kernel / architecture info",
            "git --version" to "Git CLI availability",
            "python3 -V" to "Python runtime",
            "node -v" to "Node.js runtime",
            "rg --version" to "Ripgrep fast search"
        )
        for ((cmd, desc) in checks) {
            val res = runner.exec(cmd, env.rootHomeDir, emptyMap(), timeoutMs = 8_000L)
            val combined = (res.stdout + "\n" + res.stderr).trim()
            val ok = res.exitCode == 0 && combined.isNotBlank()
            val note = when {
                ok -> desc
                cmd.startsWith("git") || cmd.startsWith("python3") || cmd.startsWith("node") || cmd.startsWith("rg") ->
                    "Not installed and auto-install failed (needs internet). Try: Repair, or run `apk add git ripgrep` in Terminal."
                else -> desc
            }
            results += SelfTestItem(
                command = cmd,
                passed = ok,
                output = combined.ifBlank { "(exit ${res.exitCode})" },
                note = note
            )
        }
        results
    }

    suspend fun getDiagnostics(): LinuxDiagnostics = withContext(Dispatchers.IO) {
        val prootOk = env.hasProotAndRootfs()
        LinuxDiagnostics(
            rootfsReady = env.rootHomeDir.exists() && env.projectsDir.exists(),
            prootAvailable = prootOk,
            executionMode = if (prootOk) "PRoot + Arm64 Rootfs" else "Android POSIX Shell + Built-in Kotlin Tools",
            abi = Build.SUPPORTED_ABIS.joinToString(", "),
            rootfsBytes = dirSize(env.rootfsDir),
            projectsBytes = dirSize(env.projectsDir),
            setupInstructions = buildString {
                appendLine("To bundle a full Debian/Alpine arm64 userland inside the APK:")
                appendLine("1. Place arm64 proot binaries in app/src/main/jniLibs/arm64-v8a/libproot.so & libproot-loader.so")
                appendLine("2. Place Debian slim or Alpine arm64 tarball in app/src/main/assets/rootfs.tar.gz (or import via Settings -> Linux -> Import rootfs.tar.gz)")
                appendLine("3. Run /root/setup-packages.sh to install git, python3, nodejs, npm, ripgrep, curl.")
            }
        )
    }

    fun ensureBaseDirs() {
        listOf(
            env.rootfsDir,
            env.rootHomeDir,
            env.projectsDir,
            env.globalSkillsDir,
            env.globalCommandsDir,
            File(env.rootHomeDir, ".yscode/toolchains"),
            env.checkpointsDir,
            env.tmpDir,
            env.etcDir,
            File(env.rootfsDir, "usr/bin"),
            File(env.rootfsDir, "usr/local/bin"),
            File(env.rootfsDir, "bin")
        ).forEach { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    private fun writeBaseConfigFiles() {
        File(env.etcDir, "resolv.conf").writeText(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
        )
        val bashrc = File(env.rootHomeDir, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(
                """
                export HOME=/root
                export TERM=xterm-256color
                export LANG=C.UTF-8
                alias ll='ls -la'
                """.trimIndent() + "\n"
            )
        }
        val setupScript = File(env.rootHomeDir, "setup-packages.sh")
        setupScript.writeText(
            """
            #!/usr/bin/env sh
            set -e
            if command -v apt-get >/dev/null 2>&1; then
              apt-get update && apt-get install -y git curl ca-certificates bash coreutils grep sed findutils python3 nodejs npm ripgrep
            elif command -v apk >/dev/null 2>&1; then
              apk update && apk add --no-cache git curl ca-certificates bash coreutils grep sed findutils python3 nodejs npm ripgrep
            else
              echo "Running in Android POSIX shell; install a Debian/Alpine rootfs to use apt/apk."
              exit 0
            fi
            """.trimIndent() + "\n"
        )
        setupScript.setExecutable(true, false)
        env.installPackageShims()
    }

    private fun dirSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }
}
