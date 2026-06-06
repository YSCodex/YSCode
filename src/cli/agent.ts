import { configManager } from '../config/index.js';
import { getLogger } from '../logger/index.js';
import { agent } from '../agent/index.js';
import { ToolResult, AgentMessage } from '../types.js';

const logger = getLogger('cli-agent');

export class AgentManager {
  async executeTask(task: string, timeoutMs = 300000): Promise<string> {
    logger.info(`Executing task: ${task.slice(0, 100)}`);

    const timeoutPromise = new Promise<never>((_, reject) => {
      setTimeout(() => reject(new Error(`Task timed out after ${timeoutMs}ms`)), timeoutMs);
    });

    const taskPromise = this.runTask(task);

    try {
      const result = await Promise.race([taskPromise, timeoutPromise]);
      return result;
    } catch (error) {
      logger.error('Task execution failed', error);
      throw error;
    }
  }

  private async runTask(task: string): Promise<string> {
    const result = await agent.executeTask(task);
    return result.content;
  }

  async chat(message: string): Promise<string> {
    const result = await agent.chat(message);
    return result.content;
  }

  reset(): void {
    agent.reset();
  }

  getState() {
    return agent.getState();
  }

  getMessages() {
    return agent.getMessages();
  }
}
