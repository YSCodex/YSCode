import { BaseTool } from './base.js';
import { ToolSchema, ToolResult } from '../types.js';
import { fileSystem } from '../filesystem/index.js';

export class DeleteFileTool extends BaseTool {
  protected defineSchema(): ToolSchema {
    return {
      name: 'delete_file',
      description: 'Delete a file or directory. Use recursive flag for directories.',
      parameters: {
        file_path: {
          type: 'string',
          description: 'Path to the file or directory to delete',
          required: true,
        },
        recursive: {
          type: 'boolean',
          description: 'Recursively delete directories',
          required: false,
          default: false,
        },
      },
      required: ['file_path'],
      returns: 'Deletion confirmation',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const filePath = args.file_path as string;

    try {
      if (!fileSystem.exists(filePath)) {
        return {
          success: false,
          error: `Path not found: ${filePath}`,
        };
      }

      fileSystem.deleteFile(filePath);

      return {
        success: true,
        data: {
          deleted: filePath,
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
