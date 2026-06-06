import { ProviderConfig, ModelConfig } from '../types.js';
import { BaseProvider } from './base.js';
import { OpenAIProvider } from './openai.js';
import { AnthropicProvider } from './anthropic.js';
import { GeminiProvider } from './gemini.js';
import { OpenRouterProvider } from './openrouter.js';
import { DeepSeekProvider } from './deepseek.js';
import { GroqProvider } from './groq.js';
import { OllamaProvider } from './ollama.js';
import { LMStudioProvider } from './lmstudio.js';
import { CustomProvider } from './custom.js';
import { getLogger } from '../logger/index.js';

export { BaseProvider } from './base.js';
export { OpenAIProvider } from './openai.js';
export { AnthropicProvider } from './anthropic.js';
export { GeminiProvider } from './gemini.js';
export { OpenRouterProvider } from './openrouter.js';
export { DeepSeekProvider } from './deepseek.js';
export { GroqProvider } from './groq.js';
export { OllamaProvider } from './ollama.js';
export { LMStudioProvider } from './lmstudio.js';
export { CustomProvider } from './custom.js';

const logger = getLogger('providers');

const providerRegistry: Record<string, new (config: ProviderConfig, modelConfig: ModelConfig) => BaseProvider> = {
  openai: OpenAIProvider,
  anthropic: AnthropicProvider,
  gemini: GeminiProvider,
  openrouter: OpenRouterProvider,
  deepseek: DeepSeekProvider,
  groq: GroqProvider,
  ollama: OllamaProvider,
  lmstudio: LMStudioProvider,
  custom: CustomProvider,
};

export function createProvider(config: ProviderConfig, modelConfig: ModelConfig): BaseProvider {
  const ProviderClass = providerRegistry[config.type];
  if (!ProviderClass) {
    throw new Error(`Unsupported provider type: ${config.type}`);
  }
  logger.info(`Creating provider: ${config.type} with model ${modelConfig.model}`);
  return new ProviderClass(config, modelConfig);
}

export function getSupportedProviderTypes(): string[] {
  return Object.keys(providerRegistry);
}

export function getProviderNames(): string[] {
  return Object.values(providerRegistry).map((P) => {
    const instance = new (P as any)({ type: 'openai', name: '', models: [], defaultModel: '', maxRetries: 3, rateLimit: 10, timeout: 60000 } as ProviderConfig, {} as ModelConfig);
    return instance.getName();
  });
}
