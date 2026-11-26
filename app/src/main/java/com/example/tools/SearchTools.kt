// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

class GlobTool : Tool {
    override val name = "glob"
    override val description = "Fast file search matching a glob pattern (e.g. '**/*.kt', 'src/*.ts')."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("pattern") { put("type", "string"); put("description", "Glob pattern such as '**/*.py'") }
            putJsonObject("path") { put("type", "string"); put("description", "Base directory (default '.')") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
    }

    private val skipDirs = setOf(".git", "node_modules", ".gradle", "build", "__pycache__", ".venv")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val pattern = args.str("pattern") ?: return ToolResult.error("Missing 'pattern'")
        val basePath = args.str("path") ?: "."
        val baseDir = ctx.env.resolveProjectPath(basePath, ctx.projectDir)
        if (!baseDir.exists() || !baseDir.isDirectory) return ToolResult.error("Directory not found: $basePath")

        val regex = globToRegex(pattern)
        val matches = mutableListOf<String>()
        baseDir.walkTopDown()
            .onEnter { dir -> dir == baseDir || dir.name !in skipDirs }
            .filter { it.isFile }
            .forEach { file ->
                val rel = file.relativeTo(baseDir).path.replace(File.separatorChar, '/')
                if (regex.matches(rel) || regex.matches(file.name)) {
                    matches += rel
                    if (matches.size >= 500) return@forEach
                }
            }

        if (matches.isEmpty()) return ToolResult.ok("No files matched '$pattern'", summary = "0 matches")
        val truncatedNote = if (matches.size >= 500) "\n... (capped at 500 results)" else ""
        return ToolResult.ok(matches.sorted().joinToString("\n") + truncatedNote, summary = "${matches.size} files")
    }
}

class GrepTool : Tool {
    override val name = "grep"
    override val description = "Fast regex content search across files in the project."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("pattern") { put("type", "string"); put("description", "Regex pattern to search for") }
            putJsonObject("path") { put("type", "string"); put("description", "Directory or file to search (default '.')") }
            putJsonObject("glob") { put("type", "string"); put("description", "Optional filename glob filter e.g. '*.kt'") }
            putJsonObject("case_insensitive") { put("type", "boolean"); put("description", "Case insensitive match") }
            putJsonObject("max_results") { put("type", "integer"); put("description", "Max matching lines (default 100)") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
    }

    private val skipDirs = setOf(".git", "node_modules", ".gradle", "build", "__pycache__", ".venv")

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPattern = args.str("pattern") ?: return ToolResult.error("Missing 'pattern'")
        val basePath = args.str("path") ?: "."
        val globFilter = args.str("glob")
        val ignoreCase = args.bool("case_insensitive") ?: false
        val maxResults = (args.int("max_results") ?: 100).coerceIn(1, 500)

        val regex = runCatching {
            if (ignoreCase) Regex(rawPattern, RegexOption.IGNORE_CASE) else Regex(rawPattern)
        }.getOrElse { return ToolResult.error("Invalid regex '$rawPattern': ${it.message}") }

        val globRegex = globFilter?.let { globToRegex(it) }
        val target = ctx.env.resolveProjectPath(basePath, ctx.projectDir)
        if (!target.exists()) return ToolResult.error("Path not found: $basePath")

        val hits = mutableListOf<String>()
        val files: Sequence<File> = if (target.isFile) {
            sequenceOf(target)
        } else {
            target.walkTopDown()
                .onEnter { dir -> dir == target || dir.name !in skipDirs }
                .filter { it.isFile && it.length() < 1_500_000L }
        }

        for (file in files) {
            val rel = if (target.isFile) file.name else file.relativeTo(target).path.replace(File.separatorChar, '/')
            if (globRegex != null && !globRegex.matches(rel) && !globRegex.matches(file.name)) continue
            if (isLikelyBinary(file)) continue
            runCatching {
                file.useLines { seq ->
                    seq.forEachIndexed { idx, line ->
                        if (hits.size < maxResults && regex.containsMatchIn(line)) {
                            hits += "$rel:${idx + 1}: ${line.take(240)}"
                        }
                    }
                }
            }
            if (hits.size >= maxResults) break
        }

        if (hits.isEmpty()) return ToolResult.ok("No matches for /$rawPattern/", summary = "0 matches")
        return ToolResult.ok(hits.joinToString("\n"), summary = "${hits.size} matches")
    }

    private fun isLikelyBinary(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in setOf("png", "jpg", "jpeg", "webp", "gif", "zip", "gz", "tar", "so", "jar", "apk", "pdf")) {
            return true
        }
        return runCatching {
            file.inputStream().use { inp ->
                val buf = ByteArray(256)
                val n = inp.read(buf)
                (0 until n).any { buf[it] == 0.toByte() }
            }
        }.getOrDefault(false)
    }
}

private fun globToRegex(glob: String): Regex {
    val sb = StringBuilder("^")
    var i = 0
    val clean = glob.replace('\\', '/').removePrefix("./")
    while (i < clean.length) {
        when {
            clean.startsWith("**/", i) -> {
                sb.append("(?:.*/)?")
                i += 3
            }
            clean.startsWith("**", i) -> {
                sb.append(".*")
                i += 2
            }
            clean[i] == '*' -> {
                sb.append("[^/]*")
                i++
            }
            clean[i] == '?' -> {
                sb.append("[^/]")
                i++
            }
            clean[i] in ".^$+{}[]()|\\" -> {
                sb.append('\\').append(clean[i])
                i++
            }
            else -> {
                sb.append(clean[i])
                i++
            }
        }
    }
    sb.append("$")
    return Regex(sb.toString())
}
