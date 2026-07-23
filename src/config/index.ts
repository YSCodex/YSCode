import { readFileSync, writeFileSync, existsSync, mkdirSync } from 'fs';
import { join, dirname } from 'path';
import { homedir } from 'os';
import { Config, ProviderConfig, ModelConfig } from '../types.js';
export type { Config } from '../types.js';

const DEFAULT_CONFIG_PATH = join(homedir(), '.ys-code-agent', 'config.json');

const DEFAULT_CONFIG: Config = {
  version: '1.0.0',
  providers: [
    {
      type: 'openai',
      name: 'openai',
      apiKey: '',
      baseUrl: 'https://api.openai.com/v1',
      models: ['gpt-4o', 'gpt-4o-mini', 'gpt-4-turbo', 'gpt-3.5-turbo'],
      defaultModel: 'gpt-4o',
      maxRetries: 3,
      rateLimit: 10,
      timeout: 60000,
    },
    {
      type: 'anthropic',
      name: 'anthropic',
      apiKey: '',
      baseUrl: 'https://api.anthropic.com/v1',
      models: ['claude-sonnet-4-20250514', 'claude-3-opus-20240229', 'claude-3-sonnet-20240229', 'claude-3-haiku-20240307'],
      defaultModel: 'claude-sonnet-4-20250514',
      maxRetries: 3,
      rateLimit: 10,
      timeout: 60000,
    },
    {
      type: 'gemini',
      name: 'gemini',
      apiKey: '',
      baseUrl: 'https://generativelanguage.googleapis.com/v1beta',
      models: ['gemini-2.0-flash', 'gemini-1.5-pro', 'gemini-1.5-flash'],
      defaultModel: 'gemini-2.0-flash',
      maxRetries: 3,
      rateLimit: 10,
      timeout: 60000,
    },
    {
      type: 'openrouter',
      name: 'openrouter',
      apiKey: '',
      baseUrl: 'https://openrouter.ai/api/v1',
      models: ['google/gemma-4-31b-it:free', 'qwen/qwen-3-coder-32b:free', 'meta-llama/llama-3.3-70b-instruct:free', 'deepseek/deepseek-chat:free', 'google/gemini-2.0-flash:free', 'anthropic/claude-sonnet-4-20250514', 'openai/gpt-4o', 'google/gemini-2.0-flash'],
      defaultModel: 'google/gemma-4-31b-it:free',
      maxRetries: 3,
      rateLimit: 10,
      timeout: 60000,
    },
    {
      type: 'deepseek',
      name: 'deepseek',
      apiKey: '',
      baseUrl: 'https://api.deepseek.com/v1',
      models: ['deepseek-chat', 'deepseek-coder'],
      defaultModel: 'deepseek-chat',
      maxRetries: 3,
      rateLimit: 10,
      timeout: 60000,
    },
    {
      type: 'groq',
      name: 'groq',
      apiKey: '',
      baseUrl: 'https://api.groq.com/openai/v1',
      models: ['llama-3.3-70b-versatile', 'mixtral-8x7b-32768', 'gemma2-9b-it'],
      defaultModel: 'llama-3.3-70b-versatile',
      maxRetries: 3,
      rateLimit: 10,
      timeout: 60000,
    },
    {
      type: 'ollama',
      name: 'ollama',
      apiKey: '',
      baseUrl: 'http://localhost:11434/v1',
      models: ['llama3.3', 'codellama', 'mistral', 'qwen2.5-coder'],
      defaultModel: 'llama3.3',
      maxRetries: 3,
      rateLimit: 10,
      timeout: 120000,
    },
    {
      type: 'lmstudio',
      name: 'lmstudio',
      apiKey: '',
      baseUrl: 'http://localhost:1234/v1',
      models: ['local-model'],
      defaultModel: 'local-model',
      maxRetries: 3,
      rateLimit: 10,
      timeout: 120000,
    },
    {
      type: 'custom',
      name: 'custom',
      apiKey: '',
      baseUrl: '',
      models: ['custom-model'],
      defaultModel: 'custom-model',
      maxRetries: 3,
      rateLimit: 10,
      timeout: 60000,
    },
  ],
  activeProvider: 'openrouter',
  model: {
    provider: 'openrouter',
    model: 'google/gemma-4-31b-it:free',
    temperature: 0.7,
    maxTokens: 4096,
    topP: 0.9,
    frequencyPenalty: 0,
    presencePenalty: 0,
    stop: [],
  },
  permissions: {
    autoApprove: false,
    allowedCommands: [],
    deniedCommands: ['rm -rf /', 'sudo', 'shutdown', 'reboot', 'init', 'dd', 'mkfs'],
    allowedPaths: [],
    deniedPaths: [],
    maxFileSize: 10485760,
    askForConfirmation: true,
  },
  theme: {
    mode: 'dark',
    primaryColor: '#0066FF',
    secondaryColor: '#00CC66',
    backgroundColor: '#1a1b26',
    textColor: '#c0caf5',
    accentColor: '#7dcfff',
    fontFamily: 'monospace',
    fontSize: 14,
  },
  tools: {
    enabledTools: [],
    disabledTools: [],
    toolTimeout: 30000,
    maxToolRetries: 3,
  },
  session: {
    autoSave: true,
    saveInterval: 60000,
    maxSessions: 50,
    sessionDir: join(homedir(), '.ys-code-agent', 'sessions'),
  },
  memory: {
    shortTermSize: 100,
    longTermEnabled: true,
    dbPath: join(homedir(), '.ys-code-agent', 'memory.db'),
    autoSummarize: true,
    summarizationThreshold: 50,
  },
  logging: {
    level: 'warn',
    file: join(homedir(), '.ys-code-agent', 'logs', 'agent.log'),
    maxSize: 10485760,
    maxFiles: 5,
    consoleOutput: true,
  },
  security: {
    sandboxMode: false,
    readOnlyMode: false,
    dangerousCommandDetection: true,
    maxCommandLength: 10000,
    allowedEnvVars: ['PATH', 'HOME', 'SHELL', 'USER', 'NODE_ENV'],
  },
  fileSystem: {
    maxFileSize: 10485760,
    encoding: 'utf-8',
    cacheEnabled: true,
    cacheSize: 100,
    ignorePatterns: ['node_modules/**', '.git/**', 'dist/**', 'build/**', '.next/**', '__pycache__/**'],
  },
  git: {
    autoCommit: false,
    commitMessagePrefix: 'YS: ',
    signCommits: false,
    defaultBranch: 'main',
  },
  context: {
    maxTokens: 128000,
    compressionEnabled: true,
    summarizationEnabled: true,
    relevanceThreshold: 0.3,
    maxFiles: 50,
  },
};

