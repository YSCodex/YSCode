// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.preview

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Tiny static file server bound to 127.0.0.1, so "run it on localhost" works for plain HTML/CSS/JS apps even when no
 * dev server is running in Linux. It lives in the app process and stops with the app.
 */
object PreviewServer {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "yscode-preview").also { it.isDaemon = true } }
    private val servers = java.util.concurrent.ConcurrentHashMap<Int, ServerSocket>()
    private val roots = java.util.concurrent.ConcurrentHashMap<Int, File>()

    /** The folder that holds the site: the project itself, or one sub-folder with an index.html. */
    fun findWebRoot(dir: File): File? {
        if (File(dir, "index.html").isFile) return dir
        return dir.listFiles { f -> f.isDirectory && f.name !in setOf("node_modules", ".git", "build", "dist", ".gradle") }
            ?.sortedBy { it.name }?.firstOrNull { File(it, "index.html").isFile }
    }

    /** Serves the site of [dir] on [preferredPort] (falls back to a free port). Returns the port in use. */
    @Synchronized
    fun serve(dir: File, preferredPort: Int): Int {
        val web = findWebRoot(dir) ?: dir
        servers[preferredPort]?.takeIf { !it.isClosed }?.let { roots[preferredPort] = web; return preferredPort }
        val ss = runCatching { ServerSocket(preferredPort, 20, InetAddress.getByName("127.0.0.1")) }
            .getOrElse { ServerSocket(0, 20, InetAddress.getByName("127.0.0.1")) }
        val port = ss.localPort
        servers[port] = ss
        roots[port] = web
        pool.execute {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(c) } }
            }
        }
        return port
    }

    @Synchronized
    fun stopAll() {
        servers.values.forEach { runCatching { it.close() } }
        servers.clear(); roots.clear()
    }

    fun isReachable(port: Int, timeoutMs: Int = 500): Boolean = runCatching {
        Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs); true }
    }.getOrDefault(false)

    private fun handle(s: Socket) {
        s.use { sock ->
            sock.soTimeout = 8000
            val reader = sock.getInputStream().bufferedReader()
            val request = reader.readLine() ?: return
            while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
            val parts = request.split(" ")
            val method = parts.getOrNull(0) ?: return
            var path = java.net.URLDecoder.decode((parts.getOrNull(1) ?: "/").substringBefore('?').substringBefore('#'), "UTF-8")
            val base = roots[sock.localPort]?.canonicalFile ?: return
            if (path.endsWith("/")) path += "index.html"
            var file = File(base, path.trimStart('/')).canonicalFile
            if (file.isDirectory) file = File(file, "index.html")
            val out = sock.getOutputStream()
            if (method != "GET" && method != "HEAD") { respond(out, 405, "text/plain", "Method not allowed".toByteArray(), false); return }
            if (!file.path.startsWith(base.path) || !file.isFile) {
                respond(out, 404, "text/html; charset=utf-8", "<h3>404</h3><p>${path.replace("<", "&lt;")} was not found in the project folder.</p>".toByteArray(), method == "HEAD")
                return
            }
            respond(out, 200, mime(file.name), file.readBytes(), method == "HEAD")
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, type: String, body: ByteArray, headOnly: Boolean) {
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        val crlf = "\r\n"
        out.write(("HTTP/1.1 $code $status" + crlf + "Content-Type: $type" + crlf + "Content-Length: ${body.size}" + crlf + "Cache-Control: no-store" + crlf + "Connection: close" + crlf + crlf).toByteArray())
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "txt", "md" -> "text/plain; charset=utf-8"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }
}
