import {
  ContentGenerator,
  GenerateContentParameters,
  GenerateContentResponse,
} from './types.js';
import { ToolRegistry } from './toolRegistry.js';
import { getLogger } from '../logger/index.js';

const logger = getLogger('agent');

export interface AgentMessage {
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  toolCallId?: string;
  toolCalls?: Array<{
    id: string;
    type: string;
    function: { name: string; arguments: string };
  }>;
}

export interface AgentConfig {
  maxTurns: number;
  systemPrompt: string;
}

interface ToolCallRecord {
  id: string;
  name: string;
  arguments: Record<string, unknown>;
}

export class Agent {
  private messages: AgentMessage[] = [];
  private config: AgentConfig;

  constructor(
    private generator: ContentGenerator,
    private tools: ToolRegistry,
    config?: Partial<AgentConfig>,
  ) {
    this.config = {
      maxTurns: 25,
      systemPrompt: `You are an AI coding assistant with direct filesystem access.

## Available Tools
- glob: Find files using glob patterns
- read_file: Read file contents
- write_file: Write content to a file
- edit_file: Edit specific parts of a file
- run_command: Execute shell commands
- search_files: Search for text in files
- web_fetch: Fetch content from URLs

## Rules
- Use tools to accomplish tasks - don't just describe what to do
- After writing files, confirm with a summary
- Show file paths when referencing code`,
      ...config,
    };
  }

  addSystemMessage(content: string): void {
    this.messages.push({ role: 'system', content });
  }

  addUserMessage(content: string): void {
    this.messages.push({ role: 'user', content });
  }

  getMessages(): AgentMessage[] {
    return [...this.messages];
  }

  reset(): void {
    this.messages = [];
  }

  private prepareRequest(): GenerateContentParameters {
    const toolDeclarations = this.tools.getFunctionDeclarations();

    return {
      messages: this.messages,
      tools: toolDeclarations.length > 0 ? toolDeclarations : undefined,
      systemInstruction: this.config.systemPrompt,
    };
  }

  async chat(userMessage: string): Promise<GenerateContentResponse> {
    this.addUserMessage(userMessage);
    return this.run();
  }

  private async run(): Promise<GenerateContentResponse> {
    for (let turn = 0; turn < this.config.maxTurns; turn++) {
      const request = this.prepareRequest();
      const response = await this.generator.generateContent(request);

      const assistantMsg: AgentMessage = {
        role: 'assistant',
        content: response.content,
      };

      if (response.toolCalls && response.toolCalls.length > 0) {
        assistantMsg.toolCalls = response.toolCalls.map((tc) => ({
          id: tc.id,
          type: 'function',
          function: {
            name: tc.name,
            arguments: JSON.stringify(tc.arguments),
          },
        }));
        this.messages.push(assistantMsg);

        for (const tc of response.toolCalls) {
          logger.info(`Executing tool: ${tc.name}`, { args: tc.arguments });
          const result = await this.tools.executeToolCall(
            { id: tc.id, name: tc.name, arguments: tc.arguments },
          );

          this.messages.push({
            role: 'tool',
            content: result.llmContent,
            toolCallId: tc.id,
          });
        }
      } else {
        this.messages.push(assistantMsg);
        return response;
      }
    }

    return {
      content: 'Reached maximum turn limit.',
      finishReason: 'max_turns',
    };
  }

  async *chatStream(
    userMessage: string,
  ): AsyncGenerator<GenerateContentResponse> {
    this.addUserMessage(userMessage);

    for (let turn = 0; turn < this.config.maxTurns; turn++) {
      const request = this.prepareRequest();
      const stream = this.generator.generateContentStream(request);

      let fullContent = '';
      const toolCalls: ToolCallRecord[] = [];

      try {
        for await (const chunk of stream) {
          if (chunk.content) {
            fullContent += chunk.content;
            yield { ...chunk, content: chunk.content };
          }
          if (chunk.toolCalls) {
            for (const tc of chunk.toolCalls) {
              const existing = toolCalls.find((t) => t.id === tc.id);
              if (existing) {
                existing.arguments = { ...existing.arguments, ...tc.arguments };
              } else {
                toolCalls.push({ id: tc.id, name: tc.name, arguments: tc.arguments });
              }
            }
          }
          if (chunk.finishReason && chunk.finishReason !== 'unknown') {
            break;
          }
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        logger.error('Stream error', { error: message });
        yield {
          content: fullContent,
          finishReason: 'error',
        };
        return;
      }

      const assistantMsg: AgentMessage = {
        role: 'assistant',
        content: fullContent,
      };

      if (toolCalls.length > 0) {
        assistantMsg.toolCalls = toolCalls.map((tc) => ({
          id: tc.id,
          type: 'function',
          function: {
            name: tc.name,
            arguments: JSON.stringify(tc.arguments),
          },
        }));
        this.messages.push(assistantMsg);

        for (const tc of toolCalls) {
          logger.info(`Executing tool: ${tc.name}`);
          const result = await this.tools.executeToolCall(
            { id: tc.id, name: tc.name, arguments: tc.arguments },
          );

          yield {
            content: '',
            finishReason: 'tool_execution',
          };

          this.messages.push({
            role: 'tool',
            content: result.llmContent,
            toolCallId: tc.id,
          });
        }
      } else {
        this.messages.push(assistantMsg);
        yield {
          content: fullContent,
          finishReason: 'stop',
        };
        return;
      }
    }

    yield {
      content: 'Reached maximum turn limit.',
      finishReason: 'max_turns',
    };
  }
}
