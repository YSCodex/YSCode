// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.util

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Pure-Kotlin POSIX/USTAR tar.gz extractor that preserves symlinks and executable permissions
 * while guarding against path traversal attacks.
 */
object TarExtractor {

    fun extractTarGz(
        input: InputStream,
        destDir: File,
        onProgress: (extractedFiles: Int, currentEntry: String) -> Unit = { _, _ -> }
    ): Int {
        if (!destDir.exists()) destDir.mkdirs()
        val destCanonical = destDir.canonicalPath
        var count = 0
        GZIPInputStream(BufferedInputStream(input, 65536)).use { gz ->
            val header = ByteArray(512)
            val copyBuf = ByteArray(32768)
            var paxLongName: String? = null
            var paxLongLink: String? = null

            while (true) {
                val read = readFully(gz, header)
                if (read < 512 || header.all { it == 0.toByte() }) break

                var name = readAscii(header, 0, 100)
                val prefix = readAscii(header, 345, 155)
                if (prefix.isNotEmpty()) {
                    name = "$prefix/$name"
                }
                val mode = readOctal(header, 100, 8)
                val size = readOctal(header, 124, 12)
                val typeFlag = header[156].toInt().toChar()
                var linkName = readAscii(header, 157, 100)

                if (typeFlag == 'L' || typeFlag == 'K') {
                    val data = readExactBytes(gz, size)
                    skipPadding(gz, size)
                    val longVal = String(data, Charsets.UTF_8).trimEnd('\u0000', '\n')
                    if (typeFlag == 'L') paxLongName = longVal else paxLongLink = longVal
                    continue
                }
                if (typeFlag == 'x' || typeFlag == 'g') {
                    val data = readExactBytes(gz, size)
                    skipPadding(gz, size)
                    parsePaxHeaders(String(data, Charsets.UTF_8)).let { pax ->
                        pax["path"]?.let { paxLongName = it }
                        pax["linkpath"]?.let { paxLongLink = it }
                    }
                    continue
                }

                if (paxLongName != null) {
                    name = paxLongName
                    paxLongName = null
                }
                if (paxLongLink != null) {
                    linkName = paxLongLink
                    paxLongLink = null
                }

                val cleanName = name.removePrefix("./").trimStart('/')
                if (cleanName.isEmpty()) {
                    skipBytes(gz, size)
                    skipPadding(gz, size)
                    continue
                }

                val target = File(destDir, cleanName)
                if (!target.canonicalPath.startsWith(destCanonical)) {
                    throw SecurityException("Blocked path traversal entry: $name")
                }

                when (typeFlag) {
                    '5' -> {
                        target.mkdirs()
                    }
                    '2' -> {
                        target.parentFile?.mkdirs()
                        if (target.exists()) target.delete()
                        runCatching { Os.symlink(linkName, target.absolutePath) }
                    }
                    '1' -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = File(destDir, linkName.removePrefix("./").trimStart('/'))
                        if (linkTarget.exists()) {
                            runCatching {
                                if (target.exists()) target.delete()
                                Os.link(linkTarget.absolutePath, target.absolutePath)
                            }.onFailure {
                                linkTarget.copyTo(target, overwrite = true)
                            }
                        }
                    }
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var remaining = size
                            while (remaining > 0) {
                                val toRead = minOf(remaining, copyBuf.size.toLong()).toInt()
                                val n = gz.read(copyBuf, 0, toRead)
                                if (n <= 0) break
                                out.write(copyBuf, 0, n)
                                remaining -= n
                            }
                        }
                        if ((mode and 0b001001001L) != 0L) {
                            target.setExecutable(true, false)
                        }
                    }
                }
                skipPadding(gz, size)
                count++
                if (count % 50 == 0) {
                    onProgress(count, cleanName)
                }
            }
        }
        onProgress(count, "done")
        return count
    }

    private fun parsePaxHeaders(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val spaceIdx = line.indexOf(' ')
            if (spaceIdx <= 0) continue
            val kv = line.substring(spaceIdx + 1)
            val eqIdx = kv.indexOf('=')
            if (eqIdx > 0) {
                map[kv.substring(0, eqIdx)] = kv.substring(eqIdx + 1)
            }
        }
        return map
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun readExactBytes(input: InputStream, size: Long): ByteArray {
        val bytes = ByteArray(size.toInt())
        readFully(input, bytes)
        return bytes
    }

    private fun skipBytes(input: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val rem = (512 - (size % 512)) % 512
        if (rem > 0) skipBytes(input, rem)
    }

    private fun readAscii(buf: ByteArray, offset: Int, len: Int): String {
        val end = (offset until offset + len).firstOrNull { buf[it] == 0.toByte() } ?: (offset + len)
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readOctal(buf: ByteArray, offset: Int, len: Int): Long {
        val raw = readAscii(buf, offset, len).trim()
        if (raw.isEmpty()) return 0L
        return raw.toLongOrNull(8) ?: 0L
    }
}
