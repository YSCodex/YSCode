// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.util

import kotlin.math.max
import kotlin.math.min

enum class DiffLineType { CONTEXT, ADDED, REMOVED }

data class WordSpan(
    val text: String,
    val emphasized: Boolean
)

data class DiffLine(
    val type: DiffLineType,
    val oldLineNumber: Int?,
    val newLineNumber: Int?,
    val text: String,
    val wordSpans: List<WordSpan> = listOf(WordSpan(text, false))
)

data class DiffHunk(
    val id: Int,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>,
    val status: HunkStatus = HunkStatus.PENDING
)

enum class HunkStatus { PENDING, ACCEPTED, REJECTED }

data class FileDiff(
    val filePath: String,
    val hunks: List<DiffHunk>,
    val addedCount: Int,
    val removedCount: Int,
    val isNewFile: Boolean,
    val isDeletedFile: Boolean
)

object DiffEngine {

    fun computeDiff(
        filePath: String,
        before: String,
        after: String,
        contextLines: Int = 3
    ): FileDiff {
        val oldLines = if (before.isEmpty()) emptyList() else before.split("\n")
        val newLines = if (after.isEmpty()) emptyList() else after.split("\n")

        val ops = myersDiff(oldLines, newLines)
        val rawLines = mutableListOf<DiffLine>()
        var oldNum = 1
        var newNum = 1
        var added = 0
        var removed = 0

        for (op in ops) {
            when (op.type) {
                DiffLineType.CONTEXT -> {
                    rawLines += DiffLine(DiffLineType.CONTEXT, oldNum++, newNum++, op.text)
                }
                DiffLineType.REMOVED -> {
                    removed++
                    rawLines += DiffLine(DiffLineType.REMOVED, oldNum++, null, op.text)
                }
                DiffLineType.ADDED -> {
                    added++
                    rawLines += DiffLine(DiffLineType.ADDED, null, newNum++, op.text)
                }
            }
        }

        // Pair adjacent removed/added lines for word-level highlight
        val enriched = addWordLevelHighlights(rawLines)
        val hunks = groupIntoHunks(enriched, contextLines)
        return FileDiff(
            filePath = filePath,
            hunks = hunks,
            addedCount = added,
            removedCount = removed,
            isNewFile = before.isEmpty() && after.isNotEmpty(),
            isDeletedFile = before.isNotEmpty() && after.isEmpty()
        )
    }

    /**
     * Reconstructs file content by reverting a specific rejected hunk (or applying accepted hunks).
     * Given the original [before] text and [after] text, applies all hunks except those marked REJECTED.
     */
    fun applyHunkDecisions(before: String, hunks: List<DiffHunk>): String {
        val oldLines = if (before.isEmpty()) emptyList() else before.split("\n")
        val out = mutableListOf<String>()
        var oldCursor = 1 // 1-based

        for (hunk in hunks.sortedBy { it.oldStart }) {
            val targetStart = max(1, hunk.oldStart)
            while (oldCursor < targetStart && oldCursor - 1 < oldLines.size) {
                out += oldLines[oldCursor - 1]
                oldCursor++
            }
            if (hunk.status == HunkStatus.REJECTED) {
                // Keep original lines from this hunk
                for (line in hunk.lines) {
                    if (line.type != DiffLineType.ADDED) {
                        out += line.text
                        oldCursor++
                    }
                }
            } else {
                // ACCEPTED or PENDING -> keep the new lines
                for (line in hunk.lines) {
                    if (line.type != DiffLineType.REMOVED) {
                        out += line.text
                    }
                    if (line.type != DiffLineType.ADDED) {
                        oldCursor++
                    }
                }
            }
        }
        while (oldCursor - 1 < oldLines.size) {
            out += oldLines[oldCursor - 1]
            oldCursor++
        }
        return out.joinToString("\n")
    }

    fun formatUnifiedDiff(diff: FileDiff): String {
        val sb = StringBuilder()
        sb.appendLine("--- a/${diff.filePath}")
        sb.appendLine("+++ b/${diff.filePath}")
        for (h in diff.hunks) {
            sb.appendLine("@@ -${h.oldStart},${h.oldCount} +${h.newStart},${h.newCount} @@")
            for (l in h.lines) {
                val prefix = when (l.type) {
                    DiffLineType.CONTEXT -> " "
                    DiffLineType.ADDED -> "+"
                    DiffLineType.REMOVED -> "-"
                }
                sb.appendLine("$prefix${l.text}")
            }
        }
        return sb.toString()
    }

    private data class RawOp(val type: DiffLineType, val text: String)

