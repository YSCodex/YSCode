// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

import android.content.Context
import com.example.linux.ExecResult
import com.example.linux.LinuxEnvironment
import com.example.linux.ProotRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AgyState(val installed: Boolean, val version: String?, val signedIn: Boolean)

data class AgyAccount(val name: String, val active: Boolean)

/** Progress of a sign-in started with [AgyManager.startLogin]. */
class AgyLogin(private val process: Process) {
    val url = MutableStateFlow<String?>(null)
    val output = MutableStateFlow<List<String>>(emptyList())
    /** null while running, true when signed in, false on failure. */
    val result = MutableStateFlow<Boolean?>(null)

    /** Types text and presses Enter. A terminal in raw mode wants a carriage return, not a line feed. */
    @Volatile var codeSent = false
    /** Last part of agy's screen text (for the wizard walker). */
    @Volatile var tail: String = ""
    /** Whether to opt in to Google's optional usage-data sharing on agy's terms screen. Off unless the user turns it on. */
    @Volatile var shareData = false

    fun send(line: String) {
        if (line.isNotEmpty()) codeSent = true
        sendRaw(line + "\r\n")
    }

    fun sendRaw(s: String) {
        runCatching { process.outputStream.write(s.toByteArray()); process.outputStream.flush() }
    }

    fun cancel() {
        runCatching { process.destroyForcibly() }
    }
}

/**
 * Runs Google's official Antigravity CLI (`agy`) inside YSCode's Linux. YSCode never reads or reuses the Google
 * login: `agy` signs in and talks to Google itself, YSCode only starts the official binary and shows its output.
 */
