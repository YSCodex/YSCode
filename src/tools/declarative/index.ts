export * from './ToolTypes.js';
export * from './ToolRegistry.js';

import { ToolRegistry } from './ToolRegistry.js';
import type { AnyDeclarativeTool } from './ToolTypes.js';
import { ReadFileTool } from '../core/FileTools.js';
import { WriteFileTool } from '../core/FileTools.js';
import { EditFileTool } from '../core/FileTools.js';
import { DeleteFileTool } from '../core/FileTools.js';
import { SearchTool } from '../core/FileTools.js';
import { GlobTool } from '../core/FileTools.js';
import { TerminalTool } from '../core/SystemTools.js';
import { GitTool } from '../core/SystemTools.js';
import { WebFetchTool } from '../core/SystemTools.js';
import { MemoryTool } from '../core/SystemTools.js';
import { configManager } from '../../config/index.js';

export function registerDeclarativeTools(registry: { registerTool: (tool: AnyDeclarativeTool) => void }): void {
  const tools: AnyDeclarativeTool[] = [
    new ReadFileTool() as unknown as AnyDeclarativeTool,
    new WriteFileTool() as unknown as AnyDeclarativeTool,
    new EditFileTool() as unknown as AnyDeclarativeTool,
    new DeleteFileTool() as unknown as AnyDeclarativeTool,
    new SearchTool() as unknown as AnyDeclarativeTool,
    new GlobTool() as unknown as AnyDeclarativeTool,
    new TerminalTool() as unknown as AnyDeclarativeTool,
    new GitTool() as unknown as AnyDeclarativeTool,
    new WebFetchTool() as unknown as AnyDeclarativeTool,
    new MemoryTool() as unknown as AnyDeclarativeTool,
  ];
  for (const tool of tools) {
    registry.registerTool(tool);
  }
}

export function initializeTools(): ToolRegistry {
  const registry = new ToolRegistry(configManager);
  registerDeclarativeTools(registry);
  return registry;
}