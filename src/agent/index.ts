import { configManager } from '../config/index.js';
import { getLogger } from '../logger/index.js';
import { createProvider } from '../providers/index.js';
import { initializeTools } from '../tools/declarative/index.js';
import { ToolRegistry } from '../tools/declarative/ToolRegistry.js';
import { memoryManager } from '../memory/index.js';
import { sessionManager } from '../session/index.js';
import { hookSystem } from '../hooks/index.js';
import { debugLogger } from '../utils/debugLogger.js';
import type {
  ToolResult,
  ToolResultDisplay,
  ShellExecutionConfig,
} from '../tools/declarative/ToolTypes.js';
import type {
  AgentState, AgentMessage, PlanStep, ToolCall,
  ProviderResponse, ProviderStreamChunk, AgentStatus, ProviderConfig, ModelConfig
} from '../types.js';
import { generateId, countTokens, formatDuration } from '../utils/index.js';

const logger = getLogger('agent');

const SYSTEM_PROMPT = `You are YS Code Agent v4.0 — a production-grade terminal AI coding assistant.

## CRITICAL RULE — USE TOOLS, DON'T JUST TALK
When the user asks you to make, create, write, or build something — USE the write_file tool to actually create the file on disk.
When the user asks to read, show, or check something — USE the read_file tool.
When the user asks to run, execute, or test something — USE the terminal tool.
When the user asks to search or find something — USE the search tool.
NEVER just show code in chat without writing it to disk when the user asks to create something.

## Available Tools
- read_file: Read content from a file
- write_file: Write content to a file
- edit_file: Edit specific parts of a file
- delete_file: Delete a file or directory
- terminal: Execute a shell command
- git: Execute git commands
- web_fetch: Fetch content from a URL
- search: Search for text in files
- glob: Find files matching a pattern
- memory: Manage agent memory

## Response Format
- Use clear section headers for complex responses
- Show file paths when referencing code
- Keep responses concise but complete
- After writing files, confirm with a summary of what was created
- Use markdown formatting in your responses`;

export class Agent {
  private state: AgentState;
  private provider: ReturnType<typeof createProvider> | null = null;
  private toolRegistry: ToolRegistry | null = null;
  private maxIterations = 50;
  private currentIteration = 0;

  constructor() {
    this.state = this.createInitialState();
    this.initProvider();
    this.initTools();
  }

  private createInitialState(): AgentState {
    const mem = memoryManager.getContext();
    return {
      task: '',
      plan: [],
      currentStep: 0,
      messages: [
        { role: 'system', content: SYSTEM_PROMPT, timestamp: Date.now() },
      ],
      context: {
        currentDirectory: process.cwd(),
        openFiles: [],
        recentFiles: [],
        projectStructure: [],
        environment: {},
        tokenCount: 0,
      },
      memory: mem,
      status: 'idle',
      startTime: Date.now(),
    };
  }

  private initProvider(): void {
    const providerConfig = configManager.getActiveProvider();
    const modelConfig = configManager.getModelConfig();
    try {
      this.provider = createProvider(providerConfig, modelConfig);
      logger.info(`Initialized provider: ${providerConfig.type} with model ${modelConfig.model}`);
    } catch (error) {
      logger.error('Failed to initialize provider', error);
    }
  }

  private initTools(): void {
    try {
      this.toolRegistry = initializeTools();
      logger.info(`Initialized ${this.toolRegistry.getAllToolNames().length} tools`);
    } catch (error) {
      logger.error('Failed to initialize tools', error);
    }
  }

  switchProvider(providerName: string): boolean {
    const providerConfig = configManager.getProvider(providerName);
    if (!providerConfig) {
      logger.error(`Provider not found: ${providerName}`);
      return false;
    }
    configManager.setActiveProvider(providerName);
    const modelConfig = configManager.getModelConfig();
    try {
      this.provider = createProvider(providerConfig, modelConfig);
      logger.info(`Switched to provider: ${providerName} with model ${modelConfig.model}`);
      return true;
    } catch (error) {
      logger.error(`Failed to switch provider: ${providerName}`, error);
      return false;
    }
  }

  private ensureProvider(): void {
    if (!this.provider) {
      this.initProvider();
      if (!this.provider) {
        throw new Error('No AI provider configured. Set a provider API key.');
      }
    }
  }

