// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import com.example.util.DiffEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

class ReadFileTool : Tool {
    override val name = "read_file"
    override val description = "Read a file with 1-based line numbers. Use offset and limit for large files."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "File path relative to project or absolute") }
            putJsonObject("offset") { put("type", "integer"); put("description", "1-based starting line number (default 1)") }
            putJsonObject("limit") { put("type", "integer"); put("description", "Maximum lines to read (default 300)") }
        }
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("path")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult.error("Missing 'path'")
        val file = ctx.env.resolveProjectPath(rawPath, ctx.projectDir)
        if (!file.exists()) return ToolResult.error("File not found: $rawPath")
        if (file.isDirectory) return ToolResult.error("Path is a directory, use list_dir: $rawPath")
        if (com.example.util.ImageLoader.sniffMime(file) != null) {
            // Like OpenCode's read tool: images are delivered as real image attachments, not mojibake.
            return ImageDelivery.deliver(file, ctx, null)
        }
        run {
            val head = ByteArray(4096)
            val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
            if (n > 0 && (0 until n).any { head[it].toInt() == 0 }) {
                return ToolResult.error(
                    "Binary file ($rawPath, ${file.length()} bytes): cannot be shown as text. Use view_image for images, " +
                        "or bash (strings, xxd, unzip -l, tar -tf) for other binaries."
                )
            }
        }
        if (file.length() > 5L * 1024L * 1024L) {
            return ToolResult.error("File is larger than 5 MB (${file.length()} bytes). Use bash/head to inspect.")
        }

        val lines = runCatching { file.readLines() }.getOrElse {
            return ToolResult.error("Failed to read file: ${it.message}")
        }
        ctx.readTracker.markRead(file)

        val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.int("limit") ?: 300).coerceIn(1, 2000)
        if (lines.isEmpty()) return ToolResult.ok("(empty file)", summary = "0 lines")

        val startIdx = (offset - 1).coerceAtMost(lines.size)
        val endIdx = (startIdx + limit).coerceAtMost(lines.size)
        val out = buildString {
            for (i in startIdx until endIdx) {
                append(i + 1).append(": ").appendLine(lines[i])
            }
            if (endIdx < lines.size) {
                append("... [showing lines ${startIdx + 1}-$endIdx of ${lines.size}; pass offset=${endIdx + 1} for more]")
            }
        }
        return ToolResult.ok(out.trimEnd(), summary = "${endIdx - startIdx} lines")
    }
}

class WriteFileTool : Tool {
    override val name = "write_file"
    override val description = "Create or overwrite a file atomically. Read existing files before overwriting."
    override val risk = Risk.WRITE
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "Target file path") }
            putJsonObject("content") { put("type", "string"); put("description", "Full file content to write") }
        }
        put("required", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("path"))
            add(kotlinx.serialization.json.JsonPrimitive("content"))
        })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult.error("Missing 'path'")
        val content = args.str("content") ?: return ToolResult.error("Missing 'content'")
        val file = ctx.env.resolveProjectPath(rawPath, ctx.projectDir)

        if (file.exists() && file.isFile && !ctx.readTracker.hasRead(file)) {
            return ToolResult.error("Read-before-write policy: call read_file on '$rawPath' before overwriting it.")
        }

        val before = if (file.exists() && file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        atomicWrite(file, content).getOrElse {
            return ToolResult.error("Failed to write '$rawPath': ${it.message}")
        }
        ctx.readTracker.markRead(file)
        ctx.changesRepository.recordChange(ctx.projectDir, file, before, content)

        val rel = relativeLabel(file, ctx.projectDir)
        val diff = DiffEngine.computeDiff(rel, before, content)
        return ToolResult.ok(
            output = "Wrote ${content.length} chars to $rel (+${diff.addedCount} -${diff.removedCount})",
            summary = "+${diff.addedCount} -${diff.removedCount}",
            diff = ToolDiffInfo(rel, before, content, diff.addedCount, diff.removedCount)
        )
    }
}

