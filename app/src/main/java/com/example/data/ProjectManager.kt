// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.example.linux.LinuxEnvironment
import com.example.linux.ProotRunner
import com.example.llm.HttpClients
import com.example.util.ZipHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class ProjectTemplate(val id: String, val label: String, val subtitle: String) {
    EMPTY("empty", "Empty Project", "Blank directory ready for any stack"),
    NODE("node", "Node.js App", "package.json + index.js + tests"),
    PYTHON("python", "Python Script", "main.py + test_main.py + requirements.txt"),
    KOTLIN("kotlin", "Android / Kotlin", "Kotlin starter + Gradle structure + README"),
    STATIC_WEB("web", "Static Web App", "index.html + style.css + app.js (live WebView preview)")
}

class ProjectManager(
    private val context: Context,
    private val env: LinuxEnvironment,
    private val runner: ProotRunner,
    private val projectDao: ProjectDao,
    private val sessionDao: SessionDao
) {
    suspend fun createFromTemplate(name: String, template: ProjectTemplate): Result<ProjectEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeName = sanitizeDirName(name)
                val dir = uniqueProjectDir(safeName)
                dir.mkdirs()
                writeTemplateFiles(dir, name.trim().ifBlank { safeName }, template)
                registerProject(name.trim().ifBlank { safeName }, dir)
            }
        }

    suspend fun cloneFromGit(url: String, customName: String = ""): Result<ProjectEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanUrl = url.trim().removeSuffix("/")
                if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                    error("Enter an HTTP/HTTPS repository URL")
                }
                val inferred = customName.trim().ifBlank {
                    cleanUrl.substringAfterLast('/').removeSuffix(".git").ifBlank { "cloned-repo" }
                }
                val dir = uniqueProjectDir(sanitizeDirName(inferred))
                env.projectsDir.mkdirs()

                val res = runner.exec("git clone \"$cleanUrl\" \"${dir.name}\"", env.projectsDir, timeoutMs = 120_000L)
                if (res.exitCode != 0 || !dir.exists()) {
                    // Fallback for GitHub URLs when git binary is absent
                    val gh = Regex("""^https?://github\.com/([^/]+)/([^/]+?)(?:\.git)?$""").find(cleanUrl)
                        ?: error(res.stderr.ifBlank { "git clone failed (exit ${res.exitCode})" })
                    val owner = gh.groupValues[1]
                    val repo = gh.groupValues[2]
                    var bytes: ByteArray? = null
                    for (branch in listOf("main", "master")) {
                        val req = Request.Builder()
                            .url("https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch")
                            .get().build()
                        HttpClients.streamingClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) bytes = resp.body?.bytes()
                        }
                        if (bytes != null) break
                    }
                    val archive = bytes ?: error("Failed to download repository ZIP from GitHub")
                    dir.mkdirs()
                    ZipHelper.unzip(ByteArrayInputStream(archive), dir, stripSingleTopDir = true)
                }
                registerProject(inferred, dir)
            }
        }

    suspend fun importFromZip(uri: Uri, nameHint: String = "imported-zip"): Result<ProjectEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = context.contentResolver.openInputStream(uri) ?: error("Unable to open ZIP file")
                val dir = uniqueProjectDir(sanitizeDirName(nameHint))
                dir.mkdirs()
                stream.use { ZipHelper.unzip(it, dir, maxBytes = 100L * 1024L * 1024L, stripSingleTopDir = true) }
                registerProject(nameHint, dir)
            }
        }

    suspend fun importFromSafFolder(treeUri: Uri): Result<ProjectEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolved = SafPathResolver.resolveTreeUri(treeUri)
                if (resolved != null && env.hasAllFilesAccess() && resolved.canWrite()) {
                    val local = addLocalFolder(resolved)
                    if (local.isSuccess) return@runCatching local.getOrThrow()
                }
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val folderName = docId.substringAfterLast('/').substringAfterLast(':').ifBlank { "imported-folder" }
                val dir = uniqueProjectDir(sanitizeDirName(folderName))
                dir.mkdirs()
                // Keep read+write access across app restarts so edits and new files can be synced back.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                copySafTree(treeUri, docId, dir)
                val sync = FolderSync(context)
                sync.link(dir, treeUri)
                sync.initManifest(dir)
                registerProject(folderName, dir)
            }
        }

    /**
     * Opens an on-device folder in place (no copy into the rootfs). The real path is bind-mounted into
     * PRoot under the same absolute path, so Bash, git and JVMs all see the user's actual files.
     */
    suspend fun addLocalFolder(hostDir: File): Result<ProjectEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val canonical = hostDir.canonicalFile
                if (!canonical.isDirectory) error("${canonical.absolutePath} is not a folder")
                if (!canonical.canRead()) error("No read access to ${canonical.absolutePath}. Grant All files access.")
                if (!canonical.canWrite()) error("${canonical.absolutePath} is read-only. Grant write access or pick another folder.")
                val existing = projectDao.getAll().firstOrNull { proj ->
                    proj.storageMode == ProjectEntity.STORAGE_LOCAL_PATH &&
                        proj.localPath?.let { runCatching { File(it).canonicalPath == canonical.path }.getOrDefault(false) } == true
                }
                if (existing != null) {
                    env.registerProjectDir(canonical)
                    return@runCatching existing
                }
                env.registerProjectDir(canonical)
                registerProject(
                    name = canonical.name.ifBlank { "local-folder" },
                    dir = canonical,
                    storageMode = ProjectEntity.STORAGE_LOCAL_PATH
                )
            }
        }

    /** Pull external changes, then push project changes, for a project linked to a local folder. */
    suspend fun syncLinked(projectDir: File): String? {
        val sync = FolderSync(context)
        if (!sync.isLinked(projectDir)) return null
        val a = sync.pull(projectDir)
        val b = sync.push(projectDir)
        return listOfNotNull(a, b).joinToString("; ").ifBlank { "Already in sync" }
    }

    suspend fun exportProjectZipToUri(project: ProjectEntity, destUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val out = context.contentResolver.openOutputStream(destUri) ?: error("Unable to open destination file")
                out.use { ZipHelper.zipDirectory(File(project.path), it) }
            }
        }

    suspend fun createShareableProjectZip(project: ProjectEntity): Result<Uri> =
        withContext(Dispatchers.IO) {
            runCatching {
                val outFile = File(context.cacheDir, "${sanitizeDirName(project.name)}.zip")
                FileOutputStream(outFile).use { ZipHelper.zipDirectory(File(project.path), it) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
            }
        }

    suspend fun generateYsCodeMd(projectDir: File): File = withContext(Dispatchers.IO) {
        val topFiles = projectDir.listFiles()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
            ?.take(30)
            ?.joinToString("\n") { if (it.isDirectory) "- ${it.name}/" else "- ${it.name}" }
            ?: "(empty)"

        val stackHints = buildList {
            if (File(projectDir, "package.json").exists()) add("Node.js (npm test / node)")
            if (File(projectDir, "pyproject.toml").exists() || File(projectDir, "requirements.txt").exists()) add("Python 3 (pytest)")
            if (File(projectDir, "build.gradle.kts").exists() || File(projectDir, "build.gradle").exists()) add("Gradle / Kotlin")
            if (File(projectDir, "index.html").exists()) add("Static Web (HTML/CSS/JS)")
        }.ifEmpty { listOf("General workspace") }

        val content = """
            # ${projectDir.name} — YSCode Project Memory

            ## Detected Stack
            ${stackHints.joinToString(", ")}

            ## Structure
            $topFiles

            ## Guidelines for Agent
            - Read files before editing and keep changes minimal and tested.
            - Run tests or verification commands before reporting completion.
        """.trimIndent() + "\n"

        val outFile = File(projectDir, "YSCODE.md")
        outFile.writeText(content)
        outFile
    }

    suspend fun deleteProject(project: ProjectEntity, deleteFiles: Boolean = true) = withContext(Dispatchers.IO) {
        projectDao.deleteById(project.id)
        if (project.storageMode == ProjectEntity.STORAGE_LOCAL_PATH) {
            // In-place projects own the user's real folder: never delete it.
            project.localPath?.let { env.unregisterBind(it) }
            env.unregisterBind(File(project.path).absolutePath)
        } else if (deleteFiles) {
            File(project.path).deleteRecursively()
        }
    }

    private suspend fun registerProject(
        name: String,
        dir: File,
        storageMode: String = ProjectEntity.STORAGE_ROOTFS
    ): ProjectEntity {
        val id = "proj_" + UUID.randomUUID().toString().take(8)
        val canonical = dir.canonicalPath
        if (storageMode == ProjectEntity.STORAGE_LOCAL_PATH) env.registerProjectDir(File(canonical))
        val project = ProjectEntity(
            id = id,
            name = name,
            path = canonical,
            storageMode = storageMode,
            localPath = if (storageMode == ProjectEntity.STORAGE_LOCAL_PATH) canonical else null
        )
        projectDao.upsert(project)
        val defaultSession = SessionEntity(
            id = "sess_" + UUID.randomUUID().toString().take(8),
            projectId = id,
            title = "New Session"
        )
        sessionDao.upsert(defaultSession)
        return project
    }

    private fun copySafTree(treeUri: Uri, parentDocId: String, destDir: File) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                if (name == ".git" || name == "node_modules" || name == "build") continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val subDir = File(destDir, name).apply { mkdirs() }
                    copySafTree(treeUri, childId, subDir)
                } else {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    val outFile = File(destDir, name)
                    context.contentResolver.openInputStream(docUri)?.use { inp ->
                        FileOutputStream(outFile).use { out -> inp.copyTo(out) }
                    }
                }
            }
        }
    }

    private fun uniqueProjectDir(base: String): File {
        env.projectsDir.mkdirs()
        var candidate = File(env.projectsDir, base)
        var n = 2
        while (candidate.exists()) {
            candidate = File(env.projectsDir, "${base}_$n")
            n++
        }
        return candidate
    }

    private fun sanitizeDirName(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').ifBlank { "project" }

    private fun writeTemplateFiles(dir: File, title: String, template: ProjectTemplate) {
        when (template) {
            ProjectTemplate.EMPTY -> {
                File(dir, "README.md").writeText("# $title\n\nCreated locally in YSCode.\n")
            }
            ProjectTemplate.NODE -> {
                File(dir, "package.json").writeText(
                    """
                    {
                      "name": "${sanitizeDirName(title)}",
                      "version": "1.0.0",
                      "type": "module",
                      "scripts": {
                        "start": "node index.js",
                        "test": "node --test"
                      }
                    }
                    """.trimIndent() + "\n"
                )
                File(dir, "index.js").writeText(
                    "export function greet(name = 'YSCode') {\n  return `Hello, \${name}!`;\n}\n\nconsole.log(greet());\n"
                )
                File(dir, "index.test.js").writeText(
                    "import test from 'node:test';\nimport assert from 'node:assert/strict';\nimport { greet } from './index.js';\n\ntest('greet works', () => {\n  assert.equal(greet('Android'), 'Hello, Android!');\n});\n"
                )
            }
            ProjectTemplate.PYTHON -> {
                File(dir, "main.py").writeText(
                    "def greet(name: str = 'YSCode') -> str:\n    return f'Hello, {name}!'\n\nif __name__ == '__main__':\n    print(greet())\n"
                )
                File(dir, "test_main.py").writeText(
                    "import unittest\nfrom main import greet\n\nclass TestMain(unittest.TestCase):\n    def test_greet(self):\n        self.assertEqual(greet('Python'), 'Hello, Python!')\n\nif __name__ == '__main__':\n    unittest.main()\n"
                )
                File(dir, "requirements.txt").writeText("# Add Python dependencies here\n")
            }
            ProjectTemplate.KOTLIN -> {
                File(dir, "src/main/kotlin").mkdirs()
                File(dir, "src/main/kotlin/Main.kt").writeText(
                    "fun main() {\n    println(\"Hello from $title on YSCode!\")\n}\n"
                )
                File(dir, "README.md").writeText("# $title (Kotlin)\n")
            }
            ProjectTemplate.STATIC_WEB -> {
                File(dir, "index.html").writeText(
                    """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8" />
                      <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                      <title>$title</title>
                      <link rel="stylesheet" href="style.css" />
                    </head>
                    <body>
                      <main class="card">
                        <h1>$title</h1>
                        <p id="status">Built locally in YSCode.</p>
                        <button id="btn">Tap me</button>
                      </main>
                      <script src="app.js"></script>
                    </body>
                    </html>
                    """.trimIndent() + "\n"
                )
                File(dir, "style.css").writeText(
                    "body { font-family: system-ui, sans-serif; background: #0E0F11; color: #E8EAED; display: grid; place-items: center; min-height: 100vh; margin: 0; }\n.card { background: #16181B; border: 1px solid #2A2E33; border-radius: 16px; padding: 24px; max-width: 340px; text-align: center; }\nbutton { background: #D97757; color: #fff; border: none; border-radius: 10px; padding: 10px 18px; font-weight: 600; cursor: pointer; }\n"
                )
                File(dir, "app.js").writeText(
                    "let count = 0;\ndocument.getElementById('btn').addEventListener('click', () => {\n  count++;\n  document.getElementById('status').textContent = `Tapped \${count} time(s)`;\n});\n"
                )
            }
        }
    }
}
