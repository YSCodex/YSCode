// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Two-way sync between a project copy (app storage) and the user's chosen local folder (Storage Access Framework tree).
 * pull(): external changes/new files -> project. push(): agent/user edits, new files, deletions -> external folder.
 * Skips .git, node_modules, build outputs. A small manifest records the last synced state of both sides.
 */
class FolderSync(private val context: Context) {
    private val skip = setOf(".git", "node_modules", "build", ".gradle", ".yscode", "__pycache__", ".idea", ".venv")
    private val maxFile = 50L * 1024L * 1024L

    private fun linkFile(dir: File) = File(dir, ".yscode/linked.json")
    private fun manifestFile(dir: File) = File(dir, ".yscode/sync_manifest.json")

    private class Entry(val l: String, val s: String)
    private class Remote(val docId: String, val rel: String, val isDir: Boolean, val size: Long, val mtime: Long)

    fun link(projectDir: File, treeUri: Uri) {
        runCatching {
            linkFile(projectDir).parentFile?.mkdirs()
            linkFile(projectDir).writeText(JSONObject().put("treeUri", treeUri.toString()).toString())
        }
    }

    fun linkedUri(projectDir: File): Uri? = runCatching {
        val f = linkFile(projectDir)
        if (!f.isFile) null else Uri.parse(JSONObject(f.readText()).getString("treeUri"))
    }.getOrNull()

    fun isLinked(projectDir: File): Boolean = linkedUri(projectDir) != null

