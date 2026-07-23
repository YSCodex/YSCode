export type AuthType = 'openai' | 'anthropic' | 'gemini' | 'qwen-oauth';

export enum ToolKind {
  Read = 'read',
  Edit = 'edit',
  Delete = 'delete',
  Move = 'move',
  Search = 'search',
  Execute = 'execute',
  Think = 'think',
  Fetch = 'fetch',
  Agent = 'agent',
  Other = 'other',
}

export interface ToolResult {
  llmContent: string;
  returnDisplay?: string;
  resultFilePaths?: string[];
  error?: string;
}

export interface ToolCallConfirmationDetails {
  type: 'info' | 'edit' | 'execute' | 'mcp' | 'plan';
  title: string;
  prompt: string;
  details?: string;
}

export interface ToolLocation {
  path: string;
  line?: number;
}

export type PermissionDecision = 'allow' | 'ask' | 'deny';

export interface ContentGeneratorConfig {
  model: string;
  apiKey?: string;
  apiKeyEnvKey?: string;
  baseUrl?: string;
  authType?: AuthType;
  timeout?: number;
  maxRetries?: number;
  retryErrorCodes?: number[];
  temperature?: number;
  maxTokens?: number;
  topP?: number;
  topK?: number;
  repetitionPenalty?: number;
  presencePenalty?: number;
  frequencyPenalty?: number;
  reasoning?: { effort?: 'low' | 'medium' | 'high' | 'max'; budgetTokens?: number };
  customHeaders?: Record<string, string>;
  extraBody?: Record<string, unknown>;
  contextWindowSize?: number;
}

export interface GenerateContentParameters {
  messages: Array<{
    role: 'user' | 'assistant' | 'system' | 'tool';
    content: string;
    toolCallId?: string;
    toolCalls?: Array<{
      id: string;
      type: string;
      function: { name: string; arguments: string };
    }>;
  }>;
  tools?: Array<{
    type: string;
    function: {
      name: string;
      description: string;
      parameters: Record<string, unknown>;
    };
  }>;
  systemInstruction?: string;
}

export interface GenerateContentResponse {
  content: string;
  toolCalls?: Array<{
    id: string;
    name: string;
    arguments: Record<string, unknown>;
  }>;
  usage?: { promptTokens: number; completionTokens: number; totalTokens: number };
  finishReason: string;
  thought?: string;
}

export interface ContentGenerator {
  generateContent(request: GenerateContentParameters): Promise<GenerateContentResponse>;
  generateContentStream(
    request: GenerateContentParameters
  ): AsyncGenerator<GenerateContentResponse>;
  countTokens(request: { messages: Array<{ role: string; content: string }> }): Promise<number>;
}

export interface ToolInvocation<TParams extends object> {
  params: TParams;
  getDescription(): string;
  toolLocations(): ToolLocation[];
  getDefaultPermission(): PermissionDecision;
  execute(signal?: AbortSignal): Promise<ToolResult>;
}

export interface DeclarativeTool<TParams extends object> {
  readonly name: string;
  readonly displayName: string;
  readonly description: string;
  readonly kind: ToolKind;
  readonly parameterSchema: Record<string, unknown>;
  readonly isOutputMarkdown: boolean;
  readonly maxOutputChars: number;
  build(params: TParams): ToolInvocation<TParams>;
  buildAndExecute(params: TParams, signal?: AbortSignal): Promise<ToolResult>;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type AnyDeclarativeTool = DeclarativeTool<any>;
export type ToolFactory = () => Promise<AnyDeclarativeTool>;