class AgyManager(
    context: Context,
    private val runner: ProotRunner,
    private val env: LinuxEnvironment
) {
    companion object {
        /** agy 1.5.x keeps its Google login in this file when there is no OS keyring. */
        const val TOKEN_FILE = ".gemini/antigravity-cli/antigravity-oauth-token"
        private const val ACCOUNTS_DIR = ".gemini/yscode-agy-accounts"
        private const val PATH_FIX = "export PATH=\"\$HOME/.local/bin:\$PATH\" NO_COLOR=1 TERM=dumb AGY_NO_UPDATE_PROMPT=1; "
        private val URL_RE = Regex("https?://[^\\s\"'<>]+")
        fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
        private val ANSI_RE = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][A-Za-z0-9]|\u001B[=>]")
        private const val HANDSHAKE_REPLY = "\u001B[?2026;1\$y\u001B[?2027;1\$y\u001B[?1u\n"
        private val CLEAN_ANSI = Regex("\u001B(?:\\][^\u0007]*(?:\u0007|\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")
        fun cleanTerminalText(t: String): String = t.replace(CLEAN_ANSI, "").filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }
        private val OAUTH_URL = Regex("https://accounts\\.google\\.com/[^\\s\"'<>]*?[?&]state=[A-Za-z0-9._~-]+")
        /** agy wraps the long URL over several rows; removing the whitespace rebuilds it. */
        fun extractGoogleOAuthUrl(output: String): String? {
            val compact = output.replace(Regex("[\\r\\n\\t ]+"), "")
            return OAUTH_URL.find(compact)?.value?.takeIf { "client_id=" in it && "code_challenge=" in it }
        }
        fun stripAnsi(s: String) = ANSI_RE.replace(s, "").replace("\r", "")
    }

    private val prefs = context.getSharedPreferences("yscode_agy", Context.MODE_PRIVATE)
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Raw lines of the last chat run, kept for the diagnostics view. */
    @Volatile var lastRun: List<String> = emptyList()

    var autoApprove: Boolean
        get() = prefs.getBoolean("auto_approve", true)
        set(v) { prefs.edit().putBoolean("auto_approve", v).apply() }

    var model: String?
        get() = prefs.getString("model", null)
        set(v) { prefs.edit().putString("model", v).apply() }

    fun conversationFor(sessionId: String): String? = prefs.getString("conv_$sessionId", null)
    fun rememberConversation(sessionId: String, conversationId: String) {
        prefs.edit().putString("conv_$sessionId", conversationId).apply()
    }

    fun appendLog(line: String) {
        _log.value = (_log.value + line.trimEnd()).takeLast(200)
    }

    fun clearLog() { _log.value = emptyList() }

    private fun homeVirtual(): String = env.toVirtualPath(env.rootHomeDir)

    suspend fun sh(cmd: String, timeoutMs: Long = 120_000L, log: Boolean = false): ExecResult =
        runner.exec(PATH_FIX + cmd, env.rootHomeDir, emptyMap(), timeoutMs, false) { if (log) appendLog(it) }

    /** Starts the `agy` process of one chat turn; the caller reads its output. */
    fun spawn(command: String, cwd: File): Process = runner.spawnInteractiveProcess(PATH_FIX + command, cwd)

    @Volatile private var cached: AgyState? = null
    @Volatile private var cachedAt = 0L

    /** Same as [check] but skips the slow proot round trip when a good result is under 10 minutes old. */
    suspend fun checkCached(): AgyState {
        val c = cached
        if (c != null && c.installed && c.signedIn && System.currentTimeMillis() - cachedAt < 600_000L) return c
        return check()
    }

    fun invalidateCheck() { cached = null }

    suspend fun check(): AgyState = withContext(Dispatchers.IO) {
        val r = sh(
            "if [ -x \"\$HOME/.local/bin/agy\" ]; then echo \"V:\$(\"\$HOME/.local/bin/agy\" --version 2>&1 | head -n 1)\"; else echo MISSING; fi; " +
                "if [ -s \"\$HOME/$TOKEN_FILE\" ]; then echo SIGNED; else echo NOSIGN; fi"
        )
        val lines = r.stdout.lines().map { it.trim() }
        val v = lines.firstOrNull { it.startsWith("V:") }?.removePrefix("V:")?.trim()
        val installed = v != null && v.isNotBlank() && !v.contains("not found", true) && !v.contains("No such file", true) && !v.contains("error", true)
        AgyState(installed, if (installed) v else null, lines.any { it == "SIGNED" }).also { cached = it; cachedAt = System.currentTimeMillis() }
    }

    /** Downloads the official installer and runs it. Needs internet; everything is logged for the UI. */
    suspend fun install(): AgyState = withContext(Dispatchers.IO) {
        _busy.value = true
        try {
            appendLog("Installing the official Antigravity CLI (agy)...")
            val dir = File(env.rootHomeDir, ".yscode").apply { mkdirs() }
            File(dir, "install_agy.sh").writeText(
                """
                apk add --no-cache curl bash ca-certificates gcompat libstdc++ libgcc >/dev/null 2>&1 || true
                curl -fsSL https://antigravity.google/cli/install.sh | bash 2>&1
                echo "--- installer finished"
                "${'$'}HOME/.local/bin/agy" --version 2>&1 | head -n 3
                """.trimIndent() + "\n"
            )
            sh("sh ${shQuote(homeVirtual() + "/.yscode/install_agy.sh")}", 600_000L, log = true)
            val s = check()
            appendLog(if (s.installed) "Installed: ${s.version}" else "agy could not be started in this Linux. See the log above.")
            s
        } finally {
            _busy.value = false
        }
    }

    private val signedOutMarkers = listOf("please sign in", "sign in required", "authentication required", "not logged in", "not logged into", "not signed in")

    /** True when the CLI itself accepts the current login (`agy models` needs a login but no model call). */
    suspend fun verify(): Boolean = withContext(Dispatchers.IO) {
        val r = sh("agy models 2>&1", 60_000L)
        val out = (r.stdout + " " + r.stderr).replace(Regex("\\s+"), " ")
        r.exitCode == 0 && out.isNotBlank() && signedOutMarkers.none { out.contains(it, ignoreCase = true) }
    }

    suspend fun models(): List<String> = withContext(Dispatchers.IO) {
        val r = sh("agy models 2>&1", 60_000L)
        val lines = r.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.any { it.contains("authentication", true) || it.startsWith("error", true) }) {
            throw IllegalStateException("agy says you are not signed in. Sign in again in Providers > Antigravity.")
        }
        lines.filterNot { it.startsWith("Fetching", true) || it.startsWith("Loading", true) }
    }

    suspend fun usage(): String = withContext(Dispatchers.IO) {
        val r = sh("agy -p '/usage' --output-format text --print-timeout 60s 2>&1", 90_000L)
        r.stdout.trim().ifBlank { r.stderr.trim().ifBlank { "No usage information was returned." } }
    }

    // ---------------------------------------------------------------- sign in

    /**
     * Sign-in with agy's own flow, driven like a terminal user would. agy's screen needs a real terminal, so it
     * runs under `script`. It shows a Google URL (opened for the user), waits for the one-time code, then asks a few
     * first-run questions that this walker answers (privacy choice follows [AgyLogin.shareData]).
     */
    fun startLogin(scope: CoroutineScope): AgyLogin {
        val cmd = "export SSH_CONNECTION='127.0.0.1 1 127.0.0.1 1' TERM=xterm-256color AGY_NO_UPDATE_PROMPT=1; " +
            "command -v script >/dev/null 2>&1 || apk add --no-cache util-linux-misc >/dev/null 2>&1; " +
            "script -qfc 'stty cols 120 rows 40; agy' /dev/null 2>&1"
        val process = spawn(cmd, env.rootHomeDir)
        val login = AgyLogin(process)
        val credential = File(env.rootHomeDir, TOKEN_FILE)

        scope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 10 * 60_000L
            var ok = false
            try {
                val buf = ByteArray(4096)
                val raw = StringBuilder()
                val input = process.inputStream
                var handshakes = 0
                var lastHandshakeAt = 0
                var menuDone = false
                var colorDone = false
                var renderDone = false
                var privacyDone = false
                var trustDone = false
                suspend fun key(s: String) { login.sendRaw(s); kotlinx.coroutines.delay(150) }
                while (System.currentTimeMillis() < deadline) {
                    val n = input.read(buf)
                    if (n < 0) break
                    raw.append(String(buf, 0, n, Charsets.UTF_8))
                    if (raw.length > 80_000) raw.delete(0, raw.length - 50_000)
                    val clean = cleanTerminalText(raw.toString())
                    login.tail = clean.takeLast(3500)
                    login.output.value = clean.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.takeLast(14)

                    // agy asks the terminal what it supports before it draws; answer, or it may ignore the keyboard.
                    if (handshakes < 5 && raw.length - lastHandshakeAt > 200 && raw.substring(lastHandshakeAt).contains("\u001B[?u")) {
                        login.sendRaw(HANDSHAKE_REPLY); handshakes++; lastHandshakeAt = raw.length
                    }
                    val url = extractGoogleOAuthUrl(clean)
                    if (url != null && login.url.value != url) login.url.value = url

                    if (handshakes > 0 && !menuDone && clean.contains("Select login method", true) && clean.contains("> 1. Google OAuth", true) && url == null) {
                        key("\r\n"); menuDone = true
                    }
                    if (!colorDone && clean.contains("Choose your color scheme", true)) { key("\r\n"); colorDone = true }
                    if (!renderDone && clean.contains("no flickering (altscreen)", true) && clean.contains("Native Terminal experience (inline)", true)) {
                        key("\u001B[B\r\n"); renderDone = true
                    }
                    if (!privacyDone && clean.contains("Terms of Service & Data Use", true) && clean.contains("help improve Antigravity CLI", true)) {
                        val checked = clean.contains("[x] Yes", true)
                        // Space flips the checkbox, two Tabs reach [Done], then Enter.
                        key(if (checked != login.shareData) " \t\t\r\n" else "\t\t\r\n"); privacyDone = true
                    }
                    if (!trustDone && clean.contains("Do you trust the contents of this project", true) && clean.contains("Yes, I trust this folder", true)) {
                        key("\r\n"); trustDone = true
                    }
                    if (clean.contains("authentication failed", true) || clean.contains("failed to exchange", true)) break

                    val mainScreen = clean.contains("for shortcuts", true) || (clean.contains("Antigravity CLI", true) && clean.contains("Google AI", true))
                    if (login.codeSent && mainScreen) {
                        // agy shows its main screen before it finished saving the account next to the token.
                        awaitCredentialSettled(credential, process)
                        runCatching { login.sendRaw("/quit\r\n") }
                        repeat(100) { if (process.isAlive) kotlinx.coroutines.delay(100) }
                        ok = verify()
                        break
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { process.destroyForcibly() }
                runCatching { sh("pkill -f 'agy' 2>/dev/null; pkill -f 'script -qfc' 2>/dev/null; true", 15_000L) }
                login.result.value = ok
            }
        }
        return login
    }

    /** Waits until agy has written its credential file and stopped changing it. */
    private suspend fun awaitCredentialSettled(credential: File, process: Process) {
        var last = -1L to -1L
        var stableSince = 0L
        val start = System.currentTimeMillis()
        while (process.isAlive && System.currentTimeMillis() - start < 20_000L) {
            kotlinx.coroutines.delay(250)
            val sig = if (credential.isFile) credential.length() to credential.lastModified() else -1L to -1L
            val now = System.currentTimeMillis()
            if (sig != last) { last = sig; stableSince = now }
            else if (sig.first > 0L && now - stableSince >= 3_000L) return
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) { sh("rm -f \"\$HOME/$TOKEN_FILE\"") }

    // ---------------------------------------------------------------- saved accounts (switcher)

    suspend fun accounts(): List<AgyAccount> = withContext(Dispatchers.IO) {
        val r = sh(
            "mkdir -p \"\$HOME/$ACCOUNTS_DIR\"; for f in \"\$HOME/$ACCOUNTS_DIR\"/*; do [ -f \"\$f\" ] || continue; n=\$(basename \"\$f\"); " +
                "if cmp -s \"\$f\" \"\$HOME/$TOKEN_FILE\"; then echo \"A:\$n\"; else echo \"I:\$n\"; fi; done"
        )
        r.stdout.lines().mapNotNull { l ->
            when {
                l.startsWith("A:") -> AgyAccount(l.removePrefix("A:"), true)
                l.startsWith("I:") -> AgyAccount(l.removePrefix("I:"), false)
                else -> null
            }
        }
    }

    /** Copies the current login under a name; tries to use the e-mail inside the token file as the name. */
    suspend fun saveCurrentAccount(preferredName: String? = null): String? = withContext(Dispatchers.IO) {
        val email = sh("sed -n 's/.*\"email\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' \"\$HOME/$TOKEN_FILE\" 2>/dev/null | head -n 1").stdout.trim()
        val raw = preferredName?.takeIf { it.isNotBlank() } ?: email.ifBlank { "account-" + System.currentTimeMillis() / 1000 }
        val name = raw.replace(Regex("[^A-Za-z0-9._@-]"), "_")
        val r = sh("mkdir -p \"\$HOME/$ACCOUNTS_DIR\" && cp \"\$HOME/$TOKEN_FILE\" \"\$HOME/$ACCOUNTS_DIR/$name\" && chmod 600 \"\$HOME/$ACCOUNTS_DIR/$name\" && echo OK")
        if (r.stdout.contains("OK")) name else null
    }

    suspend fun useAccount(name: String) = withContext(Dispatchers.IO) {
        sh("cp \"\$HOME/$ACCOUNTS_DIR/${name.replace(Regex("[^A-Za-z0-9._@-]"), "_")}\" \"\$HOME/$TOKEN_FILE\" && chmod 600 \"\$HOME/$TOKEN_FILE\"")
    }

    suspend fun removeAccount(name: String) = withContext(Dispatchers.IO) {
        sh("rm -f \"\$HOME/$ACCOUNTS_DIR/${name.replace(Regex("[^A-Za-z0-9._@-]"), "_")}\"")
    }
}
