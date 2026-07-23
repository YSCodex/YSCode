export type ProviderType =
  | 'openai'
  | 'gemini'
  | 'anthropic'
  | 'openrouter'
  | 'deepseek'
  | 'groq'
  | 'ollama'
  | 'lmstudio'
  | 'custom';

export interface ProviderConfig {
  type: ProviderType;
  name: string;
  apiKey?: string;
  baseUrl?: string;
  models: string[];
  defaultModel: string;
  maxRetries: number;
  rateLimit: number;
  timeout: number;
}

export interface ModelConfig {
  provider: ProviderType;
  model: string;
  temperature: number;
  maxTokens: number;
  topP: number;
  frequencyPenalty: number;
  presencePenalty: number;
  stop: string[];
}

export interface Config {
  version: string;
  providers: ProviderConfig[];
  activeProvider: string;
  model: ModelConfig;
  permissions: PermissionConfig;
  theme: ThemeConfig;
  tools: ToolConfig;
  session: SessionConfig;
  memory: MemoryConfig;
  logging: LoggingConfig;
  security: SecurityConfig;
  fileSystem: FileSystemConfig;
  git: GitConfig;
  context: ContextConfig;
}

export interface PermissionConfig {
  autoApprove: boolean;
  allowedCommands: string[];
  deniedCommands: string[];
  allowedPaths: string[];
  deniedPaths: string[];
  maxFileSize: number;
  askForConfirmation: boolean;
}

export interface ThemeConfig {
  mode: 'dark' | 'light' | 'auto';
  primaryColor: string;
  secondaryColor: string;
  backgroundColor: string;
  textColor: string;
  accentColor: string;
  fontFamily: string;
  fontSize: number;
}

export interface ToolConfig {
  enabledTools: string[];
  disabledTools: string[];
  toolTimeout: number;
  maxToolRetries: number;
}

export interface SessionConfig {
  autoSave: boolean;
  saveInterval: number;
  maxSessions: number;
  sessionDir: string;
}

export interface MemoryConfig {
  shortTermSize: number;
  longTermEnabled: boolean;
  dbPath: string;
  autoSummarize: boolean;
  summarizationThreshold: number;
}

export interface LoggingConfig {
  level: 'debug' | 'info' | 'warn' | 'error';
  file: string;
  maxSize: number;
  maxFiles: number;
  consoleOutput: boolean;
}

export interface SecurityConfig {
  sandboxMode: boolean;
  readOnlyMode: boolean;
  dangerousCommandDetection: boolean;
  maxCommandLength: number;
  allowedEnvVars: string[];
}

export interface FileSystemConfig {
  maxFileSize: number;
  encoding: string;
  cacheEnabled: boolean;
  cacheSize: number;
  ignorePatterns: string[];
}

export interface GitConfig {
  autoCommit: boolean;
  commitMessagePrefix: string;
  signCommits: boolean;
  defaultBranch: string;
}

export interface ContextConfig {
  maxTokens: number;
  compressionEnabled: boolean;
  summarizationEnabled: boolean;
  relevanceThreshold: number;
  maxFiles: number;
}

export interface ToolResult {
  success: boolean;
  data?: unknown;
  error?: string;
  metadata?: Record<string, unknown>;
}

export interface ToolSchema {
  name: string;
  description: string;
  parameters: Record<string, ToolParameter>;
  required: string[];
  returns: string;
}

export interface ToolParameter {
  type: 'string' | 'number' | 'boolean' | 'array' | 'object';
  description: string;
  required: boolean;
  default?: unknown;
  enum?: string[];
  minimum?: number;
  maximum?: number;
  properties?: Record<string, Record<string, unknown>>;
}

export interface ToolCall {
  id: string;
  name: string;
  arguments: Record<string, unknown>;
  timestamp: number;
}

export interface AgentMessage {
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  toolCalls?: ToolCall[];
  toolResults?: ToolResult[];
  toolCallId?: string;
  timestamp: number;
  metadata?: Record<string, unknown>;
}

