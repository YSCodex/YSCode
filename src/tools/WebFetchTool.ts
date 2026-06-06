import { BaseTool } from './base.js';
import { ToolSchema, ToolResult } from '../types.js';
import { getLogger } from '../logger/index.js';

const logger = getLogger('tool:webfetch');

export class WebFetchTool extends BaseTool {
  protected defineSchema(): ToolSchema {
    return {
      name: 'web_fetch',
      description: 'Fetch content from a URL. Can fetch web pages and API responses.',
      parameters: {
        url: {
          type: 'string',
          description: 'URL to fetch content from',
          required: true,
        },
        max_length: {
          type: 'number',
          description: 'Maximum content length to return',
          required: false,
          default: 10000,
          minimum: 100,
          maximum: 100000,
        },
        format: {
          type: 'string',
          description: 'Response format',
          required: false,
          default: 'text',
          enum: ['text', 'markdown', 'html', 'json'],
        },
        method: {
          type: 'string',
          description: 'HTTP method',
          required: false,
          default: 'GET',
          enum: ['GET', 'POST'],
        },
        headers: {
          type: 'object',
          description: 'Additional HTTP headers',
          required: false,
        },
        body: {
          type: 'string',
          description: 'Request body for POST requests',
          required: false,
        },
      },
      required: ['url'],
      returns: 'Fetched content from the URL',
    };
  }

  async execute(args: Record<string, unknown>): Promise<ToolResult> {
    const url = args.url as string;
    const maxLength = (args.max_length as number) || 10000;
    const format = (args.format as string) || 'text';
    const method = (args.method as string) || 'GET';
    const extraHeaders = args.headers as Record<string, string> | undefined;
    const body = args.body as string | undefined;

    try {
      if (!url.startsWith('http://') && !url.startsWith('https://')) {
        return { success: false, error: 'Invalid URL. Must start with http:// or https://' };
      }

      const headers: Record<string, string> = {
        'User-Agent': 'YS-Code-Agent/1.0',
        'Accept': format === 'json' ? 'application/json' : 'text/html,text/markdown',
        ...extraHeaders,
      };

      const requestOptions: RequestInit = {
        method,
        headers,
      };

      if (method === 'POST' && body) {
        requestOptions.body = body;
      }

      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 15000);

      try {
        const response = await fetch(url, {
          ...requestOptions,
          signal: controller.signal,
        });
        clearTimeout(timeoutId);

        let content = '';
        const contentType = response.headers.get('content-type') || '';

        if (contentType.includes('application/json')) {
          content = JSON.stringify(await response.json(), null, 2);
        } else {
          content = await response.text();
        }

        if (content.length > maxLength) {
          content = content.slice(0, maxLength) + '\n\n... (truncated)';
        }

        return {
          success: true,
          data: {
            url,
            status: response.status,
            status_text: response.statusText,
            content_type: contentType,
            content_length: content.length,
            content,
          },
        };
      } finally {
        clearTimeout(timeoutId);
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      logger.error(`Failed to fetch URL: ${url}`, { error: message });

      return {
        success: false,
        error: `Failed to fetch URL: ${message}`,
      };
    }
  }
}
