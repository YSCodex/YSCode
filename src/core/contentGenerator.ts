import {
  ContentGenerator,
  ContentGeneratorConfig,
  GenerateContentParameters,
  GenerateContentResponse,
  AuthType,
} from './types.js';

export function createContentGenerator(
  config: ContentGeneratorConfig,
): ContentGenerator {
  const authType = config.authType || 'openai';

  switch (authType) {
    case 'openai':
      return new OpenAIContentGenerator(config);
    case 'anthropic':
      return new AnthropicContentGenerator(config);
    case 'gemini':
      return new GeminiContentGenerator(config);
    default:
      return new OpenAIContentGenerator(config);
  }
}

class OpenAIContentGenerator implements ContentGenerator {
  private config: ContentGeneratorConfig;

  constructor(config: ContentGeneratorConfig) {
    this.config = config;
  }

  private getApiKey(): string {
    return (
      this.config.apiKey ||
      process.env[this.config.apiKeyEnvKey || 'OPENAI_API_KEY'] ||
      ''
    );
  }

  private getBaseUrl(): string {
    return this.config.baseUrl || 'https://api.openai.com/v1';
  }

  private buildHeaders(): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${this.getApiKey()}`,
      'User-Agent': 'ys-code-agent/3.0',
    };
  }

  private buildRequestBody(
    request: GenerateContentParameters,
  ): Record<string, unknown> {
    const messages: Record<string, unknown>[] = request.messages.map((m) => {
      const msg: Record<string, unknown> = { role: m.role };
      if (m.role === 'tool') {
        msg.content = m.content;
        msg.tool_call_id = m.toolCallId || '';
      } else if (m.role === 'assistant' && m.toolCalls && m.toolCalls.length > 0) {
        msg.content = m.content || null;
        msg.tool_calls = m.toolCalls.map((tc) => ({
          id: tc.id,
          type: tc.type,
          function: tc.function,
        }));
      } else {
        msg.content = m.content || '';
      }
      return msg;
    });

    const body: Record<string, unknown> = {
      model: this.config.model,
      messages,
      temperature: this.config.temperature ?? 0.7,
      max_tokens: this.config.maxTokens ?? 8192,
      top_p: this.config.topP ?? 0.95,
      stream: false,
    };

    if (request.tools && request.tools.length > 0) {
      body.tools = request.tools;
      body.tool_choice = 'auto';
    }

    if (this.config.repetitionPenalty !== undefined) {
      body.repetition_penalty = this.config.repetitionPenalty;
    }
    if (this.config.presencePenalty !== undefined) {
      body.presence_penalty = this.config.presencePenalty;
    }
    if (this.config.frequencyPenalty !== undefined) {
      body.frequency_penalty = this.config.frequencyPenalty;
    }
    if (this.config.extraBody) {
      Object.assign(body, this.config.extraBody);
    }

    return body;
  }

  private parseToolCalls(
    data: Record<string, unknown>,
  ): GenerateContentResponse['toolCalls'] {
    const choices = data.choices as Array<Record<string, unknown>> | undefined;
    if (!choices || choices.length === 0) return undefined;
    const message = choices[0].message as Record<string, unknown> | undefined;
    if (!message) return undefined;
    const calls = message.tool_calls as
      | Array<Record<string, unknown>>
      | undefined;
    if (!calls) return undefined;

    return calls
      .map((call) => {
        try {
          const fn = call.function as Record<string, unknown>;
          return {
            id: call.id as string,
            name: (fn.name as string) || '',
            arguments: JSON.parse((fn.arguments as string) || '{}'),
          };
        } catch {
          return null;
        }
      })
      .filter(Boolean) as GenerateContentResponse['toolCalls'];
  }

  async generateContent(
    request: GenerateContentParameters,
  ): Promise<GenerateContentResponse> {
    const url = `${this.getBaseUrl()}/chat/completions`;
    const body = this.buildRequestBody(request);

    const response = await fetch(url, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`OpenAI API error ${response.status}: ${errorText}`);
    }

    const data = (await response.json()) as Record<string, unknown>;
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

  async *generateContentStream(
    request: GenerateContentParameters,
  ): AsyncGenerator<GenerateContentResponse> {
    const url = `${this.getBaseUrl()}/chat/completions`;
    const body = this.buildRequestBody(request);
    body.stream = true;

    const response = await fetch(url, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`OpenAI API error ${response.status}: ${errorText}`);
    }

    const reader = response.body?.getReader();
    if (!reader) throw new Error('No response body');

    const decoder = new TextDecoder();
    let buffer = '';
    let accumulatedContent = '';
    const collectedToolCalls: GenerateContentResponse['toolCalls'] = [];

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
          yield {
            content: accumulatedContent,
            toolCalls: collectedToolCalls.length > 0 ? collectedToolCalls : undefined,
            finishReason: 'stop',
          };
          return;
        }

        try {
          const data = JSON.parse(dataStr) as Record<string, unknown>;
          const choices = data.choices as Array<Record<string, unknown>> || [];
          const delta = choices[0]?.delta as Record<string, unknown> || {};

          if (delta.content) {
            accumulatedContent += delta.content as string;
            yield { content: delta.content as string, finishReason: 'unknown' };
          }

          if (delta.tool_calls) {
            const calls = delta.tool_calls as Array<Record<string, unknown>>;
            for (const call of calls) {
              const fn = call.function as Record<string, unknown> || {};
              const existing = collectedToolCalls.find(
                (t) => t.id === call.id,
              );
              if (existing) {
                try {
                  existing.arguments = {
                    ...existing.arguments,
                    ...JSON.parse((fn.arguments as string) || '{}'),
                  };
                } catch {}
              } else {
                try {
                  collectedToolCalls.push({
                    id: call.id as string,
                    name: (fn.name as string) || '',
                    arguments: JSON.parse((fn.arguments as string) || '{}'),
                  });
                } catch {
                  collectedToolCalls.push({
                    id: call.id as string,
                    name: (fn.name as string) || '',
                    arguments: {},
                  });
                }
              }
            }
          }

          const finishReason = choices[0]?.finish_reason as string | undefined;
          if (finishReason) {
            yield {
              content: accumulatedContent,
              toolCalls:
                collectedToolCalls.length > 0 ? collectedToolCalls : undefined,
              finishReason,
            };
          }
        } catch {}
      }
    }
  }

  async countTokens(request: {
    messages: Array<{ role: string; content: string }>;
  }): Promise<number> {
    const totalChars = request.messages.reduce(
      (sum, m) => sum + (m.content || '').length,
      0,
    );
    return Math.ceil(totalChars / 4);
  }
}

class AnthropicContentGenerator implements ContentGenerator {
  private config: ContentGeneratorConfig;

  constructor(config: ContentGeneratorConfig) {
    this.config = config;
  }

  private getApiKey(): string {
    return (
      this.config.apiKey ||
      process.env[this.config.apiKeyEnvKey || 'ANTHROPIC_API_KEY'] ||
      ''
    );
  }

  private getBaseUrl(): string {
    return this.config.baseUrl || 'https://api.anthropic.com/v1';
  }

  private buildHeaders(): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      'x-api-key': this.getApiKey(),
      'anthropic-version': '2023-06-01',
      'User-Agent': 'ys-code-agent/3.0',
    };
  }

  private buildRequestBody(
    request: GenerateContentParameters,
  ): Record<string, unknown> {
    const systemMessages = request.messages.filter(
      (m) => m.role === 'system',
    );
    const nonSystemMessages = request.messages.filter(
      (m) => m.role !== 'system',
    );

    const mappedMessages = nonSystemMessages.map((m) => {
      if (m.role === 'assistant' && m.toolCalls && m.toolCalls.length > 0) {
        return {
          role: 'assistant',
          content: [
            ...(m.content ? [{ type: 'text' as const, text: m.content }] : []),
            ...m.toolCalls.map((tc) => ({
              type: 'tool_use' as const,
              id: tc.id,
              name: tc.function.name,
              input: (() => {
                try {
                  return JSON.parse(tc.function.arguments);
                } catch {
                  return {};
                }
              })(),
            })),
          ],
        };
      }
      if (m.role === 'tool') {
        return {
          role: 'user',
          content: [
            {
              type: 'tool_result' as const,
              tool_use_id: m.toolCallId || '',
              content: m.content,
            },
          ],
        };
      }
      return { role: m.role, content: m.content };
    });

    const body: Record<string, unknown> = {
      model: this.config.model,
      messages: mappedMessages,
      max_tokens: this.config.maxTokens ?? 8192,
      stream: false,
    };

    if (systemMessages.length > 0) {
      body.system = systemMessages.map((m) => ({ type: 'text', text: m.content }));
    }

    if (request.tools && request.tools.length > 0) {
      body.tools = request.tools.map((t) => ({
        name: t.function.name,
        description: t.function.description,
        input_schema: t.function.parameters,
      }));
    }

    if (this.config.temperature !== undefined) body.temperature = this.config.temperature;
    if (this.config.topP !== undefined) body.top_p = this.config.topP;

    return body;
  }

  async generateContent(
    request: GenerateContentParameters,
  ): Promise<GenerateContentResponse> {
    const url = `${this.getBaseUrl()}/messages`;
    const body = this.buildRequestBody(request);

    const response = await fetch(url, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Anthropic API error ${response.status}: ${errorText}`);
    }

    const data = (await response.json()) as Record<string, unknown>;
    const content = data.content as Array<Record<string, unknown>> || [];
    const usage = data.usage as Record<string, unknown> || {};

    let text = '';
    const toolCalls: GenerateContentResponse['toolCalls'] = [];

    for (const block of content) {
      if (block.type === 'text') {
        text += (block.text as string) || '';
      } else if (block.type === 'tool_use') {
        toolCalls.push({
          id: block.id as string,
          name: block.name as string,
          arguments: block.input as Record<string, unknown>,
        });
      }
    }

    return {
      content: text,
      toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
      usage: {
        promptTokens: (usage.input_tokens as number) || 0,
        completionTokens: (usage.output_tokens as number) || 0,
        totalTokens:
          ((usage.input_tokens as number) || 0) +
          ((usage.output_tokens as number) || 0),
      },
      finishReason: (data.stop_reason as string) || 'end_turn',
    };
  }

  async *generateContentStream(
    request: GenerateContentParameters,
  ): AsyncGenerator<GenerateContentResponse> {
    const url = `${this.getBaseUrl()}/messages`;
    const body = this.buildRequestBody(request);
    body.stream = true;

    const response = await fetch(url, {
      method: 'POST',
      headers: this.buildHeaders(),
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Anthropic API error ${response.status}: ${errorText}`);
    }

    const reader = response.body?.getReader();
    if (!reader) throw new Error('No response body');

    const decoder = new TextDecoder();
    let buffer = '';
    let accumulatedContent = '';
    const collectedToolCalls: GenerateContentResponse['toolCalls'] = [];

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
          yield {
            content: accumulatedContent,
            toolCalls: collectedToolCalls.length > 0 ? collectedToolCalls : undefined,
            finishReason: 'end_turn',
          };
          return;
        }

        try {
          const data = JSON.parse(dataStr) as Record<string, unknown>;
          const type = data.type as string;

          if (type === 'content_block_delta') {
            const delta = data.delta as Record<string, unknown> || {};
            if (delta.type === 'text_delta') {
              accumulatedContent += (delta.text as string) || '';
              yield { content: delta.text as string, finishReason: 'unknown' };
            }
          } else if (type === 'message_delta') {
            const delta = data.delta as Record<string, unknown> || {};
            const stopReason = delta.stop_reason as string | undefined;
            if (stopReason) {
              yield {
                content: accumulatedContent,
                toolCalls: collectedToolCalls.length > 0 ? collectedToolCalls : undefined,
                finishReason: stopReason,
              };
            }
          }
        } catch {}
      }
    }
  }

  async countTokens(request: {
    messages: Array<{ role: string; content: string }>;
  }): Promise<number> {
    const totalChars = request.messages.reduce(
      (sum, m) => sum + (m.content || '').length,
      0,
    );
    return Math.ceil(totalChars / 4);
  }
}

class GeminiContentGenerator implements ContentGenerator {
  private config: ContentGeneratorConfig;

  constructor(config: ContentGeneratorConfig) {
    this.config = config;
  }

  private getApiKey(): string {
    return (
      this.config.apiKey ||
      process.env[this.config.apiKeyEnvKey || 'GEMINI_API_KEY'] ||
      ''
    );
  }

  private getBaseUrl(): string {
    return (
      this.config.baseUrl ||
      'https://generativelanguage.googleapis.com/v1beta'
    );
  }

  private buildRequestBody(
    request: GenerateContentParameters,
  ): Record<string, unknown> {
    const contents: Record<string, unknown>[] = [];
    const systemMessages: string[] = [];

    for (const m of request.messages) {
      if (m.role === 'system') {
        systemMessages.push(m.content);
        continue;
      }

      const role = m.role === 'assistant' ? 'model' : 'user';
      const parts: Record<string, unknown>[] = [];

      if (m.content) {
        parts.push({ text: m.content });
      }

      if (m.toolCalls) {
        for (const tc of m.toolCalls) {
          parts.push({
            functionCall: {
              name: tc.function.name,
              args: (() => {
                try {
                  return JSON.parse(tc.function.arguments);
                } catch {
                  return {};
                }
              })(),
            },
          });
        }
      }

      if (m.role === 'tool' && m.toolCallId) {
        parts.push({
          functionResponse: {
            name: m.toolCallId,
            response: { content: m.content },
          },
        });
      }

      contents.push({ role, parts });
    }

    const body: Record<string, unknown> = {
      contents,
      generationConfig: {
        temperature: this.config.temperature ?? 0.7,
        maxOutputTokens: this.config.maxTokens ?? 8192,
        topP: this.config.topP ?? 0.95,
      },
    };

    if (systemMessages.length > 0) {
      body.systemInstruction = { parts: systemMessages.map((t) => ({ text: t })) };
    }

    if (request.tools && request.tools.length > 0) {
      body.tools = request.tools.map((t) => ({
        functionDeclarations: [
          {
            name: t.function.name,
            description: t.function.description,
            parameters: t.function.parameters,
          },
        ],
      }));
    }

    return body;
  }

  async generateContent(
    request: GenerateContentParameters,
  ): Promise<GenerateContentResponse> {
    const url = `${this.getBaseUrl()}/models/${this.config.model}:generateContent?key=${this.getApiKey()}`;
    const body = this.buildRequestBody(request);

    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'User-Agent': 'ys-code-agent/3.0' },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Gemini API error ${response.status}: ${errorText}`);
    }

    const data = (await response.json()) as Record<string, unknown>;
    const candidates = data.candidates as Array<Record<string, unknown>> || [];
    const candidate = candidates[0] || {};
    const content = candidate.content as Record<string, unknown> || {};
    const parts = content.parts as Array<Record<string, unknown>> || [];
    const usage = data.usageMetadata as Record<string, unknown> || {};

    let text = '';
    const toolCalls: GenerateContentResponse['toolCalls'] = [];

    for (const part of parts) {
      if (part.text) {
        text += part.text as string;
      }
      if (part.functionCall) {
        const fc = part.functionCall as Record<string, unknown>;
        toolCalls.push({
          id: fc.name as string,
          name: fc.name as string,
          arguments: fc.args as Record<string, unknown>,
        });
      }
    }

    return {
      content: text,
      toolCalls: toolCalls.length > 0 ? toolCalls : undefined,
      usage: {
        promptTokens: (usage.promptTokenCount as number) || 0,
        completionTokens: (usage.candidatesTokenCount as number) || 0,
        totalTokens: (usage.totalTokenCount as number) || 0,
      },
      finishReason: (candidate.finishReason as string) || 'STOP',
    };
  }

  async *generateContentStream(
    request: GenerateContentParameters,
  ): AsyncGenerator<GenerateContentResponse> {
    const url = `${this.getBaseUrl()}/models/${this.config.model}:streamGenerateContent?key=${this.getApiKey()}&alt=sse`;
    const body = this.buildRequestBody(request);

    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'User-Agent': 'ys-code-agent/3.0' },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Gemini API error ${response.status}: ${errorText}`);
    }

    const reader = response.body?.getReader();
    if (!reader) throw new Error('No response body');

    const decoder = new TextDecoder();
    let buffer = '';
    let accumulatedContent = '';
    const collectedToolCalls: GenerateContentResponse['toolCalls'] = [];

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
              accumulatedContent += part.text as string;
              yield { content: part.text as string, finishReason: 'unknown' };
            }
            if (part.functionCall) {
              const fc = part.functionCall as Record<string, unknown>;
              collectedToolCalls.push({
                id: fc.name as string,
                name: fc.name as string,
                arguments: fc.args as Record<string, unknown>,
              });
            }
          }

          const finishReason = candidate.finishReason as string | undefined;
          if (finishReason && finishReason !== 'unknown') {
            yield {
              content: accumulatedContent,
              toolCalls: collectedToolCalls.length > 0 ? collectedToolCalls : undefined,
              finishReason,
            };
          }
        } catch {}
      }
    }
  }

  async countTokens(request: {
    messages: Array<{ role: string; content: string }>;
  }): Promise<number> {
    const totalChars = request.messages.reduce(
      (sum, m) => sum + (m.content || '').length,
      0,
    );
    return Math.ceil(totalChars / 4);
  }
}
