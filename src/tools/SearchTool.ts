import { BaseTool } from './base.js';
import { ToolSchema, ToolResult } from '../types.js';
import { fileSystem } from '../filesystem/index.js';

export class SearchTool extends BaseTool {
  protected defineSchema(): ToolSchema {
    return {
      name: 'search',
      description: 'Search for text in files. Supports regex patterns and file globs.',
      parameters: {
        query: {
          type: 'string',
          description: 'Text or regex pattern to search for',
          required: true,
        },
        pattern: {
          type: 'string',
          description: 'File glob pattern (e.g., "**/*.ts", "src/**/*.js")',
          required: false,
          default: '**/*.{ts,js,tsx,jsx,json,md,py,java,cpp,c,h,hpp,rs,go,yaml,yml,toml,xml,html,css,scss,less,sql,sh,bash}',
        },
        max_results: {
          type: 'number',
          description: 'Maximum number of results to return',
          required: false,
          default: 50,
          minimum: 1,
          maximum: 500,
        },
        case_sensitive: {
          type: 'boolean',
          description: 'Whether the search should be case sensitive',
          required: false,
          default: false,
        },
      },
      required: ['query'],
      returns: 'Search results with file paths, line numbers, and matching content',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const query = args.query as string;
    const pattern = (args.pattern as string) || '**/*';
    const maxResults = (args.max_results as number) || 50;
    const caseSensitive = (args.case_sensitive as boolean) || false;

    try {
      const results = await fileSystem.searchFiles(query, {
        maxResults,
        pattern,
        caseSensitive,
      });

      return {
        success: true,
        data: {
          query,
          total_results: results.length,
          results,
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
