import { EventEmitter } from 'events';
import type { AnyDeclarativeTool, ToolResult, ToolKind, ToolInvocation, ToolResultDisplay, ShellExecutionConfig } from './ToolTypes';
import { isTool } from './ToolTypes';
import type { ConfigManager } from '../../config/index.js';

export class ToolRegistry extends EventEmitter {
  private tools: Map<string, AnyDeclarativeTool> = new Map();
  private lazyTools: Map<string, () => Promise<AnyDeclarativeTool>> = new Map();
  private maxTools: number;

  constructor(configManager?: ConfigManager, options?: { maxTools?: number }) {
    super();
    this.maxTools = options?.maxTools ?? 100;
    if (configManager) { /* stored for future use */ }
  }

  registerTool(tool: AnyDeclarativeTool): void {
    if (this.tools.size >= this.maxTools) throw new Error(`Maximum tool limit (${this.maxTools}) reached`);
    this.tools.set(tool.name, tool as AnyDeclarativeTool);
    this.emit('tool:registered', tool);
  }

  register(tool: AnyDeclarativeTool): void { this.registerTool(tool); }

  registerLazy(name: string, factory: () => Promise<AnyDeclarativeTool>): void {
    this.lazyTools.set(name, factory);
  }

  getTool(name: string): AnyDeclarativeTool | undefined {
    return this.tools.get(name);
  }

  async getOrLoadTool(name: string): Promise<AnyDeclarativeTool | undefined> {
    let tool = this.tools.get(name);
    if (!tool && this.lazyTools.has(name)) {
      const factory = this.lazyTools.get(name)!;
      tool = await factory();
      if (tool) { this.tools.set(name, tool); this.lazyTools.delete(name); this.emit('tool:registered', tool); }
    }
    return tool;
  }

  getAllTools(): AnyDeclarativeTool[] { return Array.from(this.tools.values()); }
  getAllToolNames(): string[] { return Array.from(this.tools.keys()); }

  getToolsByKind(kind: ToolKind): AnyDeclarativeTool[] {
    return this.getAllTools().filter(t => t.kind === kind);
  }

  unregister(name: string): boolean {
    if (this.tools.has(name)) { this.tools.delete(name); this.emit('tool:unregistered', name); return true; }
    return false;
  }

  has(name: string): boolean { return this.tools.has(name) || this.lazyTools.has(name); }

  getFunctionDeclarations(): Record<string, unknown>[] {
    return this.getAllTools().map(tool => ({
      name: tool.name, description: tool.description, parameters: tool.parameterSchema,
    }));
  }

  async executeTool(name: string, params: Record<string, unknown>, signal: AbortSignal, updateOutput?: (output: ToolResultDisplay) => void, shellExecutionConfig?: ShellExecutionConfig): Promise<ToolResult> {
    const tool = this.getTool(name);
    if (!tool) return { success: false, llmContent: `Tool "${name}" not found`, returnDisplay: `Tool "${name}" not found`, error: { message: `Tool "${name}" not found`, type: 'TOOL_NOT_FOUND' } };
    this.emit('tool:execute:start', { name, params });
    try {
      const result = await tool.validateBuildAndExecute(params, signal);
      this.emit('tool:execute:end', { name, params, result });
      return result;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.emit('tool:execute:error', { name, params, error: errorMessage });
      return { success: false, llmContent: `Error executing tool "${name}": ${errorMessage}`, returnDisplay: errorMessage, error: { message: errorMessage, type: 'EXECUTION_FAILED' } };
    }
  }

  getToolSchema(name: string): unknown { return this.tools.get(name)?.schema; }
  getAllSchemas(): Record<string, unknown> { const s: Record<string, unknown> = {}; for (const [n, t] of this.tools) s[n] = t.schema; return s; }
  getToolCount(): number { return this.tools.size; }
  getLazyToolCount(): number { return this.lazyTools.size; }

  clear(): void {
    const oldSize = this.tools.size;
    this.tools.clear(); this.lazyTools.clear();
    this.emit('tools:cleared', oldSize);
  }

  async loadLazyTools(): Promise<void> {
    for (const [name] of this.lazyTools) {
      try {
        const factory = this.lazyTools.get(name)!;
        const tool = await factory();
        if (tool) { this.tools.set(name, tool); this.lazyTools.delete(name); this.emit('tool:registered', tool); }
      } catch (error) { this.emit('tool:load:error', { name, error }); }
    }
  }
}

export default ToolRegistry;