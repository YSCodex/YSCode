import chalk from 'chalk';
import { getLogger } from '../logger/index.js';
import { configManager } from '../config/index.js';
import { phoneConfig } from './phoneOptimizer.js';
import { CommandPopup, CATEGORY_ICONS, CommandEntry } from './CommandPopup.js';
import { generateWelcome, animateDiamond } from './WelcomeScreen.js';
import { AgentStatus } from '../types.js';
import { createInterface, cursorTo, clearLine, clearScreenDown, moveCursor } from 'readline';

const logger = getLogger('ui');

const INDICATORS: Record<AgentStatus, string> = {
  idle: '○',
  thinking: '◐',
  planning: '◑',
  executing: '◓',
  waiting: '◒',
  completed: '●',
  error: '✕',
};

const STATUS_COLORS: Record<AgentStatus, (s: string) => string> = {
  idle: chalk.gray,
  thinking: chalk.cyan,
  planning: chalk.yellow,
  executing: chalk.magenta,
  waiting: chalk.blue,
  completed: chalk.green,
  error: chalk.red,
};

export type ApprovalMode = 'safe' | 'normal' | 'yolo';
export type AgentMode = 'chat' | 'plan' | 'goal' | 'review' | 'arena';

export class TUI {
  private status: AgentStatus = 'idle';
  private approvalMode: ApprovalMode = 'normal';
  private agentMode: AgentMode = 'chat';
  private onInput: ((input: string) => void) | null = null;
  private onCancelRequest: (() => void) | null = null;
  private running = false;
  private commandPopup: CommandPopup;
  private inputHistory: string[] = [];
  private historyIndex = -1;
  private popupActive = false;
  private cancelRequested = false;
  private rl: ReturnType<typeof createInterface> | null = null;

  constructor() {
    this.commandPopup = new CommandPopup();
    this.loadHistory();
  }

  private loadHistory(): void {
    try {
      const { readFileSync, existsSync } = require('fs');
      const { join, homedir } = require('path');
      const histPath = join(homedir(), '.ys', 'history.json');
      if (existsSync(histPath)) {
        this.inputHistory = JSON.parse(readFileSync(histPath, 'utf-8'));
      }
    } catch {}
  }

  private saveHistory(): void {
    try {
      const { writeFileSync, mkdirSync, existsSync } = require('fs');
      const { join, homedir, dirname } = require('path');
      const histPath = join(homedir(), '.ys', 'history.json');
      const dir = dirname(histPath);
      if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
      writeFileSync(histPath, JSON.stringify(this.inputHistory.slice(-500)), 'utf-8');
    } catch {}
  }

  setOnCancelRequest(cb: () => void): void {
    this.onCancelRequest = cb;
  }

  start(): void {
    this.running = true;
    if (!process.stdin.isTTY) {
      this.startLineMode();
      return;
    }
    this.startTTYMode();
  }

  private startTTYMode(): void {
    const self = this;
    this.rl = createInterface({
      input: process.stdin,
      output: process.stdout,
      prompt: '',
      historySize: 0,
      removeHistoryDuplicates: true,
    });

    this.rl.on('line', async (line: string) => {
      const trimmed = line.trim();
      if (trimmed) {
        this.addHistory(trimmed);
        if (this.onInput) {
          await this.onInput(trimmed);
        }
      }
      this.showPrompt();
    });

    this.rl.on('SIGINT', () => {
      if (this.cancelRequested || this.status === 'thinking' || this.status === 'executing') {
        if (this.onCancelRequest) this.onCancelRequest();
      } else {
        process.stdout.write('\n');
        this.printLine(chalk.yellow('Use /exit to quit, or press Ctrl+C again to force quit'));
        this.cancelRequested = true;
        setTimeout(() => { this.cancelRequested = false; }, 2000);
      }
      this.showPrompt();
    });

    this.rl.on('close', () => {
      process.exit(0);
    });

    this.showPrompt();
  }

