import { BaseTool } from './base.js';
import { ToolSchema, ToolResult } from '../types.js';
import { fileSystem } from '../filesystem/index.js';

export class EditFileTool extends BaseTool {
  protected defineSchema(): ToolSchema {
    return {
      name: 'edit_file',
      description: 'Edit a file by replacing text. Uses exact string matching to find and replace content.',
      parameters: {
        file_path: {
          type: 'string',
          description: 'Path to the file to edit',
          required: true,
        },
        old_string: {
          type: 'string',
          description: 'Text to search for (must exist in the file)',
          required: true,
        },
        new_string: {
          type: 'string',
          description: 'Text to replace with',
          required: true,
        },
      },
      required: ['file_path', 'old_string', 'new_string'],
      returns: 'Edit confirmation with diff info',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const filePath = args.file_path as string;
    const oldString = args.old_string as string;
    const newString = args.new_string as string;

    try {
      if (!fileSystem.exists(filePath)) {
        return {
          success: false,
          error: `File not found: ${filePath}`,
        };
      }

      const content = fileSystem.readFile(filePath);

      if (!content.includes(oldString)) {
        return {
          success: false,
          error: `Could not find exact match for old_string in ${filePath}. The text must match exactly including whitespace.`,
        };
      }

      const newContent = content.replace(oldString, newString);
      fileSystem.writeFile(filePath, newContent);

      const oldLines = oldString.split('\n').length;
      const newLines = newString.split('\n').length;

      return {
        success: true,
        data: {
          file_path: filePath,
          old_length: oldString.length,
          new_length: newString.length,
          lines_changed: `${oldLines} -> ${newLines} lines`,
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
