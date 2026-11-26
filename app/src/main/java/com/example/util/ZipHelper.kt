// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.util

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipHelper {

    fun unzip(
        input: InputStream,
        destDir: File,
        maxBytes: Long = 50L * 1024L * 1024L,
        stripSingleTopDir: Boolean = false
    ): List<String> {
        if (!destDir.exists()) destDir.mkdirs()
        val destCanonical = destDir.canonicalPath
        val extracted = mutableListOf<String>()
        var totalBytes = 0L
        val entries = mutableListOf<Pair<String, ByteArray?>>()

        ZipInputStream(BufferedInputStream(input)).use { zis ->
            val buf = ByteArray(16384)
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.trimStart('/')
                if (name.isEmpty()) continue
                if (entry.isDirectory) {
                    entries += name to null
                } else {
                    val baos = java.io.ByteArrayOutputStream()
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        totalBytes += n
                        if (totalBytes > maxBytes) {
                            throw IllegalStateException("ZIP archive exceeds maximum allowed size (${maxBytes / (1024 * 1024)} MB)")
                        }
                        baos.write(buf, 0, n)
                    }
                    entries += name to baos.toByteArray()
                }
                zis.closeEntry()
            }
        }

        val commonPrefix = if (stripSingleTopDir && entries.isNotEmpty()) {
            val firstSegs = entries.map { it.first.substringBefore('/') }.distinct()
            if (firstSegs.size == 1 && entries.any { it.first.contains('/') }) "${firstSegs.first()}/" else ""
        } else ""

        for ((rawName, bytes) in entries) {
            val relName = if (commonPrefix.isNotEmpty()) rawName.removePrefix(commonPrefix) else rawName
            if (relName.isEmpty()) continue
            val outFile = File(destDir, relName)
            if (!outFile.canonicalPath.startsWith(destCanonical)) {
                throw SecurityException("Blocked zip-slip entry: $rawName")
            }
            if (bytes == null) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { it.write(bytes) }
                extracted += relName
            }
        }
        return extracted
    }

    fun zipDirectory(sourceDir: File, output: OutputStream, excludeDirs: Set<String> = setOf(".git", "node_modules")) {
        ZipOutputStream(BufferedOutputStream(output)).use { zos ->
            val basePath = sourceDir.canonicalPath
            sourceDir.walkTopDown()
                .onEnter { dir -> dir == sourceDir || dir.name !in excludeDirs }
                .filter { it.isFile }
                .forEach { file ->
                    val rel = file.canonicalPath.removePrefix(basePath).trimStart(File.separatorChar)
                    if (rel.isNotEmpty()) {
                        zos.putNextEntry(ZipEntry(rel.replace(File.separatorChar, '/')))
                        FileInputStream(file).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
        }
    }
}
