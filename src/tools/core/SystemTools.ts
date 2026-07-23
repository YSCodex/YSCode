import { BaseDeclarativeTool, BaseToolInvocation } from '../declarative/ToolTypes';
import type {
  ToolResult,
  ToolInvocation,
  ToolResultDisplay,
  ToolKind,
  ShellExecutionConfig,
} from '../declarative/ToolTypes';
import { spawn, exec } from 'child_process';
import * as path from 'path';

interface TerminalParams {
  command: string;
  cwd?: string;
  timeout?: number;
  env?: Record<string, string>;
  [key: string]: unknown;
}

interface TerminalResult extends ToolResult {
  stdout: string;
  stderr: string;
  exitCode: number;
}

class TerminalInvocation extends BaseToolInvocation<TerminalParams, TerminalResult> {
  getDescription(): string {
    return `Execute: ${this.params.command}`;
  }

  async execute(
    signal: AbortSignal,
    updateOutput?: (output: ToolResultDisplay) => void,
    shellExecutionConfig?: ShellExecutionConfig
  ): Promise<TerminalResult> {
    return new Promise((resolve) => {
      const cwd = this.params.cwd || shellExecutionConfig?.cwd || process.cwd();
      const env = { ...process.env, ...this.params.env, ...shellExecutionConfig?.env };
      const timeout = this.params.timeout || shellExecutionConfig?.timeout || 30000;

      const child = spawn(this.params.command, { cwd, env, shell: true, stdio: ['pipe', 'pipe', 'pipe'] });
      let stdout = '';
      let stderr = '';
      let timedOut = false;

      const timeoutHandle = setTimeout(() => { timedOut = true; child.kill('SIGTERM'); }, timeout);
      signal.addEventListener('abort', () => child.kill('SIGTERM'));

      child.stdout.on('data', (data) => { stdout += data.toString(); updateOutput?.(data.toString()); });
      child.stderr.on('data', (data) => { stderr += data.toString(); });

      child.on('close', (code) => {
        clearTimeout(timeoutHandle);
        if (timedOut) {
          resolve({
            success: false, llmContent: `Command timed out after ${timeout}ms`, returnDisplay: `Timed out: ${this.params.command}`,
            stdout, stderr, exitCode: -1, error: { message: `Command timed out after ${timeout}ms`, type: 'TIMEOUT' },
          });
        } else {
          const display = stdout + (stderr ? `\n${stderr}` : '');
          resolve({ success: true, llmContent: display, returnDisplay: display, stdout, stderr, exitCode: code ?? 0 });
        }
      });

      child.on('error', (error) => {
        clearTimeout(timeoutHandle);
        resolve({ success: false, llmContent: `Error: ${error.message}`, returnDisplay: error.message, stdout, stderr, exitCode: -1, error: { message: error.message, type: 'EXECUTION_FAILED' } });
      });
    });
  }
}

export class TerminalTool extends BaseDeclarativeTool<TerminalParams, TerminalResult> {
  constructor() {
    super(
      'terminal', 'Terminal', 'Executes a shell command.',
      'shell' as ToolKind,
      { type: 'object', properties: { command: { type: 'string', description: 'The command to execute.' }, cwd: { type: 'string', description: 'The working directory.' }, timeout: { type: 'integer', description: 'Timeout in milliseconds.' }, env: { type: 'object', description: 'Environment variables.' } }, required: ['command'] },
      true, true, false, true, 'run command'
    );
  }

  protected createInvocation(params: TerminalParams): ToolInvocation<TerminalParams, TerminalResult> {
    return new TerminalInvocation(params);
  }
}

interface GitParams { command: string; args?: string[]; cwd?: string; [key: string]: unknown; }
interface GitResult extends ToolResult { stdout: string; stderr: string; exitCode: number; }

class GitInvocation extends BaseToolInvocation<GitParams, GitResult> {
  getDescription(): string { return `Git: ${this.params.command} ${this.params.args?.join(' ') || ''}`; }

  async execute(_signal: AbortSignal): Promise<GitResult> {
    return new Promise((resolve) => {
      const cwd = this.params.cwd || process.cwd();
      const cmd = `git ${this.params.command} ${(this.params.args || []).map(a => `"${a}"`).join(' ')}`;
      exec(cmd, { cwd }, (error, stdout, stderr) => {
        resolve({
          success: !error, llmContent: stdout || stderr, returnDisplay: stdout || stderr, stdout, stderr, exitCode: error ? error.code || 1 : 0,
          ...(error ? { error: { message: error.message, type: 'EXECUTION_FAILED' as const } } : {}),
        });
      });
    });
  }
}

export class GitTool extends BaseDeclarativeTool<GitParams, GitResult> {
  constructor() {
    super('git', 'Git', 'Executes a git command.', 'git' as ToolKind, { type: 'object', properties: { command: { type: 'string', description: 'The git command to execute.' }, args: { type: 'array', items: { type: 'string' }, description: 'Arguments.' }, cwd: { type: 'string', description: 'Working directory.' } }, required: ['command'] }, true, false, false, true, 'git command');
  }
  protected createInvocation(params: GitParams): ToolInvocation<GitParams, GitResult> { return new GitInvocation(params); }
}

