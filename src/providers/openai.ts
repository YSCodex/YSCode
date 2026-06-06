import { BaseProvider } from './base.js';
import { ProviderConfig, ModelConfig, ProviderResponse, ProviderStreamChunk, ToolCall } from '../types.js';

export class OpenAIProvider extends BaseProvider {
  constructor(config: ProviderConfig, modelConfig: ModelConfig) {
    super(config, modelConfig);
  }

  getName(): string {
    return 'OpenAI';
  }

  getType(): string {
    return 'openai';
  }

  async generate(
    messages: Array<{ role: string; content: string }>,
    tools?: unknown[]
  ): Promise<ProviderResponse> {
    const url = `${this.config.baseUrl}/chat/completions`;
    const body = this.buildRequestBody(messages, tools);

    const response = await this.fetchWithRetry(url, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify(body),
    });

    const data = await response.json() as Record<string, unknown>;

    const choices = data.choices as Array<Record<string, unknown>>;
    const message = choices[0]?.message as Record<string, unknown> || {};
    const usage = data.usage as Record<string, unknown> || {};

    return {
      content: (message.content as string) || '',
      toolCalls: this.parseToolCalls(data),
      usage: {
        promptTokens: (usage.prompt_tokens as number) || 0,
        completionTokens: (usage.completion_tokens as number) || 0,
        totalTokens: (usage.total_tokens as number) || 0,
      },
      finishReason: (choices[0]?.finish_reason as string) || 'stop',
    };
  }

  async *generateStream(
    messages: Array<{ role: string; content: string }>,
    tools?: unknown[]
  ): AsyncGenerator<ProviderStreamChunk> {
    const url = `${this.config.baseUrl}/chat/completions`;
    const body = this.buildStreamBody(messages, tools);

    const response = await this.fetchWithRetry(url, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify(body),
    });

    const reader = response.body?.getReader();
    if (!reader) {
      throw new Error('No response body stream available');
    }

    const decoder = new TextDecoder();
    let buffer = '';

    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed || !trimmed.startsWith('data: ')) continue;

          const dataStr = trimmed.slice(6);
          if (dataStr === '[DONE]') {
            yield { type: 'done' };
            return;
          }

          try {
            const data = JSON.parse(dataStr) as Record<string, unknown>;
            const choices = data.choices as Array<Record<string, unknown>> || [];
            const delta = choices[0]?.delta as Record<string, unknown> || {};
            const finishReason = choices[0]?.finish_reason as string | null;

            if (delta.content) {
              yield { type: 'text', content: delta.content as string };
            }

            if (delta.tool_calls) {
              const calls = delta.tool_calls as Array<Record<string, unknown>>;
              for (const call of calls) {
                const fn = call.function as Record<string, unknown> || {};
                yield {
                  type: 'tool_call',
                  toolCall: {
                    id: call.id as string,
                    name: fn.name as string || '',
                    arguments: fn.arguments ? JSON.parse(fn.arguments as string) : {},
                    timestamp: Date.now(),
                  },
                };
              }
            }

            if (finishReason) {
              const usage = data.usage as Record<string, unknown>;
              if (usage) {
                yield {
                  type: 'done',
                  usage: {
                    promptTokens: (usage.prompt_tokens as number) || 0,
                    completionTokens: (usage.completion_tokens as number) || 0,
                    totalTokens: (usage.total_tokens as number) || 0,
                  },
                };
              } else {
                yield { type: 'done' };
              }
            }
          } catch {
          }
        }
      }
    } finally {
      reader.releaseLock();
    }
  }
}
