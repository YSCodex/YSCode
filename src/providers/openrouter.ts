import { OpenAIProvider } from './openai.js';
import { ProviderConfig, ModelConfig } from '../types.js';

export class OpenRouterProvider extends OpenAIProvider {
  constructor(config: ProviderConfig, modelConfig: ModelConfig) {
    super(config, modelConfig);
  }

  getName(): string {
    return 'OpenRouter';
  }

  getType(): string {
    return 'openrouter';
  }

  protected buildHeaders(): Record<string, string> {
    return {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${this.getApiKey()}`,
      'HTTP-Referer': 'https://github.com/ys-code-agent',
      'X-Title': 'YS Code Agent',
    };
  }
}
