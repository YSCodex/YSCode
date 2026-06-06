import { BaseTool } from './base.js';
import { ToolSchema, ToolResult } from '../types.js';
import { fileSystem } from '../filesystem/index.js';

export class DirectoryTreeTool extends BaseTool {
  protected defineSchema(): ToolSchema {
    return {
      name: 'directory_tree',
      description: 'Get the directory tree structure. Shows files and folders in a tree format.',
      parameters: {
        path: {
          type: 'string',
          description: 'Directory path to show tree for',
          required: false,
          default: '.',
        },
        max_depth: {
          type: 'number',
          description: 'Maximum depth of the tree',
          required: false,
          default: 4,
          minimum: 1,
          maximum: 10,
        },
      },
      required: [],
      returns: 'Directory tree as formatted text',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const dirPath = (args.path as string) || '.';
    const maxDepth = (args.max_depth as number) || 4;

    try {
      const tree = fileSystem.getDirectoryTree(dirPath, maxDepth);

      return {
        success: true,
        data: {
          path: dirPath,
          max_depth: maxDepth,
          tree,
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
