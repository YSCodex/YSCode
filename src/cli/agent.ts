import { configManager } from '../config/index.js';
import { getLogger } from '../logger/index.js';
import { agent } from '../agent/index.js';

const logger = getLogger('cli-agent');

export class AgentManager {
  async executeTask(task: string, timeoutMs = 300000): Promise<string> {
    logger.info(`Executing task: ${task.slice(0, 100)}`);
    const timeoutPromise = new Promise<never>((_, reject) => setTimeout(() => reject(new Error(`Task timed out after ${timeoutMs}ms`)), timeoutMs));
    const taskPromise = this.runTask(task);
    try { return await Promise.race([taskPromise, timeoutPromise]); }
    catch (error) { logger.error('Task execution failed', error); throw error; }
  }

  private async runTask(task: string): Promise<string> {
    const result = await agent.executeTask(task);
    return result.content;
  }

  async chat(message: string): Promise<string> {
    const result = await agent.chat(message);
    return result.content;
  }

  async *chatStream(message: string): AsyncGenerator<string, void, void> {
    const gen = agent.chatStream(message);
    while (true) {
      const next = await gen.next();
      if (next.done) break;
      yield next.value as string;
    }
  }

  reset(): void { agent.reset(); }
  getState() { return agent.getState(); }
  getMessages() { return agent.getMessages(); }
}