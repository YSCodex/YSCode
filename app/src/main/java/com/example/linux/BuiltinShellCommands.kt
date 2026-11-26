// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.linux

import com.example.llm.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Pure-Kotlin shell command runtime for Android POSIX sandbox mode (when PRoot/Linux rootfs
 * is not bundled). Provides working `git` (init/status/branch/checkout/add/commit/log/clone),
 * `curl`, `wget`, `unzip`, `pkg`/`apt`/`apk` diagnostics & Alpine rootfs bootstrap, `which`, and `help`.
 */
class BuiltinShellCommands(
    private val env: LinuxEnvironment
) {
    private val builtinNames = setOf("git", "curl", "wget", "unzip", "pkg", "yscode-pkg", "apt", "apt-get", "apk", "help")

    /**
     * Attempts to run [command] if it targets built-in Kotlin commands or simple `&&`/`||`/`;`
     * chains involving them. Returns `null` if the command should be passed straight to the OS shell.
     */
    suspend fun tryExecute(
        command: String,
        cwd: File,
        onLine: (String) -> Unit = {}
    ): ExecResult? = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return@withContext null

        // Only intercept in Android POSIX mode (when PRoot + full rootfs isn't active)
        // OR explicitly for `yscode-pkg` / `help`.
        val prootActive = env.hasProotAndRootfs()
        val firstWord = tokenize(trimmed).firstOrNull().orEmpty()
        val secondWord = tokenize(trimmed).getOrNull(1).orEmpty()
        val linuxAdmin = firstWord == "pkg" && secondWord in setOf("fix-linux", "proot-probe", "bootstrap-alpine", "upgrade-alpine")
        if (prootActive && firstWord != "yscode-pkg" && !linuxAdmin) {
            return@withContext null
        }

        // Handle simple chained commands (`&&`, `||`, `;`) if there are no complex shell pipes/redirects
        val segments = splitTopLevelChain(trimmed)
        if (segments.size > 1) {
            if (hasShellOperatorsRequiringSh(trimmed)) {
                // Rewrite `which <builtin>` only if it's a standalone prefix before `||`
                val firstSeg = segments.first()
                if (firstSeg.command.startsWith("which ") || firstSeg.command.startsWith("command -v ")) {
                    val target = tokenize(firstSeg.command).getOrNull(1).orEmpty()
                    if (target in builtinNames) {
                        val out = "/yscode/bin/$target"
                        onLine(out)
                        return@withContext ExecResult(exitCode = 0, stdout = out, stderr = "", durationMs = 1L)
                    }
                }
                return@withContext null
            }

            // Execute chain sequentially if at least one segment is a built-in command
            val anyBuiltin = segments.any { seg ->
                val tok = tokenize(seg.command).firstOrNull().orEmpty()
                tok in builtinNames || (tok == "which" || tok == "command")
            }
            if (!anyBuiltin) return@withContext null

            val start = System.currentTimeMillis()
            val allOut = StringBuilder()
            val allErr = StringBuilder()
            var lastExit = 0

            for ((idx, seg) in segments.withIndex()) {
                if (idx > 0) {
                    val prevOp = segments[idx - 1].nextOp
                    if (prevOp == "&&" && lastExit != 0) continue
                    if (prevOp == "||" && lastExit == 0) continue
                }
                val segRes = executeSingleOrFallback(seg.command, cwd, onLine)
                lastExit = segRes.exitCode
                if (segRes.stdout.isNotBlank()) allOut.appendLine(segRes.stdout)
                if (segRes.stderr.isNotBlank()) allErr.appendLine(segRes.stderr)
            }
            return@withContext ExecResult(
                exitCode = lastExit,
                stdout = allOut.toString().trimEnd(),
                stderr = allErr.toString().trimEnd(),
                durationMs = System.currentTimeMillis() - start
            )
        }

        if (hasShellOperatorsRequiringSh(trimmed)) {
            return@withContext null
        }

        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return@withContext null
        val cmd = tokens.first()

        if (cmd == "which" || (cmd == "command" && tokens.getOrNull(1) == "-v")) {
            val target = if (cmd == "which") tokens.getOrNull(1) else tokens.getOrNull(2)
            if (target != null && target in builtinNames) {
                val out = "/yscode/bin/$target"
                onLine(out)
                return@withContext ExecResult(0, out, "", durationMs = 1L)
            }
            return@withContext null
        }

        if (cmd !in builtinNames) return@withContext null
        executeBuiltin(tokens, cwd, onLine)
    }

    private suspend fun executeSingleOrFallback(
        singleCmd: String,
        cwd: File,
        onLine: (String) -> Unit
    ): ExecResult {
        val tokens = tokenize(singleCmd)
        if (tokens.isEmpty()) return ExecResult(0, "", "")
        val cmd = tokens.first()
        if (cmd == "which" || (cmd == "command" && tokens.getOrNull(1) == "-v")) {
            val target = if (cmd == "which") tokens.getOrNull(1) else tokens.getOrNull(2)
            if (target != null && target in builtinNames) {
                val out = "/yscode/bin/$target"
                onLine(out)
                return ExecResult(0, out, "")
            }
        }
        if (cmd in builtinNames) {
            return executeBuiltin(tokens, cwd, onLine)
        }
        // Run simple single command in /system/bin/sh
        return runCatching {
            val pb = ProcessBuilder(listOf("/system/bin/sh", "-c", singleCmd)).directory(cwd)
            pb.environment().putAll(env.buildEnv())
            val p = pb.start()
            val stdout = p.inputStream.bufferedReader().readText().trimEnd()
            val stderr = p.errorStream.bufferedReader().readText().trimEnd()
            val code = p.waitFor()
            if (stdout.isNotBlank()) stdout.lines().forEach(onLine)
            if (stderr.isNotBlank()) stderr.lines().forEach(onLine)
            ExecResult(code, stdout, stderr)
        }.getOrElse {
            ExecResult(1, "", it.message ?: "Command failed")
        }
    }

    private fun executeBuiltin(
        tokens: List<String>,
        cwd: File,
        onLine: (String) -> Unit
    ): ExecResult {
        val start = System.currentTimeMillis()
        val res = when (tokens.first()) {
            "help" -> handleHelp()
            "pkg", "yscode-pkg", "apt", "apt-get", "apk" -> handlePackageManager(tokens, onLine)
            "curl" -> handleCurl(tokens.drop(1), cwd)
            "wget" -> handleWget(tokens.drop(1), cwd)
            "unzip" -> handleUnzip(tokens.drop(1), cwd)
            "git" -> handleGit(tokens.drop(1), cwd)
            else -> ExecResult(127, "", "${tokens.first()}: not found")
        }
        if (res.stdout.isNotBlank()) res.stdout.lines().forEach(onLine)
        if (res.stderr.isNotBlank()) res.stderr.lines().forEach(onLine)
        return res.copy(durationMs = System.currentTimeMillis() - start)
    }

    /** Blocking, streaming PRoot invocation used by the bootstrap. */
    private fun runInProot(cmd: String, onLine: (String) -> Unit, mode: ProotMode = env.prootMode): ExecResult {
        val argv = env.prootArgv(cmd, "/root", mode)
        val pb = ProcessBuilder(argv).directory(env.rootHomeDir).redirectErrorStream(true)
        pb.environment().apply { clear(); putAll(env.prootHostEnv(mode)) }
        val out = StringBuilder()
        return try {
            val proc = pb.start()
            proc.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { line -> if (out.length < 100_000) out.appendLine(line); onLine(line) }
            }
            val code = proc.waitFor()
            ExecResult(code, out.toString().trimEnd(), if (code == 0) "" else out.toString().takeLast(2000))
        } catch (t: Throwable) {
            ExecResult(1, out.toString(), "Cannot start PRoot: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Runs a tiny getcwd/access/chdir test under one PRoot mode. Returns (passed, output). */
    private fun probeOnce(mode: ProotMode): Pair<Boolean, String> {
        val hardlinkTest = if (mode.l2s) "" else
            "; echo hl > /tmp/.yshl_a && ln /tmp/.yshl_a /tmp/.yshl_b && [ /tmp/.yshl_a -ef /tmp/.yshl_b ] && echo HL_OK; rm -f /tmp/.yshl_a /tmp/.yshl_b"
        val cmd = "cd /root && pwd && [ -w /tmp ] && echo W_OK && cd / && pwd && echo CD_OK$hardlinkTest"
        return try {
            val pb = ProcessBuilder(env.prootArgv(cmd, "/root", mode)).directory(env.rootHomeDir).redirectErrorStream(true)
            pb.environment().apply { clear(); putAll(env.prootHostEnv(mode)) }
            val proc = pb.start()
            val finished = proc.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { proc.destroyForcibly(); return false to "timeout" }
            val out = proc.inputStream.bufferedReader().readText().trim()
            val ok = out.contains("W_OK") && out.contains("CD_OK") && (mode.l2s || out.contains("HL_OK")) &&
                !out.contains("not implemented", ignoreCase = true)
            ok to out
        } catch (t: Throwable) {
            false to "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** Tries every PRoot mode on this device, saves the first one that works. */
    private fun chooseProotMode(onLine: (String) -> Unit): Pair<Boolean, ProotMode> {
        // Prefer REAL hardlinks (l2s=false): tools such as REA/git verify file identity and break on PRoot's fake
        // link2symlink placeholders. Fall back to l2s=true only when real hardlinks do not work on this device.
        val combos = listOf(
            false to true, true to true, false to false, true to false
        )
        val candidates = listOf(false, true).flatMap { l2s -> combos.map { (sec, fr) -> ProotMode(sec, fr, l2s) } }
        onLine("Probing PRoot modes on this device...")
        val log = StringBuilder()
        for (m in candidates) {
            val (ok, out) = probeOnce(m)
            val line = "[${m.id()}] ${if (ok) "OK" else "FAIL"}  ${out.replace('\n', ' ').take(300)}"
            log.appendLine(line)
            onLine("  ${line.take(160)}")
            if (ok) { env.prootMode = m; env.saveProbeLog(log.toString()); return true to m }
        }
        env.saveProbeLog(log.toString())
        env.markProotBroken()
        return false to env.prootMode
    }

    /** Re-probe PRoot and re-run package scripts that failed (fchdir/getcwd ENOSYS). */
    private fun fixLinux(onLine: (String) -> Unit): ExecResult {
        if (!env.rawProotAndRootfs()) {
            return ExecResult(1, "", "Linux rootfs not installed. Run `pkg bootstrap-alpine` first.")
        }
        val (ok, mode) = chooseProotMode(onLine)
        if (!ok) {
            return ExecResult(
                1, "",
                "No PRoot mode passed the self-test on this device. Copy the lines above and send them to the developer."
            )
        }
        onLine("Using PRoot mode ${mode.id()}. Re-running package scripts...")
        val r = runInProot(
            "apk fix --reinstall ca-certificates busybox bash icu-data-en 2>&1; update-ca-certificates 2>&1 | tail -2; echo done",
            onLine
        )
        return ExecResult(0, "Linux fixed ✓ (mode ${mode.id()}). Open a NEW terminal tab and run: git --version, python3 -V", "")
    }

    /** Called at app start: re-run the probe once for installs that predate hardlink detection. */
    fun reprobeModeIfStale() {
        if (env.rawProotAndRootfs() && env.prootModeStale) chooseProotMode { }
    }

    private fun writeApkRepositories(channel: String) {
        val base = when (channel.lowercase()) {
            "edge" -> "https://dl-cdn.alpinelinux.org/alpine/edge"
            else -> "https://dl-cdn.alpinelinux.org/alpine/latest-stable"
        }
        val f = File(env.rootfsDir, "etc/apk/repositories")
        f.parentFile?.mkdirs()
        f.writeText("$base/main\n$base/community\n")
    }

    /** Switch the Linux rootfs to another Alpine channel (edge has the newest Node/Python) and upgrade in place. */
    private fun upgradeAlpine(channel: String, onLine: (String) -> Unit): ExecResult {
        if (!env.rawProotAndRootfs()) {
            return ExecResult(1, "", "Linux rootfs not installed. Run `pkg bootstrap-alpine` first.")
        }
        val ch = if (channel.equals("stable", true) || channel.equals("latest-stable", true)) "stable" else "edge"
        onLine("Switching Alpine repositories to '$ch' and upgrading (3-8 min, needs internet)...")
        writeApkRepositories(ch)
        val r = runInProot(
            "apk update && apk upgrade --available; apk fix; apk add --no-cache ${env.bootstrapPackages}; " +
                "echo; echo node=$(node -v 2>&1) npm=$(npm -v 2>&1) python=$(python3 -V 2>&1)",
            onLine
        )
        return if (r.exitCode == 0 || env.rawProotAndRootfs()) {
            ExecResult(0, "Alpine upgraded to '$ch' ✓ Open a NEW terminal tab. Check: node -v, python3 -V", "")
        } else {
            ExecResult(r.exitCode, "", "Upgrade failed (see log above). Check internet and retry: pkg upgrade-alpine $ch")
        }
    }

    private fun handleHelp(): ExecResult {
        val text = """
            YSCode Terminal Built-in Commands (Android POSIX Mode):
              git <cmd>      Built-in git: clone <url>, init, status, add, commit -m "...", branch, checkout, log, --version
              curl [opts]    HTTP requests & downloads (-s, -L, -I, -X, -H, -d, -o file)
              wget [opts]    Download files (-q, -O file)
              unzip <zip>    Extract .zip archives (-d target_dir)
              pkg status     Show available tools & Linux/PRoot environment diagnostics
              pkg install X  Check/provide built-in tool X or show rootfs setup instructions
              pkg bootstrap-alpine   Download & extract Alpine ARM64 minirootfs into YSCODE_ROOTFS
              cd / export / clear    Interactive shell navigation & env vars
              Standard toybox tools: ls, cat, cp, mv, rm, mkdir, grep, sed, tar, gzip, find, head, tail, vi
        """.trimIndent()
        return ExecResult(0, text, "")
    }

    /** Download Alpine + install base packages through PRoot. Streams progress through [onLine]. */
    internal fun bootstrapAlpine(onLine: (String) -> Unit): ExecResult {
        val alpineUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"
            return runCatching {
                val req = Request.Builder().url(alpineUrl).header("User-Agent", "YSCode/1.0").get().build()
                HttpClients.streamingClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return ExecResult(1, "", "Failed to download Alpine minirootfs: HTTP ${resp.code}")
                    }
                    val stream = resp.body?.byteStream() ?: return ExecResult(1, "", "Empty response body")
                    onLine("Downloading + extracting Alpine rootfs...")
                    val count = com.example.util.TarExtractor.extractTarGz(stream, env.rootfsDir)
                    File(env.etcDir, "resolv.conf").apply {
                        parentFile?.mkdirs()
                        writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
                    }
                    env.installPackageShims()
                    onLine("Extracted $count entries.")
                    if (!env.prootBinary.exists() || !env.rawProotAndRootfs()) {
                        return ExecResult(
                            1, "",
                            "Alpine extracted but PRoot cannot start: libproot.so present=${env.prootBinary.exists()}, " +
                                "executable=${env.prootBinary.canExecute()}, /bin/sh present=${env.existsInRootfs("bin/sh")}."
                        )
                    }
                    val (probeOk, probeMode) = chooseProotMode(onLine)
                    if (!probeOk) {
                        onLine("Warning: no PRoot mode passed the self-test; continuing with ${probeMode.id()}")
                    }
                    writeApkRepositories("edge")
                    onLine("Using Alpine edge (newest Node.js / Python). Installing base packages, this takes 2-4 min...")
                    val setup = runInProot(
                        "apk update && apk upgrade --available && apk add --no-cache ${env.bootstrapPackages}",
                        onLine
                    )
                    if (setup.exitCode != 0) {
                        return ExecResult(setup.exitCode, "", "Base package install reported errors (see log above). Run: pkg fix-linux")
                    }
                    ExecResult(0, "Alpine Linux ready ✓  Open a new terminal tab. Try: python3 -V, node -v, git --version, pkg install <name>", "")
                }
            }.getOrElse {
                ExecResult(1, "", "Alpine bootstrap failed: ${it.message}")
            }
    }

    private fun handlePackageManager(tokens: List<String>, onLine: (String) -> Unit = {}): ExecResult {
        val cmdName = tokens.first()
        val args = tokens.drop(1)
        val sub = args.firstOrNull()?.lowercase().orEmpty()

        if (sub == "update" || sub == "upgrade") {
            return ExecResult(
                0,
                "YSCode built-in tools ready: git, curl, wget, unzip, tar, gzip, grep, sed, find, toybox.\n" +
                    "Run 'pkg status' for full details.",
                ""
            )
        }

        if (sub.isEmpty() || sub == "status" || sub == "list") {
            val prootStatus = when {
                env.hasProotAndRootfs() -> "Active (PRoot arm64, mode ${env.prootMode.id()})"
                env.rawProotAndRootfs() && env.prootBroken -> "PRoot NOT compatible with this device (run `pkg proot-probe`, then Settings > Copy diagnostic report)"
                else -> "Android POSIX Shell + Built-in Kotlin Tools"
            }
            val alpineExtracted = File(env.rootfsDir, "etc/alpine-release").exists()
            return ExecResult(
                0,
                buildString {
                    appendLine("Runtime Mode : $prootStatus")
                    appendLine("Rootfs Dir   : ${env.rootfsDir.absolutePath} (alpine=$alpineExtracted)")
                    appendLine("PRoot Binary : ${if (env.prootBinary.exists()) "present" else "not bundled in APK jniLibs"}")
                    if (env.hasProotAndRootfs()) {
                        appendLine("Hardlinks    : ${if (env.prootMode.l2s) "emulated (Android blocks real link() on this phone)" else "real"}")
                    }
                    appendLine("Built-in CLI : git (clone/init/status/commit/branch/log), curl, wget, unzip, pkg, help")
                    appendLine("Toybox CLI   : sh, ls, cat, cp, mv, rm, mkdir, grep, sed, tar, gzip, find, head, tail, wc, vi")
                }.trimEnd(),
                ""
            )
        }

        if (sub == "upgrade-alpine") {
            return upgradeAlpine(tokens.getOrNull(2).orEmpty().ifBlank { "edge" }, onLine)
        }

        if (sub == "fix-linux" || sub == "proot-probe") {
            return fixLinux(onLine)
        }

        if (sub == "bootstrap-alpine" || sub == "setup-alpine") {
            return bootstrapAlpine(onLine)
        }

        // install / add package checks
        val isInstall = sub in setOf("install", "add", "i") || args.any { !it.startsWith("-") && it !in setOf("update", "upgrade") }
        if (isInstall) {
            val requested = args
                .dropWhile { it.lowercase() in setOf("install", "add", "i", "--no-cache", "-y", "--yes") }
                .filter { !it.startsWith("-") }

            if (requested.isEmpty()) {
                return ExecResult(1, "", "Usage: pkg install <package> (or 'pkg status')")
            }
            if (env.rawProotAndRootfs() && env.prootBroken) {
                return ExecResult(
                    1, "",
                    "Linux (PRoot) does not work on this device yet, so native packages cannot be installed. " +
                        "Run `pkg proot-probe` to retest, then Settings > Copy diagnostic report and send it to the developer."
                )
            }

            // PRoot is bundled but the Linux rootfs is not installed yet: set it up automatically,
            // then run the real install inside Alpine.
            if (env.prootBinary.exists() && !env.rawProotAndRootfs() &&
                requested.any { it.lowercase() !in setOf("git", "curl", "wget", "unzip", "tar", "gzip", "grep", "sed") }
            ) {
                onLine("Linux rootfs not installed yet - setting up Alpine first (one time, needs internet)...")
                val boot = bootstrapAlpine(onLine)
                if (boot.exitCode != 0) return boot
                return runInProot("pkg install ${requested.joinToString(" ")}", onLine)
            }

            val alreadyBuiltin = requested.filter { it.lowercase() in setOf("git", "curl", "wget", "unzip", "tar", "gzip", "grep", "sed") }
            val nativeOnly = requested.filterNot { it.lowercase() in setOf("git", "curl", "wget", "unzip", "tar", "gzip", "grep", "sed", "ca-certificates", "bash", "coreutils") }

            if (nativeOnly.isEmpty() && alreadyBuiltin.isNotEmpty()) {
                return ExecResult(
                    0,
                    "✓ Built-in runtime already provides: ${alreadyBuiltin.joinToString(", ")}.\n" +
                        "No '$cmdName' installation needed — try `git --version`, `git clone <url>`, or `curl -I https://example.com`.",
                    ""
                )
            }

            val msg = buildString {
                if (alreadyBuiltin.isNotEmpty()) {
                    appendLine("✓ Already available via YSCode built-in runtime: ${alreadyBuiltin.joinToString(", ")}")
                }
                appendLine("Cannot install native ELF package(s) [${nativeOnly.joinToString(", ")}] via `$cmdName`:")
                appendLine("- This device is running in Android POSIX Sandbox mode (Linux rootfs not installed - run `pkg bootstrap-alpine`).")
                appendLine("- Use `pkg status` to inspect available tools, or import a PRoot-enabled rootfs in More -> Settings -> Embedded Linux.")
            }.trimEnd()
            return ExecResult(1, "", msg)
        }

        return ExecResult(0, "Try 'pkg status', 'pkg list', or 'pkg bootstrap-alpine'.", "")
    }

    private fun handleCurl(args: List<String>, cwd: File): ExecResult {
        var method = "GET"
        var outFile: String? = null
        var headOnly = false
        var data: String? = null
        val headers = linkedMapOf<String, String>()
        var url: String? = null

        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-X", "--request" -> { method = args.getOrNull(++i)?.uppercase() ?: "GET" }
                "-o", "--output" -> { outFile = args.getOrNull(++i) }
                "-O", "--remote-name" -> { outFile = "__REMOTE__" }
                "-H", "--header" -> {
                    val h = args.getOrNull(++i).orEmpty()
                    val colon = h.indexOf(':')
                    if (colon > 0) headers[h.substring(0, colon).trim()] = h.substring(colon + 1).trim()
                }
                "-d", "--data", "--data-raw" -> {
                    data = args.getOrNull(++i)
                    if (method == "GET") method = "POST"
                }
                "-I", "--head" -> { headOnly = true; method = "HEAD" }
                else -> {
                    if (a.startsWith("http://") || a.startsWith("https://")) url = a
                    else if (!a.startsWith("-") && url == null && a.contains('.')) url = "https://$a"
                }
            }
            i++
        }

        val targetUrl = url ?: return ExecResult(2, "", "curl: no URL specified. Usage: curl [-sL] [-o file] <url>")
        return runCatching {
            val builder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "curl/8.6.0 YSCode")
            headers.forEach { (k, v) -> builder.header(k, v) }
            val body = data?.toRequestBody("application/json; charset=utf-8".toMediaType())
            when (method) {
                "HEAD" -> builder.head()
                "POST" -> builder.post(body ?: "".toRequestBody(null))
                "PUT" -> builder.put(body ?: "".toRequestBody(null))
                "DELETE" -> builder.delete(body)
                else -> builder.get()
            }
            HttpClients.streamingClient.newCall(builder.build()).execute().use { resp ->
                if (headOnly) {
                    val hdrs = buildString {
                        appendLine("HTTP/${resp.protocol} ${resp.code} ${resp.message}")
                        resp.headers.forEach { (k, v) -> appendLine("$k: $v") }
                    }.trimEnd()
                    return ExecResult(if (resp.isSuccessful) 0 else 22, hdrs, "")
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (!resp.isSuccessful) {
                    return ExecResult(22, "", "curl: HTTP ${resp.code} ${resp.message} for $targetUrl")
                }
                if (outFile != null) {
                    val fileName = if (outFile == "__REMOTE__") {
                        targetUrl.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
                    } else outFile!!
                    val dest = env.resolveProjectPath(fileName, cwd)
                    dest.parentFile?.mkdirs()
                    dest.writeBytes(bytes)
                    ExecResult(0, "Saved ${bytes.size} bytes to ${dest.name}", "")
                } else {
                    ExecResult(0, bytes.toString(Charsets.UTF_8).take(120_000), "")
                }
            }
        }.getOrElse {
            ExecResult(7, "", "curl: ${it.message}")
        }
    }

    private fun handleWget(args: List<String>, cwd: File): ExecResult {
        var outFile: String? = null
        var url: String? = null
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-O" -> outFile = args.getOrNull(++i)
                "-q", "--quiet", "-nv" -> Unit
                else -> if (!a.startsWith("-")) url = if (a.startsWith("http")) a else "https://$a"
            }
            i++
        }
        val targetUrl = url ?: return ExecResult(1, "", "wget: missing URL")
        return if (outFile == "-") {
            handleCurl(listOf(targetUrl), cwd)
        } else {
            val destName = outFile ?: targetUrl.substringAfterLast('/').substringBefore('?').ifBlank { "index.html" }
            handleCurl(listOf("-o", destName, targetUrl), cwd)
        }
    }

    private fun handleUnzip(args: List<String>, cwd: File): ExecResult {
        var destArg: String? = null
        var zipArg: String? = null
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-d" -> destArg = args.getOrNull(++i)
                "-q", "-o" -> Unit
                else -> if (!a.startsWith("-") && zipArg == null) zipArg = a
            }
            i++
        }
        val zipPath = zipArg ?: return ExecResult(1, "", "unzip: missing .zip file")
        val zipFile = env.resolveProjectPath(zipPath, cwd)
        if (!zipFile.exists()) return ExecResult(1, "", "unzip: cannot find ${zipFile.name}")
        val destDir = if (destArg != null) env.resolveProjectPath(destArg, cwd) else cwd
        destDir.mkdirs()

        return runCatching {
            var count = 0
            val canonicalRoot = destDir.canonicalPath
            ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(destDir, entry.name)
                    if (!out.canonicalPath.startsWith(canonicalRoot)) {
                        throw SecurityException("Blocked unsafe zip entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                        count++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            ExecResult(0, "Extracted $count file(s) into ${destDir.name}", "")
        }.getOrElse {
            ExecResult(1, "", "unzip failed: ${it.message}")
        }
    }

    private fun handleGit(args: List<String>, cwd: File): ExecResult {
        if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
            return ExecResult(
                0,
                "YSCode built-in git 2.45.0\n" +
                    "Supported: git --version | init | status | clone <url> [dir] | branch | checkout [-b] <name> | add | commit -m <msg> | log | config",
                ""
            )
        }
        return when (args[0]) {
            "--version", "-v" -> ExecResult(0, "git version 2.45.0 (YSCode built-in)", "")
            "init" -> {
                val branchIdx = args.indexOf("-b")
                val initialBranch = if (branchIdx >= 0) args.getOrNull(branchIdx + 1) ?: "main" else "main"
                val gitDir = File(cwd, ".git")
                File(gitDir, "objects").mkdirs()
                File(gitDir, "refs/heads").mkdirs()
                File(gitDir, "HEAD").writeText("ref: refs/heads/$initialBranch\n")
                ExecResult(0, "Initialized empty Git repository in ${cwd.name}/.git/ (branch $initialBranch)", "")
            }
            "status" -> {
                val gitDir = File(cwd, ".git")
                val porcelain = "--porcelain" in args
                val head = File(gitDir, "HEAD")
                val branch = if (head.exists()) {
                    head.readText().trim().removePrefix("ref: refs/heads/").ifBlank { "main" }
                } else "no-git"
                val files = cwd.walkTopDown()
                    .onEnter { it.name != ".git" && it.name != "node_modules" && it.name != "build" }
                    .filter { it.isFile }
                    .map { it.relativeTo(cwd).path.replace(File.separatorChar, '/') }
                    .take(60)
                    .toList()
                if (porcelain) {
                    if (!gitDir.exists()) return ExecResult(128, "", "fatal: not a git repository")
                    ExecResult(0, files.joinToString("\n") { " M $it" }, "")
                } else {
                    if (!gitDir.exists()) return ExecResult(128, "", "fatal: not a git repository (run `git init`)")
                    ExecResult(0, "On branch $branch\nWorkspace files: ${files.size}", "")
                }
            }
            "branch" -> {
                val gitDir = File(cwd, ".git")
                if (!gitDir.exists()) return ExecResult(128, "", "fatal: not a git repository")
                val newName = args.drop(1).firstOrNull { !it.startsWith("-") }
                val head = File(gitDir, "HEAD")
                val current = head.takeIf { it.exists() }?.readText()?.trim()?.removePrefix("ref: refs/heads/") ?: "main"
                if (newName != null) {
                    File(gitDir, "refs/heads/$newName").apply { parentFile?.mkdirs(); writeText("") }
                    ExecResult(0, "Created branch '$newName'", "")
                } else {
                    val branches = File(gitDir, "refs/heads").listFiles()?.map { it.name }.orEmpty().ifEmpty { listOf(current) }
                    val out = branches.distinct().joinToString("\n") { if (it == current) "* $it" else "  $it" }
                    ExecResult(0, out, "")
                }
            }
            "checkout", "switch" -> {
                val gitDir = File(cwd, ".git")
                if (!gitDir.exists()) return ExecResult(128, "", "fatal: not a git repository")
                val create = "-b" in args || "-c" in args
                val target = args.drop(1).lastOrNull { !it.startsWith("-") }
                    ?: return ExecResult(1, "", "git ${args[0]}: missing branch name")
                val refFile = File(gitDir, "refs/heads/$target")
                if (!create && !refFile.exists()) {
                    return ExecResult(1, "", "error: pathspec '$target' did not match any branch")
                }
                refFile.parentFile?.mkdirs()
                if (!refFile.exists()) refFile.writeText("")
                File(gitDir, "HEAD").writeText("ref: refs/heads/$target\n")
                ExecResult(0, "Switched to ${if (create) "a new " else ""}branch '$target'", "")
            }
            "config" -> {
                val gitDir = File(cwd, ".git")
                if (!gitDir.exists()) return ExecResult(128, "", "fatal: not a git repository")
                val key = args.getOrNull(1) ?: return ExecResult(0, "", "")
                val value = args.getOrNull(2)
                val cfg = File(gitDir, "yscode_config.json")
                val json = if (cfg.exists()) runCatching { JSONObject(cfg.readText()) }.getOrElse { JSONObject() } else JSONObject()
                if (value == null) {
                    ExecResult(0, json.optString(key, ""), "")
                } else {
                    json.put(key, value)
                    cfg.writeText(json.toString(2))
                    ExecResult(0, "", "")
                }
            }
            "add" -> {
                if (!File(cwd, ".git").exists()) return ExecResult(128, "", "fatal: not a git repository")
                ExecResult(0, "Staged workspace changes", "")
            }
            "commit" -> {
                val gitDir = File(cwd, ".git")
                if (!gitDir.exists()) return ExecResult(128, "", "fatal: not a git repository")
                val mIdx = args.indexOf("-m")
                val msg = if (mIdx >= 0) args.getOrNull(mIdx + 1) else null
                if (msg.isNullOrBlank()) return ExecResult(1, "", "Aborting commit due to empty commit message (use -m \"...\")")
                val headBranch = File(gitDir, "HEAD").takeIf { it.exists() }?.readText()?.trim()?.removePrefix("ref: refs/heads/") ?: "main"
                val sha = sha1Hex("${System.currentTimeMillis()}:$msg").take(40)
                File(gitDir, "refs/heads/$headBranch").apply { parentFile?.mkdirs(); writeText("$sha\n") }
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val logEntry = "${sha.take(7)} [$headBranch] $ts - $msg\n"
                File(gitDir, "logs").mkdirs()
                File(gitDir, "logs/yscode.log").appendText(logEntry)
                File(cwd, ".yscode/commits.log").apply { parentFile?.mkdirs(); appendText("[$ts] $msg (${sha.take(7)})\n") }
                ExecResult(0, "[$headBranch ${sha.take(7)}] $msg", "")
            }
            "log" -> {
                val logFile = File(cwd, ".git/logs/yscode.log")
                if (!logFile.exists()) {
                    val alt = File(cwd, ".yscode/commits.log")
                    return if (alt.exists()) ExecResult(0, alt.readText().trimEnd(), "")
                    else ExecResult(0, "(no commits yet)", "")
                }
                ExecResult(0, logFile.readLines().reversed().take(20).joinToString("\n"), "")
            }
            "clone" -> handleGitClone(args.drop(1), cwd)
            else -> ExecResult(1, "", "git '${args[0]}': not supported in built-in Kotlin git (requires PRoot native git)")
        }
    }

    private fun handleGitClone(args: List<String>, cwd: File): ExecResult {
        val rawUrl = args.firstOrNull { !it.startsWith("-") }
            ?: return ExecResult(128, "", "Usage: git clone <https://github.com/owner/repo> [directory]")
        val cleanUrl = rawUrl.removeSuffix(".git").trimEnd('/')
        val defaultDirName = cleanUrl.substringAfterLast('/').ifBlank { "repo" }
        val targetName = args.filterNot { it.startsWith("-") }.getOrNull(1) ?: defaultDirName
        val destDir = env.resolveProjectPath(targetName, cwd)

        val ghMatch = Regex("""https?://github\.com/([^/]+)/([^/]+)""").find(cleanUrl)
            ?: return ExecResult(1, "", "Built-in git clone currently supports GitHub HTTPS URLs (e.g. https://github.com/owner/repo).")

        val owner = ghMatch.groupValues[1]
        val repo = ghMatch.groupValues[2]

        // Resolve default branch from GitHub API, falling back to main / master
        val defaultBranch = runCatching {
            val apiReq = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo")
                .header("User-Agent", "YSCode/1.0")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            HttpClients.streamingClient.newCall(apiReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    JSONObject(resp.body?.string().orEmpty()).optString("default_branch", "main").ifBlank { "main" }
                } else null
            }
        }.getOrNull()

        val candidateBranches = listOfNotNull(defaultBranch, "main", "master").distinct()
        for (branch in candidateBranches) {
            val zipUrl = "https://github.com/$owner/$repo/archive/refs/heads/$branch.zip"
            val attempt = runCatching {
                val req = Request.Builder().url(zipUrl).header("User-Agent", "YSCode/1.0").get().build()
                HttpClients.streamingClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val stream = resp.body?.byteStream() ?: return@use null
                    destDir.mkdirs()
                    var count = 0
                    val canonicalDest = destDir.canonicalPath
                    ZipInputStream(stream.buffered()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            // Strip top-level `<repo>-<branch>/` folder inside GitHub zip
                            val rel = entry.name.substringAfter('/', "")
                            if (rel.isNotEmpty()) {
                                val out = File(destDir, rel)
                                if (!out.canonicalPath.startsWith(canonicalDest)) {
                                    throw SecurityException("Blocked unsafe zip entry: ${entry.name}")
                                }
                                if (entry.isDirectory) {
                                    out.mkdirs()
                                } else {
                                    out.parentFile?.mkdirs()
                                    FileOutputStream(out).use { zis.copyTo(it) }
                                    count++
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    val gitDir = File(destDir, ".git")
                    File(gitDir, "objects").mkdirs()
                    File(gitDir, "refs/heads").mkdirs()
                    File(gitDir, "HEAD").writeText("ref: refs/heads/$branch\n")
                    File(gitDir, "config").writeText("[remote \"origin\"]\n\turl = $rawUrl\n")
                    count
                }
            }.getOrNull()
            if (attempt != null) {
                return ExecResult(0, "Cloned $owner/$repo ($branch, $attempt files) into ${destDir.name}/", "")
            }
        }
        return ExecResult(128, "", "fatal: could not clone $rawUrl (check URL or network connectivity)")
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class ChainSegment(val command: String, val nextOp: String?)

    companion object {
        fun hasShellOperatorsRequiringSh(command: String): Boolean {
            var inSingle = false
            var inDouble = false
            var i = 0
            while (i < command.length) {
                val c = command[i]
                when {
                    c == '\'' && !inDouble -> inSingle = !inSingle
                    c == '"' && !inSingle -> inDouble = !inDouble
                    !inSingle && !inDouble -> {
                        val next = command.getOrNull(i + 1)
                        if (c == '>' || c == '<' || c == '`' || c == '(' || c == ')') return true
                        if (c == '|' && next != '|') return true
                        if (c == '$' && next == '(') return true
                    }
                }
                i++
            }
            return false
        }

        private fun splitTopLevelChain(command: String): List<ChainSegment> {
            val out = mutableListOf<ChainSegment>()
            val buf = StringBuilder()
            var inSingle = false
            var inDouble = false
            var i = 0
            while (i < command.length) {
                val c = command[i]
                val next = command.getOrNull(i + 1)
                when {
                    c == '\'' && !inDouble -> { inSingle = !inSingle; buf.append(c) }
                    c == '"' && !inSingle -> { inDouble = !inDouble; buf.append(c) }
                    !inSingle && !inDouble && c == '&' && next == '&' -> {
                        val seg = buf.toString().trim()
                        if (seg.isNotEmpty()) out += ChainSegment(seg, "&&")
                        buf.clear()
                        i++
                    }
                    !inSingle && !inDouble && c == '|' && next == '|' -> {
                        val seg = buf.toString().trim()
                        if (seg.isNotEmpty()) out += ChainSegment(seg, "||")
                        buf.clear()
                        i++
                    }
                    !inSingle && !inDouble && c == ';' -> {
                        val seg = buf.toString().trim()
                        if (seg.isNotEmpty()) out += ChainSegment(seg, ";")
                        buf.clear()
                    }
                    else -> buf.append(c)
                }
                i++
            }
            val tail = buf.toString().trim()
            if (tail.isNotEmpty()) out += ChainSegment(tail, null)
            return out
        }

        fun tokenize(command: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var inSingle = false
            var inDouble = false
            var i = 0
            while (i < command.length) {
                val c = command[i]
                when {
                    c == '\'' && !inDouble -> inSingle = !inSingle
                    c == '"' && !inSingle -> inDouble = !inDouble
                    c.isWhitespace() && !inSingle && !inDouble -> {
                        if (cur.isNotEmpty()) {
                            out += cur.toString()
                            cur.clear()
                        }
                    }
                    else -> cur.append(c)
                }
                i++
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }
}
