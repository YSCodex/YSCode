import chalk from 'chalk';
import { getLogger } from '../logger/index.js';
import { AgentManager } from './agent.js';
import { tui } from '../ui/index.js';
import { agent } from '../agent/index.js';
import { configManager } from '../config/index.js';
import { memoryManager } from '../memory/index.js';
import { sessionManager } from '../session/index.js';
import { executeCommand } from '../commands/CommandRegistry.js';
import { AgentMessage } from '../types.js';
import { formatDuration } from '../utils/index.js';

const logger = getLogger('interactive');

export class InteractiveMode {
  private agentManager: AgentManager;
  private running = true;

  constructor() {
    this.agentManager = new AgentManager();
  }

  async start(initialMessage?: string): Promise<void> {
    logger.info('Starting interactive mode');
    this.checkApiKey();
    await this.initProject();
    tui.start();
    tui.setOnInput(async (input) => {
      await this.handleInput(input);
    });
    tui.setOnCancelRequest(() => {
      this.handleCancel();
    });
    if (initialMessage) {
      await this.handleInput(initialMessage);
    }
  }

  private async initProject(): Promise<void> {
    try {
      memoryManager.store('project_root', process.cwd(), 'project');
      memoryManager.addSummary(`Working directory: ${process.cwd()}`, 'project');
      const { analyzeProject } = await import('../project/index.js');
      const projectInfo = await analyzeProject();
      if (projectInfo) {
        memoryManager.store('project_info', JSON.stringify(projectInfo), 'project');
        if (projectInfo.languages?.length) {
          memoryManager.addSummary(`Languages: ${projectInfo.languages.join(', ')}`, 'project');
        }
        if (projectInfo.frameworks?.length) {
          memoryManager.addSummary(`Frameworks: ${projectInfo.frameworks.join(', ')}`, 'project');
        }
      }
    } catch {
      logger.debug('Project auto-analysis skipped');
    }
  }

  private checkApiKey(): void {
    const config = configManager.getConfig();
    const provider = configManager.getActiveProvider();
    if (!provider.apiKey && !process.env[`${provider.type.toUpperCase()}_API_KEY`]) {
      tui.printLine(chalk.yellow(`\n⚠ No API key configured for ${provider.name}`));
      tui.printLine(chalk.gray(`  Set ${provider.type.toUpperCase()}_API_KEY environment variable`));
      tui.printLine(chalk.gray('  Or use /provider <name> to switch to a configured provider'));
    }
  }

  private async handleInput(input: string): Promise<void> {
    if (!input.trim()) return;
    if (input.startsWith('/')) {
      const handled = await executeCommand(input);
      if (!handled) {
      }
      return;
    }
    await this.handleMessage(input);
  }

  private handleCancel(): void {
    tui.printLine(chalk.yellow('\n⚠ Request cancelled'));
    tui.setStatus('idle');
  }

  private async handleMessage(message: string): Promise<void> {
    if (!message.trim()) return;
    tui.setStatus('thinking');
    tui.printLine('');
    const abortController = new AbortController();
    tui.abortController = abortController;
    try {
      const startTime = Date.now();
      const stream = agent.chatStream(message);
      let fullContent = '';
      tui.printLine(chalk.gray('  ─'));
      process.stdout.write('  ');
      for await (const chunk of stream) {
        if (abortController.signal.aborted) break;
        fullContent += chunk;
        process.stdout.write(chunk);
      }
      const duration = Date.now() - startTime;
      tui.setStatus('completed');
      tui.printLine('');
      this.showStatusLine(duration);
    } catch (error) {
      if (abortController.signal.aborted) {
        tui.printLine(chalk.yellow('\n⚠ Cancelled'));
      } else {
        tui.setStatus('error');
        const msg = error instanceof Error ? error.message : String(error);
        tui.printError(msg);
        logger.error('Chat error', error);
      }
    }
    tui.abortController = null;
    tui.setStatus('idle');
  }

  private showStatusLine(duration: number): void {
    const config = configManager.getConfig();
    const messages = agent.getMessages();
    const totalTokens = messages.reduce((acc, m) => acc + this.countTokens(m.content), 0);
    const statusStr = chalk.gray(`[${formatDuration(duration)}] [${config.activeProvider}/${config.model.model}] [${messages.length} msgs]`);
    tui.printLine(statusStr);
  }

  private countTokens(text: string): number {
    let tokens = 0;
    for (let i = 0; i < text.length; i++) {
      const char = text[i];
      if (char.match(/[\x00-\x7F]/)) {
        tokens += char.match(/[a-zA-Z0-9]/) ? 0.25 : 1;
      } else if (char.match(/[\u4e00-\u9fff\u3040-\u309f\u30a0-\u30ff]/)) {
        tokens += 2;
      } else {
        tokens += 1;
      }
    }
    return Math.ceil(tokens);
  }
}
