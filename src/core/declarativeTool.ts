import {
  DeclarativeTool,
  ToolInvocation,
  ToolKind,
  ToolResult,
  PermissionDecision,
  ToolLocation,
} from './types.js';

export abstract class BaseToolInvocation<TParams extends object>
  implements ToolInvocation<TParams>
{
  constructor(readonly params: TParams) {}

  abstract getDescription(): string;

  toolLocations(): ToolLocation[] {
    return [];
  }

  getDefaultPermission(): PermissionDecision {
    return 'allow';
  }

  abstract execute(signal?: AbortSignal): Promise<ToolResult>;
}

export abstract class BaseDeclarativeTool<TParams extends object = Record<string, unknown>>
  implements DeclarativeTool<TParams>
{
  readonly isOutputMarkdown: boolean;
  readonly maxOutputChars: number;

  constructor(
    readonly name: string,
    readonly displayName: string,
    readonly description: string,
    readonly kind: ToolKind,
    readonly parameterSchema: Record<string, unknown>,
    options?: { isOutputMarkdown?: boolean; maxOutputChars?: number },
  ) {
    this.isOutputMarkdown = options?.isOutputMarkdown ?? true;
    this.maxOutputChars = options?.maxOutputChars ?? 10000;
  }

  protected abstract createInvocation(params: TParams): ToolInvocation<TParams>;

  build(params: TParams): ToolInvocation<TParams> {
    return this.createInvocation(params);
  }

  async buildAndExecute(
    params: TParams,
    signal?: AbortSignal,
  ): Promise<ToolResult> {
    const invocation = this.build(params);
    return invocation.execute(signal);
  }

  validateParams(params: Record<string, unknown>): string | null {
    const schema = this.parameterSchema as {
      properties?: Record<string, Record<string, unknown>>;
      required?: string[];
    };

    if (schema.required) {
      for (const key of schema.required) {
        if (params[key] === undefined || params[key] === null) {
          return `Missing required parameter: ${key}`;
        }
      }
    }

    if (schema.properties) {
      for (const [key, value] of Object.entries(params)) {
        const prop = schema.properties[key];
        if (!prop) continue;

        const expectedType = prop.type as string;
        if (expectedType === 'string' && typeof value !== 'string') {
          return `Parameter '${key}' must be a string, got ${typeof value}`;
        }
        if (expectedType === 'number' && typeof value !== 'number') {
          return `Parameter '${key}' must be a number, got ${typeof value}`;
        }
        if (expectedType === 'boolean' && typeof value !== 'boolean') {
          return `Parameter '${key}' must be a boolean, got ${typeof value}`;
        }
        if (expectedType === 'array' && !Array.isArray(value)) {
          return `Parameter '${key}' must be an array, got ${typeof value}`;
        }
      }
    }

    return null;
  }
}


