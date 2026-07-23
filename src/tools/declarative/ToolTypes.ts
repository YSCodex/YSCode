export type ToolKind =
  | 'file_read'
  | 'file_write'
  | 'file_edit'
  | 'search'
  | 'shell'
  | 'git'
  | 'web'
  | 'memory'
  | 'mcp'
  | 'subagent'
  | 'task'
  | 'other';

export type ToolPermission = 'allow' | 'ask' | 'deny';

export interface ToolLocation {
  path: string;
  description?: string;
}

export interface ToolConfirmationOutcome {
  decision: 'proceed' | 'cancel' | 'modify';
  modifiedParams?: Record<string, unknown>;
}

export interface ToolCallConfirmationDetails {
  type: 'info' | 'warning' | 'danger' | 'diff';
  title: string;
  prompt: string;
  onConfirm: (outcome: ToolConfirmationOutcome, payload?: ToolConfirmationPayload) => void;
}

export interface ToolConfirmationPayload {
  modifiedArgs?: Record<string, unknown>;
}

export type ToolResultDisplay = string | { type: string; content: string; metadata?: Record<string, unknown> };

export type ToolErrorType =
  | 'FILE_NOT_FOUND'
  | 'PERMISSION_DENIED'
  | 'INVALID_TOOL_PARAMS'
  | 'EXECUTION_FAILED'
  | 'TOOL_NOT_FOUND'
  | 'MCP_ERROR'
  | 'TIMEOUT'
  | 'CANCELLED';

export interface ToolResult {
  success: boolean;
  llmContent?: string;
  returnDisplay?: ToolResultDisplay;
  error?: {
    message: string;
    type?: ToolErrorType;
  };
  persistedOutputFiles?: string[];
  resultFilePaths?: string[];
  artifacts?: ToolArtifact[];
  modelOverride?: string;
}

export interface ToolArtifact {
  kind?: 'file' | 'link' | 'html' | 'image' | 'video' | 'audio' | 'pdf' | 'notebook' | 'other';
  storage?: 'workspace' | 'external_url' | 'managed' | 'published';
  title: string;
  description?: string;
  workspacePath?: string;
  managedId?: string;
  url?: string;
  mimeType?: string;
  sizeBytes?: number;
  metadata?: Record<string, string | number | boolean | null>;
}

export interface ToolInvocation<
  TParams extends Record<string, unknown>,
  TResult extends ToolResult
> {
  params: TParams;
  getDescription(): string;
  toolLocations(): ToolLocation[];
  getDefaultPermission(): Promise<ToolPermission>;
  getConfirmationDetails(abortSignal: AbortSignal): Promise<ToolCallConfirmationDetails>;
  execute(
    signal: AbortSignal,
    updateOutput?: (output: ToolResultDisplay) => void,
    shellExecutionConfig?: ShellExecutionConfig
  ): Promise<TResult>;
}

export interface ShellExecutionConfig {
  cwd?: string;
  env?: Record<string, string>;
  timeout?: number;
}

export abstract class BaseToolInvocation<
  TParams extends Record<string, unknown>,
  TResult extends ToolResult
> implements ToolInvocation<TParams, TResult>
{
  constructor(readonly params: TParams) {}

  abstract getDescription(): string;

  toolLocations(): ToolLocation[] {
    return [];
  }

  getDefaultPermission(): Promise<ToolPermission> {
    return Promise.resolve('allow');
  }

  async getConfirmationDetails(
    _abortSignal: AbortSignal
  ): Promise<ToolCallConfirmationDetails> {
    return {
      type: 'info',
      title: `Confirm ${this.constructor.name.replace(/Invocation$/, '')}`,
      prompt: this.getDescription(),
      onConfirm: async () => {},
    };
  }

  abstract execute(
    signal: AbortSignal,
    updateOutput?: (output: ToolResultDisplay) => void,
    shellExecutionConfig?: ShellExecutionConfig
  ): Promise<TResult>;
}

export interface ToolBuilder<
  TParams extends Record<string, unknown>,
  TResult extends ToolResult
