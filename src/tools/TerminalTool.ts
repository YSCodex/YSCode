import { BaseTool } from './base.js';
import { ToolSchema, ToolResult, CommandResult } from '../types.js';
import { TerminalExecutor } from '../terminal/index.js';
import { getLogger } from '../logger/index.js';

const logger = getLogger('tool:terminal');

export class TerminalTool extends BaseTool {
  private executor: TerminalExecutor;

  constructor() {
    super();
    this.executor = new TerminalExecutor();
  }

  protected defineSchema(): ToolSchema {
    return {
      name: 'terminal',
      description: 'Execute shell commands in the project directory. Returns stdout, stderr, and exit code.',
      parameters: {
        command: {
          type: 'string',
          description: 'Shell command to execute',
          required: true,
        },
        description: {
          type: 'string',
          description: 'Description of what the command does',
          required: false,
        },
        timeout: {
          type: 'number',
          description: 'Command timeout in milliseconds',
          required: false,
          default: 30000,
          minimum: 1000,
          maximum: 300000,
        },
        workdir: {
          type: 'string',
          description: 'Working directory for command execution',
          required: false,
        },
      },
      required: ['command'],
      returns: 'Command output with stdout, stderr, exit code, and duration',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const command = args.command as string;
    const timeout = (args.timeout as number) || 30000;
    const workdir = args.workdir as string | undefined;

    if (!command || command.trim().length === 0) {
      return {
        success: false,
        error: 'Command cannot be empty',
      };
    }

    try {
      const result: CommandResult = await this.executor.execute(command, {
        timeout,
        workdir,
      });

      return {
        success: result.exitCode === 0,
        data: {
          command: result.command,
          stdout: result.stdout,
          stderr: result.stderr,
          exit_code: result.exitCode,
          duration: result.duration,
          cancelled: result.cancelled,
        },
        metadata: {
          exitCode: result.exitCode,
          duration: result.duration,
        },
      };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      logger.error(`Command failed: ${command}`, { error: message });

      return {
        success: false,
        error: message,
        data: {
          command,
          stdout: '',
          stderr: message,
          exit_code: -1,
          duration: 0,
          cancelled: false,
        },
      };
    }
  }
}
