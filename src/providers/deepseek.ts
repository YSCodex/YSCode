import { OpenAIProvider } from './openai.js';
import { ProviderConfig, ModelConfig } from '../types.js';

export class DeepSeekProvider extends OpenAIProvider {
  constructor(config: ProviderConfig, modelConfig: ModelConfig) {
    super(config, modelConfig);
  }

  getName(): string {
    return 'DeepSeek';
  }

  getType(): string {
    return 'deepseek';
  }
}