export class ConfigManager {
  private config: Config;
  private configPath: string;
  private envPrefix = 'YS_CODE_AGENT_';

  constructor(configPath?: string) {
    this.configPath = configPath || process.env.YS_CONFIG_PATH || DEFAULT_CONFIG_PATH;
    this.config = this.load();
  }

  private load(): Config {
    try {
      if (existsSync(this.configPath)) {
        const data = readFileSync(this.configPath, 'utf-8');
        const parsed = JSON.parse(data);
        return this.mergeDefaults(parsed);
      }
    } catch {
      console.warn(`Failed to load config from ${this.configPath}, using defaults`);
    }
    return this.mergeDefaults({});
  }

  private mergeDefaults(custom: Partial<Config>): Config {
    const merged = {
      ...DEFAULT_CONFIG,
      ...custom,
      providers: custom.providers || DEFAULT_CONFIG.providers,
      model: { ...DEFAULT_CONFIG.model, ...custom.model },
      permissions: { ...DEFAULT_CONFIG.permissions, ...custom.permissions },
      theme: { ...DEFAULT_CONFIG.theme, ...custom.theme },
      tools: { ...DEFAULT_CONFIG.tools, ...custom.tools },
      session: { ...DEFAULT_CONFIG.session, ...custom.session },
      memory: { ...DEFAULT_CONFIG.memory, ...custom.memory },
      logging: { ...DEFAULT_CONFIG.logging, ...custom.logging },
      security: { ...DEFAULT_CONFIG.security, ...custom.security },
      fileSystem: { ...DEFAULT_CONFIG.fileSystem, ...custom.fileSystem },
      git: { ...DEFAULT_CONFIG.git, ...custom.git },
      context: { ...DEFAULT_CONFIG.context, ...custom.context },
    };

    if (custom.providers) {
      merged.providers = DEFAULT_CONFIG.providers.map((defaultProv) => {
        const customProv = custom.providers?.find((p) => p.name === defaultProv.name);
        return customProv ? { ...defaultProv, ...customProv } : defaultProv;
      });

      const customNames = new Set(custom.providers.map((p) => p.name));
      const defaultNames = new Set(DEFAULT_CONFIG.providers.map((p) => p.name));
      custom.providers.forEach((p) => {
        if (!defaultNames.has(p.name)) {
          merged.providers.push(p);
        }
      });
    }

    return merged;
  }

