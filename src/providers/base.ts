import { ProviderConfig, ModelConfig, ProviderResponse, ProviderStreamChunk, ToolCall, ToolSchema, ToolParameter } from '../types.js';

export abstract class BaseProvider {
  protected config: ProviderConfig;
  protected modelConfig: ModelConfig;

  constructor(config: ProviderConfig, modelConfig: ModelConfig) {
    this.config = config;
    this.modelConfig = modelConfig;
  }

  abstract getName(): string;
  abstract getType(): string;
  abstract generate(messages: Array<{ role: string; content: string }>, tools?: unknown[]): Promise<ProviderResponse>;
  abstract generateStream(
    messages: Array<{ role: string; content: string }>,
    tools?: unknown[]
  ): AsyncGenerator<ProviderStreamChunk>;

  static convertToOpenAITools(tools: ToolSchema[]): unknown[] {
    return tools.map((schema) => {
      const properties: Record<string, Record<string, unknown>> = {};
      for (const [key, param] of Object.entries(schema.parameters)) {
        const prop: Record<string, unknown> = {
          type: param.type,
          description: param.description,
        };
        if (param.enum) prop.enum = param.enum;
        if (param.minimum !== undefined) prop.minimum = param.minimum;
        if (param.maximum !== undefined) prop.maximum = param.maximum;
        properties[key] = prop;
      }

      return {
        type: 'function',
        function: {
          name: schema.name,
          description: schema.description,
          parameters: {
            type: 'object',
            properties,
            required: schema.required,
          },
        },
      };
    });
  }

  static convertToAnthropicTools(tools: ToolSchema[]): unknown[] {
    return tools.map((schema) => {
      const properties: Record<string, Record<string, unknown>> = {};
      for (const [key, param] of Object.entries(schema.parameters)) {
        const prop: Record<string, unknown> = {
          type: param.type,
          description: param.description,
        };
        if (param.enum) prop.enum = param.enum;
        properties[key] = prop;
      }

      return {
        name: schema.name,
        description: schema.description,
        input_schema: {
          type: 'object',
          properties,
          required: schema.required,
        },
      };
    });
  }

  protected getApiKey(): string {
    return this.config.apiKey || process.env[`${this.config.type.toUpperCase()}_API_KEY`] || '';
  }

  protected buildHeaders(): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${this.getApiKey()}`,
    };
  }

  protected buildRequestBody(messages: Array<Record<string, unknown>>, tools?: unknown[]): Record<string, unknown> {
    const mappedMessages: Record<string, unknown>[] = messages.map((m) => {
      const msg: Record<string, unknown> = {
        role: m.role,
      };

      if (m.role === 'tool') {
        msg.content = m.content;
        msg.tool_call_id = m.toolCallId || m.tool_call_id || '';
      } else if (m.role === 'assistant' && m.toolCalls && (m.toolCalls as ToolCall[]).length > 0) {
        msg.content = m.content || null;
        msg.tool_calls = (m.toolCalls as ToolCall[]).map((tc: ToolCall) => ({
          id: tc.id,
          type: 'function',
          function: {
            name: tc.name,
            arguments: JSON.stringify(tc.arguments),
          },
        }));
      } else {
        msg.content = m.content || '';
      }

      return msg;
    });

    const body: Record<string, unknown> = {
      model: this.modelConfig.model,
      messages: mappedMessages,
      temperature: this.modelConfig.temperature,
      max_tokens: this.modelConfig.maxTokens,
      top_p: this.modelConfig.topP,
      frequency_penalty: this.modelConfig.frequencyPenalty,
      presence_penalty: this.modelConfig.presencePenalty,
      stream: false,
    };

    if (this.modelConfig.stop && this.modelConfig.stop.length > 0) {
      body.stop = this.modelConfig.stop;
    }

    if (tools && tools.length > 0) {
      body.tools = BaseProvider.convertToOpenAITools(tools as ToolSchema[]);
      body.tool_choice = 'auto';
    }

    return body;
  }

  protected buildStreamBody(messages: Array<{ role: string; content: string }>, tools?: unknown[]): Record<string, unknown> {
    const body = this.buildRequestBody(messages, tools);
    body.stream = true;
    return body;
  }

  protected async fetchWithRetry(url: string, options: RequestInit, retries = this.config.maxRetries): Promise<Response> {
    let lastError: Error | null = null;

    for (let attempt = 0; attempt <= retries; attempt++) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), this.config.timeout);

        const response = await fetch(url, {
          ...options,
          signal: controller.signal,
        });

        clearTimeout(timeoutId);

        if (response.status === 429) {
          const retryAfter = parseInt(response.headers.get('retry-after') || '5', 10);
          await new Promise((resolve) => setTimeout(resolve, retryAfter * 1000));
          continue;
        }

        if (response.status === 401) {
          const provName = this.config.name || this.config.type;
          const modelName = this.modelConfig.model;
          const hasKey = this.getApiKey().length > 0;
          throw new Error(
            `Authentication failed for provider "${provName}" (model: ${modelName}). ` +
            (hasKey
              ? `The API key is set but was rejected. Check that the key is valid for model "${modelName}".`
              : `No API key found. Set ${provName.toUpperCase()}_API_KEY environment variable or use /key ${provName} <key>.`)
          );
        }

        if (response.status === 403) {
          throw new Error(
            `Access forbidden for provider "${this.config.name}" with model "${this.modelConfig.model}". ` +
            `Your API key may not have access to this model or the provider rejected the request.`
          );
        }

        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`HTTP ${response.status}: ${errorText}`);
        }

        return response;
      } catch (error) {
        lastError = error instanceof Error ? error : new Error(String(error));

        if (error instanceof Error && error.name === 'AbortError') {
          throw new Error('Request timed out');
        }

        if (attempt < retries) {
          const delay = Math.min(1000 * Math.pow(2, attempt), 10000);
          await new Promise((resolve) => setTimeout(resolve, delay));
        }
      }
    }

    throw lastError || new Error('Request failed after all retries');
  }

  protected parseToolCalls(data: Record<string, unknown>): ToolCall[] {
    const toolCalls: ToolCall[] = [];
    const choices = data.choices as Array<Record<string, unknown>> | undefined;

    if (!choices || choices.length === 0) return toolCalls;

    const message = choices[0].message as Record<string, unknown> | undefined;
    if (!message) return toolCalls;

    const calls = message.tool_calls as Array<Record<string, unknown>> | undefined;
    if (!calls) return toolCalls;

    for (const call of calls) {
      try {
        toolCalls.push({
          id: call.id as string,
          name: call.function as unknown ? (call.function as Record<string, unknown>).name as string : '',
          arguments: call.function as unknown ? JSON.parse((call.function as Record<string, unknown>).arguments as string) : {},
          timestamp: Date.now(),
        });
      } catch {
      }
    }

    return toolCalls;
  }
}


