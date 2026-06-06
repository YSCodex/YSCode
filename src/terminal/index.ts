import { spawn, ChildProcess, execSync } from 'child_process';
import { resolve } from 'path';
import { getLogger } from '../logger/index.js';
import { configManager } from '../config/index.js';
import { CommandResult } from '../types.js';
import { isDangerousCommand } from '../utils/index.js';

const logger = getLogger('terminal');

interface ExecuteOptions {
  timeout?: number;
  workdir?: string;
  env?: Record<string, string>;
}

export class TerminalExecutor {
  private activeProcesses: Map<string, ChildProcess> = new Map();
  private basePath: string;

  constructor(basePath?: string) {
    this.basePath = basePath || process.cwd();
  }

  async execute(command: string, options?: ExecuteOptions): Promise<CommandResult> {
    const timeout = options?.timeout || 30000;
    const workdir = options?.workdir || this.basePath;
    const startTime = Date.now();

    const config = configManager.getConfig();
    if (config.security.dangerousCommandDetection && isDangerousCommand(command)) {
      logger.warn(`Dangerous command detected and blocked: ${command}`);
      return {
        command,
        stdout: '',
        stderr: 'This command was blocked by the security system as potentially dangerous.',
        exitCode: -1,
        duration: 0,
        cancelled: true,
      };
    }

    if (command.length > config.security.maxCommandLength) {
      return {
        command,
        stdout: '',
        stderr: `Command exceeds maximum length of ${config.security.maxCommandLength} characters.`,
        exitCode: -1,
        duration: 0,
        cancelled: true,
      };
    }

    logger.info(`Executing: ${command}`, { workdir, timeout });

    return new Promise((resolvePromise) => {
      const resolvedDir = resolve(workdir);

      const child = spawn('bash', ['-c', command], {
        cwd: resolvedDir,
        env: {
          ...process.env,
          ...options?.env,
        },
        stdio: ['pipe', 'pipe', 'pipe'],
        shell: false,
      });

      const processId = `proc_${Date.now()}`;
      this.activeProcesses.set(processId, child);

      let stdout = '';
      let stderr = '';
      let cancelled = false;

      const timeoutId = setTimeout(() => {
        cancelled = true;
        this.kill(processId);
        logger.warn(`Command timed out after ${timeout}ms: ${command}`);
      }, timeout);

      child.stdout?.on('data', (data: Buffer) => {
        stdout += data.toString();
      });

      child.stderr?.on('data', (data: Buffer) => {
        stderr += data.toString();
      });

      child.on('error', (error) => {
        clearTimeout(timeoutId);
        this.activeProcesses.delete(processId);
        resolvePromise({
          command,
          stdout,
          stderr: error.message,
          exitCode: -1,
          duration: Date.now() - startTime,
          cancelled: false,
        });
      });

      child.on('close', (exitCode) => {
        clearTimeout(timeoutId);
        this.activeProcesses.delete(processId);
        resolvePromise({
          command,
          stdout,
          stderr,
          exitCode: exitCode ?? -1,
          duration: Date.now() - startTime,
          cancelled,
        });
      });
    });
  }

  executeSync(command: string, options?: { workdir?: string }): string {
    const workdir = options?.workdir || this.basePath;
    try {
      return execSync(command, {
        cwd: resolve(workdir),
        encoding: 'utf-8',
        maxBuffer: 10 * 1024 * 1024,
      }).trim();
    } catch (error) {
      if (error instanceof Error) {
        return error.message;
      }
      return String(error);
    }
  }

  async executeStream(
    command: string,
    callbacks: {
      onStdout?: (data: string) => void;
      onStderr?: (data: string) => void;
      onExit?: (code: number) => void;
    },
    options?: ExecuteOptions
  ): Promise<CommandResult> {
    const timeout = options?.timeout || 30000;
    const workdir = options?.workdir || this.basePath;
    const startTime = Date.now();
    const resolvedDir = resolve(workdir);

    return new Promise((resolvePromise) => {
      const child = spawn('bash', ['-c', command], {
        cwd: resolvedDir,
        env: { ...process.env, ...options?.env },
        stdio: ['pipe', 'pipe', 'pipe'],
        shell: false,
      });

      const processId = `proc_stream_${Date.now()}`;
      this.activeProcesses.set(processId, child);

      let stdout = '';
      let stderr = '';
      let cancelled = false;

      const timeoutId = setTimeout(() => {
        cancelled = true;
        this.kill(processId);
      }, timeout);

      child.stdout?.on('data', (data: Buffer) => {
        const chunk = data.toString();
        stdout += chunk;
        callbacks.onStdout?.(chunk);
      });

      child.stderr?.on('data', (data: Buffer) => {
        const chunk = data.toString();
        stderr += chunk;
        callbacks.onStderr?.(chunk);
      });

      child.on('close', (exitCode) => {
        clearTimeout(timeoutId);
        this.activeProcesses.delete(processId);
        callbacks.onExit?.(exitCode ?? -1);
        resolvePromise({
          command,
          stdout,
          stderr,
          exitCode: exitCode ?? -1,
          duration: Date.now() - startTime,
          cancelled,
        });
      });
    });
  }

  kill(processId: string): boolean {
    const child = this.activeProcesses.get(processId);
    if (child) {
      try {
        treeKill(child.pid!);
        this.activeProcesses.delete(processId);
        return true;
      } catch {
        try {
          child.kill('SIGKILL');
          this.activeProcesses.delete(processId);
          return true;
        } catch {
          return false;
        }
      }
    }
    return false;
  }

  killAll(): void {
    for (const [id] of this.activeProcesses) {
      this.kill(id);
    }
  }

  getActiveProcesses(): number {
    return this.activeProcesses.size;
  }

  setBasePath(basePath: string): void {
    this.basePath = basePath;
  }
}

function treeKill(pid: number): void {
  try {
    const result = execSync(`kill -15 ${pid} 2>/dev/null; sleep 0.5; kill -9 ${pid} 2>/dev/null`, {
      timeout: 2000,
      encoding: 'utf-8',
    });
  } catch {
    try {
      process.kill(pid, 'SIGTERM');
      setTimeout(() => {
        try {
          process.kill(pid, 'SIGKILL');
        } catch {
        }
      }, 500);
    } catch {
    }
  }
}

export const terminalExecutor = new TerminalExecutor();
