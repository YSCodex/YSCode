import { configManager } from '../config/index.js';
import { getLogger } from '../logger/index.js';
import { createProvider } from '../providers/index.js';
import { toolRegistry } from '../tools/index.js';
import { ToolRegistry } from '../tools/base.js';
import { memoryManager } from '../memory/index.js';
import { contextEngine } from '../context/index.js';
import { securityManager } from '../security/index.js';
import { sessionManager } from '../session/index.js';
import { fileSystem } from '../filesystem/index.js';
import { analyzeProject } from '../project/index.js';
import {
  AgentState, AgentMessage, PlanStep, ToolCall, ToolResult,
  ProviderResponse, ProviderStreamChunk, AgentStatus, ProviderConfig, ModelConfig
} from '../types.js';
import { generateId, countTokens } from '../utils/index.js';

const logger = getLogger('agent');

const SYSTEM_PROMPT = `You are YS Code Agent — a production-grade terminal AI coding assistant.
You have DIRECT access to the filesystem and terminal through tool calls.

## CRITICAL RULE — USE TOOLS, DON'T JUST TALK
When the user asks you to make, create, write, or build something — USE the write_file tool to actually create the file on disk.
When the user asks to read, show, or check something — USE the read_file tool.
When the user asks to run, execute, or test something — USE the run_command tool.
When the user asks to search or find something — USE the search_files tool.
NEVER just show code in chat without writing it to disk when the user asks to create something.

## Available Tools
- write_file: Write content to a file (creates/overwrites)
- read_file: Read file contents from disk  
- edit_file: Edit specific parts of a file
- delete_file: Delete a file
- run_command: Execute a shell command
- search_files: Search for text in files
- glob: Find files matching a pattern
- list_directory: List directory contents
- git: Execute git commands
- web_fetch: Fetch content from a URL
- memory: Read/write project memory

## Response Format
- Use clear section headers for complex responses
- Show file paths when referencing code
- Keep responses concise but complete
- After writing files, confirm with a summary of what was created
- Use markdown formatting in your responses`;

export class Agent {
  private state: AgentState;
  private provider: ReturnType<typeof createProvider> | null = null;
  private tools: ToolRegistry = toolRegistry;
  private maxIterations = 50;
  private currentIteration = 0;

  constructor() {
    this.state = this.createInitialState();
    this.initProvider();
  }

  private createInitialState(): AgentState {
    return {
      task: '',
      plan: [],
      currentStep: 0,
      messages: [
        { role: 'system', content: SYSTEM_PROMPT, timestamp: Date.now() },
      ],
      context: contextEngine.getContext(),
      memory: memoryManager.getContext(),
      status: 'idle',
      startTime: Date.now(),
    };
  }