  private startLineMode(): void {
    const rl = createInterface({ input: process.stdin, output: process.stdout, prompt: '' });
    rl.on('line', (line: string) => {
      const trimmed = line.trim();
      if (trimmed && this.onInput) {
        this.addHistory(trimmed);
        this.onInput(trimmed);
      }
      rl.prompt();
    });
    rl.on('SIGINT', () => process.exit(0));
    rl.prompt();
  }

  private buildPromptPrefix(): string {
    const statusIndicator = STATUS_COLORS[this.status](INDICATORS[this.status]);
    const config = configManager.getConfig();
    const model = config.model.model;
    const modelShort = model.length > 18 ? model.slice(0, 16) + '…' : model;

    let modeBadge = '';
    if (this.agentMode === 'plan') modeBadge = chalk.blue(' [plan]');
    else if (this.agentMode === 'goal') modeBadge = chalk.magenta(' [goal]');
    else if (this.agentMode === 'arena') modeBadge = chalk.yellow(' [arena]');

    let approvalBadge = '';
    if (this.approvalMode === 'safe') approvalBadge = chalk.cyan(' [safe]');
    else if (this.approvalMode === 'yolo') approvalBadge = chalk.red(' [yolo]');

    return `${statusIndicator} ${chalk.cyan('ys')} ${chalk.yellow(`[${modelShort}]`)}${modeBadge}${approvalBadge} ${chalk.gray('›')} `;
  }

  showPrompt(): void {
    if (!this.running || !this.rl) return;
    this.rl.setPrompt(this.buildPromptPrefix());
    this.rl.prompt(true);
  }

  private addHistory(input: string): void {
    if (this.inputHistory[this.inputHistory.length - 1] !== input) {
      this.inputHistory.push(input);
    }
    this.historyIndex = -1;
    this.saveHistory();
  }

  setOnInput(handler: (input: string) => void): void {
    this.onInput = handler;
  }

  setStatus(status: AgentStatus): void {
    this.status = status;
    if (this.rl) {
      this.rl.setPrompt(this.buildPromptPrefix());
    }
  }

  setApprovalMode(mode: ApprovalMode): void {
    this.approvalMode = mode;
  }

  setAgentMode(mode: AgentMode): void {
    this.agentMode = mode;
  }

  getApprovalMode(): ApprovalMode {
    return this.approvalMode;
  }

  getAgentMode(): AgentMode {
    return this.agentMode;
  }

  printLine(line: string): void {
    if (!process.stdout.isTTY) {
      console.log(line);
      return;
    }
    // Clear current line (hides partial user input), write output
    try {
      cursorTo(process.stdout, 0);
      clearLine(process.stdout, 1);
      process.stdout.write(line + '\n');
    } catch {
      console.log(line);
    }
  }

  printAssistant(message: string): void {
    this.printLine(chalk.green(message));
  }

  printWarning(message: string): void {
    this.printLine(chalk.yellow(`⚠ ${message}`));
  }

  printError(error: string): void {
    const w = Math.max(Math.min(process.stdout.columns || 80, 56), 10);
    this.printLine(chalk.red(`╔${'═'.repeat(w)}╗`));
    this.printLine(chalk.red(`║`) + `  ${chalk.red('✗')} ${chalk.white(error)}${' '.repeat(Math.max(0, w - error.length - 7))}` + chalk.red(`║`));
    this.printLine(chalk.red(`╚${'═'.repeat(w)}╝`));
  }

  printToolCall(toolName: string, args: Record<string, unknown>): void {
    const argsStr = Object.entries(args)
      .slice(0, 3)
      .map(([k, v]) => `${k}=${String(v).slice(0, 50)}`)
      .join(', ');
    this.printLine(chalk.gray(`  ⚡ ${toolName}(${argsStr}${Object.keys(args).length > 3 ? ', ...' : ''})`));
  }