  private async generateWithRetry(
    messages: Array<{ role: string; content: string }>,
    maxRetries = 3
  ): Promise<ProviderResponse> {
    this.ensureProvider();

    let lastError: Error | null = null;
    const tools = this.toolRegistry?.getFunctionDeclarations() || [];

    for (let attempt = 0; attempt < maxRetries; attempt++) {
      try {
        const response = await this.provider!.generate(messages, tools as unknown[]);
        if (response.toolCalls && response.toolCalls.length > 0) {
          this.state.messages.push({
            role: 'assistant',
            content: response.content || '',
            toolCalls: response.toolCalls,
            timestamp: Date.now(),
          });

          for (const toolCall of response.toolCalls) {
            const result = await this.executeToolCall(toolCall);
            this.state.messages.push({
              role: 'tool',
              content: typeof result.llmContent === 'string' ? result.llmContent : JSON.stringify(result.llmContent || { error: result.error?.message }),
              toolResults: [{ success: result.success, error: result.error?.message, data: result.llmContent }],
              toolCallId: toolCall.id,
              timestamp: Date.now(),
            });
          }

          return this.generateWithRetry(
            [
              { role: 'system', content: SYSTEM_PROMPT },
              ...this.state.messages.slice(-20).map(m => ({ role: m.role, content: typeof m.content === 'string' ? m.content : '' })),
            ],
            maxRetries - 1
          );
        }

        return response;
      } catch (error) {
        lastError = error instanceof Error ? error : new Error(String(error));
        logger.warn(`Generation attempt ${attempt + 1} failed`, { error: lastError.message });
        if (attempt < maxRetries - 1) {
          await new Promise((resolve) => setTimeout(resolve, Math.min(1000 * Math.pow(2, attempt), 10000)));
        }
      }
    }

    throw lastError || new Error('Generation failed after all retries');
  }

  private async executeToolCall(toolCall: ToolCall): Promise<ToolResult> {
    if (!this.toolRegistry) {
      return { success: false, llmContent: 'Tool registry not initialized', error: { message: 'Tool registry not initialized', type: 'TOOL_NOT_FOUND' } };
    }

    const tool = this.toolRegistry.getTool(toolCall.name);
    if (!tool) {
      return { success: false, llmContent: `Tool not found: ${toolCall.name}`, error: { message: `Tool not found: ${toolCall.name}`, type: 'TOOL_NOT_FOUND' } };
    }

    try {
      const invocation = tool.build(toolCall.arguments as Record<string, unknown>);
      const result = await invocation.execute(new AbortController().signal);
      return { ...result, success: !result.error, llmContent: result.llmContent || result.returnDisplay as string || '' };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return { success: false, llmContent: message, error: { message, type: 'EXECUTION_FAILED' } };
    }
  }

  async executeTask(task: string): Promise<AgentMessage> {
    this.state.task = task;
    this.state.startTime = Date.now();
    this.state.status = 'thinking';
    this.currentIteration = 0;

    this.state.messages.push({ role: 'user', content: task, timestamp: Date.now() });

    try {
      const projectInfo = await import('../project/index.js').then(m => m.analyzeProject());
      memoryManager.store('project_info', JSON.stringify(projectInfo), 'project');
    } catch { logger.warn('Failed to analyze project'); }

    sessionManager.createSession({ title: 'Task: ' + task.slice(0, 50) });
    await hookSystem.execute('UserPrompt', { sessionId: sessionManager.getCurrentSession()?.id || '', userPrompt: task, cwd: process.cwd() });

    while (this.currentIteration < this.maxIterations) {
      this.currentIteration++;
      this.state.status = 'thinking';

      const messages = [
        { role: 'system', content: SYSTEM_PROMPT },
        ...this.state.messages.slice(-10).map(m => ({ role: m.role, content: typeof m.content === 'string' ? m.content : '' })),
        { role: 'user', content: `[Iteration ${this.currentIteration}] Continue working on the task: ${task}\n\nContext:\nProject root: ${process.cwd()}\nWhat have you done so far? What needs to be done next? Use the available tools to make progress.` },
      ];

      try {
        const response = await this.generateWithRetry(messages);
        const content = response.content || 'No response generated';
        this.state.messages.push({ role: 'assistant', content, timestamp: Date.now() });
      } catch (error) {
        this.state.messages.push({ role: 'assistant', content: `Error: ${error instanceof Error ? error.message : String(error)}`, timestamp: Date.now() });
        break;
      }

      if (this.isTaskComplete()) break;
    }

    this.state.status = 'completed';
    const finalMessages = this.state.messages.filter(m => m.role === 'assistant' && !m.toolCalls);
    const finalResponse = finalMessages[finalMessages.length - 1] || {
      role: 'assistant' as const,
      content: 'Task completed. Check the above results for details.',
      timestamp: Date.now(),
    };

    memoryManager.addTask(this.state.task, this.currentIteration < this.maxIterations ? 'completed' : 'cancelled', Date.now() - this.state.startTime);
    return finalResponse;
  }

