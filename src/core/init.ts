import { createContentGenerator } from './contentGenerator.js';
import { ContentGeneratorConfig, AuthType } from './types.js';
import { ToolRegistry } from './toolRegistry.js';
import { Agent } from './agent.js';
import { registerDeclarativeTools } from '../tools/declarative/index.js';
import { getLogger } from '../logger/index.js';

const logger = getLogger('core');

export interface CoreInitOptions {
  provider?: 'openai' | 'anthropic' | 'gemini' | 'openrouter';
  model?: string;
  apiKey?: string;
  baseUrl?: string;
  systemPrompt?: string;
  maxTurns?: number;
}

export function initCore(options: CoreInitOptions = {}): {
  agent: Agent;
  toolRegistry: ToolRegistry;
} {
  const toolRegistry = new ToolRegistry();

  registerDeclarativeTools(toolRegistry as any);

  const authType: AuthType =
    options.provider === 'anthropic'
      ? 'anthropic'
      : options.provider === 'gemini'
        ? 'gemini'
        : 'openai';

  const genConfig: ContentGeneratorConfig = {
    model: options.model || 'gpt-4o',
    apiKey: options.apiKey,
    baseUrl: options.baseUrl,
    authType,
    temperature: 0.7,
    maxTokens: 8192,
    topP: 0.95,
  };

  if (options.apiKey && !genConfig.apiKey) {
    genConfig.apiKey = options.apiKey;
  }

  const generator = createContentGenerator(genConfig);
  const agent = new Agent(generator, toolRegistry, {
    maxTurns: options.maxTurns || 25,
    systemPrompt: options.systemPrompt,
  });

  logger.info('Core initialized', {
    provider: options.provider || 'openai',
    model: genConfig.model,
  });

  return { agent, toolRegistry };
}
