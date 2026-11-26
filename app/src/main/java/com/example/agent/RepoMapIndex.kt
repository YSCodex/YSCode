// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.agent

import com.example.linux.ProotRunner
import com.example.util.JsonHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class RepoSymbol(
    val name: String,
    val kind: String, // "class", "interface", "object", "fun", "struct", "enum", "export", "type"
    val line: Int,
    val signature: String = ""
)

@Serializable
data class RepoFileSymbols(
    val relativePath: String,
    val language: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val symbols: List<RepoSymbol>,
    val references: List<String> = emptyList()
)

@Serializable
data class RepoMapCache(
    val generatedAt: Long = 0L,
    val usedTreeSitter: Boolean = false,
    val files: List<RepoFileSymbols> = emptyList()
)

data class RankedRepoFile(
    val entry: RepoFileSymbols,
    val score: Double
)

class RepoMapIndex(
    private val runner: ProotRunner? = null
) {
    private val ignoredDirs = setOf(
        ".git", "node_modules", "build", ".gradle", "dist", "target",
        "__pycache__", ".venv", "venv", ".idea", ".yscode", ".next", "out"
    )

    private val supportedExtensions = setOf(
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "go", "rs", "dart", "c", "cpp", "h", "hpp"
    )

    suspend fun buildOrRefresh(
        projectDir: File,
        useTreeSitterIfAvailable: Boolean = true
    ): RepoMapCache = withContext(Dispatchers.IO) {
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return@withContext RepoMapCache()
        }

        val cacheFile = File(projectDir, ".yscode/repomap.json")
        val existingCache = if (cacheFile.exists()) {
            runCatching { JsonHelper.json.decodeFromString<RepoMapCache>(cacheFile.readText()) }.getOrNull()
        } else null
        val cachedByPath = existingCache?.files?.associateBy { it.relativePath }.orEmpty()

        val sourceFiles = projectDir.walkTopDown()
            .onEnter { d -> d == projectDir || d.name !in ignoredDirs }
            .filter { it.isFile && it.extension.lowercase() in supportedExtensions && it.length() <= 256_000L }
            .take(500)
            .toList()

        val treeSitterAvailable = if (useTreeSitterIfAvailable && runner != null) {
            probeTreeSitter(projectDir)
        } else false

        val parsedFiles = sourceFiles.map { file ->
            val rel = file.relativeTo(projectDir).path.replace(File.separatorChar, '/')
            val mtime = file.lastModified()
            val size = file.length()
            val cached = cachedByPath[rel]
            if (cached != null && cached.lastModified == mtime && cached.sizeBytes == size && !treeSitterAvailable) {
                cached
            } else {
                val lang = languageForExt(file.extension.lowercase())
                val text = runCatching { file.readText() }.getOrDefault("")
                val tsSymbols = if (treeSitterAvailable && runner != null) {
                    parseWithTreeSitter(projectDir, rel)
                } else emptyList()
                val symbols = tsSymbols.ifEmpty { extractSymbolsFast(text, lang) }
                val refs = extractIdentifierTokens(text)
                RepoFileSymbols(
                    relativePath = rel,
                    language = lang,
                    lastModified = mtime,
                    sizeBytes = size,
                    symbols = symbols.take(60),
                    references = refs
                )
            }
        }

        val cache = RepoMapCache(
            generatedAt = System.currentTimeMillis(),
            usedTreeSitter = treeSitterAvailable,
            files = parsedFiles
        )
        runCatching {
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(JsonHelper.json.encodeToString(cache))
        }
        cache
    }

    suspend fun formatRepoMap(
        projectDir: File,
        pathPrefix: String = "",
        maxChars: Int = 2800,
        useTreeSitterIfAvailable: Boolean = true
    ): String {
        val cache = buildOrRefresh(projectDir, useTreeSitterIfAvailable)
        val filtered = if (pathPrefix.isBlank()) {
            cache.files
        } else {
            val clean = pathPrefix.trim().trimStart('/').replace('\\', '/')
            cache.files.filter { it.relativePath.startsWith(clean, ignoreCase = true) }
        }
        if (filtered.isEmpty()) return "(no indexed source files found)"

        val ranked = rankFilesByReferences(filtered)
        val sb = StringBuilder()
        sb.appendLine("Repo Map (${filtered.size} files, engine=${if (cache.usedTreeSitter) "tree-sitter+kotlin" else "kotlin-ast"}):")
        for (item in ranked) {
            val f = item.entry
            if (f.symbols.isEmpty()) continue
            val block = buildString {
                appendLine("${f.relativePath}:")
                f.symbols.take(12).forEach { sym ->
                    val sig = if (sym.signature.isNotBlank()) sym.signature else "${sym.kind} ${sym.name}"
                    appendLine("  L${sym.line}: $sig")
                }
            }
            if (sb.length + block.length > maxChars) {
                sb.appendLine("... (truncated; query repo_map with path_prefix for more)")
                break
            }
            sb.append(block)
        }
        return sb.toString().trim()
    }

    fun rankFilesByReferences(files: List<RepoFileSymbols>): List<RankedRepoFile> {
        val definedNames = HashMap<String, MutableList<String>>()
        for (f in files) {
            for (s in f.symbols) {
                if (s.name.length >= 3) {
                    definedNames.getOrPut(s.name) { mutableListOf() }.add(f.relativePath)
                }
            }
        }

        val scores = files.associate { it.relativePath to 1.0 + (it.symbols.size * 0.15) }.toMutableMap()
        for (f in files) {
            for (tok in f.references) {
                val owners = definedNames[tok] ?: continue
                for (owner in owners) {
                    if (owner != f.relativePath) {
                        scores[owner] = (scores[owner] ?: 1.0) + 0.5
                    }
                }
            }
        }
        return files
            .map { RankedRepoFile(it, scores[it.relativePath] ?: 1.0) }
            .sortedByDescending { it.score }
    }

    @Volatile private var tsProbeAt = 0L
    @Volatile private var tsProbeResult = false

    private suspend fun probeTreeSitter(projectDir: File): Boolean {
        val now = System.currentTimeMillis()
        if (now - tsProbeAt < 600_000L) return tsProbeResult
        val r = runner ?: return false
        val result = runCatching {
            val res = r.exec("command -v tree-sitter >/dev/null 2>&1 && echo OK", projectDir, timeoutMs = 2_500L)
            res.exitCode == 0 && res.stdout.contains("OK")
        }.getOrDefault(false)
        tsProbeAt = now
        tsProbeResult = result
        return result
    }

    private suspend fun parseWithTreeSitter(projectDir: File, relPath: String): List<RepoSymbol> {
        val r = runner ?: return emptyList()
        return runCatching {
            val res = r.exec("tree-sitter tags \"$relPath\"", projectDir, timeoutMs = 4_000L)
            if (res.exitCode != 0 || res.stdout.isBlank()) return emptyList()
            res.stdout.lines().mapNotNull { line ->
                // tree-sitter tags output commonly includes: <name> | <kind> (line, col)
                val match = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s+\|\s+(\w+).*?\((\d+)""").find(line.trim())
                if (match != null) {
                    RepoSymbol(
                        name = match.groupValues[1],
                        kind = match.groupValues[2],
                        line = (match.groupValues[3].toIntOrNull() ?: 0) + 1,
                        signature = "${match.groupValues[2]} ${match.groupValues[1]}"
                    )
                } else null
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        fun languageForExt(ext: String): String = when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "jsx" -> "javascript"
            "ts", "tsx" -> "typescript"
            "go" -> "go"
            "rs" -> "rust"
            "dart" -> "dart"
            "c", "h" -> "c"
            "cpp", "hpp" -> "cpp"
            else -> ext
        }

        fun extractSymbolsFast(source: String, language: String): List<RepoSymbol> {
            val out = mutableListOf<RepoSymbol>()
            val lines = source.lines()

            val ktRegex = Regex("""^\s*(?:(?:public|private|internal|protected|open|abstract|sealed|data|value|enum|annotation|suspend|inline|override|operator)\s+)*(class|interface|object|enum\s+class|fun|typealias)\s+([A-Za-z_][A-Za-z0-9_<>.]*)""")
            val pyRegex = Regex("""^\s*(?:async\s+)?(def|class)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            val jsRegex = Regex("""^\s*(?:export\s+(?:default\s+)?)?(?:async\s+)?(function|class|interface|type|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            val jsConstFnRegex = Regex("""^\s*(?:export\s+)?(?:const|let)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?:async\s+)?(?:\([^)]*\)|[A-Za-z_][A-Za-z0-9_]*)\s*=>""")
            val goRegex = Regex("""^\s*(func(?:\s*\([^)]+\))?|type)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            val rsRegex = Regex("""^\s*(?:pub(?:\([^)]+\))?\s+)?(?:async\s+)?(fn|struct|enum|trait|impl|mod)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            val javaDartRegex = Regex("""^\s*(?:(?:public|private|protected|static|final|abstract|sealed|override)\s+)*(class|interface|enum|record|mixin|extension)\s+([A-Za-z_][A-Za-z0-9_]*)""")
            val dartFnRegex = Regex("""^\s*(?:Future<[^>]+>|void|String|int|double|bool|dynamic|Widget|[A-Z][A-Za-z0-9_<>?]*)\s+([a-z_][A-Za-z0-9_]*)\s*\(""")

            lines.forEachIndexed { idx, rawLine ->
                val lineNo = idx + 1
                val trimmed = rawLine.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*")) {
                    return@forEachIndexed
                }

                when (language) {
                    "kotlin" -> ktRegex.find(rawLine)?.let { m ->
                        val kind = m.groupValues[1].replace(Regex("\\s+"), " ")
                        val name = m.groupValues[2].substringBefore('<')
                        out += RepoSymbol(name, kind, lineNo, trimmed.take(90))
                    }
                    "python" -> pyRegex.find(rawLine)?.let { m ->
                        out += RepoSymbol(m.groupValues[2], m.groupValues[1], lineNo, trimmed.take(90))
                    }
                    "javascript", "typescript" -> {
                        val m1 = jsRegex.find(rawLine)
                        if (m1 != null) {
                            out += RepoSymbol(m1.groupValues[2], m1.groupValues[1], lineNo, trimmed.take(90))
                        } else {
                            jsConstFnRegex.find(rawLine)?.let { m2 ->
                                out += RepoSymbol(m2.groupValues[1], "fun", lineNo, trimmed.take(90))
                            }
                        }
                    }
                    "go" -> goRegex.find(rawLine)?.let { m ->
                        val kind = if (m.groupValues[1].startsWith("func")) "fun" else "type"
                        out += RepoSymbol(m.groupValues[2], kind, lineNo, trimmed.take(90))
                    }
                    "rust" -> rsRegex.find(rawLine)?.let { m ->
                        out += RepoSymbol(m.groupValues[2], m.groupValues[1], lineNo, trimmed.take(90))
                    }
                    "java", "dart", "c", "cpp" -> {
                        val m1 = javaDartRegex.find(rawLine)
                        if (m1 != null) {
                            out += RepoSymbol(m1.groupValues[2], m1.groupValues[1], lineNo, trimmed.take(90))
                        } else if (language == "dart" || language == "java") {
                            dartFnRegex.find(rawLine)?.let { m2 ->
                                val name = m2.groupValues[1]
                                if (name !in setOf("if", "for", "while", "switch", "catch", "return")) {
                                    out += RepoSymbol(name, "fun", lineNo, trimmed.take(90))
                                }
                            }
                        }
                    }
                }
            }
            return out
        }

        private fun extractIdentifierTokens(source: String): List<String> {
            return Regex("""\b[A-Z][A-Za-z0-9_]{2,40}\b|\b[a-z][A-Za-z0-9_]{3,40}\b""")
                .findAll(source.take(40_000))
                .map { it.value }
                .distinct()
                .take(200)
                .toList()
        }
    }
}
