// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class ProviderType(val label: String) {
    OPENAI_COMPAT("OpenAI-Compatible"),
    ANTHROPIC("Anthropic Messages"),
    GEMINI("Google Gemini"),
    CODEX("ChatGPT Plan (Codex)"),
    AGY("Antigravity (agy CLI)")
}

@Serializable
data class ModelConfig(
    val id: String,
    val displayName: String = id,
    val contextWindow: Int = 128_000,
    val supportsTools: Boolean = true,
    val supportsVision: Boolean = true,
    val supportsReasoning: Boolean = false,
    val inputPricePer1M: Double = 0.0,
    val outputPricePer1M: Double = 0.0
)

data class ProviderPreset(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val requiresApiKey: Boolean,
    val defaultModels: List<ModelConfig>,
    val hint: String
)

object ProviderPresets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "openai",
            name = "OpenAI",
            type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.openai.com/v1",
            requiresApiKey = true,
            defaultModels = listOf(
                ModelConfig("gpt-4.1", "GPT-4.1", 1_000_000, true, true, false, 2.0, 8.0),
                ModelConfig("gpt-4o", "GPT-4o", 128_000, true, true, false, 2.5, 10.0),
                ModelConfig("o4-mini", "o4-mini", 200_000, true, true, true, 1.1, 4.4)
            ),
            hint = "sk-..."
        ),
        ProviderPreset(
            id = "anthropic",
            name = "Anthropic",
            type = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            requiresApiKey = true,
            defaultModels = listOf(
                ModelConfig("claude-sonnet-4-20250514", "Claude Sonnet 4", 200_000, true, true, true, 3.0, 15.0),
                ModelConfig("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet", 200_000, true, true, true, 3.0, 15.0),
                ModelConfig("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", 200_000, true, true, false, 0.8, 4.0)
            ),
            hint = "sk-ant-..."
        ),
        ProviderPreset(
            id = "gemini",
            name = "Google Gemini",
            type = ProviderType.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com",
            requiresApiKey = true,
            defaultModels = listOf(
                ModelConfig("gemini-2.5-pro", "Gemini 2.5 Pro", 1_000_000, true, true, true, 1.25, 10.0),
                ModelConfig("gemini-2.5-flash", "Gemini 2.5 Flash", 1_000_000, true, true, true, 0.15, 0.60)
            ),
            hint = "AIza..."
        ),
        ProviderPreset(
            id = "openrouter",
            name = "OpenRouter",
            type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://openrouter.ai/api/v1",
            requiresApiKey = true,
            defaultModels = listOf(
                ModelConfig("anthropic/claude-sonnet-4", "Claude Sonnet 4 (OR)", 200_000, true, true, true),
                ModelConfig("deepseek/deepseek-chat-v3-0324", "DeepSeek V3", 128_000, true, false, false),
                ModelConfig("qwen/qwen3-coder", "Qwen3 Coder", 128_000, true, false, false)
            ),
            hint = "sk-or-..."
        ),
        ProviderPreset(
            id = "deepseek",
            name = "DeepSeek",
            type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.deepseek.com/v1",
            requiresApiKey = true,
            defaultModels = listOf(
                ModelConfig("deepseek-chat", "DeepSeek Chat (V3)", 64_000, true, false, false, 0.27, 1.10),
                ModelConfig("deepseek-reasoner", "DeepSeek Reasoner (R1)", 64_000, true, false, true, 0.55, 2.19)
            ),
            hint = "sk-..."
        ),
        ProviderPreset(
            id = "groq",
            name = "Groq",
            type = ProviderType.OPENAI_COMPAT,
            baseUrl = "https://api.groq.com/openai/v1",
            requiresApiKey = true,
            defaultModels = listOf(
                ModelConfig("llama-3.3-70b-versatile", "Llama 3.3 70B", 128_000, true, false, false),
                ModelConfig("qwen/qwen3-32b", "Qwen3 32B", 128_000, true, false, true)
            ),
            hint = "gsk_..."
        ),
        ProviderPreset(
            id = "ollama",
            name = "Ollama (LAN)",
            type = ProviderType.OPENAI_COMPAT,
            baseUrl = "http://192.168.1.100:11434/v1",
            requiresApiKey = false,
            defaultModels = listOf(
                ModelConfig("qwen2.5-coder:14b", "Qwen2.5 Coder 14B", 32_768, true, false, false),
                ModelConfig("llama3.1:8b", "Llama 3.1 8B", 32_768, true, false, false)
            ),
            hint = "Optional for LAN Ollama"
        ),
        ProviderPreset(
            id = "lmstudio",
            name = "LM Studio (LAN)",
            type = ProviderType.OPENAI_COMPAT,
            baseUrl = "http://192.168.1.100:1234/v1",
            requiresApiKey = false,
            defaultModels = listOf(
                ModelConfig("local-model", "Local Loaded Model", 32_768, true, false, false)
            ),
            hint = "Optional for LM Studio"
        )
    )
}

