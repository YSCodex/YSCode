import { BaseTool } from './base.js';
import { ToolSchema, ToolResult } from '../types.js';
import { fileSystem } from '../filesystem/index.js';

export class ReadFileTool extends BaseTool {
  protected defineSchema(): ToolSchema {
    return {
      name: 'read_file',
      description: 'Read the contents of a file. Supports reading specific line ranges.',
      parameters: {
        file_path: {
          type: 'string',
          description: 'Path to the file to read',
          required: true,
        },
        offset: {
          type: 'number',
          description: 'Starting line number (1-indexed)',
          required: false,
          default: 1,
          minimum: 1,
        },
        limit: {
          type: 'number',
          description: 'Maximum number of lines to read',
          required: false,
          default: 2000,
          minimum: 1,
          maximum: 100000,
        },
      },
      required: ['file_path'],
      returns: 'File content as text',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const filePath = args.file_path as string;
    const offset = (args.offset as number) || 1;
    const limit = (args.limit as number) || 2000;

    try {
      if (!fileSystem.exists(filePath)) {
        return {
          success: false,
          error: `File not found: ${filePath}`,
        };
      }

      const info = fileSystem.getFileInfo(filePath);
      if (info?.isDirectory) {
        return {
          success: false,
          error: `Path is a directory, not a file: ${filePath}`,
        };
      }

      const lines = fileSystem.readFileLines(filePath, offset, offset + limit - 1);
      const content = lines.join('\n');
      const totalLines = fileSystem.readFile(filePath).split('\n').length;

      return {
        success: true,
        data: {
          file_path: filePath,
          content,
          total_lines: totalLines,
          start_line: offset,
          end_line: Math.min(offset + limit - 1, totalLines),
          size: info?.size,
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