    private fun loadManifest(dir: File): MutableMap<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        runCatching {
            val f = manifestFile(dir)
            if (f.isFile) {
                val o = JSONObject(f.readText())
                for (k in o.keys()) {
                    val parts = o.getString(k).split("|")
                    if (parts.size == 2) out[k] = Entry(parts[0], parts[1])
                }
            }
        }
        return out
    }

    private fun saveManifest(dir: File, m: Map<String, Entry>) {
        runCatching {
            val o = JSONObject()
            m.forEach { (k, v) -> o.put(k, "${v.l}|${v.s}") }
            manifestFile(dir).parentFile?.mkdirs()
            manifestFile(dir).writeText(o.toString())
        }
    }

    private fun sig(f: File) = "${f.length()}:${f.lastModified()}"

    private fun listRemote(tree: Uri): List<Remote> {
        val out = ArrayList<Remote>()
        fun walk(parentId: String, parentRel: String, depth: Int) {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            context.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name in skip) continue
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val rel = if (parentRel.isEmpty()) name else "$parentRel/$name"
                    out += Remote(id, rel, isDir, if (c.isNull(3)) 0L else c.getLong(3), if (c.isNull(4)) 0L else c.getLong(4))
                    if (isDir && depth < 12) walk(id, rel, depth + 1)
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
        return out
    }

    private fun queryRemoteSig(docUri: Uri): String {
        return runCatching {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) "${if (c.isNull(0)) 0L else c.getLong(0)}:${if (c.isNull(1)) 0L else c.getLong(1)}" else "0:0" }
        }.getOrNull() ?: "0:0"
    }

    /** Called right after the initial import: marks every file as "in sync" so nothing is re-copied or overwritten. */
    suspend fun initManifest(projectDir: File): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val tree = linkedUri(projectDir) ?: return@runCatching
            val manifest = loadManifest(projectDir)
            for (r in listRemote(tree)) {
                if (r.isDir) continue
                val local = File(projectDir, r.rel)
                if (local.isFile) manifest[r.rel] = Entry(sig(local), "${r.size}:${r.mtime}")
            }
            saveManifest(projectDir, manifest)
        }
    }

    /** External folder -> project. Never overwrites a file that was also changed inside the project. */
    suspend fun pull(projectDir: File): String? = withContext(Dispatchers.IO) {
        val tree = linkedUri(projectDir) ?: return@withContext null
        runCatching {
            val manifest = loadManifest(projectDir)
            var copied = 0
            for (r in listRemote(tree)) {
                if (r.isDir) { File(projectDir, r.rel).mkdirs(); continue }
                val local = File(projectDir, r.rel)
                val srcSig = "${r.size}:${r.mtime}"
                val entry = manifest[r.rel]
                val localSig = if (local.isFile) sig(local) else null
                val shouldCopy = when {
                    localSig == null -> true
                    entry != null && entry.l == localSig && entry.s != srcSig -> true
                    else -> false
                }
                if (!shouldCopy) continue
                local.parentFile?.mkdirs()
                val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, r.docId)
                val tmp = File(local.parentFile, ".${local.name}.yspull")
                context.contentResolver.openInputStream(docUri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: continue
                if (local.exists()) local.delete()
                tmp.renameTo(local)
                manifest[r.rel] = Entry(sig(local), srcSig)
                copied++
            }
            saveManifest(projectDir, manifest)
            if (copied > 0) "Pulled $copied file(s) from the linked folder" else null
        }.getOrElse { "Folder pull failed: ${it.message}" }
    }

    /** Project -> external folder: edits, new files (with folders) and deletions of previously synced files. */
    suspend fun push(projectDir: File): String? = withContext(Dispatchers.IO) {
        val tree = linkedUri(projectDir) ?: return@withContext null
        runCatching {
            val manifest = loadManifest(projectDir)
            val remote = listRemote(tree)
            val byRel = remote.associateBy { it.rel }
            val dirIds = HashMap<String, String>()
            dirIds[""] = DocumentsContract.getTreeDocumentId(tree)
            remote.filter { it.isDir }.forEach { dirIds[it.rel] = it.docId }

            fun ensureDir(relDir: String): String {
                dirIds[relDir]?.let { return it }
                val parentId = ensureDir(relDir.substringBeforeLast('/', ""))
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
                val created = DocumentsContract.createDocument(
                    context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, relDir.substringAfterLast('/')
                ) ?: error("Cannot create folder $relDir")
                val id = DocumentsContract.getDocumentId(created)
                dirIds[relDir] = id
                return id
            }

            var written = 0
            var deleted = 0
            val seen = HashSet<String>()
            projectDir.walkTopDown()
                .onEnter { d -> d == projectDir || d.name !in skip }
                .filter { it.isFile && it.length() <= maxFile }
                .forEach { f ->
                    val rel = f.relativeTo(projectDir).path.replace(File.separatorChar, '/')
                    if (rel.split('/').any { it in skip } || f.name.endsWith(".yspull")) return@forEach
                    seen += rel
                    val s = sig(f)
                    val entry = manifest[rel]
                    if (entry != null && entry.l == s) return@forEach
                    val ext = f.extension.lowercase()
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                    val existing = byRel[rel]
                    val docUri = if (existing != null) {
                        DocumentsContract.buildDocumentUriUsingTree(tree, existing.docId)
                    } else {
                        val parentId = ensureDir(rel.substringBeforeLast('/', ""))
                        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
                        DocumentsContract.createDocument(context.contentResolver, parentUri, mime, f.name)
                            ?: error("Cannot create ${f.name}")
                    }
                    context.contentResolver.openOutputStream(docUri, "wt")?.use { out ->
                        f.inputStream().use { it.copyTo(out) }
                    } ?: error("Cannot write ${f.name}")
                    manifest[rel] = Entry(s, queryRemoteSig(docUri))
                    written++
                }

            // Files that were synced before but are gone from the project: delete the external copy (only if unchanged there).
            for ((rel, e) in manifest.toList()) {
                if (rel in seen || File(projectDir, rel).exists()) continue
                val r = byRel[rel]
                if (r != null && !r.isDir && "${r.size}:${r.mtime}" == e.s) {
                    runCatching {
                        DocumentsContract.deleteDocument(
                            context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, r.docId)
                        )
                        deleted++
                    }
                }
                manifest.remove(rel)
            }
            saveManifest(projectDir, manifest)
            if (written > 0 || deleted > 0) "Synced to linked folder: $written written, $deleted deleted" else null
        }.getOrElse { "Folder push failed: ${it.message}" }
    }
}
