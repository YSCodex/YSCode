// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.git

import com.example.util.DiffEngine
import com.example.util.FileDiff
import com.example.util.HunkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class TrackedFileChange(
    val relativePath: String,
    val absolutePath: String,
    val beforeContent: String,
    val afterContent: String,
    val diff: FileDiff,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isFullyResolved: Boolean
        get() = diff.hunks.isNotEmpty() && diff.hunks.all { it.status != HunkStatus.PENDING }
}

class ChangesRepository {
    private val _changes = MutableStateFlow<List<TrackedFileChange>>(emptyList())
    val changes: StateFlow<List<TrackedFileChange>> = _changes.asStateFlow()

    fun recordChange(projectDir: File, file: File, before: String, after: String) {
        val rel = runCatching {
            file.canonicalFile.relativeTo(projectDir.canonicalFile).path.replace(File.separatorChar, '/')
        }.getOrElse { file.name }

        val existing = _changes.value.find { it.absolutePath == file.canonicalPath }
        val originalBefore = existing?.beforeContent ?: before
        if (originalBefore == after) {
            _changes.value = _changes.value.filterNot { it.absolutePath == file.canonicalPath }
            return
        }
        val diff = DiffEngine.computeDiff(rel, originalBefore, after)
        val entry = TrackedFileChange(
            relativePath = rel,
            absolutePath = file.canonicalPath,
            beforeContent = originalBefore,
            afterContent = after,
            diff = diff
        )
        _changes.value = _changes.value.filterNot { it.absolutePath == file.canonicalPath } + entry
    }

    fun setHunkStatus(relativePath: String, hunkId: Int, status: HunkStatus) {
        val current = _changes.value.find { it.relativePath == relativePath } ?: return
        val updatedHunks = current.diff.hunks.map { h ->
            if (h.id == hunkId) h.copy(status = status) else h
        }
        val newContent = DiffEngine.applyHunkDecisions(current.beforeContent, updatedHunks)
        writeFileSafely(File(current.absolutePath), newContent)

        if (newContent == current.beforeContent && updatedHunks.all { it.status == HunkStatus.REJECTED }) {
            _changes.value = _changes.value.filterNot { it.relativePath == relativePath }
        } else {
            val updatedDiff = current.diff.copy(hunks = updatedHunks)
            _changes.value = _changes.value.map {
                if (it.relativePath == relativePath) {
                    it.copy(afterContent = newContent, diff = updatedDiff)
                } else it
            }
        }
    }

    fun acceptFile(relativePath: String) {
        _changes.value = _changes.value.filterNot { it.relativePath == relativePath }
    }

    fun revertFile(relativePath: String) {
        val current = _changes.value.find { it.relativePath == relativePath } ?: return
        val file = File(current.absolutePath)
        if (current.beforeContent.isEmpty() && current.diff.isNewFile) {
            file.delete()
        } else {
            writeFileSafely(file, current.beforeContent)
        }
        _changes.value = _changes.value.filterNot { it.relativePath == relativePath }
    }

    fun acceptAll() {
        _changes.value = emptyList()
    }

    fun revertAll() {
        _changes.value.toList().forEach { change ->
            revertFile(change.relativePath)
        }
        _changes.value = emptyList()
    }

    fun clearTrackedOnly() {
        _changes.value = emptyList()
    }

    private fun writeFileSafely(file: File, content: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.writeText(content)
            tmp.delete()
        }
    }
}