    private fun myersDiff(a: List<String>, b: List<String>): List<RawOp> {
        val n = a.size
        val m = b.size
        if (n == 0) return b.map { RawOp(DiffLineType.ADDED, it) }
        if (m == 0) return a.map { RawOp(DiffLineType.REMOVED, it) }
        // Guard extremely huge files (>4000 lines) with fast prefix/suffix trim + fallback
        if (n + m > 4500) {
            return fastPrefixSuffixDiff(a, b)
        }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    max(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val result = ArrayList<RawOp>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                result += RawOp(DiffLineType.CONTEXT, a[i])
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                result += RawOp(DiffLineType.REMOVED, a[i])
                i++
            } else {
                result += RawOp(DiffLineType.ADDED, b[j])
                j++
            }
        }
        while (i < n) result += RawOp(DiffLineType.REMOVED, a[i++])
        while (j < m) result += RawOp(DiffLineType.ADDED, b[j++])
        return result
    }

    private fun fastPrefixSuffixDiff(a: List<String>, b: List<String>): List<RawOp> {
        var prefix = 0
        val minLen = min(a.size, b.size)
        while (prefix < minLen && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < (minLen - prefix) && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++

        val result = mutableListOf<RawOp>()
        for (i in 0 until prefix) result += RawOp(DiffLineType.CONTEXT, a[i])
        for (i in prefix until a.size - suffix) result += RawOp(DiffLineType.REMOVED, a[i])
        for (j in prefix until b.size - suffix) result += RawOp(DiffLineType.ADDED, b[j])
        for (i in a.size - suffix until a.size) result += RawOp(DiffLineType.CONTEXT, a[i])
        return result
    }

    private fun addWordLevelHighlights(lines: List<DiffLine>): List<DiffLine> {
        val out = lines.toMutableList()
        var i = 0
        while (i < out.size) {
            if (out[i].type == DiffLineType.REMOVED) {
                val remStart = i
                while (i < out.size && out[i].type == DiffLineType.REMOVED) i++
                val addStart = i
                while (i < out.size && out[i].type == DiffLineType.ADDED) i++
                val pairs = min(addStart - remStart, i - addStart)
                for (p in 0 until pairs) {
                    val oldLine = out[remStart + p]
                    val newLine = out[addStart + p]
                    val (oldSpans, newSpans) = computeWordSpans(oldLine.text, newLine.text)
                    out[remStart + p] = oldLine.copy(wordSpans = oldSpans)
                    out[addStart + p] = newLine.copy(wordSpans = newSpans)
                }
            } else {
                i++
            }
        }
        return out
    }

    private fun computeWordSpans(oldText: String, newText: String): Pair<List<WordSpan>, List<WordSpan>> {
        val a = tokenizeWords(oldText)
        val b = tokenizeWords(newText)
        if (a.size + b.size > 300) {
            return listOf(WordSpan(oldText, true)) to listOf(WordSpan(newText, true))
        }
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else max(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val oldSpans = mutableListOf<WordSpan>()
        val newSpans = mutableListOf<WordSpan>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) {
                oldSpans += WordSpan(a[i], false)
                newSpans += WordSpan(b[j], false)
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                oldSpans += WordSpan(a[i++], true)
            } else {
                newSpans += WordSpan(b[j++], true)
            }
        }
        while (i < n) oldSpans += WordSpan(a[i++], true)
        while (j < m) newSpans += WordSpan(b[j++], true)
        return mergeSpans(oldSpans) to mergeSpans(newSpans)
    }

    private fun mergeSpans(spans: List<WordSpan>): List<WordSpan> {
        if (spans.isEmpty()) return listOf(WordSpan("", false))
        val merged = mutableListOf<WordSpan>()
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.emphasized == s.emphasized) {
                merged[merged.lastIndex] = last.copy(text = last.text + s.text)
            } else {
                merged += s
            }
        }
        return if (merged.isEmpty()) listOf(WordSpan("", false)) else merged
    }

    private fun tokenizeWords(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var currentIsWord: Boolean? = null
        for (ch in text) {
            val isWord = ch.isLetterOrDigit() || ch == '_'
            if (currentIsWord == null || currentIsWord == isWord && !ch.isWhitespace()) {
                sb.append(ch)
                currentIsWord = isWord
            } else {
                if (sb.isNotEmpty()) tokens += sb.toString()
                sb.clear()
                sb.append(ch)
                currentIsWord = isWord
            }
        }
        if (sb.isNotEmpty()) tokens += sb.toString()
        return tokens
    }

    private fun groupIntoHunks(lines: List<DiffLine>, context: Int): List<DiffHunk> {
        val changedIndices = lines.indices.filter { lines[it].type != DiffLineType.CONTEXT }
        if (changedIndices.isEmpty()) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var start = max(0, changedIndices.first() - context)
        var end = min(lines.lastIndex, changedIndices.first() + context)

        for (idx in changedIndices.drop(1)) {
            val candStart = max(0, idx - context)
            val candEnd = min(lines.lastIndex, idx + context)
            if (candStart <= end + 1) {
                end = max(end, candEnd)
            } else {
                ranges += start..end
                start = candStart
                end = candEnd
            }
        }
        ranges += start..end

        return ranges.mapIndexed { hunkIndex, range ->
            val slice = lines.subList(range.first, range.last + 1)
            val oldStart = slice.firstOrNull { it.oldLineNumber != null }?.oldLineNumber
                ?: slice.firstOrNull()?.newLineNumber ?: 1
            val newStart = slice.firstOrNull { it.newLineNumber != null }?.newLineNumber
                ?: slice.firstOrNull()?.oldLineNumber ?: 1
            val oldCount = slice.count { it.type != DiffLineType.ADDED }
            val newCount = slice.count { it.type != DiffLineType.REMOVED }
            DiffHunk(
                id = hunkIndex,
                oldStart = oldStart,
                oldCount = oldCount,
                newStart = newStart,
                newCount = newCount,
                lines = slice
            )
        }
    }
}
