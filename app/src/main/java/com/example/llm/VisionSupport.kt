// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

/** Decides whether a model can receive images. Conservative: unknown names are treated as text-only. */
object VisionSupport {
    private val textOnlyHints = Regex("(embedding|whisper|tts|rerank|moderation|dall-e|gpt-image|image-1)")
    private val positive = listOf(
        "gpt-4o", "gpt-4\\.1", "gpt-4-turbo", "gpt-4-vision", "gpt-5", "chatgpt-4o",
        "vision", "(^|[-_.])vl($|[-_.])", "-vl", "pixtral", "llava", "minicpm-v", "internvl", "omni",
        "gemini", "claude", "llama-?4", "llama-3\\.2-(11|90)b", "gemma-?3", "phi-(3\\.5|4)-(vision|multimodal)",
        "grok-(4|2-vision|vision)", "qwen.*vl", "nova-(lite|pro|premier)", "mistral-(medium|small)-(3|2506)"
    ).map { Regex(it) }

    private fun shortName(id: String) = id.lowercase().substringAfterLast('/')

    /** True only for families known to be text-only even though their names can look like vision models (e.g. deepseek-v4.1). */
    fun isKnownTextOnly(modelId: String): Boolean {
        val n = shortName(modelId)
        if (textOnlyHints.containsMatchIn(n)) return true
        return n.contains("deepseek") && !(n.contains("vl") || n.contains("janus") || n.contains("ocr"))
    }

    fun inferVision(modelId: String): Boolean {
        val n = shortName(modelId)
        if (isKnownTextOnly(n)) return false
        val o = Regex("^o([134])(-.*)?$").find(n)
        if (o != null) return !o.groupValues[2].contains("mini") || o.groupValues[1] == "4"
        return positive.any { it.containsMatchIn(n) }
    }

    /** Stored flag, corrected for known text-only families (fixes models fetched with the old name heuristic). */
    fun effective(modelId: String, storedFlag: Boolean): Boolean = storedFlag && !isKnownTextOnly(modelId)
}
