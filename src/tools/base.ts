import { ToolSchema, ToolResult, ToolCall, ToolPermissions, ToolPermission } from '../types.js';
import { getLogger } from '../logger/index.js';
import { generateId } from '../utils/index.js';

export abstract class BaseTool {
  protected schema: ToolSchema;
  protected logger = getLogger(`tool:${this.constructor.name}`);
  protected permissions: ToolPermission[] = [];

  constructor() {
    this.schema = this.defineSchema();
  }

  protected abstract defineSchema(): ToolSchema;
  abstract execute(args: Record<string, unknown>): Promise<ToolResult>;

  getName(): string {
    return this.schema.name;
  }

  getDescription(): string {
    return this.schema.description;
  }

  getSchema(): ToolSchema {
    return { ...this.schema };
  }

  getPermissions(): ToolPermission[] {
    return [...this.permissions];
  }

  validateArgs(args: Record<string, unknown>): { valid: boolean; errors: string[] } {
    const errors: string[] = [];

    for (const required of this.schema.required) {
      if (args[required] === undefined || args[required] === null || args[required] === '') {
        errors.push(`Missing required parameter: ${required}`);
      }
    }

    for (const [key, value] of Object.entries(args)) {
      const param = this.schema.parameters[key];
      if (!param) continue;

      if (param.enum && !param.enum.includes(value as string)) {
        errors.push(`Invalid value for ${key}: must be one of ${param.enum.join(', ')}`);
      }

      if (param.type === 'string' && typeof value !== 'string') {
        errors.push(`Parameter ${key} must be a string, got ${typeof value}`);
      } else if (param.type === 'number' && typeof value !== 'number') {
        errors.push(`Parameter ${key} must be a number, got ${typeof value}`);
      } else if (param.type === 'boolean' && typeof value !== 'boolean') {
        errors.push(`Parameter ${key} must be a boolean, got ${typeof value}`);
      } else if (param.type === 'array' && !Array.isArray(value)) {
        errors.push(`Parameter ${key} must be an array, got ${typeof value}`);
      } else if (param.type === 'object' && (typeof value !== 'object' || value === null || Array.isArray(value))) {
        errors.push(`Parameter ${key} must be an object, got ${typeof value}`);
      }

      if (param.type === 'number' && typeof value === 'number') {
        if (param.minimum !== undefined && value < param.minimum) {
          errors.push(`Parameter ${key} must be >= ${param.minimum}`);
        }
        if (param.maximum !== undefined && value > param.maximum) {
          errors.push(`Parameter ${key} must be <= ${param.maximum}`);
        }
      }
    }

    return { valid: errors.length === 0, errors };
  }

  async executeWithLogging(call: ToolCall): Promise<ToolResult> {
    const startTime = Date.now();
    this.logger.info(`Executing: ${call.name}`, { args: call.arguments });

    try {
      const validation = this.validateArgs(call.arguments);
      if (!validation.valid) {
        this.logger.error(`Validation failed: ${validation.errors.join(', ')}`);
        return {
          success: false,
          error: `Validation errors: ${validation.errors.join('; ')}`,
        };
      }

      const result = await this.execute(call.arguments);
      const duration = Date.now() - startTime;

      this.logger.info(`Completed: ${call.name}`, { duration, success: result.success });

      return {
        ...result,
        metadata: {
          ...result.metadata,
          duration,
          toolName: call.name,
          toolId: call.id,
        },
      };
    } catch (error) {
      const duration = Date.now() - startTime;
      const message = error instanceof Error ? error.message : String(error);
      this.logger.error(`Failed: ${call.name}`, { error: message, duration });

      return {
        success: false,
        error: message,
        metadata: { duration, toolName: call.name, toolId: call.id },
      };
    }
  }
}

export class ToolRegistry {
  private tools: Map<string, BaseTool> = new Map();
  private logger = getLogger('tool-registry');

  register(tool: BaseTool): void {
    const name = tool.getName();
    if (this.tools.has(name)) {
      this.logger.warn(`Overwriting tool: ${name}`);
    }
    this.tools.set(name, tool);
    this.logger.info(`Registered tool: ${name}`);
  }

  registerAll(tools: BaseTool[]): void {
    for (const tool of tools) {
      this.register(tool);
    }
  }

  get(name: string): BaseTool | undefined {
    return this.tools.get(name);
  }

  has(name: string): boolean {
    return this.tools.has(name);
  }

  getAll(): BaseTool[] {
    return [...this.tools.values()];
  }

  getSchemas(): ToolSchema[] {
    return this.getAll().map((t) => t.getSchema());
  }

  unregister(name: string): boolean {
    return this.tools.delete(name);
  }

  getToolNames(): string[] {
    return [...this.tools.keys()];
  }

  async executeToolCall(call: ToolCall): Promise<ToolResult> {
    const tool = this.get(call.name);
    if (!tool) {
      return {
        success: false,
        error: `Tool not found: ${call.name}. Available tools: ${this.getToolNames().join(', ')}`,
      };
    }

    return tool.executeWithLogging(call);
  }

  async executeToolCalls(calls: ToolCall[]): Promise<Map<string, ToolResult>> {
    const results = new Map<string, ToolResult>();

    for (const call of calls) {
      const result = await this.executeToolCall(call);
      results.set(call.id, result);
    }

    return results;
  }

  getToolPermissions(name: string): ToolPermission[] {
    const tool = this.get(name);
    return tool ? tool.getPermissions() : [];
  }

  clear(): void {
    this.tools.clear();
  }
}

export const toolRegistry = new ToolRegistry();
