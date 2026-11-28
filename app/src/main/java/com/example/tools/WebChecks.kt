// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Plain-HTTP checks the browser tool offers: safe download verification and a site audit (broken links + basic SEO). */
object WebChecks {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; YSCode) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_DOWNLOAD_BYTES = 80L * 1024 * 1024

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ download check

    /** Fetches the file, hashes it and reports headers. Nothing is saved to disk and nothing is installed. */
    suspend fun downloadCheck(url: String, expectedSha: String?): String = withContext(Dispatchers.IO) {
        val u = url.toHttpUrlOrNull() ?: return@withContext "Not a valid http(s) URL: $url"
        try {
            val req = Request.Builder().url(u).header("User-Agent", UA).get().build()
            client.newCall(req).execute().use { describeDownload(it, expectedSha) }
        } catch (e: Exception) {
            "FAILED to fetch $url: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun describeDownload(resp: Response, expectedSha: String?): String {
        val hops = generateSequence(resp.priorResponse) { it.priorResponse }.count()
        val len = resp.header("Content-Length")?.toLongOrNull()
        val type = resp.header("Content-Type").orEmpty()
        val sb = StringBuilder()
        sb.append("Final URL: ").append(resp.request.url).append('\n')
        sb.append("Status: ").append(resp.code).append('\n')
        sb.append("Redirects followed: ").append(hops).append('\n')
        sb.append("Content-Type: ").append(type.ifBlank { "(none)" }).append('\n')
        sb.append("Content-Length: ").append(len?.toString() ?: "unknown").append('\n')
        resp.header("Content-Disposition")?.let { sb.append("Content-Disposition: ").append(it).append('\n') }
        resp.header("Last-Modified")?.let { sb.append("Last-Modified: ").append(it).append('\n') }
        if (!resp.isSuccessful) {
            return sb.append("RESULT: FAILED. The server did not return the file (HTTP ${resp.code}).").toString()
        }
        if (type.contains("text/html", ignoreCase = true)) {
            sb.append("WARNING: the link returns an HTML page, not a file download.\n")
        }
        if (len != null && len > MAX_DOWNLOAD_BYTES) {
            return sb.append("The file is larger than 80 MB, so only the headers were checked.").toString()
        }
        val body = resp.body ?: return sb.append("RESULT: no response body.").toString()
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var head = ByteArray(0)
        var cut = false
        body.byteStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (total == 0L) head = buf.copyOf(minOf(n, 4))
                md.update(buf, 0, n)
                total += n
                if (total > MAX_DOWNLOAD_BYTES) { cut = true; break }
            }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        val isZip = head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        sb.append("Downloaded and hashed: ").append(total).append(" bytes")
        if (cut) sb.append(" (stopped at the 80 MB limit)")
        sb.append('\n')
        sb.append("SHA-256: ").append(sha).append('\n')
        sb.append("File signature: ").append(if (isZip) "ZIP (APK and JAR files are ZIP files)" else "not a ZIP file").append('\n')
        val looksApk = resp.request.url.encodedPath.endsWith(".apk", ignoreCase = true)
        if (looksApk && !isZip) sb.append("WARNING: the URL ends in .apk but the content is not a ZIP/APK.\n")
        if (len != null && len != total && !cut) sb.append("WARNING: Content-Length ($len) differs from the bytes received ($total).\n")
        if (!expectedSha.isNullOrBlank()) {
            val ok = expectedSha.trim().lowercase() == sha
            sb.append("Expected SHA-256 ").append(if (ok) "MATCHES." else "does NOT match (expected ${expectedSha.trim().lowercase()}).").append('\n')
        }
        sb.append("Nothing was saved to the phone and nothing was installed.")
        return sb.toString()
    }

    // ------------------------------------------------------------------ site audit

    private class PageInfo(
        val url: String, val status: Int, val title: String?, val description: String?, val h1: Int,
        val canonical: Boolean, val imagesWithoutAlt: Int, val hasLang: Boolean, val links: List<String>, val note: String?
    )

    private class LinkResult(val url: String, val status: Int, val finalUrl: String?, val error: String?)

    private val skipExt = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "css", "js", "map", "pdf", "zip", "apk", "gz", "mp4", "mp3", "woff", "woff2", "ttf", "xml", "txt", "json"
    )

    private fun sameSite(a: String, b: String): Boolean = a.removePrefix("www.").equals(b.removePrefix("www."), ignoreCase = true)

    private fun fetchPage(url: HttpUrl): PageInfo {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
            client.newCall(req).execute().use { resp ->
                val type = resp.header("Content-Type").orEmpty()
                if (!resp.isSuccessful) return PageInfo(url.toString(), resp.code, null, null, 0, false, 0, false, emptyList(), null)
                if (!type.contains("html", ignoreCase = true)) return PageInfo(url.toString(), resp.code, null, null, 0, false, 0, false, emptyList(), "not HTML ($type)")
                val html = resp.body?.source()?.let { src -> src.request(1_500_000); src.buffer.clone().readUtf8() }.orEmpty()
                parse(resp.request.url, resp.code, html)
            }
        } catch (e: Exception) {
            PageInfo(url.toString(), 0, null, null, 0, false, 0, false, emptyList(), "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun parse(base: HttpUrl, status: Int, html: String): PageInfo {
        val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        val title = Regex("<title[^>]*>(.*?)</title>", opts).find(html)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
        var desc: String? = null
        for (m in Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val tag = m.value
            if (Regex("name\\s*=\\s*[\"']description[\"']", RegexOption.IGNORE_CASE).containsMatchIn(tag)) {
                desc = Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.trim()
                break
            }
        }
        val h1 = Regex("<h1\\b", RegexOption.IGNORE_CASE).findAll(html).count()
        val canonical = Regex("<link[^>]+rel\\s*=\\s*[\"']canonical[\"']", RegexOption.IGNORE_CASE).containsMatchIn(html)
        val noAlt = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).count { !Regex("\\balt\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(it.value) }
        val lang = Regex("<html[^>]*\\blang\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html)
        val links = LinkedHashSet<String>()
        for (m in Regex("<a\\b[^>]*?\\bhref\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html)) {
            val href = m.groupValues[1].trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("mailto:") || href.startsWith("tel:") || href.startsWith("javascript:")) continue
            val resolved = base.resolve(href) ?: continue
            links.add(resolved.newBuilder().fragment(null).build().toString())
        }
        return PageInfo(base.toString(), status, title, desc, h1, canonical, noAlt, lang, links.toList(), null)
    }

    private fun checkLink(url: String): LinkResult {
        val u = url.toHttpUrlOrNull() ?: return LinkResult(url, 0, null, "invalid URL")
        fun run(head: Boolean): LinkResult {
            val b = Request.Builder().url(u).header("User-Agent", UA)
            val req = if (head) b.head().build() else b.get().build()
            return client.newCall(req).execute().use { r ->
                LinkResult(url, r.code, r.request.url.toString().takeIf { it != url }, null)
            }
        }
        return try {
            val first = run(head = true)
            if (first.status in setOf(403, 405, 429, 501)) run(head = false) else first
        } catch (e: Exception) {
            try { run(head = false) } catch (e2: Exception) { LinkResult(url, 0, null, "${e2.javaClass.simpleName}: ${e2.message}") }
        }
    }

    /** Crawls the site (static HTML, same host) and checks every link. */
    suspend fun audit(start: String, maxPages: Int, depth: Int, checkExternal: Boolean): String = withContext(Dispatchers.IO) {
        val startUrl = start.toHttpUrlOrNull() ?: return@withContext "Not a valid http(s) URL: $start"
        withTimeoutOrNull(100_000L) { runAudit(startUrl, maxPages.coerceIn(1, 60), depth.coerceIn(0, 4), checkExternal) }
            ?: "Audit timed out after 100 seconds. Try a smaller max_pages or depth."
    }

    private suspend fun runAudit(startUrl: HttpUrl, maxPages: Int, maxDepth: Int, checkExternal: Boolean): String {
        val host = startUrl.host
        val pages = LinkedHashMap<String, PageInfo>()
        val sources = HashMap<String, MutableSet<String>>()
        val queue = ArrayDeque<Pair<HttpUrl, Int>>()
        val seen = HashSet<String>()
        val first = startUrl.newBuilder().fragment(null).build()
        queue.addLast(first to 0)
        seen.add(first.toString())
        while (queue.isNotEmpty() && pages.size < maxPages) {
            val (url, d) = queue.removeFirst()
            val info = fetchPage(url)
            pages[url.toString()] = info
            for (l in info.links) {
                sources.getOrPut(l) { LinkedHashSet() }.add(url.toString())
                val lu = l.toHttpUrlOrNull() ?: continue
                val ext = lu.encodedPath.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
                if (d < maxDepth && sameSite(lu.host, host) && ext !in skipExt && seen.add(l)) queue.addLast(lu to d + 1)
            }
        }
        // Links to check: everything found that was not crawled as a page.
        val toCheck = sources.keys.filter { it !in pages }.filter { l ->
            val h = l.toHttpUrlOrNull()?.host ?: return@filter false
            sameSite(h, host) || checkExternal
        }.take(120)
        val sem = Semaphore(8)
        val results = coroutineScope {
            toCheck.map { l -> async(Dispatchers.IO) { sem.withPermit { checkLink(l) } } }.awaitAll()
        }
        val broken = ArrayList<String>()
        val redirects = ArrayList<String>()
        fun from(l: String) = sources[l]?.firstOrNull()?.removePrefix(startUrl.scheme + "://") ?: "?"
        for (p in pages.values) if (p.status !in 200..299) broken.add("${p.status.takeIf { it != 0 } ?: "ERR"} ${p.url}  (found on ${from(p.url)})${p.note?.let { " - $it" } ?: ""}")
        for (r in results) {
            when {
                r.error != null || r.status >= 400 || r.status == 0 -> broken.add("${if (r.status == 0) "ERR" else r.status} ${r.url}  (found on ${from(r.url)})${r.error?.let { " - $it" } ?: ""}")
                r.finalUrl != null -> redirects.add("${r.url} -> ${r.finalUrl}")
            }
        }
        val seo = ArrayList<String>()
        for (p in pages.values) {
            if (p.status !in 200..299 || p.note != null) continue
            val path = p.url.removePrefix(startUrl.scheme + "://" + startUrl.host)
            val issues = ArrayList<String>()
            if (p.title.isNullOrBlank()) issues.add("no <title>") else if (p.title.length > 65) issues.add("title is ${p.title.length} chars (aim for 60 or less)")
            if (p.description.isNullOrBlank()) issues.add("no meta description") else if (p.description.length > 165) issues.add("meta description is ${p.description.length} chars (aim for 160 or less)")
            if (p.h1 == 0) issues.add("no <h1>") else if (p.h1 > 1) issues.add("${p.h1} <h1> tags")
            if (!p.canonical) issues.add("no canonical link")
            if (!p.hasLang) issues.add("<html> has no lang")
            if (p.imagesWithoutAlt > 0) issues.add("${p.imagesWithoutAlt} image(s) without alt")
            if (issues.isNotEmpty()) seo.add("${path.ifEmpty { "/" }}: ${issues.joinToString("; ")}")
        }
        val out = StringBuilder()
        out.append("Audit of ${startUrl.scheme}://$host (static HTML; links added by JavaScript are not seen)\n")
        out.append("Pages crawled: ${pages.size}, other links checked: ${results.size}${if (toCheck.size == 120) " (limit 120)" else ""}\n")
        out.append("Broken: ${broken.size}, redirects: ${redirects.size}, pages with SEO notes: ${seo.size}\n")
        out.append("\nBROKEN LINKS / PAGES\n")
        if (broken.isEmpty()) out.append("none\n") else broken.take(40).forEach { out.append("- ").append(it).append('\n') }
        out.append("\nREDIRECTS\n")
        if (redirects.isEmpty()) out.append("none\n") else redirects.take(25).forEach { out.append("- ").append(it).append('\n') }
        out.append("\nSEO NOTES\n")
        if (seo.isEmpty()) out.append("none\n") else seo.take(40).forEach { out.append("- ").append(it).append('\n') }
        out.append("\nPAGES\n")
        pages.values.take(60).forEach { p -> out.append("- ").append(p.status).append(' ').append(p.url.removePrefix(startUrl.scheme + "://")).append(" | ").append(p.title ?: "(no title)").append('\n') }
        return out.toString().take(9_000)
    }
}
