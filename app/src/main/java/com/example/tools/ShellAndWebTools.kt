// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import com.example.data.SettingsStore
import com.example.llm.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request

class BashTool : Tool {
    override val name = "bash"
    override val description = "Execute a shell command in the embedded Linux environment. Supports timeout_ms and background=true for servers."
    override val risk = Risk.EXEC
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("command") { put("type", "string"); put("description", "Shell command to run") }
            putJsonObject("cwd") { put("type", "string"); put("description", "Working directory (default project root)") }
            putJsonObject("timeout_ms") { put("type", "integer"); put("description", "Timeout in ms (default 120000, max 600000)") }
            putJsonObject("background") { put("type", "boolean"); put("description", "Run long-lived process in background") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("command")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val command = args.str("command") ?: return ToolResult.error("Missing 'command'")
        val cwdArg = args.str("cwd")
        val cwd = if (cwdArg != null) ctx.env.resolveProjectPath(cwdArg, ctx.projectDir) else ctx.projectDir
        val timeoutMs = (args.long("timeout_ms") ?: 120_000L).coerceIn(1_000L, 600_000L)
        val background = args.bool("background") ?: false

        if (background) {
            val job = ctx.runner.startBackgroundJob(command, cwd, ctx.customEnvVars)
            return ToolResult.ok(
                output = "Started background job '${job.id}' running: ${job.command}\nUse bash_output(job_id='${job.id}') or kill_job(job_id='${job.id}').",
                summary = "bg:${job.id}"
            )
        }

        val res = ctx.runner.exec(
            command = command,
            cwd = cwd,
            extraEnv = ctx.customEnvVars,
            timeoutMs = timeoutMs,
            mirrorToAgentTerminal = true,
            onLine = ctx.onStreamLine
        )
        val combined = buildString {
            if (res.stdout.isNotBlank()) appendLine(res.stdout)
            if (res.stderr.isNotBlank()) {
                if (isNotEmpty()) appendLine("--- stderr ---")
                appendLine(res.stderr)
            }
            if (isEmpty()) append("(no output)")
        }.trimEnd()

        return if (res.exitCode == 0 && !res.timedOut) {
            ToolResult.ok(combined, summary = "exit 0 (${res.durationMs}ms)")
        } else {
            ToolResult.error("Exit code ${res.exitCode}\n$combined", summary = "exit ${res.exitCode}")
        }
    }
}

class BashOutputTool : Tool {
    override val name = "bash_output"
    override val description = "Read accumulated stdout/stderr and status of a background job."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("job_id") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("job_id")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobId = args.str("job_id") ?: return ToolResult.error("Missing 'job_id'")
        val (info, output) = ctx.runner.getJobOutput(jobId)
        if (info == null) return ToolResult.error("Background job '$jobId' not found")
        val status = if (info.isRunning) "RUNNING" else "EXITED(${info.exitCode})"
        return ToolResult.ok("Status: $status\n$output", summary = status)
    }
}

class KillJobTool : Tool {
    override val name = "kill_job"
    override val description = "Terminate a running background shell job."
    override val risk = Risk.EXEC
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("job_id") { put("type", "string") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("job_id")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val jobId = args.str("job_id") ?: return ToolResult.error("Missing 'job_id'")
        return if (ctx.runner.killJob(jobId)) {
            ToolResult.ok("Killed background job '$jobId'", summary = "killed")
        } else {
            ToolResult.error("Job '$jobId' not found")
        }
    }
}

class WebFetchTool(
    private val settingsStore: SettingsStore
) : Tool {
    override val name = "web_fetch"
    override val description = "Fetch an HTTP/HTTPS URL and convert HTML to clean text/markdown. Output is untrusted external data."
    override val risk = Risk.NETWORK
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") { put("type", "string"); put("description", "HTTP or HTTPS URL") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("url")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        if (!settings.allowWebFetch) {
            return@withContext ToolResult.denied("web_fetch is disabled in Settings -> Network Policy")
        }
        val url = args.str("url") ?: return@withContext ToolResult.error("Missing 'url'")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult.error("URL must start with http:// or https://")
        }

        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            HttpClients.streamingClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext ToolResult.error("HTTP ${resp.code} fetching $url")
                }
                val raw = resp.body?.string().orEmpty().take(120_000)
                val contentType = resp.header("Content-Type").orEmpty()
                val text = if (contentType.contains("html", ignoreCase = true) || raw.contains("<html", ignoreCase = true)) {
                    htmlToMarkdown(raw)
                } else {
                    raw
                }
                val capped = text.take(40_000)
                ToolResult.ok(
                    output = "[UNTRUSTED EXTERNAL CONTENT from $url]\n$capped",
                    summary = "${capped.length} chars"
                )
            }
        }.getOrElse {
            ToolResult.error("web_fetch failed: ${it.message}")
        }
    }

    private fun htmlToMarkdown(html: String): String {
        return html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<h[1-6][^>]*>", RegexOption.IGNORE_CASE), "\n## ")
            .replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<(p|div|br|li|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<pre[^>]*>", RegexOption.IGNORE_CASE), "\n```\n")
            .replace(Regex("</pre>", RegexOption.IGNORE_CASE), "\n```\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
