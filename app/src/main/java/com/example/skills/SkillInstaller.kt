// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.skills

import android.content.Context
import android.net.Uri
import com.example.linux.LinuxEnvironment
import com.example.llm.HttpClients
import com.example.util.ZipHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.OnErrorAction

data class SkillInstallCandidate(
    val parsed: ParsedSkill,
    val stagingDir: File,
    val files: List<String>,
    val sourceLabel: String
)

class SkillInstaller(
    private val context: Context,
    private val env: LinuxEnvironment,
    private val repository: SkillRepository
) {
    private val maxArchiveBytes = 60L * 1024L * 1024L

    suspend fun prepareFromMarkdown(markdown: String, sourceLabel: String = "Pasted Markdown"): Result<SkillInstallCandidate> =
        withContext(Dispatchers.IO) {
            runCatching {
                val parsed = SkillParser.parse(markdown).getOrThrow()
                val staging = File(context.cacheDir, "skill_stage_${System.nanoTime()}").apply { mkdirs() }
                File(staging, "SKILL.md").writeText(markdown.trimEnd() + "\n")
                SkillInstallCandidate(parsed, staging, listOf("SKILL.md"), sourceLabel)
            }
        }

    suspend fun prepareFromZipUri(uri: Uri): Result<SkillInstallCandidate> = withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openInputStream(uri) ?: error("Unable to open ZIP file")
            val staging = File(context.cacheDir, "skill_stage_${System.nanoTime()}").apply { mkdirs() }
            val files = stream.use { ZipHelper.unzip(it, staging, maxBytes = maxArchiveBytes, stripSingleTopDir = true) }
            locateSkillInStaging(staging, files, "Local ZIP")
        }
    }

    suspend fun prepareFromGitHubUrl(url: String): Result<SkillInstallCandidate> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = url.trim().removeSuffix(".git").trimEnd('/')
            val parsedUrl = parseGitHubRepo(clean)
                ?: error("Enter a valid GitHub repository URL (e.g. https://github.com/user/repo)")

            val branches = if (parsedUrl.branch != null) listOf(parsedUrl.branch) else listOf("HEAD")
            var zipBytes: ByteArray? = null
            var lastErr = "Download failed"
            for (branch in branches) {
                val codeloadUrl = if (branch == "HEAD") "https://codeload.github.com/${parsedUrl.owner}/${parsedUrl.repo}/zip/HEAD"
                else "https://codeload.github.com/${parsedUrl.owner}/${parsedUrl.repo}/zip/$branch"
                val req = Request.Builder().url(codeloadUrl).get().build()
                HttpClients.streamingClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        if (bytes.size > maxArchiveBytes) error("Repository ZIP exceeds 5 MB limit")
                        zipBytes = bytes
                    } else {
                        lastErr = "HTTP ${resp.code} from GitHub ($branch)"
                    }
                }
                if (zipBytes != null) break
            }
            val bytes = zipBytes ?: error(lastErr)
            val stagingRoot = File(context.cacheDir, "skill_stage_${System.nanoTime()}").apply { mkdirs() }
            val allFiles = ZipHelper.unzip(
                ByteArrayInputStream(bytes),
                stagingRoot,
                maxBytes = maxArchiveBytes,
                stripSingleTopDir = true
            )
            val targetDir = if (!parsedUrl.subpath.isNullOrBlank()) {
                File(stagingRoot, parsedUrl.subpath).also {
                    if (!it.exists()) error("Subpath '${parsedUrl.subpath}' not found in repository")
                }
            } else stagingRoot
            val relFiles = targetDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(targetDir).path }.toList()
            locateSkillInStaging(targetDir, relFiles, clean)
        }
    }

    suspend fun commitInstall(
        candidate: SkillInstallCandidate,
        scope: String,
        projectDir: File?
    ): Result<com.example.data.SkillMetaEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val baseDir = if (scope == "project" && projectDir != null) {
                File(projectDir, ".yscode/skills")
            } else {
                env.globalSkillsDir
            }
            val destDir = File(baseDir, candidate.parsed.name)
            destDir.deleteRecursively()
            destDir.mkdirs()
            candidate.stagingDir.copyRecursively(destDir, overwrite = true)
            destDir.walkTopDown().filter { it.extension in setOf("sh", "py") }.forEach {
                it.setExecutable(true, false)
            }
            val skillMd = File(destDir, "SKILL.md").readText()
            repository.saveSkillContent(
                skillDir = destDir,
                markdown = skillMd,
                scope = scope,
                projectId = if (scope == "project") projectDir?.name else null
            ).getOrThrow()
        }
    }

    private fun locateSkillInStaging(staging: File, files: List<String>, sourceLabel: String): SkillInstallCandidate {
        val rootSkill = File(staging, "SKILL.md")
        val skillFile = if (rootSkill.exists()) {
            rootSkill
        } else {
            staging.walkTopDown().firstOrNull { it.isFile && it.name == "SKILL.md" }
                ?: error("No SKILL.md found in archive")
        }
        val skillDir = skillFile.parentFile ?: staging
        val parsed = SkillParser.parse(skillFile.readText(), skillDir.name).getOrThrow()
        val relFiles = skillDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(skillDir).path }.toList()
        return SkillInstallCandidate(parsed, skillDir, relFiles, sourceLabel)
    }

    private data class GitHubTarget(val owner: String, val repo: String, val branch: String?, val subpath: String?)

    private fun parseGitHubRepo(url: String): GitHubTarget? {
        val regex = Regex("""^https?://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(?:/(.+))?)?$""")
        val m = regex.find(url) ?: return null
        return GitHubTarget(
            owner = m.groupValues[1],
            repo = m.groupValues[2],
            branch = m.groupValues[3].ifBlank { null },
            subpath = m.groupValues[4].ifBlank { null }
        )
    }

    // ------------------------------------------------------------------------------------
    // Full GitHub plugin/skill installer (used by the agent's install_skill tool)
    // ------------------------------------------------------------------------------------

    data class GitHubInstallReport(
        val repoVirtualPath: String,
        val installedSkills: List<String>,
        val skippedSkills: List<String>,
        val allSkillNames: List<String>,
        val mcpHints: List<String>,
        val notes: List<String>
    )

    suspend fun installFromGitHub(
        url: String,
        scope: String,
        projectDir: File?,
        only: Set<String>
    ): Result<GitHubInstallReport> = withContext(Dispatchers.IO) {
        runCatching {
            val target = parseGitHubFlexible(url)
                ?: error("Not a valid GitHub reference: $url (use https://github.com/owner/repo or owner/repo)")
            val ref = target.branch ?: "HEAD"
            val zipUrl = if (ref == "HEAD") "https://codeload.github.com/${target.owner}/${target.repo}/zip/HEAD"
            else "https://codeload.github.com/${target.owner}/${target.repo}/zip/$ref"

            val tmpZip = File(context.cacheDir, "gh_${System.nanoTime()}.zip")
            val repoDir = File(env.rootHomeDir, ".yscode/plugins/${target.owner}__${target.repo}")
            try {
                val maxZip = 150L * 1024L * 1024L
                var total = 0L
                HttpClients.streamingClient.newCall(Request.Builder().url(zipUrl).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error(
                            "GitHub returned HTTP ${resp.code} for ${target.owner}/${target.repo}" +
                                if (resp.code == 404) " (repo not found, private, or wrong branch)" else ""
                        )
                    }
                    val body = resp.body ?: error("Empty response from GitHub")
                    body.byteStream().use { input ->
                        tmpZip.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxZip) error("Repository archive is larger than 150 MB")
                                out.write(buf, 0, n)
                            }
                        }
                    }
                }

                if (repoDir.exists()) repoDir.deleteRecursively()
                repoDir.mkdirs()
                val extracted = streamUnzip(tmpZip, repoDir)

                val scanRoot = if (!target.subpath.isNullOrBlank()) {
                    File(repoDir, target.subpath).also { if (!it.exists()) error("Subpath '${target.subpath}' not found in repository") }
                } else repoDir

                val skipDirs = setOf("node_modules", ".git", "dist", "build", "__pycache__", ".venv", "venv")
                val skillFiles = scanRoot.walkTopDown()
                    .onEnter { d -> d == scanRoot || d.name !in skipDirs }
                    .filter { it.isFile && it.name == "SKILL.md" && it.relativeTo(scanRoot).path.count { c -> c == '/' } <= 8 }
                    .toList()

                data class Found(val dir: File, val parsed: ParsedSkill)
                val found = skillFiles.mapNotNull { f ->
                    val parsed = SkillParser.parse(runCatching { f.readText() }.getOrDefault(""), f.parentFile?.name ?: "skill").getOrNull()
                    if (parsed == null) null else Found(f.parentFile ?: scanRoot, parsed)
                }.distinctBy { it.parsed.name }

                val notes = mutableListOf<String>()
                notes += "Downloaded ${total / 1024} KB, extracted $extracted files."

                val selected: List<Found> = when {
                    only.isNotEmpty() -> found.filter { f -> f.parsed.name in only || f.dir.name in only }
                    found.size > 25 -> {
                        notes += "Found ${found.size} skills (more than 25): none auto-installed. Call install_skill again with `only` set to the skill names you want."
                        emptyList()
                    }
                    else -> found
                }

                val baseDir = if (scope == "project" && projectDir != null) File(projectDir, ".yscode/skills") else env.globalSkillsDir
                val installed = mutableListOf<String>()
                val skipped = mutableListOf<String>()
                val repoVirtual = env.toVirtualPath(repoDir)
                for (f in selected) {
                    val dest = File(baseDir, f.parsed.name)
                    dest.deleteRecursively()
                    dest.mkdirs()
                    f.dir.copyRecursively(dest, overwrite = true) { _, _ -> OnErrorAction.SKIP }
                    File(dest, ".yscode-source").writeText(repoVirtual)
                    dest.walkTopDown().filter { it.isFile && (it.extension in setOf("sh", "py", "js", "mjs")) }.forEach { it.setExecutable(true, false) }
                    val md = runCatching { File(dest, "SKILL.md").readText() }.getOrNull()
                    if (md == null) { skipped += f.parsed.name; continue }
                    val saved = repository.saveSkillContent(
                        skillDir = dest,
                        markdown = md,
                        scope = scope,
                        projectId = if (scope == "project") projectDir?.name else null
                    )
                    if (saved.isSuccess) installed += f.parsed.name else skipped += f.parsed.name
                }
                if (installed.isNotEmpty()) repository.rescan(projectDir)

                GitHubInstallReport(
                    repoVirtualPath = repoVirtual,
                    installedSkills = installed,
                    skippedSkills = skipped,
                    allSkillNames = found.map { it.parsed.name },
                    mcpHints = detectMcpHints(repoDir, scanRoot, repoVirtual),
                    notes = notes
                )
            } finally {
                runCatching { tmpZip.delete() }
            }
        }
    }

    private fun streamUnzip(zip: File, dest: File): Int {
        val destCanonical = dest.canonicalPath
        var count = 0
        var totalBytes = 0L
        val maxTotal = 500L * 1024L * 1024L
        val maxFile = 30L * 1024L * 1024L
        java.util.zip.ZipInputStream(java.io.BufferedInputStream(zip.inputStream())).use { zis ->
            var prefix: String? = null
            val buf = ByteArray(64 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break
                val raw = entry.name.trimStart('/')
                if (raw.isEmpty()) continue
                if (prefix == null) prefix = raw.substringBefore('/') + "/"
                val rel = raw.removePrefix(prefix)
                if (rel.isEmpty()) continue
                val segs = rel.split('/')
                if (segs.contains(".git") || segs.contains("node_modules")) { zis.closeEntry(); continue }
                val outFile = File(dest, rel)
                if (!outFile.canonicalPath.startsWith(destCanonical)) { zis.closeEntry(); continue } // zip-slip guard
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    var written = 0L
                    var tooBig = false
                    outFile.outputStream().use { out ->
                        while (true) {
                            val n = zis.read(buf)
                            if (n < 0) break
                            written += n
                            totalBytes += n
                            if (written > maxFile) { tooBig = true; break }
                            if (totalBytes > maxTotal) error("Repository is larger than 500 MB when extracted")
                            out.write(buf, 0, n)
                        }
                    }
                    if (tooBig) { outFile.delete() } else {
                        count++
                        if (written in 2..2_000_000) {
                            val head = ByteArray(2)
                            val got = runCatching { outFile.inputStream().use { it.read(head) } }.getOrDefault(-1)
                            if (got == 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) {
                                outFile.setExecutable(true, false)
                            }
                        }
                    }
                }
                zis.closeEntry()
            }
        }
        return count
    }

    /** Looks for MCP server definitions (.mcp.json, plugin manifests, package.json, pyproject). */
    private fun detectMcpHints(repoDir: File, scanRoot: File, repoVirtual: String): List<String> {
        val hints = mutableListOf<String>()
        val rootVars = listOf("\${CLAUDE_PLUGIN_ROOT}", "\${CODEX_PLUGIN_ROOT}", "\${CURSOR_PLUGIN_ROOT}", "\${PLUGIN_ROOT}", "\${CLAUDE_PLUGIN_DIR}")
        fun fix(s: String): String {
            var r = s
            for (v in rootVars) r = r.replace(v, repoVirtual)
            return r
        }
        val candidates = listOf(
            ".mcp.json", "mcp.json", ".claude-plugin/plugin.json", ".codex-plugin/plugin.json",
            ".cursor-plugin/plugin.json", ".claude-plugin/.mcp.json", ".codex-plugin/.mcp.json"
        )
        for (rel in candidates) {
            val f = File(repoDir, rel)
            if (!f.isFile) continue
            val root = runCatching { com.example.util.JsonHelper.parseOrNull(f.readText()) }.getOrNull() ?: continue
            val obj = runCatching { root as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: continue
            var servers = obj["mcpServers"] ?: obj["servers"]
            // plugin.json may reference another file: "mcpServers": "./.mcp.json"
            if (servers is kotlinx.serialization.json.JsonPrimitive && servers.isString) {
                val ref = File(f.parentFile, servers.content)
                val alt = File(repoDir, servers.content.removePrefix("./"))
                val target = listOf(ref, alt).firstOrNull { it.isFile }
                val inner = target?.let { runCatching { com.example.util.JsonHelper.parseOrNull(it.readText()) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
                servers = inner?.get("mcpServers") ?: inner
            }
            val srvObj = servers as? kotlinx.serialization.json.JsonObject ?: continue
            for ((name, v) in srvObj) {
                val so = v as? kotlinx.serialization.json.JsonObject ?: continue
                val cmd = (so["command"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val url = (so["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val args = (so["args"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                val envKeys = (so["env"] as? kotlinx.serialization.json.JsonObject)?.keys.orEmpty()
                val line = if (url != null) "MCP '$name' (from $rel): HTTP url=${fix(url)}" +
                    (if (envKeys.isNotEmpty()) " env=${envKeys.joinToString()}" else "")
                else "MCP '$name' (from $rel): command=${fix(cmd ?: "?")} args=${args.joinToString(" ") { fix(it) }}" +
                    (if (envKeys.isNotEmpty()) " env keys needed: ${envKeys.joinToString()}" else "")
                hints += line
            }
        }
        val pkg = File(scanRoot, "package.json").takeIf { it.isFile } ?: File(repoDir, "package.json").takeIf { it.isFile }
        if (pkg != null) {
            val txt = runCatching { pkg.readText() }.getOrDefault("")
            if (txt.contains("modelcontextprotocol") || txt.contains("\"mcp\"", ignoreCase = true) || txt.contains("mcp-server", ignoreCase = true)) {
                hints += "Node MCP server detected (${pkg.relativeTo(repoDir).path}): run `npm install` (and `npm run build` if a build script exists), then add_mcp_server with command `node` and the built entry file from package.json (\"bin\"/\"main\")."
            } else if (txt.contains("\"build\"")) {
                hints += "package.json found (${pkg.relativeTo(repoDir).path}); if the README says it provides an MCP server, run `npm install && npm run build` in $repoVirtual."
            }
        }
        val py = File(scanRoot, "pyproject.toml").takeIf { it.isFile } ?: File(repoDir, "pyproject.toml").takeIf { it.isFile }
        if (py != null && runCatching { py.readText() }.getOrDefault("").contains("mcp", ignoreCase = true)) {
            hints += "Python MCP server detected: `pip install --break-system-packages -e $repoVirtual` (or uv), then add_mcp_server with command `python3` args [\"-m\", <module>] per README."
        }
        return hints.take(12)
    }

    private fun parseGitHubFlexible(raw: String): GitHubTarget? {
        var u = raw.trim().replace("\u2060", "").replace("\u200b", "").removeSuffix(".git").trimEnd('/')
        if (!u.startsWith("http")) {
            val short = Regex("""^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$""").find(u)
            if (short != null) u = "https://github.com/${short.groupValues[1]}/${short.groupValues[2]}"
        }
        val m = Regex("""^https?://(?:www\.)?github\.com/([^/]+)/([^/#?]+)(?:/(?:tree|blob)/([^/]+)(?:/([^#?]+))?)?""").find(u) ?: return null
        return GitHubTarget(
            owner = m.groupValues[1],
            repo = m.groupValues[2].removeSuffix(".git"),
            branch = m.groupValues[3].ifBlank { null },
            subpath = m.groupValues[4].ifBlank { null }?.let { sp -> if (sp.endsWith("SKILL.md")) sp.substringBeforeLast('/', "") else sp }?.ifBlank { null }
        )
    }
}
