// SPDX-License-Identifier: GPL-3.0-or-later
package com.example.llm

import com.example.data.ProviderEntity
import com.example.data.SecretStore
import com.example.util.JsonHelper

class LlmClientFactory(
    private val secretStore: SecretStore,
    private val codexAuth: CodexAuth,
    private val agyManager: AgyManager
) {
    fun create(provider: ProviderEntity): LlmClient {
        val apiKey = secretStore.getSecret(provider.secretAlias)
        val headers = runCatching {
            JsonHelper.json.decodeFromString<Map<String, String>>(provider.headersJson)
        }.getOrDefault(emptyMap())

        val type = runCatching { ProviderType.valueOf(provider.type) }
            .getOrDefault(ProviderType.OPENAI_COMPAT)

        return when (type) {
            ProviderType.OPENAI_COMPAT -> OpenAiClient(provider.baseUrl, apiKey, headers)
            ProviderType.ANTHROPIC -> AnthropicClient(provider.baseUrl, apiKey, headers)
            ProviderType.GEMINI -> GeminiClient(provider.baseUrl, apiKey, headers)
            ProviderType.CODEX -> CodexClient(codexAuth)
            ProviderType.AGY -> AgyClient(agyManager)
        }
    }

    fun parseModels(provider: ProviderEntity): List<ModelConfig> {
        return runCatching {
            JsonHelper.json.decodeFromString<List<ModelConfig>>(provider.modelsJson)
        }.getOrDefault(emptyList())
    }
}
