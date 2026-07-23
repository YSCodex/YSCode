import { EventEmitter } from 'events';
import type {
  AnyDeclarativeTool,
  ToolResult,
  ToolInvocation,
  ToolResultDisplay,
  ShellExecutionConfig,
  ToolConfirmationOutcome,
} from '../../tools/declarative/ToolTypes.js';
import type { ToolRegistry } from '../../tools/declarative/ToolRegistry.js';
import type { Config } from '../../config/index.js';
import { debugLogger } from '../../utils/debugLogger.js';

export type AgentEventType =
  | 'agent:start'
  | 'agent:stop'
  | 'agent:thinking'
  | 'agent:message'
  | 'agent:tool_call'
  | 'agent:tool_result'
  | 'agent:error'
  | 'agent:turn_start'
  | 'agent:turn_end'
  | 'agent:handoff';

export interface AgentEvent {
  type: AgentEventType;
  data: unknown;
  timestamp: number;
}

export interface AgentMessage {
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  toolCallId?: string;
  toolName?: string;
  timestamp: number;
}

export interface ToolCall {
  id: string;
  name: string;
  params: Record<string, unknown>;
  status: 'pending' | 'running' | 'completed' | 'failed' | 'cancelled';
  result?: ToolResult;
  startTime?: number;
  endTime?: number;
}

export interface AgentState {
  status: 'idle' | 'thinking' | 'tool_execution' | 'waiting_for_confirmation' | 'error';
  currentTurn: number;
  messageHistory: AgentMessage[];
  pendingToolCalls: ToolCall[];
  completedToolCalls: ToolCall[];
  context: Record<string, unknown>;
  startTime?: number;
  endTime?: number;
}

export interface AgentConfig {
  maxTurns?: number;
  maxToolCallsPerTurn?: number;
  toolTimeout?: number;
  enableStreaming?: boolean;
  enableHistory?: boolean;
  maxHistoryLength?: number;
  autoConfirm?: boolean;
  confirmDangerousTools?: boolean;
}

export interface AgentCoreOptions {
  toolRegistry: ToolRegistry;
  config: Config;
  agentConfig?: AgentConfig;
}

export class AgentCore extends EventEmitter {
  private toolRegistry: ToolRegistry;
  private config: Config;
  private agentConfig: Required<AgentConfig>;
  private state: AgentState;
  private abortController: AbortController | null = null;
  private turnCount: number = 0;

  constructor(options: AgentCoreOptions) {
    super();
    this.toolRegistry = options.toolRegistry;
    this.config = options.config;
    this.agentConfig = {
      maxTurns: options.agentConfig?.maxTurns ?? 10,
      maxToolCallsPerTurn: options.agentConfig?.maxToolCallsPerTurn ?? 10,
      toolTimeout: options.agentConfig?.toolTimeout ?? 30000,
      enableStreaming: options.agentConfig?.enableStreaming ?? true,
      enableHistory: options.agentConfig?.enableHistory ?? true,
      maxHistoryLength: options.agentConfig?.maxHistoryLength ?? 100,
      autoConfirm: options.agentConfig?.autoConfirm ?? false,
      confirmDangerousTools: options.agentConfig?.confirmDangerousTools ?? true,
    };

    this.state = {
      status: 'idle',
      currentTurn: 0,
      messageHistory: [],
      pendingToolCalls: [],
      completedToolCalls: [],
      context: {},
    };
  }

  getConfig(): Config {
    return this.config;
  }

  getAgentConfig(): Required<AgentConfig> {
    return this.agentConfig;
  }

  getState(): AgentState {
    return { ...this.state, messageHistory: [...this.state.messageHistory] };
  }

  updateConfig(config: Partial<AgentConfig>): void {
    this.agentConfig = { ...this.agentConfig, ...config };
  }

  async start(): Promise<void> {
    this.abortController = new AbortController();
    this.state.status = 'idle';
    this.state.startTime = Date.now();
    this.emit('agent:start', this.state);
    debugLogger.info('Agent started');
  }

  async stop(): Promise<void> {
    if (this.abortController) {
      this.abortController.abort();
      this.abortController = null;
    }
    this.state.status = 'idle';
    this.state.endTime = Date.now();
    this.emit('agent:stop', this.state);
    debugLogger.info('Agent stopped');
  }

  async processMessage(message: string, attachments?: unknown[]): Promise<void> {
    if (this.state.status !== 'idle') {
      throw new Error(`Agent is busy (status: ${this.state.status})`);
    }

    this.state.status = 'thinking';
    this.state.currentTurn++;
    this.state.startTime = Date.now();

    this.addMessage({
      role: 'user',
      content: message,
      timestamp: Date.now(),
    });

    this.emit('agent:thinking', { message, turn: this.state.currentTurn });

    try {
      await this.runTurn(message, attachments);
    } catch (error) {
      this.handleError(error);
    } finally {
      this.state.status = 'idle';
      this.state.endTime = Date.now();
      this.emit('agent:turn_end', this.state);
    }
  }

