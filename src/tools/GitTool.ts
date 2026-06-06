import { BaseTool } from './base.js';
import { ToolSchema, ToolResult } from '../types.js';
import { GitManager } from '../git/index.js';

export class GitTool extends BaseTool {
  private git: GitManager;

  constructor() {
    super();
    this.git = new GitManager();
  }

  protected defineSchema(): ToolSchema {
    return {
      name: 'git',
      description: 'Execute Git operations. Supports status, diff, commit, branch, checkout, log, and more.',
      parameters: {
        action: {
          type: 'string',
          description: 'Git action to perform',
          required: true,
          enum: ['status', 'diff', 'commit', 'branch', 'checkout', 'log', 'add', 'reset', 'stash', 'push', 'pull', 'fetch', 'merge', 'rebase', 'init', 'clone'],
        },
        args: {
          type: 'array',
          description: 'Additional arguments for the git command',
          required: false,
        },
        message: {
          type: 'string',
          description: 'Commit message (required for commit action)',
          required: false,
        },
        path: {
          type: 'string',
          description: 'File path for add/reset/checkout operations',
          required: false,
        },
      },
      required: ['action'],
      returns: 'Git command output',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const action = args.action as string;
    const message = args.message as string | undefined;
    const gitArgs = args.args as string[] | undefined;
    const path = args.path as string | undefined;

    try {
      let result: string;

      switch (action) {
        case 'status':
          result = await this.git.status();
          break;
        case 'diff':
          result = await this.git.diff(path);
          break;
        case 'commit':
          if (!message) {
            return { success: false, error: 'Commit message is required for commit action' };
          }
          result = await this.git.commit(message, gitArgs);
          break;
        case 'add':
          result = await this.git.add(path || '.');
          break;
        case 'branch':
          result = await this.git.branch(gitArgs);
          break;
        case 'checkout':
          result = await this.git.checkout(gitArgs?.[0] || path || '');
          break;
        case 'log':
          result = await this.git.log(gitArgs);
          break;
        case 'reset':
          result = await this.git.reset(gitArgs?.[0]);
          break;
        case 'stash':
          result = await this.git.stash(gitArgs);
          break;
        case 'push':
          result = await this.git.push(gitArgs);
          break;
        case 'pull':
          result = await this.git.pull(gitArgs);
          break;
        case 'fetch':
          result = await this.git.fetch(gitArgs);
          break;
        case 'merge':
          result = await this.git.merge(gitArgs?.[0] || '');
          break;
        case 'init':
          result = await this.git.init();
          break;
        case 'clone':
          result = await this.git.clone(gitArgs?.[0] || '', gitArgs?.[1]);
          break;
        default:
          return { success: false, error: `Unknown git action: ${action}` };
      }

      return {
        success: true,
        data: {
          action,
          output: result,
        },
      };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : String(error),
      };
    }
  }
}