@Serializable
sealed class LlmContentPart {
    @Serializable
    data class Text(val text: String) : LlmContentPart()

    @Serializable
    data class Reasoning(val text: String, val durationMs: Long = 0L) : LlmContentPart()

    @Serializable
    data class ImageBase64(val mimeType: String, val dataBase64: String, val fileName: String = "image.png") : LlmContentPart()

    @Serializable
    data class ToolUse(
        val id: String,
        val name: String,
        val argumentsJson: String
    ) : LlmContentPart()

    @Serializable
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val output: String,
        val isError: Boolean = false
    ) : LlmContentPart()
}

@Serializable
data class LlmMessage(
    val id: String,
    val role: String, // "user", "assistant", "tool"
    val parts: List<LlmContentPart>,
    val createdAt: Long = System.currentTimeMillis(),
    val isCompactedSummary: Boolean = false
) {
    fun plainText(): String = parts.filterIsInstance<LlmContentPart.Text>().joinToString("\n") { it.text }
    fun reasoningText(): String = parts.filterIsInstance<LlmContentPart.Reasoning>().joinToString("\n") { it.text }
    fun toolCalls(): List<LlmContentPart.ToolUse> = parts.filterIsInstance<LlmContentPart.ToolUse>()
    fun toolResults(): List<LlmContentPart.ToolResult> = parts.filterIsInstance<LlmContentPart.ToolResult>()
}

data class ToolSpec(
    val name: String,
    val description: String,
    val parametersSchema: JsonObject
)

data class ChatRequest(
    val model: String,
    val system: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null,
    val supportsTools: Boolean = true,
    /** Set by the agent loop; used by providers that run their own agent in the project folder. */
    val projectDir: java.io.File? = null,
    val sessionId: String? = null
)

sealed class LlmEvent {
    data class TextDelta(val text: String) : LlmEvent()
    data class ReasoningDelta(val text: String) : LlmEvent()
    data class ToolCallStart(val id: String, val name: String) : LlmEvent()
    data class ToolCallDelta(val id: String, val argumentsDelta: String) : LlmEvent()
    data class ToolCallEnd(val id: String, val name: String, val argumentsJson: String) : LlmEvent()
    /** A tool the provider already ran itself (e.g. the Antigravity CLI). Shown as a finished tool card, never executed again. */
    data class ExternalTool(val id: String, val name: String, val argumentsJson: String, val output: String, val isError: Boolean = false) : LlmEvent()
    data class Usage(val inputTokens: Long, val outputTokens: Long) : LlmEvent()
    data class Done(val finishReason: String = "stop") : LlmEvent()
    data class Error(val message: String, val httpCode: Int? = null, val retryable: Boolean = false) : LlmEvent()
}

data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val latencyMs: Long = 0L,
    val discoveredModels: List<ModelConfig> = emptyList()
)
