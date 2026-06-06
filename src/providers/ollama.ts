import { OpenAIProvider } from './openai.js';
import { ProviderConfig, ModelConfig } from '../types.js';

export class OllamaProvider extends OpenAIProvider {
  constructor(config: ProviderConfig, modelConfig: ModelConfig) {
    super(config, modelConfig);
  }

  getName(): string {
    return 'Ollama';
  }

  getType(): string {
    return 'ollama';
  }
}