  private applyEnvOverrides(): void {
    for (const key of Object.keys(process.env)) {
      if (key.startsWith(this.envPrefix)) {
        const configKey = key.slice(this.envPrefix.length).toLowerCase();
        const value = process.env[key]!;
        this.setNested(configKey, value);
      }
    }

    for (const provider of this.config.providers) {
      const envKey = `${provider.type.toUpperCase()}_API_KEY`;
      const envName = `${provider.name.toUpperCase()}_API_KEY`;
      const genericKey = `API_KEY_${provider.name.toUpperCase()}`;
      if (process.env[envKey]) {
        provider.apiKey = process.env[envKey]!;
      }
      if (process.env[envName]) {
        provider.apiKey = process.env[envName]!;
      }
      if (process.env[genericKey]) {
        provider.apiKey = process.env[genericKey]!;
      }
    }
  }

  private setNested(key: string, value: string): void {
    const parts = key.split('_');
    let current: Record<string, unknown> = this.config as unknown as Record<string, unknown>;
    for (let i = 0; i < parts.length - 1; i++) {
      if (!current[parts[i]] || typeof current[parts[i]] !== 'object') {
        current[parts[i]] = {};
      }
      current = current[parts[i]] as Record<string, unknown>;
    }
    const lastKey = parts[parts.length - 1];
    if (!isNaN(Number(value))) {
      (current as Record<string, unknown>)[lastKey] = Number(value);
    } else if (value === 'true' || value === 'false') {
      (current as Record<string, unknown>)[lastKey] = value === 'true';
    } else {
      (current as Record<string, unknown>)[lastKey] = value;
    }
  }

  getConfig(): Config {
    this.applyEnvOverrides();
    return { ...this.config };
  }

  get<T>(key: string): T {
    this.applyEnvOverrides();
    const keys = key.split('.');
    let current: unknown = this.config;
    for (const k of keys) {
      if (current && typeof current === 'object' && k in (current as Record<string, unknown>)) {
        current = (current as Record<string, unknown>)[k];
      } else {
        return undefined as T;
      }
    }
    return current as T;
  }

  set<T>(key: string, value: T): void {
    const keys = key.split('.');
    let current: unknown = this.config;
    for (let i = 0; i < keys.length - 1; i++) {
      if (current && typeof current === 'object') {
        if (!((current as Record<string, unknown>)[keys[i]])) {
          (current as Record<string, unknown>)[keys[i]] = {};
        }
        current = (current as Record<string, unknown>)[keys[i]];
      }
    }
    const lastKey = keys[keys.length - 1];
    if (current && typeof current === 'object') {
      (current as Record<string, unknown>)[lastKey] = value;
    }
    this.save();
  }

  save(): void {
    try {
      const dir = dirname(this.configPath);
      if (!existsSync(dir)) {
        mkdirSync(dir, { recursive: true });
      }
      writeFileSync(this.configPath, JSON.stringify(this.config, null, 2), 'utf-8');
    } catch (error) {
      console.error('Failed to save config:', error);
    }
  }

  getProvider(name: string): ProviderConfig | undefined {
    return this.config.providers.find((p) => p.name === name);
  }

  getActiveProvider(): ProviderConfig {
    const provider = this.config.providers.find((p) => p.name === this.config.activeProvider);
    return provider || this.config.providers[0];
  }

  getModelConfig(): ModelConfig {
    return { ...this.config.model };
  }

  setActiveProvider(name: string): void {
    const provider = this.config.providers.find((p) => p.name === name);
    if (provider) {
      this.config.activeProvider = name;
      this.config.model.provider = provider.type;
      if (!provider.models.includes(this.config.model.model)) {
        console.warn(`Model "${this.config.model.model}" not available for "${name}", switching to default: "${provider.defaultModel}"`);
        this.config.model.model = provider.defaultModel;
      }
      this.save();
    }
  }

  setApiKey(providerName: string, apiKey: string): void {
    const provider = this.config.providers.find((p) => p.name === providerName);
    if (provider) {
      provider.apiKey = apiKey;
      this.save();
    }
  }

  reset(): void {
    this.config = { ...DEFAULT_CONFIG };
    this.save();
  }

  exportConfig(): string {
    const safe = { ...this.config };
    safe.providers = safe.providers.map((p) => ({
      ...p,
      apiKey: p.apiKey ? '***REDACTED***' : '',
    }));
    return JSON.stringify(safe, null, 2);
  }

  importConfig(json: string): boolean {
    try {
      const parsed = JSON.parse(json);
      const clean = { ...parsed };
      if (clean.providers) {
        clean.providers = clean.providers.map((p: ProviderConfig) => {
          if (p.apiKey === '***REDACTED***') {
            const existing = this.getProvider(p.name);
            return { ...p, apiKey: existing?.apiKey || '' };
          }
          return p;
        });
      }
      this.config = this.mergeDefaults(clean);
      this.save();
      return true;
    } catch {
      return false;
    }
  }
}

export const configManager = new ConfigManager();
