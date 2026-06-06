import { BaseTool } from './base.js';
import { ToolSchema, ToolResult } from '../types.js';
import { fileSystem } from '../filesystem/index.js';

export class WriteFileTool extends BaseTool {
  protected defineSchema(): ToolSchema {
    return {
      name: 'write_file',
      description: 'Write content to a file. Creates the file if it does not exist, overwrites if it does.',
      parameters: {
        file_path: {
          type: 'string',
          description: 'Path to the file to write',
          required: true,
        },
        content: {
          type: 'string',
          description: 'Content to write to the file',
          required: true,
        },
      },
      required: ['file_path', 'content'],
      returns: 'Write confirmation with file info',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const filePath = args.file_path as string;
    const content = args.content as string;

    try {
      fileSystem.writeFile(filePath, content);

      return {
        success: true,
        data: {
          file_path: filePath,
          size: content.length,
          lines: content.split('\n').length,
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