class EditFileTool : Tool {
    override val name = "edit_file"
    override val description = "Exact string replacement in a file. Read the file first. old_string must match uniquely unless replace_all=true."
    override val risk = Risk.WRITE
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "File path to edit") }
            putJsonObject("old_string") { put("type", "string"); put("description", "Exact text to find") }
            putJsonObject("new_string") { put("type", "string"); put("description", "Replacement text") }
            putJsonObject("replace_all") { put("type", "boolean"); put("description", "Replace all occurrences (default false)") }
        }
        put("required", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("path"))
            add(kotlinx.serialization.json.JsonPrimitive("old_string"))
            add(kotlinx.serialization.json.JsonPrimitive("new_string"))
        })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult.error("Missing 'path'")
        val oldStr = args.str("old_string") ?: return ToolResult.error("Missing 'old_string'")
        val newStr = args.str("new_string") ?: return ToolResult.error("Missing 'new_string'")
        val replaceAll = args.bool("replace_all") ?: false

        if (oldStr.isEmpty()) return ToolResult.error("'old_string' cannot be empty")
        val file = ctx.env.resolveProjectPath(rawPath, ctx.projectDir)
        if (!file.exists() || !file.isFile) return ToolResult.error("File not found: $rawPath")
        if (!ctx.readTracker.hasRead(file)) {
            return ToolResult.error("Read-before-write policy: call read_file on '$rawPath' before editing.")
        }

        val before = runCatching { file.readText() }.getOrElse {
            return ToolResult.error("Failed to read '$rawPath': ${it.message}")
        }
        // Preserve CRLF if original file uses CRLF
        val usesCrlf = before.contains("\r\n")
        val normalizedBefore = if (usesCrlf) before.replace("\r\n", "\n") else before
        val normalizedOld = oldStr.replace("\r\n", "\n")
        val normalizedNew = newStr.replace("\r\n", "\n")

        val occurrences = countOccurrences(normalizedBefore, normalizedOld)
        if (occurrences == 0) {
            val hint = findNearestLineHint(normalizedBefore, normalizedOld)
            return ToolResult.error(
                "old_string not found in '$rawPath'. Re-read the file and include exact whitespace/indentation.\n$hint"
            )
        }
        if (occurrences > 1 && !replaceAll) {
            return ToolResult.error(
                "old_string matched $occurrences times in '$rawPath'. Include more surrounding context to make it unique, or set replace_all=true."
            )
        }

        val updatedNormalized = if (replaceAll) {
            normalizedBefore.replace(normalizedOld, normalizedNew)
        } else {
            val idx = normalizedBefore.indexOf(normalizedOld)
            normalizedBefore.substring(0, idx) + normalizedNew + normalizedBefore.substring(idx + normalizedOld.length)
        }
        val after = if (usesCrlf) updatedNormalized.replace("\n", "\r\n") else updatedNormalized

        atomicWrite(file, after).getOrElse {
            return ToolResult.error("Failed to write '$rawPath': ${it.message}")
        }
        ctx.changesRepository.recordChange(ctx.projectDir, file, before, after)

        val rel = relativeLabel(file, ctx.projectDir)
        val diff = DiffEngine.computeDiff(rel, before, after)
        return ToolResult.ok(
            output = "Edited $rel (+${diff.addedCount} -${diff.removedCount})",
            summary = "+${diff.addedCount} -${diff.removedCount}",
            diff = ToolDiffInfo(rel, before, after, diff.addedCount, diff.removedCount)
        )
    }
}