> {
  name: string;
  displayName: string;
  description: string;
  kind: ToolKind;
  isOutputMarkdown: boolean;
  canUpdateOutput: boolean;
  shouldDefer: boolean;
  alwaysLoad: boolean;
  searchHint?: string;
  build(params: TParams): ToolInvocation<TParams, TResult>;
  validateToolParams(params: TParams): string | null;
}

export abstract class DeclarativeTool<
  TParams extends Record<string, unknown>,
  TResult extends ToolResult
> implements ToolBuilder<TParams, TResult>
{
  constructor(
    readonly name: string,
    readonly displayName: string,
    readonly description: string,
    readonly kind: ToolKind,
    readonly parameterSchema: unknown,
    readonly isOutputMarkdown: boolean = true,
    readonly canUpdateOutput: boolean = false,
    readonly shouldDefer: boolean = false,
    readonly alwaysLoad: boolean = false,
    readonly searchHint?: string
  ) {}

  get schema(): Record<string, unknown> {
    return {
      name: this.name,
      description: this.description,
      parameters: this.parameterSchema,
    };
  }

  abstract build(params: TParams): ToolInvocation<TParams, TResult>;

  validateToolParams(_params: TParams): string | null {
    return null;
  }

  async buildAndExecute(
    params: TParams,
    signal: AbortSignal,
    updateOutput?: (output: ToolResultDisplay) => void,
    shellExecutionConfig?: ShellExecutionConfig
  ): Promise<TResult> {
    const invocation = this.build(params);
    return invocation.execute(signal, updateOutput, shellExecutionConfig);
  }

  private silentBuild(
    params: TParams
  ): ToolInvocation<TParams, TResult> | Error {
    try {
      return this.build(params);
    } catch (e) {
      if (e instanceof Error) return e;
      return new Error(String(e));
    }
  }

  async validateBuildAndExecute(
    params: TParams,
    abortSignal: AbortSignal
  ): Promise<ToolResult> {
    const invocationOrError = this.silentBuild(params);
    if (invocationOrError instanceof Error) {
      const errorMessage = invocationOrError.message;
      return {
        success: false,
        llmContent: `Error: Invalid parameters provided. Reason: ${errorMessage}`,
        returnDisplay: errorMessage,
        error: {
          message: errorMessage,
          type: 'INVALID_TOOL_PARAMS',
        },
      };
    }

    try {
      return await invocationOrError.execute(abortSignal);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return {
        success: false,
        llmContent: `Error: Tool call execution failed. Reason: ${errorMessage}`,
        returnDisplay: errorMessage,
        error: {
          message: errorMessage,
          type: 'EXECUTION_FAILED',
        },
      };
    }
  }
}

export abstract class BaseDeclarativeTool<
  TParams extends Record<string, unknown>,
  TResult extends ToolResult
> extends DeclarativeTool<TParams, TResult> {
  build(params: TParams): ToolInvocation<TParams, TResult> {
    const validationError = this.validateToolParams(params);
    if (validationError) {
      throw new Error(validationError);
    }
    return this.createInvocation(params);
  }

  validateToolParams(params: TParams): string | null {
    return this.validateToolParamValues(params);
  }

  protected validateToolParamValues(_params: TParams): string | null {
    return null;
  }

  protected abstract createInvocation(
    params: TParams
  ): ToolInvocation<TParams, TResult>;
}

export type AnyDeclarativeTool = DeclarativeTool<Record<string, unknown>, ToolResult>;

export function isTool(obj: unknown): obj is AnyDeclarativeTool {
  return (
    typeof obj === 'object' &&
    obj !== null &&
    'name' in obj &&
    'build' in obj &&
    typeof (obj as AnyDeclarativeTool).build === 'function'
  );
}

export const Kind = {
  Read: 'file_read' as const,
  Write: 'file_write' as const,
  Edit: 'file_edit' as const,
  Delete: 'file_edit' as const,
  Search: 'search' as const,
  Execute: 'shell' as const,
  Fetch: 'web' as const,
  Other: 'other' as const,
};