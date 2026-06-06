import { BaseProvider } from './base.js';
import { ProviderConfig, ModelConfig, ProviderResponse, ProviderStreamChunk, ToolCall } from '../types.js';

export class GeminiProvider extends BaseProvider {
  constructor(config: ProviderConfig, modelConfig: ModelConfig) {
    super(config, modelConfig);
  }

  getName(): string {
    return 'Gemini';
  }

  getType(): string {
    return 'gemini';
  }

  protected buildHeaders(): Record<string, string> {
    return {
      'Content-Type': 'application/json',
    };
  }

  private convertMessages(messages: Array<{ role: string; content: string }>): {
    systemInstruction?: string;
    contents: Array<{ role: string; parts: Array<{ text: string }> }>;
  } {
    const contents: Array<{ role: string; parts: Array<{ text: string }> }> = [];
    let systemInstruction: string | undefined;

    for (const msg of messages) {
      if (msg.role === 'system') {
        systemInstruction = msg.content;
      } else {
        const role = msg.role === 'assistant' ? 'model' : 'user';
        contents.push({
          role,
          parts: [{ text: msg.content }],
        });
      }
    }

    return { systemInstruction, contents };
  }

  async generate(
    messages: Array<{ role: string; content: string }>,
    tools?: unknown[]
  ): Promise<ProviderResponse> {
    const apiKey = this.getApiKey();
    const url = `${this.config.baseUrl}/models/${this.modelConfig.model}:generateContent?key=${apiKey}`;

    const { systemInstruction, contents } = this.convertMessages(messages);

    const body: Record<string, unknown> = {
      contents,
      generationConfig: {
        temperature: this.modelConfig.temperature,
        maxOutputTokens: this.modelConfig.maxTokens,
        topP: this.modelConfig.topP,
        stopSequences: this.modelConfig.stop,
      },
    };

    if (systemInstruction) {
      body.systemInstruction = { parts: [{ text: systemInstruction }] };
    }

    const response = await this.fetchWithRetry(url, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify(body),
    });

    const data = await response.json() as Record<string, unknown>;

    if (data.error) {
      const err = data.error as Record<string, unknown>;
      throw new Error(`Gemini API error: ${err.message || JSON.stringify(err)}`);
    }

    const candidates = data.candidates as Array<Record<string, unknown>> || [];
    const candidate = candidates[0] || {};
    const content = candidate.content as Record<string, unknown> || {};
    const parts = content.parts as Array<Record<string, unknown>> || [];

    let textContent = '';
    const toolCalls: ToolCall[] = [];

    for (const part of parts) {
      if (part.text) {
        textContent += part.text;
      } else if (part.functionCall) {
        const fc = part.functionCall as Record<string, unknown>;
        const args = fc.args ? (typeof fc.args === 'string' ? JSON.parse(fc.args as string) : fc.args) : {};
        toolCalls.push({
          id: `call_${Date.now()}`,
          name: fc.name as string,
          arguments: args as Record<string, unknown>,
          timestamp: Date.now(),
        });
      }
    }

    const usageMetadata = data.usageMetadata as Record<string, unknown> || {};

    return {
      content: textContent,
      toolCalls,
      usage: {
        promptTokens: (usageMetadata.promptTokenCount as number) || 0,
        completionTokens: (usageMetadata.candidatesTokenCount as number) || 0,
        totalTokens: (usageMetadata.totalTokenCount as number) || 0,
      },
      finishReason: (candidate.finishReason as string) || 'STOP',
    };
  }

  async *generateStream(
    messages: Array<{ role: string; content: string }>,
    tools?: unknown[]
  ): AsyncGenerator<ProviderStreamChunk> {
    const apiKey = this.getApiKey();
    const url = `${this.config.baseUrl}/models/${this.modelConfig.model}:streamGenerateContent?key=${apiKey}&alt=sse`;

    const { systemInstruction, contents } = this.convertMessages(messages);

    const body: Record<string, unknown> = {
      contents,
      generationConfig: {
        temperature: this.modelConfig.temperature,
        maxOutputTokens: this.modelConfig.maxTokens,
        topP: this.modelConfig.topP,
        stopSequences: this.modelConfig.stop,
      },
    };

    if (systemInstruction) {
      body.systemInstruction = { parts: [{ text: systemInstruction }] };
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
          try {
            const data = JSON.parse(dataStr) as Record<string, unknown>;

            const candidates = data.candidates as Array<Record<string, unknown>> || [];
            const candidate = candidates[0] || {};
            const content = candidate.content as Record<string, unknown> || {};
            const parts = content.parts as Array<Record<string, unknown>> || [];

            for (const part of parts) {
              if (part.text) {
                yield { type: 'text', content: part.text as string };
              } else if (part.functionCall) {
                const fc = part.functionCall as Record<string, unknown>;
                const args = fc.args ? (typeof fc.args === 'string' ? JSON.parse(fc.args as string) : fc.args) : {};
                yield {
                  type: 'tool_call',
                  toolCall: {
                    id: `call_${Date.now()}`,
                    name: fc.name as string,
                    arguments: args as Record<string, unknown>,
                    timestamp: Date.now(),
                  },
                };
              }
            }

            if (candidate.finishReason) {
              const usageMetadata = data.usageMetadata as Record<string, unknown>;
              if (usageMetadata) {
                yield {
                  type: 'done',
                  usage: {
                    promptTokens: (usageMetadata.promptTokenCount as number) || 0,
                    completionTokens: (usageMetadata.candidatesTokenCount as number) || 0,
                    totalTokens: (usageMetadata.totalTokenCount as number) || 0,
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