class MultiEditTool : Tool {
    override val name = "multi_edit"
    override val description = "Apply multiple sequential exact-match edits to a single file atomically."
    override val risk = Risk.WRITE
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string") }
            putJsonObject("edits") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("old_string") { put("type", "string") }
                        putJsonObject("new_string") { put("type", "string") }
                        putJsonObject("replace_all") { put("type", "boolean") }
                    }
                    put("required", buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("old_string"))
                        add(kotlinx.serialization.json.JsonPrimitive("new_string"))
                    })
                }
            }
        }
        put("required", buildJsonArray {
            add(kotlinx.serialization.json.JsonPrimitive("path"))
            add(kotlinx.serialization.json.JsonPrimitive("edits"))
        })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args.str("path") ?: return ToolResult.error("Missing 'path'")
        val editsArr = args["edits"]?.jsonArray ?: return ToolResult.error("Missing 'edits' array")
        val file = ctx.env.resolveProjectPath(rawPath, ctx.projectDir)
        if (!file.exists() || !file.isFile) return ToolResult.error("File not found: $rawPath")
        if (!ctx.readTracker.hasRead(file)) {
            return ToolResult.error("Read-before-write policy: call read_file on '$rawPath' before multi_edit.")
        }

        val before = runCatching { file.readText() }.getOrElse {
            return ToolResult.error("Failed to read '$rawPath': ${it.message}")
        }
        val usesCrlf = before.contains("\r\n")
        var working = if (usesCrlf) before.replace("\r\n", "\n") else before

        for ((index, el) in editsArr.withIndex()) {
            val obj = el.jsonObject
            val oldStr = obj.str("old_string")?.replace("\r\n", "\n")
                ?: return ToolResult.error("Edit #$index missing old_string")
            val newStr = obj.str("new_string")?.replace("\r\n", "\n")
                ?: return ToolResult.error("Edit #$index missing new_string")
            val replaceAll = obj.bool("replace_all") ?: false
            val count = countOccurrences(working, oldStr)
            if (count == 0) {
                return ToolResult.error("Edit #$index failed: old_string not found. No changes written.")
            }
            if (count > 1 && !replaceAll) {
                return ToolResult.error("Edit #$index failed: old_string matched $count times (must be unique). No changes written.")
            }
            working = if (replaceAll) working.replace(oldStr, newStr)
            else {
                val pos = working.indexOf(oldStr)
                working.substring(0, pos) + newStr + working.substring(pos + oldStr.length)
            }
        }

        val after = if (usesCrlf) working.replace("\n", "\r\n") else working
        atomicWrite(file, after).getOrElse { return ToolResult.error("Failed to write '$rawPath': ${it.message}") }
        ctx.changesRepository.recordChange(ctx.projectDir, file, before, after)

        val rel = relativeLabel(file, ctx.projectDir)
        val diff = DiffEngine.computeDiff(rel, before, after)
        return ToolResult.ok(
            output = "Applied ${editsArr.size} edits to $rel (+${diff.addedCount} -${diff.removedCount})",
            summary = "${editsArr.size} edits (+${diff.addedCount} -${diff.removedCount})",
            diff = ToolDiffInfo(rel, before, after, diff.addedCount, diff.removedCount)
        )
    }
}

class ListDirTool : Tool {
    override val name = "list_dir"
    override val description = "List files and directories inside a path."
    override val risk = Risk.READ
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "Directory path (default '.')") }
            putJsonObject("show_hidden") { put("type", "boolean"); put("description", "Include dotfiles (default false)") }
        }
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val rawPath = args.str("path") ?: "."
        val showHidden = args.bool("show_hidden") ?: false
        val dir = ctx.env.resolveProjectPath(rawPath, ctx.projectDir)
        if (!dir.exists()) return ToolResult.error("Directory not found: $rawPath")
        if (!dir.isDirectory) return ToolResult.error("Path is a file, not a directory: $rawPath")

        val entries = dir.listFiles()
            ?.filter { showHidden || !it.name.startsWith(".") }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .orEmpty()

        if (entries.isEmpty()) return ToolResult.ok("(empty directory)", summary = "0 items")
        val text = entries.joinToString("\n") { f ->
            if (f.isDirectory) "${f.name}/" else "${f.name} (${formatBytes(f.length())})"
        }
        return ToolResult.ok(text, summary = "${entries.size} items")
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "${b / 1024}KB"
        else -> "${b / (1024 * 1024)}MB"
    }
}

private fun atomicWrite(file: File, content: String): Result<Unit> = runCatching {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.tmp")
    tmp.writeText(content)
    if (!tmp.renameTo(file)) {
        file.writeText(content)
        tmp.delete()
    }
}

private fun relativeLabel(file: File, projectDir: File): String = runCatching {
    file.canonicalFile.relativeTo(projectDir.canonicalFile).path.replace(File.separatorChar, '/')
}.getOrElse { file.name }

private fun countOccurrences(haystack: String, needle: String): Int {
    var count = 0
    var idx = 0
    while (true) {
        idx = haystack.indexOf(needle, idx)
        if (idx < 0) break
        count++
        idx += needle.length
    }
    return count
}

private fun findNearestLineHint(content: String, needle: String): String {
    val firstLine = needle.lines().firstOrNull { it.trim().isNotEmpty() }?.trim() ?: return ""
    val lines = content.lines()
    val matches = lines.mapIndexedNotNull { i, l ->
        if (l.contains(firstLine, ignoreCase = true)) "${i + 1}: ${l.trim()}" else null
    }.take(3)
    return if (matches.isEmpty()) "" else "Possible nearby lines:\n" + matches.joinToString("\n")
}
