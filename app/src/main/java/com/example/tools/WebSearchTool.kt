// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.tools

import com.example.data.SettingsStore
import com.example.llm.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

class WebSearchTool(
    private val settingsStore: SettingsStore
) : Tool {
    override val name = "web_search"
    override val description = "Search the live web using built-in DuckDuckGo search (zero API key required). Returns titles, URLs, and snippets."
    override val risk = Risk.NETWORK
    override val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") { put("type", "string"); put("description", "Search query") }
            putJsonObject("max_results") { put("type", "integer"); put("description", "Max results to return (1-10, default 5)") }
        }
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
    }

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val settings = settingsStore.current()
        if (!settings.allowWebFetch) {
            return@withContext ToolResult.denied("web_search is disabled in Settings -> Network Policy")
        }
        val query = args.str("query")?.trim() ?: return@withContext ToolResult.error("Missing 'query'")
        if (query.isEmpty()) return@withContext ToolResult.error("Empty search query")
        val maxResults = (args.int("max_results") ?: 5).coerceIn(1, 10)

        runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            var results = fetchDuckDuckGoHtml("https://html.duckduckgo.com/html/?q=$encoded", maxResults)
            if (results.isEmpty()) {
                results = fetchDuckDuckGoLite("https://lite.duckduckgo.com/lite/?q=$encoded", maxResults)
            }
            if (results.isEmpty()) {
                results = fetchDuckDuckGoInstant("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1", maxResults)
            }

            if (results.isEmpty()) {
                return@withContext ToolResult.ok(
                    output = "[UNTRUSTED WEB SEARCH RESULTS for '$query']\nNo organic results parsed. Try refining your query or fetching a known documentation URL with web_fetch.",
                    summary = "0 results"
                )
            }
            val formatted = buildString {
                appendLine("[UNTRUSTED WEB SEARCH RESULTS for '$query']")
                results.forEachIndexed { idx, r ->
                    appendLine("${idx + 1}. ${r.title}")
                    appendLine("   URL: ${r.url}")
                    if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
                }
            }.trimEnd()
            ToolResult.ok(output = formatted, summary = "${results.size} results")
        }.getOrElse {
            ToolResult.error("web_search failed: ${it.message}")
        }
    }

    private fun fetchDuckDuckGoHtml(url: String, maxResults: Int): List<WebSearchResult> = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        HttpClients.streamingClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string().orEmpty().take(250_000)
            parseDuckDuckGoHtml(html, maxResults)
        }
    }.getOrDefault(emptyList())

    private fun fetchDuckDuckGoLite(url: String, maxResults: Int): List<WebSearchResult> = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        HttpClients.streamingClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string().orEmpty().take(250_000)
            parseDuckDuckGoLiteHtml(html, maxResults)
        }
    }.getOrDefault(emptyList())

    private fun fetchDuckDuckGoInstant(url: String, maxResults: Int): List<WebSearchResult> = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "YSCode/1.0")
            .header("Accept", "application/json")
            .get()
            .build()
        HttpClients.streamingClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val raw = resp.body?.string().orEmpty()
            val json = org.json.JSONObject(raw)
            val out = mutableListOf<WebSearchResult>()
            val abstractText = json.optString("AbstractText").trim()
            val abstractUrl = json.optString("AbstractURL").trim()
            val heading = json.optString("Heading").trim()
            if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
                out += WebSearchResult(heading.ifBlank { "Summary" }, abstractUrl, abstractText)
            }
            val topics = json.optJSONArray("RelatedTopics")
            if (topics != null) {
                for (i in 0 until topics.length()) {
                    if (out.size >= maxResults) break
                    val obj = topics.optJSONObject(i) ?: continue
                    val text = obj.optString("Text").trim()
                    val firstUrl = obj.optString("FirstURL").trim()
                    if (text.isNotBlank() && firstUrl.isNotBlank()) {
                        out += WebSearchResult(text.take(70), firstUrl, text)
                    }
                }
            }
            out
        }
    }.getOrDefault(emptyList())

    companion object {
        fun parseDuckDuckGoHtml(html: String, maxResults: Int = 5): List<WebSearchResult> {
            val results = mutableListOf<WebSearchResult>()
            val anchorRegex = Regex(
                """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            val snippetRegex = Regex(
                """<(?:a|div|span)[^>]*class="[^"]*result__snippet[^"]*"[^>]*>([\s\S]*?)</(?:a|div|span)>""",
                RegexOption.IGNORE_CASE
            )

            val anchors = anchorRegex.findAll(html).toList()
            val snippets = snippetRegex.findAll(html).toList()

            for (i in anchors.indices) {
                if (results.size >= maxResults) break
                val match = anchors[i]
                val rawHref = decodeHtmlEntities(match.groupValues[1])
                val resolvedUrl = unwrapDuckDuckGoUrl(rawHref)
                val title = stripTags(decodeHtmlEntities(match.groupValues[2])).trim()
                if (title.isBlank() || resolvedUrl.isBlank()) continue

                // Look for snippet closest after this anchor before next anchor
                val startPos = match.range.last
                val endPos = anchors.getOrNull(i + 1)?.range?.first ?: html.length
                val snippetText = snippets.firstOrNull { it.range.first in startPos until endPos }
                    ?.let { stripTags(decodeHtmlEntities(it.groupValues[1])).trim() }
                    .orEmpty()

                results += WebSearchResult(title = title, url = resolvedUrl, snippet = snippetText)
            }
            return results
        }

        fun parseDuckDuckGoLiteHtml(html: String, maxResults: Int = 5): List<WebSearchResult> {
            val results = mutableListOf<WebSearchResult>()
            val linkRegex = Regex(
                """<a[^>]*class=['"][^'"]*result-link[^'"]*['"][^>]*href=['"]([^'"]+)['"][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            )
            val snippetRegex = Regex(
                """<td[^>]*class=['"][^'"]*result-snippet[^'"]*['"][^>]*>([\s\S]*?)</td>""",
                RegexOption.IGNORE_CASE
            )
            val anchors = linkRegex.findAll(html).toList()
            val snippets = snippetRegex.findAll(html).toList()
            for (i in anchors.indices) {
                if (results.size >= maxResults) break
                val match = anchors[i]
                val resolvedUrl = unwrapDuckDuckGoUrl(decodeHtmlEntities(match.groupValues[1]))
                val title = stripTags(decodeHtmlEntities(match.groupValues[2])).trim()
                if (title.isBlank() || resolvedUrl.isBlank()) continue
                val startPos = match.range.last
                val endPos = anchors.getOrNull(i + 1)?.range?.first ?: html.length
                val snippetText = snippets.firstOrNull { it.range.first in startPos until endPos }
                    ?.let { stripTags(decodeHtmlEntities(it.groupValues[1])).trim() }
                    .orEmpty()
                results += WebSearchResult(title = title, url = resolvedUrl, snippet = snippetText)
            }
            return results
        }

        fun unwrapDuckDuckGoUrl(href: String): String {
            val full = when {
                href.startsWith("//") -> "https:$href"
                href.startsWith("/") -> "https://duckduckgo.com$href"
                else -> href
            }
            val uddgMatch = Regex("""[?&]uddg=([^&]+)""").find(full)
            return if (uddgMatch != null) {
                runCatching { URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8") }.getOrDefault(full)
            } else full
        }

        private fun stripTags(input: String): String =
            input.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ")

        private fun decodeHtmlEntities(text: String): String = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
    }
}
