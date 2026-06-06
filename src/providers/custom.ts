import { OpenAIProvider } from './openai.js';
import { ProviderConfig, ModelConfig } from '../types.js';

export class CustomProvider extends OpenAIProvider {
  constructor(config: ProviderConfig, modelConfig: ModelConfig) {
    super(config, modelConfig);
  }

  getName(): string {
    return this.config.name || 'Custom';
  }

  getType(): string {
    return 'custom';
  }
}