  printToolResult(result: { success: boolean; data?: unknown; error?: string }): void {
    if (result.success) {
      this.printLine(chalk.gray(`  ✓ ${chalk.green('success')}`));
    } else {
      this.printLine(chalk.gray(`  ✕ ${chalk.red(result.error || 'failed')}`));
    }
  }

  showStatusBar(_info: {
    status: AgentStatus;
    messages: number;
    tokens: number;
    task?: string;
    provider?: string;
    model?: string;
  }): void {
  }

  printWelcome(): void {
    const welcome = generateWelcome();
    for (const line of welcome.split('\n')) {
      this.printLine(line);
    }
    this.printLine('');

    if (configManager.getConfig().security.readOnlyMode) {
      this.printWarning('Read-only mode active. File modifications will be blocked.');
    }
    if (this.approvalMode === 'yolo') {
      this.printWarning('YOLO mode active. Agent will execute actions without confirmation.');
    }
  }

  printHelp(): void {
    const { ALL_COMMANDS } = require('./CommandPopup.js');
    const w = Math.max(Math.min(process.stdout.columns || 80, 72), 30);
    const top = `╔${'═'.repeat(w)}╗`;
    const bottom = `╚${'═'.repeat(w)}╝`;

    const lines: string[] = [top];
    const title = ` YS Code Agent — Commands `;
    const titlePad = Math.max(0, Math.floor((w - title.length) / 2));
    lines.push(`║${' '.repeat(titlePad)}${chalk.cyan(title)}${' '.repeat(Math.max(0, w - titlePad - title.length))}║`);
    lines.push(`╠${'═'.repeat(w)}╣`);

    const categories: Record<string, CommandEntry[]> = {};
    for (const cmd of ALL_COMMANDS) {
      if (!categories[cmd.category]) categories[cmd.category] = [];
      categories[cmd.category].push(cmd);
    }

    let first = true;
    for (const [cat, cmds] of Object.entries(categories)) {
      if (!first) lines.push(`║${' '.repeat(w)}║`);
      first = false;
      const icon = CATEGORY_ICONS[cat] || ' ';
      lines.push(`║  ${icon} ${chalk.white(cat.toUpperCase())}${' '.repeat(Math.max(0, w - 5 - cat.length))}║`);
      for (const cmd of cmds) {
        const usage = cmd.usage || `/${cmd.command}`;
        const cmdStr = chalk.yellow(usage);
        const desc = chalk.gray(cmd.description);
        const padding = ' '.repeat(Math.max(0, w - usage.length - cmd.description.length - 6));
        lines.push(`║  ${cmdStr}${padding}${desc}  ║`);
      }
    }

    lines.push(`╠${'═'.repeat(w)}╣`);
    lines.push(`║  ${chalk.gray('Keyboard Shortcuts:')}${' '.repeat(Math.max(0, w - 22))}║`);
    const shortcuts = [
      ['↑/↓', 'History navigation'],
      ['Tab', 'Autocomplete commands'],
      ['Ctrl+C', 'Cancel request'],
      ['Ctrl+L', 'Clear screen'],
      ['Ctrl+A', 'Line start'],
      ['Ctrl+E', 'Line end'],
      ['Ctrl+X', 'Open editor'],
      ['Esc', 'Cancel / clear input'],
    ];
    for (const [key, desc] of shortcuts) {
      const keyStr = chalk.cyan(key);
      const descStr = chalk.gray(desc);
      const padding = ' '.repeat(Math.max(0, w - key.length - desc.length - 6));
      lines.push(`║  ${keyStr}${padding}${descStr}  ║`);
    }
    lines.push(bottom);

    for (const l of lines) this.printLine(l);
  }

  clear(): void {
    console.clear();
  }

  stop(): void {
    this.running = false;
    if (this.rl) {
      this.rl.close();
      this.rl = null;
    }
    process.stdin.removeAllListeners('data');
  }

  destroy(): void {
    this.stop();
  }

  getOutputLineCount(): number {
    return 0;
  }
}

export const tui = new TUI();
