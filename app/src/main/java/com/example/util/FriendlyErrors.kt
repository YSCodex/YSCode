// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.util

/** Turns raw provider/network errors into a short, actionable sentence. Raw text is kept after a blank line. */
object FriendlyErrors {
    fun explain(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "The request failed for an unknown reason. Tap Retry."
        val l = text.lowercase()
        val friendly = when {
            "401" in l || "invalid api key" in l || "incorrect api key" in l || "unauthorized" in l || "authentication" in l ->
                "The provider rejected your API key. Check or re-enter it in Settings > Providers."
            "402" in l || "insufficient" in l || "balance" in l || "credit" in l || "quota" in l || "billing" in l ->
                "The provider says you are out of credits or quota. Top up, or pick another model/provider."
            "403" in l || "forbidden" in l || "permission" in l || "not allowed" in l ->
                "The provider refused this request (403). Your key may not have access to this model."
            "429" in l || "rate limit" in l || "too many requests" in l ->
                "Rate limit reached. Wait a minute, or switch to another model/provider."
            "context length" in l || "context_length" in l || "maximum context" in l || "too many tokens" in l || "prompt is too long" in l ->
                "This conversation is too long for the model. Run /compact or start a new session."
            ("404" in l || "not found" in l || "does not exist" in l) && "model" in l ->
                "That model was not found at this provider. Pick another model from the model menu."
            "timeout" in l || "timed out" in l ->
                "The provider took too long to answer. Check your connection and tap Retry."
            "unable to resolve host" in l || "failed to connect" in l || "network is unreachable" in l ||
                "unexpected end of stream" in l || "connection reset" in l || "connection abort" in l || "software caused" in l ->
                "Cannot reach the provider. Check your internet and tap Retry."
            "500" in l || "502" in l || "503" in l || "504" in l || "overloaded" in l || "unavailable" in l ->
                "The provider is overloaded or down right now. Retry in a moment or switch model."
            else -> null
        }
        return if (friendly == null) text else "$friendly\n\nDetails: $text"
    }
}
