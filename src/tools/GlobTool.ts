import { BaseTool } from './base.js';
import { ToolSchema, ToolResult } from '../types.js';
import { fileSystem } from '../filesystem/index.js';

export class GlobTool extends BaseTool {
  protected defineSchema(): ToolSchema {
    return {
      name: 'glob',
      description: 'Find files using glob patterns. Returns matching file paths.',
      parameters: {
        pattern: {
          type: 'string',
          description: 'Glob pattern to match files (e.g., "**/*.ts", "src/**/*.js", "**/*.{ts,js}")',
          required: true,
        },
        max_results: {
          type: 'number',
          description: 'Maximum number of results to return',
          required: false,
          default: 200,
          minimum: 1,
          maximum: 10000,
        },
      },
      required: ['pattern'],
      returns: 'List of matching file paths',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const pattern = args.pattern as string;
    const maxResults = (args.max_results as number) || 200;

    try {
      const files = await fileSystem.globSearch(pattern);
      const trimmed = files.slice(0, maxResults);

      return {
        success: true,
        data: {
          pattern,
          total_matches: files.length,
          displayed: trimmed.length,
          files: trimmed,
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