  private async runTurn(message: string, _attachments?: unknown[]): Promise<void> {
    this.emit('agent:turn_start', { turn: this.state.currentTurn });

    let turnComplete = false;
    let iterations = 0;
    const maxIterations = this.agentConfig.maxToolCallsPerTurn;

    while (!turnComplete && iterations < maxIterations) {
      iterations++;

      if (this.abortController?.signal.aborted) {
        this.state.status = 'idle';
        return;
      }

      const response = await this.generateResponse(message);

      if (response.toolCalls && response.toolCalls.length > 0) {
        this.state.status = 'tool_execution';
        await this.executeToolCalls(response.toolCalls);
      } else {
        this.addMessage({
          role: 'assistant',
          content: response.content,
          timestamp: Date.now(),
        });
        turnComplete = true;
      }
    }
  }

  private async generateResponse(_message: string): Promise<{ content: string; toolCalls?: ToolCall[] }> {
    return {
      content: 'I am processing your request.',
      toolCalls: [],
    };
  }

  private async executeToolCalls(toolCalls: ToolCall[]): Promise<void> {
    const executionPromises = toolCalls.map(async (call) => {
      call.status = 'running';
      call.startTime = Date.now();
      this.state.pendingToolCalls.push(call);

      this.emit('agent:tool_call', call);

      try {
        const tool = this.toolRegistry.getTool(call.name);
        if (!tool) {
          call.result = {
            success: false,
            llmContent: `Tool "${call.name}" not found`,
            returnDisplay: `Tool not found: ${call.name}`,
            error: {
              message: `Tool "${call.name}" not found`,
              type: 'TOOL_NOT_FOUND',
            },
          };
          call.status = 'failed';
        } else {
          const invocation = tool.build(call.params as Record<string, unknown>);
          const result = await invocation.execute(
            this.abortController?.signal ?? new AbortSignal(),
            undefined,
            { timeout: this.agentConfig.toolTimeout }
          );
          call.result = result;
          call.status = result.error ? 'failed' : 'completed';
          this.emit('agent:tool_result', { call, result });
        }
      } catch (error) {
        call.result = {
          success: false,
          llmContent: `Error: ${error instanceof Error ? error.message : String(error)}`,
          returnDisplay: `Error: ${error instanceof Error ? error.message : String(error)}`,
          error: {
            message: error instanceof Error ? error.message : String(error),
            type: 'EXECUTION_FAILED',
          },
        };
        call.status = 'failed';
        this.emit('agent:error', { call, error });
      } finally {
        call.endTime = Date.now();
        this.state.pendingToolCalls = this.state.pendingToolCalls.filter(
          (c) => c.id !== call.id
        );
        this.state.completedToolCalls.push(call);
      }
    });

    await Promise.all(executionPromises);
  }

  private addMessage(message: AgentMessage): void {
    this.state.messageHistory.push(message);
    if (this.state.messageHistory.length > this.agentConfig.maxHistoryLength) {
      this.state.messageHistory.shift();
    }
    this.emit('agent:message', message);
  }

  private handleError(error: unknown): void {
    const errorMessage = error instanceof Error ? error.message : String(error);
    debugLogger.error('Agent error:', error);
    this.emit('agent:error', { error: errorMessage, state: this.state });
  }

  async confirmToolUse(
    _toolName: string,
    _params: Record<string, unknown>
  ): Promise<ToolConfirmationOutcome> {
    if (this.agentConfig.autoConfirm) {
      return { decision: 'proceed' };
    }
    return { decision: 'proceed' };
  }

  getContext(): Record<string, unknown> {
    return this.state.context;
  }

  setContext(key: string, value: unknown): void {
    this.state.context[key] = value;
  }

  getHistory(): AgentMessage[] {
    return [...this.state.messageHistory];
  }

  clearHistory(): void {
    this.state.messageHistory = [];
    this.state.pendingToolCalls = [];
    this.state.completedToolCalls = [];
    this.state.context = {};
  }

  getStats(): {
    totalTurns: number;
    totalToolCalls: number;
    successfulToolCalls: number;
    failedToolCalls: number;
  } {
    const successful = this.state.completedToolCalls.filter(
      (c) => c.status === 'completed'
    ).length;
    const failed = this.state.completedToolCalls.filter(
      (c) => c.status === 'failed'
    ).length;

    return {
      totalTurns: this.state.currentTurn,
      totalToolCalls: this.state.completedToolCalls.length,
      successfulToolCalls: successful,
      failedToolCalls: failed,
    };
  }
}