  private initProvider(): void {
    const config = configManager.getConfig();
    const providerConfig = configManager.getActiveProvider();
    const modelConfig = configManager.getModelConfig();

    try {
      this.provider = createProvider(providerConfig, modelConfig);
      logger.info(`Initialized provider: ${providerConfig.type} with model ${modelConfig.model}`);
    } catch (error) {
      logger.error('Failed to initialize provider', error);
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

  private async think(): Promise<string> {
    const context = contextEngine.getReadableContext(this.state.task);
    const messages = [
      ...this.state.messages.slice(-10),
    ];

    const thoughtfulPrompt = `Current task: ${this.state.task}

Current iteration: ${this.currentIteration}/${this.maxIterations}

Context:
${context}

Think step by step about what needs to be done next.
What is the current state? What actions should you take? What tools should you use?
Be specific and concrete.`;

    messages.push({
      role: 'user',
      content: thoughtfulPrompt,
      timestamp: Date.now(),
    });

    try {
      const response = await this.generateWithRetry(messages);
      return response.content;
    } catch (error) {
      logger.error('Thinking failed', error);
      return 'I encountered an error while thinking. Let me try a simpler approach.';
    }
  }

  private async plan(thought: string): Promise<PlanStep[]> {
    const planPrompt = `Based on this analysis:
${thought}

Create a step-by-step plan. Each step should use one tool.

Available tools:
${this.tools.getToolNames().join(', ')}

Format each step as:
STEP: description
TOOL: tool_name
PARAMS: JSON parameters

Separate steps with blank lines.`;

    const messages = [
      { role: 'system', content: SYSTEM_PROMPT, timestamp: Date.now() },
      { role: 'user', content: planPrompt, timestamp: Date.now() },
    ];

    try {
      const response = await this.generateWithRetry(messages);
      return this.parsePlanSteps(response.content);
    } catch (error) {
      logger.error('Planning failed', error);
      return [];
    }
  }

  private parsePlanSteps(text: string): PlanStep[] {
    const steps: PlanStep[] = [];
    const blocks = text.split(/\n\n+/);

    for (const block of blocks) {
      const stepMatch = block.match(/STEP:\s*(.+)/);
      const toolMatch = block.match(/TOOL:\s*(\w+)/);
      const paramsMatch = block.match(/PARAMS:\s*(\{[\s\S]*?\})/);

      if (stepMatch && toolMatch) {
        let params: Record<string, unknown> = {};
        if (paramsMatch) {
          try {
            params = JSON.parse(paramsMatch[1]);
          } catch {
            params = {};
          }
        }

        steps.push({
          id: generateId(),
          description: stepMatch[1].trim(),
          action: toolMatch[1].trim(),
          tool: toolMatch[1].trim(),
          parameters: params,
          status: 'pending',
        });
      }
    }

    return steps;
  }

  private async executeStep(step: PlanStep): Promise<ToolResult> {
    const tool = this.tools.get(step.tool);
    if (!tool) {
      return {
        success: false,
        error: `Unknown tool: ${step.tool}. Available: ${this.tools.getToolNames().join(', ')}`,
      };
    }

    const permission = securityManager.isToolAllowed(step.tool, tool.getPermissions());
    if (!permission) {
      return {
        success: false,
        error: `Tool "${step.tool}" is not permitted in current security context`,
      };
    }

    const args = this.resolveParameters(step.parameters);

    const call: ToolCall = {
      id: generateId(),
      name: step.tool,
      arguments: args,
      timestamp: Date.now(),
    };

    this.state.status = 'executing';
    this.state.currentStep = this.state.plan.findIndex((s) => s.id === step.id);

    const result = await tool.executeWithLogging(call);

    const resultMessage: AgentMessage = {
      role: 'tool',
      content: JSON.stringify(result.data || { error: result.error }),
      toolResults: [result],
      toolCallId: call.id,
      timestamp: Date.now(),
    };
    this.state.messages.push(resultMessage);

    return result;
  }

  private resolveParameters(params: Record<string, unknown>): Record<string, unknown> {
    const resolved: Record<string, unknown> = {};

    for (const [key, value] of Object.entries(params)) {
      if (typeof value === 'string' && value.startsWith('$')) {
        const varName = value.slice(1);
        switch (varName) {
          case 'task':
            resolved[key] = this.state.task;
            break;
          case 'cwd':
            resolved[key] = process.cwd();
            break;
          default:
            resolved[key] = value;
        }
      } else {
        resolved[key] = value;
      }
    }

    return resolved;
  }

  private async analyzeResults(results: ToolResult[]): Promise<string> {
    const resultsSummary = results
      .map((r, i) => `Result ${i + 1}: ${r.success ? 'SUCCESS' : 'FAILED'}\n${r.data ? JSON.stringify(r.data).slice(0, 500) : r.error || ''}`)
      .join('\n\n');

    const analysisPrompt = `The following tool executions completed:

${resultsSummary}

Analyze these results:
1. Were they successful?
2. What did we learn?
3. Is the main task complete?
4. What should we do next?`;

    const messages = [
      { role: 'system', content: SYSTEM_PROMPT, timestamp: Date.now() },
      { role: 'user', content: analysisPrompt, timestamp: Date.now() },
    ];

    try {
      const response = await this.generateWithRetry(messages);
      return response.content;
    } catch (error) {
      logger.error('Analysis failed', error);
      return 'Analysis completed with errors.';
    }
  }

  private async fixErrors(errors: string[]): Promise<string> {
    const fixPrompt = `The following errors occurred:

${errors.join('\n')}

Analyze these errors and create a plan to fix them. What went wrong? How should we fix it?`;

    const messages = [
      { role: 'system', content: SYSTEM_PROMPT, timestamp: Date.now() },
      { role: 'user', content: fixPrompt, timestamp: Date.now() },
    ];

    try {
      const response = await this.generateWithRetry(messages);
      return response.content;
    } catch (error) {
      logger.error('Fix analysis failed', error);
      return 'Could not analyze errors automatically.';
    }
  }

  private isTaskComplete(): boolean {
    const lastMessages = this.state.messages.slice(-5);
    for (const msg of lastMessages) {
      if (msg.role === 'assistant') {
        const lower = msg.content.toLowerCase();
        if (
          lower.includes('task complete') ||
          lower.includes('done!') ||
          lower.includes('finished!') ||
          lower.includes('completed!')
        ) {
          return true;
        }
      }
    }
    return false;
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private async generateWithRetry(messages: any[], maxRetries = 3): Promise<ProviderResponse> {
    if (!this.provider) {
      this.initProvider();
      if (!this.provider) {
        throw new Error('No AI provider configured. Please set up a provider API key.');
      }
    }

    let lastError: Error | null = null;

    for (let attempt = 0; attempt < maxRetries; attempt++) {
      try {
        const tools = this.tools.getSchemas() as unknown as Record<string, unknown>[];
        const response = await this.provider.generate(
          messages as Array<{ role: string; content: string }>,
          tools
        );

        if (response.toolCalls && response.toolCalls.length > 0) {
          this.state.messages.push({
            role: 'assistant',
            content: response.content,
            toolCalls: response.toolCalls,
            timestamp: Date.now(),
          });

          for (const toolCall of response.toolCalls) {
            const result = await this.tools.executeToolCall(toolCall);
            this.state.messages.push({
              role: 'tool',
              content: JSON.stringify(result.data || { error: result.error }),
              toolResults: [result],
              toolCallId: toolCall.id,
              timestamp: Date.now(),
            });
          }

          return this.generateWithRetry(
            [
              { role: 'system', content: SYSTEM_PROMPT },
              ...this.state.messages.slice(-20),
            ],
            maxRetries - 1
          );
        }

        return response;
      } catch (error) {
        lastError = error instanceof Error ? error : new Error(String(error));
        logger.warn(`Generation attempt ${attempt + 1} failed`, { error: lastError.message });

        if (attempt < maxRetries - 1) {
          const delay = Math.min(1000 * Math.pow(2, attempt), 10000);
          await new Promise((resolve) => setTimeout(resolve, delay));
        }
      }
    }

    throw lastError || new Error('Generation failed after all retries');
  }

  async executeTask(task: string): Promise<AgentMessage> {
    this.state.task = task;
    this.state.startTime = Date.now();
    this.state.status = 'thinking';
    this.currentIteration = 0;

    this.state.messages.push({
      role: 'user',
      content: task,
      timestamp: Date.now(),
    });

    sessionManager.saveCurrentSession();

    try {
      const projectInfo = await analyzeProject();
      memoryManager.store('project_info', JSON.stringify(projectInfo), 'project');
      memoryManager.addSummary(JSON.stringify({ languages: projectInfo.languages, frameworks: projectInfo.frameworks }), 'project');
    } catch {
      logger.warn('Failed to analyze project');
    }

    while (this.currentIteration < this.maxIterations) {
      this.currentIteration++;
      this.state.status = 'thinking';

      const thought = await this.think();

      this.state.messages.push({
        role: 'assistant',
        content: `[Iteration ${this.currentIteration}] ${thought}`,
        timestamp: Date.now(),
      });

      this.state.status = 'planning';
      const planSteps = await this.plan(thought);

      if (planSteps.length === 0) {
        this.state.messages.push({
          role: 'assistant',
          content: 'I could not create a plan. Let me try a direct response.',
          timestamp: Date.now(),
        });

        try {
          const response = await this.generateWithRetry([
            { role: 'system', content: SYSTEM_PROMPT },
            ...this.state.messages.slice(-10),
          ]);

          const assistantMsg: AgentMessage = {
            role: 'assistant',
            content: response.content,
            timestamp: Date.now(),
          };

          this.state.messages.push(assistantMsg);
          this.state.status = 'completed';

          memoryManager.addTask(task, 'completed', Date.now() - this.state.startTime, response.content.slice(0, 200));
          sessionManager.saveCurrentSession();

          return assistantMsg;
        } catch (error) {
          return {
            role: 'assistant',
            content: `I encountered an error: ${error instanceof Error ? error.message : String(error)}`,
            timestamp: Date.now(),
          };
        }
      }

      this.state.plan = planSteps;
      this.state.status = 'executing';

      const results: ToolResult[] = [];
      const errors: string[] = [];

      for (const step of planSteps) {
        step.status = 'in_progress';
        sessionManager.saveCurrentSession();

        const result = await this.executeStep(step);

        if (result.success) {
          step.status = 'completed';
        } else {
          step.status = 'failed';
          errors.push(`Step "${step.description}": ${result.error}`);
        }

        results.push(result);
        step.result = result;
      }

      this.state.status = 'thinking';

      if (errors.length > 0) {
        const fixPlan = await this.fixErrors(errors);

        this.state.messages.push({
          role: 'assistant',
          content: `[Fix attempt] ${fixPlan}`,
          timestamp: Date.now(),
        });

        const fixSteps = await this.plan(fixPlan);
        for (const step of fixSteps) {
          step.status = 'in_progress';
          const result = await this.executeStep(step);
          step.status = result.success ? 'completed' : 'failed';
          results.push(result);
        }
      }

      if (this.isTaskComplete()) {
        break;
      }
    }

    this.state.status = 'completed';

    const finalMessages = this.state.messages.filter((m) => m.role === 'assistant');
    const finalResponse = finalMessages[finalMessages.length - 1] || {
      role: 'assistant' as const,
      content: 'Task completed. Check the above results for details.',
      timestamp: Date.now(),
    };

    const duration = Date.now() - this.state.startTime;
    memoryManager.addTask(this.state.task, this.currentIteration < this.maxIterations ? 'completed' : 'cancelled', duration);
    sessionManager.saveCurrentSession();

    return finalResponse;
  }

  async chat(message: string): Promise<AgentMessage> {
    this.state.messages.push({
      role: 'user',
      content: message,
      timestamp: Date.now(),
    });

    try {
      const response = await this.generateWithRetry([
        { role: 'system', content: SYSTEM_PROMPT },
        ...this.state.messages.slice(-20),
      ]);

      const assistantMsg: AgentMessage = {
        role: 'assistant',
        content: response.content,
        timestamp: Date.now(),
      };

      this.state.messages.push(assistantMsg);

      if (response.toolCalls && response.toolCalls.length > 0) {
        for (const toolCall of response.toolCalls) {
          const result = await this.tools.executeToolCall(toolCall);
          this.state.messages.push({
            role: 'tool',
            content: JSON.stringify(result.data || { error: result.error }),
            toolResults: [result],
            toolCallId: toolCall.id,
            timestamp: Date.now(),
          });
        }

        return this.chat('');
      }

      sessionManager.saveCurrentSession();
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

  getState(): AgentState {
    return { ...this.state };
  }

  getMessages(): AgentMessage[] {
    return [...this.state.messages];
  }

  reset(): void {
    this.state = this.createInitialState();
    memoryManager.clearMessages();
    this.currentIteration = 0;
    logger.info('Agent state reset');
  }

  setMaxIterations(max: number): void {
    this.maxIterations = max;
  }
}

export const agent = new Agent();
