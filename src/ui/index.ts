import chalk from 'chalk';
import { getLogger } from '../logger/index.js';
import { configManager } from '../config/index.js';
import { phoneConfig } from './phoneOptimizer.js';
import { CommandPopup, CATEGORY_ICONS, CommandEntry } from './CommandPopup.js';
import { generateWelcome, animateDiamond } from './WelcomeScreen.js';
import { AgentStatus } from '../types.js';

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
  private outputLines: string[] = [];
  private maxOutputLines = 1000;
  private onInput: ((input: string) => void) | null = null;
  private onSpecialKey: ((key: string) => void) | null = null;
  private running = false;
  private commandPopup: CommandPopup;
  private inputBuffer = '';
  private inputHistory: string[] = [];
  private historyIndex = -1;
  private cursorPos = 0;
  private popupActive = false;
  private cancelRequested = false;
  private onCancelRequest: (() => void) | null = null;
  private stdinRaw = false;

  constructor() {
    this.commandPopup = new CommandPopup();
    this.loadHistory();
    this.setupRawMode();
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

  private setupRawMode(): void {
    if (!process.stdin.isTTY) return;
    try {
      process.stdin.setRawMode?.(true);
      this.stdinRaw = true;
    } catch {
      this.stdinRaw = false;
    }
  }

  setOnCancelRequest(cb: () => void): void {
    this.onCancelRequest = cb;
  }

  start(): void {
    this.running = true;
    this.showPrompt();
    this.startKeyListener();
  }

  private startKeyListener(): void {
    if (!process.stdin.isTTY) {
      this.startLineMode();
      return;
    }

    process.stdin.on('data', (data: Buffer) => {
      if (!this.running) return;
      const input = data.toString('utf-8');

      if (this.popupActive) {
        this.handlePopupKey(input);
        this.renderScreen();
        return;
      }

      if (input === '\x1b' || input === '\x1b[' || input === '\x1b[' && data.length > 2) {
        if (input === '\x1b') {
          if (this.inputBuffer.length === 0) {
            void this.handleSpecialKey('escape');
          } else {
            this.inputBuffer = '';
            this.cursorPos = 0;
            this.renderScreen();
          }
          return;
        }
        return;
      }

      if (input === '\x1b[A') {
        void this.handleSpecialKey('up');
        return;
      }
      if (input === '\x1b[B') {
        void this.handleSpecialKey('down');
        return;
      }
      if (input === '\x1b[C') {
        void this.handleSpecialKey('right');
        return;
      }
      if (input === '\x1b[D') {
        void this.handleSpecialKey('left');
        return;
      }

      if (input === '\r' || input === '\n') {
        void this.handleSpecialKey('enter');
        return;
      }

      if (input === '\x7f' || input === '\b') {
        void this.handleSpecialKey('backspace');
        return;
      }

      if (input === '\x03') {
        void this.handleSpecialKey('ctrl_c');
        return;
      }
      if (input === '\x0c') {
        void this.handleSpecialKey('ctrl_l');
        return;
      }
      if (input === '\x01') {
        void this.handleSpecialKey('ctrl_a');
        return;
      }
      if (input === '\x05') {
        void this.handleSpecialKey('ctrl_e');
        return;
      }
      if (input === '\x0a' || input === '\x0d') {
        return;
      }
      if (input === '\x09') {
        void this.handleSpecialKey('tab');
        return;
      }
      if (input === '\x0f') {
        void this.handleSpecialKey('ctrl_o');
        return;
      }
      if (input === '\x18') {
        void this.handleSpecialKey('ctrl_x');
        return;
      }

      this.inputBuffer = this.inputBuffer.slice(0, this.cursorPos) + input + this.inputBuffer.slice(this.cursorPos);
      this.cursorPos += input.length;

      if (input === '/') {
        this.popupActive = true;
        this.commandPopup.open();
        this.commandPopup.setFilter(input);
      } else {
        if (this.inputBuffer.startsWith('/') && this.inputBuffer.length > 1) {
          this.popupActive = true;
          this.commandPopup.open();
          this.commandPopup.setFilter(this.inputBuffer);
        }
      }

      this.renderScreen();
    });

    process.stdin.on('end', () => {});
  }

  private startLineMode(): void {
    const { createInterface } = require('readline');
    const rl = createInterface({ input: process.stdin, output: process.stdout, prompt: '' });
    rl.on('line', (line: string) => {
      this.inputBuffer = line.trim();
      if (this.inputBuffer && this.onInput) {
        this.addHistory(this.inputBuffer);
        this.onInput(this.inputBuffer);
      }
      this.inputBuffer = '';
      this.showPrompt();
    });
    rl.on('SIGINT', () => process.exit(0));
    this.showPrompt();
  }

  private handlePopupKey(input: string): void {
    if (input === '\x1b') {
      this.popupActive = false;
      this.commandPopup.close();
      this.inputBuffer = '';
      this.cursorPos = 0;
      return;
    }

    if (input === '\x1b[A' || input === '\x1bOA') {
      this.commandPopup.moveUp();
      return;
    }
    if (input === '\x1b[B' || input === '\x1bOB') {
      this.commandPopup.moveDown();
      return;
    }

    if (input === '\r' || input === '\n') {
      const selectedCmd = this.commandPopup.getSelectedCommand();
      if (selectedCmd) {
        this.popupActive = false;
        this.commandPopup.close();
        const fullCmd = `/${selectedCmd} `;
        this.inputBuffer = fullCmd;
        this.cursorPos = fullCmd.length;
      }
      return;
    }

    if (input === '\x09') {
      const selectedCmd = this.commandPopup.getSelectedCommand();
      if (selectedCmd) {
        this.popupActive = false;
        this.commandPopup.close();
        const fullCmd = `/${selectedCmd} `;
        this.inputBuffer = fullCmd;
        this.cursorPos = fullCmd.length;
      }
      return;
    }

    if (input === '\x7f' || input === '\b') {
      const currentFilter = this.commandPopup.getFilterText();
      if (currentFilter.length <= 1) {
        this.popupActive = false;
        this.commandPopup.close();
        this.inputBuffer = '';
        this.cursorPos = 0;
        return;
      }
      this.commandPopup.deleteChar();
      return;
    }

    if (input.length === 1 && input.charCodeAt(0) >= 32) {
      this.commandPopup.appendChar(input);
      this.inputBuffer = this.commandPopup.getFilterText();
      this.cursorPos = this.inputBuffer.length;
    }
  }

  private async handleSpecialKey(key: string): Promise<void> {
    switch (key) {
      case 'escape':
        if (this.inputBuffer.length > 0) {
          this.inputBuffer = '';
          this.cursorPos = 0;
          this.renderScreen();
        }
        break;

      case 'enter':
        if (this.inputBuffer.trim()) {
          this.addHistory(this.inputBuffer.trim());
          const msg = this.inputBuffer.trim();
          this.inputBuffer = '';
          this.cursorPos = 0;
          this.clearOutputLine();
          if (this.onInput) this.onInput(msg);
        }
        this.renderScreen();
        break;

      case 'backspace':
        if (this.cursorPos > 0) {
          this.inputBuffer = this.inputBuffer.slice(0, this.cursorPos - 1) + this.inputBuffer.slice(this.cursorPos);
          this.cursorPos--;
          this.updatePopupFilter();
        }
        this.renderScreen();
        break;

      case 'up':
        if (this.inputHistory.length > 0) {
          if (this.historyIndex === -1) {
            this.historyIndex = this.inputHistory.length - 1;
          } else if (this.historyIndex > 0) {
            this.historyIndex--;
          }
          this.inputBuffer = this.inputHistory[this.historyIndex];
          this.cursorPos = this.inputBuffer.length;
          this.renderScreen();
        }
        break;

      case 'down':
        if (this.historyIndex >= 0) {
          this.historyIndex++;
          if (this.historyIndex >= this.inputHistory.length) {
            this.historyIndex = -1;
            this.inputBuffer = '';
          } else {
            this.inputBuffer = this.inputHistory[this.historyIndex];
          }
          this.cursorPos = this.inputBuffer.length;
          this.renderScreen();
        }
        break;

      case 'left':
        if (this.cursorPos > 0) {
          this.cursorPos--;
          this.renderScreen();
        }
        break;

      case 'right':
        if (this.cursorPos < this.inputBuffer.length) {
          this.cursorPos++;
          this.renderScreen();
        }
        break;

      case 'ctrl_a':
        this.cursorPos = 0;
        this.renderScreen();
        break;

      case 'ctrl_e':
        this.cursorPos = this.inputBuffer.length;
        this.renderScreen();
        break;

      case 'ctrl_c':
        if (this.cancelRequested || this.status === 'thinking' || this.status === 'executing') {
          if (this.onCancelRequest) this.onCancelRequest();
        } else {
          this.printLine('\n' + chalk.yellow('Use /exit to quit, or press Ctrl+C again to force quit'));
          this.cancelRequested = true;
          setTimeout(() => { this.cancelRequested = false; }, 2000);
        }
        break;

      case 'ctrl_l':
        this.clear();
        this.printWelcome();
        this.showPrompt();
        break;

      case 'tab': {
        const match = this.inputBuffer.match(/^\/?(\w*)$/);
        if (match) {
          const partial = match[1].toLowerCase();
          const cmds = this.getAllCommands().filter((c) => c.startsWith(partial));
          if (cmds.length === 1) {
            this.inputBuffer = `/${cmds[0]} `;
            this.cursorPos = this.inputBuffer.length;
            this.renderScreen();
          }
        }
        break;
      }

      case 'ctrl_o':
        break;

      case 'ctrl_x':
        void this.openExternalEditor();
        break;
    }
  }

  private updatePopupFilter(): void {
    if (this.popupActive) {
      this.commandPopup.setFilter(this.inputBuffer);
      if (this.inputBuffer === '/' || this.inputBuffer === '') {
        this.popupActive = false;
        this.commandPopup.close();
      }
    }
  }

  private getAllCommands(): string[] {
    const { ALL_COMMANDS } = require('./CommandPopup.js');
    return ALL_COMMANDS.map((c: { command: string }) => c.command);
  }

  private async openExternalEditor(): Promise<void> {
    const editor = process.env.EDITOR || 'nano';
    const { writeFileSync, unlinkSync, existsSync, mkdirSync } = await import('fs');
    const { join } = await import('path');
    const tmpDir = '/tmp/ys-agent';
    if (!existsSync(tmpDir)) mkdirSync(tmpDir, { recursive: true });
    const tmpFile = join(tmpDir, `input-${Date.now()}.md`);
    writeFileSync(tmpFile, this.inputBuffer, 'utf-8');

    const { execSync } = await import('child_process');
    try {
      execSync(`${editor} "${tmpFile}"`, { stdio: 'inherit' });
      const { readFileSync } = await import('fs');
      const content = readFileSync(tmpFile, 'utf-8').trim();
      if (content) {
        this.inputBuffer = content;
        this.cursorPos = content.length;
        this.renderScreen();
      }
    } catch {}
    try { unlinkSync(tmpFile); } catch {}
  }

  private addHistory(input: string): void {
    if (this.inputHistory[this.inputHistory.length - 1] !== input) {
      this.inputHistory.push(input);
    }
    this.historyIndex = -1;
    this.saveHistory();
  }

  private renderScreen(): void {
    if (!process.stdout.isTTY) return;
    const lines = this.buildDisplayLines();
    const clearSeq = `\x1b[0J\x1b[${process.stdout.rows || 24};0H`;
    const output = clearSeq + lines.join('\n');
    try {
      cursorToSync(process.stdout, 0, (process.stdout.rows || 24) - lines.length);
      process.stdout.write(output);
    } catch {}
  }

  private buildDisplayLines(): string[] {
    const lines: string[] = [];

    if (this.popupActive) {
      const popupLines = this.commandPopup.render().split('\n');
      lines.push(...popupLines);
    }

    lines.push(this.buildPromptLine());
    return lines;
  }

  stop(): void {
    this.running = false;
    if (this.stdinRaw && process.stdin.isTTY) {
      try { process.stdin.setRawMode?.(false); } catch {}
    }
    process.stdin.removeAllListeners('data');
    this.showShutdownScreen();
  }

  clear(): void {
    console.clear();
  }

  private clearOutputLine(): void {
    try {
      const { cursorTo, clearLine } = require('readline');
      cursorTo(process.stdout, 0);
      clearLine(process.stdout, 1);
    } catch {}
  }

  printWelcome(): void {
    const welcome = generateWelcome();
    const lines = welcome.split('\n');
    for (const line of lines) {
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

  printWarning(message: string): void {
    const w = Math.max(Math.min(process.stdout.columns || 80, 60), 10);
    const top = `╔${'═'.repeat(w)}╗`;
    const bottom = `╚${'═'.repeat(w)}╝`;
    this.printLine('');
    this.printLine(chalk.yellow(top));
    const warnLine = `  ⚠  ${chalk.yellow(message)}`;
    const pad = Math.max(0, w - warnLine.length);
    this.printLine(`║${warnLine}${' '.repeat(pad)}║`);
    this.printLine(chalk.yellow(bottom));
  }

  showPrompt(): void {
    if (!this.running) return;
    const promptLine = this.buildPromptLine();
    if (phoneConfig.isTermux) {
      this.printLine(promptLine);
    } else {
      this.renderScreen();
    }
  }

  private buildPromptLine(): string {
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

    const promptStr = `${statusIndicator} ${chalk.cyan('ys')} ${chalk.yellow(`[${modelShort}]`)}${modeBadge}${approvalBadge} ${chalk.gray('›')} `;

    const inputStr = this.inputBuffer || ' ';
    const cursorChar = phoneConfig.isTermux ? '█' : '█';
    const cursor = chalk.gray(cursorChar);
    const displayedInput = inputStr.length > 0 ? inputStr : '';

    let cursorLine = '';
    if (this.cursorPos >= displayedInput.length) {
      cursorLine = promptStr + displayedInput + cursor;
    } else {
      const before = displayedInput.slice(0, this.cursorPos);
      const after = displayedInput.slice(this.cursorPos);
      cursorLine = promptStr + before + cursor + after;
    }

    return cursorLine + '\n' + chalk.gray(`  💡 / for commands  |  ↑↓ history  |  Tab complete`);
  }

  setOnInput(handler: (input: string) => void): void {
    this.onInput = handler;
  }

  setOnSpecialKey(handler: (key: string) => void): void {
    this.onSpecialKey = handler;
  }

  setStatus(status: AgentStatus): void {
    this.status = status;
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
    try {
      const { cursorTo, clearLine } = require('readline');
      cursorTo(process.stdout, 0);
      clearLine(process.stdout, 1);
      console.log(line);
    } catch {
      console.log(line);
    }
    this.outputLines.push(line);
    if (this.outputLines.length > this.maxOutputLines) {
      this.outputLines.shift();
    }
  }

  printAssistant(message: string): void {
    this.printLine('');
    const formatted = chalk.green(message);
    this.printLine(formatted);
  }

  printError(error: string): void {
    const w = Math.max(Math.min(process.stdout.columns || 80, 56), 10);
    const top = `╔${'═'.repeat(w)}╗`;
    const bottom = `╚${'═'.repeat(w)}╝`;
    this.printLine('');
    this.printLine(chalk.red(top));
    this.printLine(`║  ${chalk.red('✗')} ${chalk.white(error)}${' '.repeat(Math.max(0, w - error.length - 7))}║`);
    this.printLine(chalk.red(bottom));
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

  showStatusBar(info: {
    status: AgentStatus;
    messages: number;
    tokens: number;
    task?: string;
    provider?: string;
    model?: string;
  }): void {
    const statusColor = STATUS_COLORS[info.status];
    const statusIcon = INDICATORS[info.status];
    const truncated = info.task ? info.task.slice(0, 40) + (info.task.length > 40 ? '...' : '') : '';

    const parts = [
      `${statusColor(`${statusIcon} ${info.status}`)}`,
      chalk.gray(`msgs:${info.messages}`),
      chalk.gray(`tok:${info.tokens}`),
    ];

    if (info.provider) parts.push(chalk.gray(`prov:${info.provider}`));
    if (truncated) parts.push(chalk.gray(`task:${truncated}`));
  }

  private showShutdownScreen(): void {
    const w = Math.max(Math.min(process.stdout.columns || 80, 50), 30);
    const top = `╭${'─'.repeat(w)}╮`;
    const bottom = `╰${'─'.repeat(w)}╯`;

    console.log('');
    console.log(chalk.cyan(top));
    console.log(`│${' '.repeat(w)}│`);
    const titleLine = '  ◆ YS Code Agent — Session Complete  ';
    const titlePad = Math.max(0, Math.floor((w - titleLine.length) / 2));
    console.log(`│${' '.repeat(titlePad)}${chalk.cyan(titleLine)}${' '.repeat(Math.max(0, w - titlePad - titleLine.length))}│`);
    console.log(`│${' '.repeat(w)}│`);

    const { sessionManager } = require('../session/index.js');
    const session = sessionManager.getCurrentSession();
    if (session) {
      const sessionId = session.id.slice(0, 8);
      const duration = formatDuration(Date.now() - session.createdAt);
      const msgCount = session.state.messages.length;
      console.log(`│  ${chalk.white('Session')}: ${chalk.yellow(sessionId.padEnd(w - 13))}│`);
      console.log(`│  ${chalk.white('Duration')}: ${chalk.yellow(duration.padEnd(w - 14))}│`);
      console.log(`│  ${chalk.white('Messages')}: ${chalk.yellow(String(msgCount).padEnd(w - 14))}│`);
    }
    console.log(`│${' '.repeat(w)}│`);
    console.log(`│  ${chalk.gray('Session saved. Resume with:')} ${chalk.yellow('ys --resume <session>')}${' '.repeat(Math.max(0, w - 43))}│`);
    console.log(`│${' '.repeat(w)}│`);
    console.log(chalk.cyan(bottom));
    console.log('');
  }

  destroy(): void {
    if (this.stdinRaw && process.stdin.isTTY) {
      try { process.stdin.setRawMode?.(false); } catch {}
    }
    process.stdin.removeAllListeners('data');
  }

  getOutputLineCount(): number {
    return this.outputLines.length;
  }
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  return `${m}m ${s}s`;
}

function cursorToSync(stream: NodeJS.WriteStream, x: number, y: number): void {
  try {
    const { cursorTo } = require('readline');
    cursorTo(stream, x, y);
  } catch {}
}

export const tui = new TUI();
