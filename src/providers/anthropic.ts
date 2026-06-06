import { BaseProvider } from './base.js';
import { ProviderConfig, ModelConfig, ProviderResponse, ProviderStreamChunk, ToolCall } from '../types.js';

export class AnthropicProvider extends BaseProvider {
  constructor(config: ProviderConfig, modelConfig: ModelConfig) {
    super(config, modelConfig);
  }

  getName(): string {
    return 'Anthropic';
  }

  getType(): string {
    return 'anthropic';
  }

  protected buildHeaders(): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      'x-api-key': this.getApiKey(),
      'anthropic-version': '2023-06-01',
    };
  }

  protected buildRequestBody(messages: Array<{ role: string; content: string }>, tools?: unknown[]): Record<string, unknown> {
    const systemMessages = messages.filter((m) => m.role === 'system');
    const chatMessages = messages.filter((m) => m.role !== 'system');

    const body: Record<string, unknown> = {
      model: this.modelConfig.model,
      messages: chatMessages.map((m) => ({
        role: m.role === 'assistant' ? 'assistant' : 'user',
        content: m.content,
      })),
      max_tokens: this.modelConfig.maxTokens,
      temperature: this.modelConfig.temperature,
      top_p: this.modelConfig.topP,
      stream: false,
    };

    if (systemMessages.length > 0) {
      body.system = systemMessages.map((m) => ({ type: 'text', text: m.content }));
    }

    if (tools && tools.length > 0) {
      body.tools = tools;
    }

    return body;
  }

  private convertMessages(messages: Array<{ role: string; content: string }>): {
    systemMessages: Array<{ type: string; text: string }>;
    chatMessages: Array<{ role: string; content: string }>;
  } {
    const systemMessages: Array<{ type: string; text: string }> = [];
    const chatMessages: Array<{ role: string; content: string }> = [];

    for (const msg of messages) {
      if (msg.role === 'system') {
        systemMessages.push({ type: 'text', text: msg.content });
      } else {
        chatMessages.push({
          role: msg.role === 'assistant' ? 'assistant' : 'user',
          content: msg.content,
        });
      }
    }

    return { systemMessages, chatMessages };
  }

  async generate(
    messages: Array<{ role: string; content: string }>,
    tools?: unknown[]
  ): Promise<ProviderResponse> {
    const url = `${this.config.baseUrl}/messages`;
    const { systemMessages, chatMessages } = this.convertMessages(messages);

    const body: Record<string, unknown> = {
      model: this.modelConfig.model,
      messages: chatMessages,
      max_tokens: this.modelConfig.maxTokens,
      temperature: this.modelConfig.temperature,
      top_p: this.modelConfig.topP,
      stream: false,
    };

    if (systemMessages.length > 0) {
      body.system = systemMessages;
    }

    if (tools && tools.length > 0) {
      body.tools = tools;
    }

    const response = await this.fetchWithRetry(url, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify(body),
    });

    const data = await response.json() as Record<string, unknown>;
    const content = data.content as Array<Record<string, unknown>> || [];

    let textContent = '';
    const toolCalls: ToolCall[] = [];

    for (const block of content) {
      if (block.type === 'text') {
        textContent += block.text || '';
      } else if (block.type === 'tool_use') {
        toolCalls.push({
          id: block.id as string,
          name: block.name as string,
          arguments: block.input as Record<string, unknown> || {},
          timestamp: Date.now(),
        });
      }
    }

    const usage = data.usage as Record<string, unknown> || {};

    return {
      content: textContent,
      toolCalls,
      usage: {
        promptTokens: (usage.input_tokens as number) || 0,
        completionTokens: (usage.output_tokens as number) || 0,
        totalTokens: ((usage.input_tokens as number) || 0) + ((usage.output_tokens as number) || 0),
      },
      finishReason: (data.stop_reason as string) || 'end_turn',
    };
  }

  async *generateStream(
    messages: Array<{ role: string; content: string }>,
    tools?: unknown[]
  ): AsyncGenerator<ProviderStreamChunk> {
    const url = `${this.config.baseUrl}/messages`;
    const { systemMessages, chatMessages } = this.convertMessages(messages);

    const body: Record<string, unknown> = {
      model: this.modelConfig.model,
      messages: chatMessages,
      max_tokens: this.modelConfig.maxTokens,
      temperature: this.modelConfig.temperature,
      top_p: this.modelConfig.topP,
      stream: true,
    };

    if (systemMessages.length > 0) {
      body.system = systemMessages;
    }

    if (tools && tools.length > 0) {
      body.tools = tools;
    }

    const response = await this.fetchWithRetry(url, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify(body),
    });

    const reader = response.body?.getReader();
    if (!reader) throw new Error('No response body stream available');

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
            const eventType = data.type as string;

            if (eventType === 'content_block_delta') {
              const delta = data.delta as Record<string, unknown> || {};
              if (delta.type === 'text_delta' && delta.text) {
                yield { type: 'text', content: delta.text as string };
              }
            } else if (eventType === 'content_block_start') {
              const block = data.content_block as Record<string, unknown> || {};
              if (block.type === 'tool_use') {
                yield {
                  type: 'tool_call',
                  toolCall: {
                    id: block.id as string,
                    name: block.name as string,
                    arguments: (block.input as Record<string, unknown>) || {},
                    timestamp: Date.now(),
                  },
                };
              }
            } else if (eventType === 'message_done') {
              const usage = data.usage as Record<string, unknown>;
              if (usage) {
                yield {
                  type: 'done',
                  usage: {
                    promptTokens: (usage.input_tokens as number) || 0,
                    completionTokens: (usage.output_tokens as number) || 0,
                    totalTokens: ((usage.input_tokens as number) || 0) + ((usage.output_tokens as number) || 0),
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
