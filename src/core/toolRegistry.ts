import { AnyDeclarativeTool, ToolFactory, ToolResult } from './types.js';

export class ToolRegistry {
  private tools: Map<string, AnyDeclarativeTool> = new Map();
  private factories: Map<string, ToolFactory> = new Map();
  private inflight: Map<string, Promise<AnyDeclarativeTool>> = new Map();

  registerTool(tool: AnyDeclarativeTool): void {
    this.tools.set(tool.name, tool);
    this.factories.delete(tool.name);
  }

  registerFactory(name: string, factory: ToolFactory): void {
    if (!this.tools.has(name)) {
      this.factories.set(name, factory);
    }
  }

  async ensureTool(name: string): Promise<AnyDeclarativeTool | undefined> {
    const cached = this.tools.get(name);
    if (cached) {
      this.factories.delete(name);
      return cached;
    }
    const existing = this.inflight.get(name);
    if (existing) return existing;
    const factory = this.factories.get(name);
    if (!factory) return undefined;
    const load = factory()
      .then((tool) => {
        this.tools.set(name, tool);
        this.factories.delete(name);
        this.inflight.delete(name);
        return tool;
      })
      .catch((err) => {
        this.inflight.delete(name);
        throw err;
      });
    this.inflight.set(name, load);
    return load;
  }

  async warmAll(options?: { strict?: boolean }): Promise<void> {
    const pending = Array.from(this.factories.keys());
    if (pending.length === 0) return;
    const results = await Promise.allSettled(
      pending.map((name) => this.ensureTool(name)),
    );
    if (options?.strict) {
      for (const result of results) {
        if (result.status === 'rejected') throw result.reason;
      }
    }
  }

  getTool(name: string): AnyDeclarativeTool | undefined {
    return this.tools.get(name);
  }

  getAllTools(): AnyDeclarativeTool[] {
    return Array.from(this.tools.values());
  }

  getToolNames(): string[] {
    return Array.from(this.tools.keys());
  }

  hasTool(name: string): boolean {
    return this.tools.has(name);
  }

  unregisterTool(name: string): boolean {
    this.factories.delete(name);
    return this.tools.delete(name);
  }

  clear(): void {
    this.tools.clear();
    this.factories.clear();
    this.inflight.clear();
  }

  getFunctionDeclarations(): Array<{
    type: string;
    function: {
      name: string;
      description: string;
      parameters: Record<string, unknown>;
    };
  }> {
    return this.getAllTools().map((tool) => ({
      type: 'function',
      function: {
        name: tool.name,
        description: tool.description,
        parameters: tool.parameterSchema,
      },
    }));
  }

  async executeToolCall(
    call: { id: string; name: string; arguments: Record<string, unknown> },
    signal?: AbortSignal,
  ): Promise<ToolResult> {
    const tool = this.tools.get(call.name);
    if (!tool) {
      return {
        llmContent: `Error: Tool '${call.name}' not found. Available: ${this.getToolNames().join(', ')}`,
        error: `Tool not found: ${call.name}`,
      };
    }

    try {
      const result = await tool.buildAndExecute(call.arguments, signal);
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      return {
        llmContent: `Error executing ${call.name}: ${message}`,
        error: message,
      };
    }
  }
}
