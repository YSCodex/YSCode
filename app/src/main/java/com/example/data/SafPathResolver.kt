// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.data

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Maps a Storage Access Framework tree URI back to a real filesystem path when the document provider
 * exposes one (external storage, raw downloads). Lets YSCode work in place on `/sdcard/...` folders
 * instead of copying them into the rootfs whenever All files access is granted.
 */
object SafPathResolver {

    fun resolveTreeUri(uri: Uri): File? {
        if (uri.scheme != "content") return null
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        return resolve(uri.authority, docId)
    }

    fun resolve(authority: String?, docId: String): File? = when (authority) {
        "com.android.externalstorage.documents" -> resolveExternal(docId)
        "com.android.providers.downloads.documents" -> resolveRaw(docId)
        else -> null
    }

    private fun resolveExternal(docId: String): File? {
        val volume = docId.substringBefore(':', "").trim()
        val relative = docId.substringAfter(':', "").trim('/')
        if (volume.isEmpty()) return null
        val base = when (volume.lowercase()) {
            "primary", "self" -> Environment.getExternalStorageDirectory()
            "home", "downloads" -> return null
            else -> File("/storage", volume)
        }
        val target = if (relative.isEmpty()) base else File(base, relative)
        return target.takeIf { it.isDirectory && it.canRead() }
    }

    private fun resolveRaw(docId: String): File? {
        if (!docId.startsWith("raw:")) return null
        val path = docId.removePrefix("raw:")
        return File(path).takeIf { it.isDirectory && it.canRead() }
    }
}