interface WebFetchParams { url: string; format?: 'markdown' | 'text' | 'html'; timeout?: number; [key: string]: unknown; }
interface WebFetchResult extends ToolResult { content: string; mimeType?: string; }

class WebFetchInvocation extends BaseToolInvocation<WebFetchParams, WebFetchResult> {
  getDescription(): string { return `Fetch: ${this.params.url}`; }

  async execute(signal: AbortSignal): Promise<WebFetchResult> {
    try {
      const timeout = this.params.timeout || 30000;
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), timeout);
      signal.addEventListener('abort', () => controller.abort());
      const response = await fetch(this.params.url, { signal: controller.signal });
      clearTimeout(timeoutId);
      if (!response.ok) return { success: false, llmContent: `HTTP ${response.status}`, returnDisplay: `Error: ${response.status}`, content: '', error: { message: `HTTP ${response.status}`, type: 'EXECUTION_FAILED' } };
      const content = await response.text();
      return { success: true, llmContent: content, returnDisplay: content, content, mimeType: response.headers.get('content-type') || '' };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return { success: false, llmContent: `Error fetching URL: ${errorMessage}`, returnDisplay: errorMessage, content: '', error: { message: errorMessage, type: 'EXECUTION_FAILED' } };
    }
  }
}

export class WebFetchTool extends BaseDeclarativeTool<WebFetchParams, WebFetchResult> {
  constructor() {
    super('web_fetch', 'Web Fetch', 'Fetches content from a URL.', 'web' as ToolKind, { type: 'object', properties: { url: { type: 'string', description: 'The URL to fetch.' }, format: { type: 'string', enum: ['markdown', 'text', 'html'] }, timeout: { type: 'integer' } }, required: ['url'] }, true, false, false, true, 'fetch URL');
  }
  protected createInvocation(params: WebFetchParams): ToolInvocation<WebFetchParams, WebFetchResult> { return new WebFetchInvocation(params); }
}

interface MemoryParams { key: string; value?: string; action: 'get' | 'set' | 'delete' | 'list'; [key: string]: unknown; }
interface MemoryResult extends ToolResult { data?: Record<string, string> | string | null; }

class MemoryInvocation extends BaseToolInvocation<MemoryParams, MemoryResult> {
  getDescription(): string { return `Memory ${this.params.action}: ${this.params.key}`; }

  async execute(_signal: AbortSignal): Promise<MemoryResult> {
    try {
      const memoryPath = path.join(process.cwd(), '.ys-agent-memory.json');
      let memory: Record<string, string> = {};
      try { const data = await import('fs').then(fs => fs.promises.readFile(memoryPath, 'utf-8')); memory = JSON.parse(data); } catch { memory = {}; }

      switch (this.params.action) {
        case 'get': {
          const value = memory[this.params.key];
          return { success: true, llmContent: value ?? 'Key not found', returnDisplay: value ?? 'Key not found', data: value ?? null };
        }
        case 'set': {
          if (!this.params.value) return { success: false, llmContent: 'Value is required', returnDisplay: 'Value required', error: { message: 'Value required', type: 'INVALID_TOOL_PARAMS' } };
          memory[this.params.key] = this.params.value;
          const { promises: fs } = await import('fs');
          await fs.writeFile(memoryPath, JSON.stringify(memory, null, 2), 'utf-8');
          return { success: true, llmContent: `Memory set: ${this.params.key}`, returnDisplay: `Set: ${this.params.key}`, data: memory };
        }
        case 'delete': {
          delete memory[this.params.key];
          const { promises: fs } = await import('fs');
          await fs.writeFile(memoryPath, JSON.stringify(memory, null, 2), 'utf-8');
          return { success: true, llmContent: `Memory deleted: ${this.params.key}`, returnDisplay: `Deleted: ${this.params.key}`, data: memory };
        }
        case 'list':
          return { success: true, llmContent: JSON.stringify(memory, null, 2), returnDisplay: Object.keys(memory).join(', '), data: memory };
        default:
          return { success: false, llmContent: `Unknown action: ${this.params.action}`, returnDisplay: 'Unknown action', error: { message: `Unknown action: ${this.params.action}`, type: 'INVALID_TOOL_PARAMS' } };
      }
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return { success: false, llmContent: `Error: ${errorMessage}`, returnDisplay: errorMessage, error: { message: errorMessage, type: 'EXECUTION_FAILED' } };
    }
  }
}

export class MemoryTool extends BaseDeclarativeTool<MemoryParams, MemoryResult> {
  constructor() {
    super('memory', 'Memory', 'Stores and retrieves memory.', 'memory' as ToolKind, { type: 'object', properties: { key: { type: 'string', description: 'The memory key.' }, value: { type: 'string', description: 'The value to set.' }, action: { type: 'string', enum: ['get', 'set', 'delete', 'list'], description: 'The action to perform.' } }, required: ['key', 'action'] }, true, false, false, true, 'store memory');
  }
  protected createInvocation(params: MemoryParams): ToolInvocation<MemoryParams, MemoryResult> { return new MemoryInvocation(params); }
}