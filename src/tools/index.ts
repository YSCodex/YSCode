import { toolRegistry } from './base.js';
import { ReadFileTool } from './ReadFileTool.js';
import { WriteFileTool } from './WriteFileTool.js';
import { EditFileTool } from './EditFileTool.js';
import { DeleteFileTool } from './DeleteFileTool.js';
import { SearchTool } from './SearchTool.js';
import { GlobTool } from './GlobTool.js';
import { DirectoryTreeTool } from './DirectoryTreeTool.js';
import { TerminalTool } from './TerminalTool.js';
import { GitTool } from './GitTool.js';
import { WebFetchTool } from './WebFetchTool.js';
import { MemoryTool } from './MemoryTool.js';
import { getLogger } from '../logger/index.js';

const logger = getLogger('tools');

export function initializeTools(): void {
  const tools = [
    new ReadFileTool(),
    new WriteFileTool(),
    new EditFileTool(),
    new DeleteFileTool(),
    new SearchTool(),
    new GlobTool(),
    new DirectoryTreeTool(),
    new TerminalTool(),
    new GitTool(),
    new WebFetchTool(),
    new MemoryTool(),
  ];

  toolRegistry.registerAll(tools);
  logger.info(`Initialized ${tools.length} tools`);
}

export { toolRegistry } from './base.js';
export { ReadFileTool } from './ReadFileTool.js';
export { WriteFileTool } from './WriteFileTool.js';
export { EditFileTool } from './EditFileTool.js';
export { DeleteFileTool } from './DeleteFileTool.js';
export { SearchTool } from './SearchTool.js';
export { GlobTool } from './GlobTool.js';
export { DirectoryTreeTool } from './DirectoryTreeTool.js';
export { TerminalTool } from './TerminalTool.js';
export { GitTool } from './GitTool.js';
export { WebFetchTool } from './WebFetchTool.js';
export { MemoryTool } from './MemoryTool.js';
