import { BaseTool } from './base.js';
import { ToolSchema, ToolResult } from '../types.js';
import { MemoryManager } from '../memory/index.js';

export class MemoryTool extends BaseTool {
  private memory: MemoryManager;

  constructor() {
    super();
    this.memory = new MemoryManager();
  }

  protected defineSchema(): ToolSchema {
    return {
      name: 'memory',
      description: 'Store and retrieve information in the agent memory system.',
      parameters: {
        action: {
          type: 'string',
          description: 'Memory action to perform',
          required: true,
          enum: ['store', 'retrieve', 'search', 'list', 'delete', 'summarize', 'clear'],
        },
        key: {
          type: 'string',
          description: 'Memory key or identifier',
          required: false,
        },
        value: {
          type: 'string',
          description: 'Value to store',
          required: false,
        },
        type: {
          type: 'string',
          description: 'Memory type (project, conversation, task, preference)',
          required: false,
          enum: ['project', 'conversation', 'task', 'preference'],
        },
        query: {
          type: 'string',
          description: 'Search query',
          required: false,
        },
      },
      required: ['action'],
      returns: 'Memory operation result',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const action = args.action as string;
    const key = args.key as string | undefined;
    const value = args.value as string | undefined;
    const type = (args.type as string) || 'conversation';
    const query = args.query as string | undefined;

    try {
      switch (action) {
        case 'store': {
          if (!key || !value) {
            return { success: false, error: 'key and value are required for store action' };
          }
          this.memory.store(key, value, type as 'project' | 'conversation' | 'task' | 'preference');
          return { success: true, data: { stored: key, type } };
        }

        case 'retrieve': {
          if (!key) {
            return { success: false, error: 'key is required for retrieve action' };
          }
          const result = this.memory.retrieve(key);
          if (result === null) {
            return { success: true, data: { key, found: false, value: null } };
          }
          return { success: true, data: { key, found: true, value: result.value, type: result.type } };
        }

        case 'search': {
          if (!query) {
            return { success: false, error: 'query is required for search action' };
          }
          const results = this.memory.search(query);
          return { success: true, data: { query, results: results.slice(0, 20) } };
        }

        case 'list': {
          const items = this.memory.list(type);
          return { success: true, data: { type, items: items.slice(0, 50) } };
        }

        case 'delete': {
          if (!key) {
            return { success: false, error: 'key is required for delete action' };
          }
          this.memory.delete(key);
          return { success: true, data: { deleted: key } };
        }

        case 'summarize': {
          const summary = this.memory.getSessionSummary();
          return { success: true, data: { summary } };
        }

        case 'clear': {
          this.memory.clear();
          return { success: true, data: { cleared: true } };
        }

        default:
          return { success: false, error: `Unknown memory action: ${action}` };
      }
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error.message : String(error),
      };
    }
  }
}