  async chat(message: string): Promise<AgentMessage> {
    this.state.messages.push({ role: 'user', content: message, timestamp: Date.now() });

    try {
      const response = await this.generateWithRetry([
        { role: 'system', content: SYSTEM_PROMPT },
        ...this.state.messages.slice(-20).map(m => ({ role: m.role, content: typeof m.content === 'string' ? m.content : '' })),
      ]);

      const assistantMsg: AgentMessage = {
        role: 'assistant',
        content: response.content,
        timestamp: Date.now(),
      };

      this.state.messages.push(assistantMsg);
      return assistantMsg;
    } catch (error) {
      const errorMsg: AgentMessage = {
        role: 'assistant',
        content: `Error: ${error instanceof Error ? error.message : String(error)}`,
        timestamp: Date.now(),
      };
      this.state.messages.push(errorMsg);
      return errorMsg;
    }
  }

  async *chatStream(message: string): AsyncGenerator<string, AgentMessage, void> {
    this.state.messages.push({ role: 'user', content: message, timestamp: Date.now() });
    this.ensureProvider();

    const tools = this.toolRegistry?.getFunctionDeclarations() || [];

    try {
      let fullContent = '';
      const stream = this.provider!.generateStream(
        [
          { role: 'system', content: SYSTEM_PROMPT },
          ...this.state.messages.slice(-20).map(m => ({ role: m.role, content: typeof m.content === 'string' ? m.content : '' })),
        ],
        tools as unknown[]
      );

      for await (const chunk of stream) {
        if (chunk.type === 'text' && chunk.content) {
          fullContent += chunk.content;
          yield chunk.content;
        } else if (chunk.type === 'tool_call' && chunk.toolCall) {
          const result = await this.executeToolCall(chunk.toolCall);
          this.state.messages.push({
            role: 'tool',
            content: typeof result.llmContent === 'string' ? result.llmContent : JSON.stringify({ error: result.error?.message }),
            toolResults: [{ success: result.success, error: result.error?.message, data: result.llmContent }],
            toolCallId: chunk.toolCall.id,
            timestamp: Date.now(),
          });
        } else if (chunk.type === 'done') {
          break;
        }
      }

      const assistantMsg: AgentMessage = {
        role: 'assistant',
        content: fullContent,
        timestamp: Date.now(),
      };
      this.state.messages.push(assistantMsg);
      return assistantMsg;
    } catch (error) {
      const errorMsg: AgentMessage = {
        role: 'assistant',
        content: `Error: ${error instanceof Error ? error.message : String(error)}`,
        timestamp: Date.now(),
      };
      this.state.messages.push(errorMsg);
      return errorMsg;
    }
  }

  private isTaskComplete(): boolean {
    const lastMessages = this.state.messages.slice(-5);
    for (const msg of lastMessages) {
      if (msg.role === 'assistant' && !msg.toolCalls) {
        const lower = msg.content.toLowerCase();
        if (lower.includes('task complete') || lower.includes('done!') || lower.includes('finished!') || lower.includes('completed!')) return true;
      }
    }
    return false;
  }

  getState(): AgentState { return { ...this.state }; }
  getMessages(): AgentMessage[] { return [...this.state.messages]; }

  reset(): void {
    this.state = this.createInitialState();
    memoryManager.clearMessages();
    this.currentIteration = 0;
    logger.info('Agent state reset');
  }

  setMaxIterations(max: number): void { this.maxIterations = max; }
  getToolRegistry(): ToolRegistry | null { return this.toolRegistry; }
}

export const agent = new Agent();