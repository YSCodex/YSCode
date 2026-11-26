// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object JsonHelper {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Attempts to parse raw tool arguments JSON; if malformed (common on smaller/local models),
     * tries progressive repair (strip markdown fences, fix trailing commas, balance braces/brackets).
     */
    fun parseOrRepairObject(raw: String): Result<JsonObject> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.success(JsonObject(emptyMap()))
        runCatching {
            return Result.success(json.parseToJsonElement(trimmed).jsonObject)
        }
        val repaired = repairJsonString(trimmed)
        return runCatching {
            json.parseToJsonElement(repaired).jsonObject
        }
    }

    fun parseOrNull(raw: String): JsonElement? = runCatching {
        json.parseToJsonElement(raw)
    }.getOrNull()

    fun repairJsonString(input: String): String {
        var s = input.trim()
        // Strip markdown ```json ... ``` fences
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            if (s.endsWith("```")) {
                s = s.removeSuffix("```").trim()
            }
        }
        // Extract first {...} block if extra prose surrounds it
        val firstBrace = s.indexOf('{')
        if (firstBrace > 0) {
            s = s.substring(firstBrace)
        }
        // Remove trailing commas before } or ]
        s = s.replace(Regex(",\\s*([}\\]])"), "$1")
        // Balance unclosed quotes if odd number of unescaped quotes
        var inString = false
        var escaped = false
        val stack = ArrayDeque<Char>()
        for (ch in s) {
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inString) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                when (ch) {
                    '{', '[' -> stack.addLast(ch)
                    '}' -> if (stack.lastOrNull() == '{') stack.removeLast()
                    ']' -> if (stack.lastOrNull() == '[') stack.removeLast()
                }
            }
        }
        val sb = StringBuilder(s)
        if (inString) sb.append('"')
        while (stack.isNotEmpty()) {
            when (stack.removeLast()) {
                '{' -> sb.append('}')
                '[' -> sb.append(']')
            }
        }
        // Clean trailing commas again after closing braces
        return sb.toString().replace(Regex(",\\s*([}\\]])"), "$1")
    }
}