export interface AgentState {
  task: string;
  plan: PlanStep[];
  currentStep: number;
  messages: AgentMessage[];
  context: ContextData;
  memory: MemoryData;
  status: AgentStatus;
  startTime: number;
  projectInfo?: ProjectInfo;
}

export interface PlanStep {
  id: string;
  description: string;
  action: string;
  tool: string;
  parameters: Record<string, unknown>;
  status: 'pending' | 'in_progress' | 'completed' | 'failed';
  result?: ToolResult;
  error?: string;
}

export interface ContextData {
  currentDirectory: string;
  openFiles: string[];
  recentFiles: string[];
  projectStructure: FileNode[];
  environment: Record<string, string>;
  tokenCount: number;
}

export interface MemoryData {
  shortTerm: ShortTermMemory;
  longTerm: LongTermMemory;
}

export interface ShortTermMemory {
  messages: AgentMessage[];
  maxSize: number;
}

export interface LongTermMemory {
  summaries: Summary[];
  preferences: Record<string, unknown>;
  previousTasks: TaskRecord[];
}

export interface Summary {
  id: string;
  content: string;
  timestamp: number;
  type: 'project' | 'conversation' | 'task';
  metadata?: Record<string, unknown>;
}

export interface TaskRecord {
  id: string;
  description: string;
  status: 'completed' | 'failed' | 'cancelled';
  timestamp: number;
  duration: number;
  result?: string;
}

export type AgentStatus = 'idle' | 'thinking' | 'planning' | 'executing' | 'waiting' | 'completed' | 'error';

export interface FileNode {
  name: string;
  path: string;
  type: 'file' | 'directory';
  size?: number;
  extension?: string;
  children?: FileNode[];
}

export interface ProjectInfo {
  name: string;
  rootPath: string;
  languages: string[];
  frameworks: string[];
  packageManager: string;
  buildSystem: string;
  dependencies: string[];
  devDependencies: string[];
  scripts: Record<string, string>;
  fileCount: number;
  totalSize: number;
}

export interface DiffResult {
  file: string;
  additions: number;
  deletions: number;
  hunks: DiffHunk[];
}

export interface DiffHunk {
  oldStart: number;
  oldLines: number;
  newStart: number;
  newLines: number;
  content: string;
}

export interface SessionData {
  id: string;
  name: string;
  state: AgentState;
  createdAt: number;
  updatedAt: number;
  metadata?: Record<string, unknown>;
}

export interface LogEntry {
  timestamp: number;
  level: string;
  module: string;
  message: string;
  data?: unknown;
}

export interface ProviderResponse {
  content: string;
  toolCalls?: ToolCall[];
  usage?: {
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
  };
  finishReason: string;
}

export interface ProviderStreamChunk {
  type: 'text' | 'tool_call' | 'tool_result' | 'error' | 'done';
  content?: string;
  toolCall?: ToolCall;
  error?: string;
  usage?: ProviderResponse['usage'];
}

export interface PermissionRequest {
  id: string;
  action: string;
  resource: string;
  details: string;
  timestamp: number;
  status: 'pending' | 'approved' | 'denied';
}

export interface CommandResult {
  command: string;
  stdout: string;
  stderr: string;
  exitCode: number;
  duration: number;
  cancelled: boolean;
}

export interface SearchResult {
  file: string;
  line: number;
  column: number;
  content: string;
  match: string;
}

export interface TokenUsage {
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  cost: number;
}

export interface ProviderLimits {
  maxTokens: number;
  maxContextLength: number;
  rateLimit: {
    requestsPerMinute: number;
    tokensPerMinute: number;
  };
  supportedModels: string[];
}

export type ToolPermission =
  | 'read'
  | 'write'
  | 'execute'
  | 'network'
  | 'file_system'
  | 'git'
  | 'memory';

export interface ToolPermissions {
  tool: string;
  permissions: ToolPermission[];
  allow: boolean;
}
