import { OpenAIProvider } from './openai.js';
import { ProviderConfig, ModelConfig } from '../types.js';

export class GroqProvider extends OpenAIProvider {
  constructor(config: ProviderConfig, modelConfig: ModelConfig) {
    super(config, modelConfig);
  }

  getName(): string {
    return 'Groq';
  }

  getType(): string {
    return 'groq';
  }
}
