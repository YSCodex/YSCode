import chalk from 'chalk';
import { getLogger } from '../logger/index.js';
import { configManager } from '../config/index.js';
import { phoneConfig } from './phoneOptimizer.js';
import { CommandPopup, CATEGORY_ICONS, CommandEntry } from './CommandPopup.js';
import { generateWelcome, animateDiamond } from './WelcomeScreen.js';
import { AgentStatus } from '../types.js';
import { renderMarkdown } from '../utils/renderMarkdown.js';
import { cursorTo, clearLine, moveCursor, emitKeypressEvents } from 'readline';

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
  private onInput: ((input: string) => Promise<void> | void) | null = null;
  private onCancelRequest: (() => void) | null = null;
  private running = false;
  private commandPopup: CommandPopup;
  private inputHistory: string[] = [];
  private historyIndex = -1;
  private popupActive = false;
  private cancelRequested = false;

  private inputBuffer = '';
  private cursorPos = 0;
  private popupLineCount = 0;
  private prevInput: string | null = null;

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

  private startLineMode(): void {
    const { createInterface } = require('readline');
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

  private startTTYMode(): void {
    try { process.stdin.setRawMode?.(true); } catch {}
    emitKeypressEvents(process.stdin);

    this.inputBuffer = '';
    this.cursorPos = 0;
    this.popupLineCount = 0;

    process.stdin.on('keypress', this.handleKeypress.bind(this));

    this.renderScreen();
  }

  private async handleKeypress(str: string, key: { name?: string; ctrl?: boolean; meta?: boolean; shift?: boolean }): Promise<void> {
    if (!this.running) return;

    if (!key) key = {};
    const k = key.name || '';
    const c = key.ctrl || false;

    if (this.popupActive) {
      await this.handlePopupKeypress(str, key);
      return;
    }

    if (k === 'escape') {
      if (this.inputBuffer.length > 0) {
        this.inputBuffer = '';
        this.cursorPos = 0;
      }
      this.renderScreen();
      return;
    }

    if (k === 'return' || k === 'enter') {
      const input = this.inputBuffer.trim();
      this.inputBuffer = '';
      this.cursorPos = 0;
      if (input) {
        this.addHistory(input);
        if (this.onInput) {
          await this.onInput(input);
        }
      }
      this.renderScreen();
      return;
    }

    if (k === 'backspace') {
      if (this.cursorPos > 0) {
        this.inputBuffer = this.inputBuffer.slice(0, this.cursorPos - 1) + this.inputBuffer.slice(this.cursorPos);
        this.cursorPos--;
      }
      this.renderScreen();
      return;
    }

    if (k === 'up') {
      if (this.inputHistory.length > 0) {
        if (this.historyIndex === -1) {
          this.historyIndex = this.inputHistory.length - 1;
        } else if (this.historyIndex > 0) {
          this.historyIndex--;
        }
        this.inputBuffer = this.inputHistory[this.historyIndex];
        this.cursorPos = this.inputBuffer.length;
      }
      this.renderScreen();
      return;
    }

    if (k === 'down') {
      if (this.historyIndex >= 0) {
        this.historyIndex++;
        if (this.historyIndex >= this.inputHistory.length) {
          this.historyIndex = -1;
          this.inputBuffer = '';
        } else {
          this.inputBuffer = this.inputHistory[this.historyIndex];
        }
        this.cursorPos = this.inputBuffer.length;
      }
      this.renderScreen();
      return;
    }

    if (k === 'left') {
      if (this.cursorPos > 0) this.cursorPos--;
      this.renderScreen();
      return;
    }

    if (k === 'right') {
      if (this.cursorPos < this.inputBuffer.length) this.cursorPos++;
      this.renderScreen();
      return;
    }

    if (k === 'tab') {
      this.handleTab();
      this.renderScreen();
      return;
    }

    if (c && k === 'c') {
      if (this.cancelRequested || this.status === 'thinking' || this.status === 'executing') {
        if (this.onCancelRequest) this.onCancelRequest();
      } else {
        this.printLine('');
        this.printLine(chalk.yellow('Use /exit to quit, or press Ctrl+C again to force quit'));
        this.cancelRequested = true;
        setTimeout(() => { this.cancelRequested = false; }, 2000);
      }
      this.renderScreen();
      return;
    }

    if (c && k === 'l') {
      this.clear();
      this.renderScreen();
      return;
    }

    if (c && k === 'x') {
      await this.openExternalEditor();
      return;
    }

    if (c && k === 'a') {
      this.cursorPos = 0;
      this.renderScreen();
      return;
    }

    if (c && k === 'e') {
      this.cursorPos = this.inputBuffer.length;
      this.renderScreen();
      return;
    }

    if (str && str.length === 1 && str.charCodeAt(0) >= 32) {
      this.inputBuffer = this.inputBuffer.slice(0, this.cursorPos) + str + this.inputBuffer.slice(this.cursorPos);
      this.cursorPos++;

      if (str === '/') {
        this.popupActive = true;
        this.commandPopup.open();
        this.commandPopup.setFilter('');
        this.renderScreen();
        return;
      }

      if (this.inputBuffer.startsWith('/') && this.inputBuffer.length > 1) {
        this.popupActive = true;
        this.commandPopup.open();
        this.commandPopup.setFilter(this.inputBuffer.slice(1));
        this.renderScreen();
        return;
      }
    }

    this.renderScreen();
  }

  private clearPopupLines(): void {
    if (this.popupLineCount > 0) {
      try { moveCursor(process.stdout, 0, -(this.popupLineCount + 1)); } catch {}
      for (let i = 0; i <= this.popupLineCount; i++) {
        try { cursorTo(process.stdout, 0); clearLine(process.stdout, 1); process.stdout.write('\n'); } catch {}
      }
      this.popupLineCount = 0;
    }
  }

  private async handlePopupKeypress(str: string, key: { name?: string; ctrl?: boolean }): Promise<void> {
    const k = key.name || '';

    if (k === 'escape') {
      this.clearPopupLines();
      this.popupActive = false;
      this.commandPopup.close();
      this.inputBuffer = '';
      this.cursorPos = 0;
      this.renderScreen();
      return;
    }

    if (k === 'up') {
      this.commandPopup.moveUp();
      this.renderScreen();
      return;
    }

    if (k === 'down') {
      this.commandPopup.moveDown();
      this.renderScreen();
      return;
    }

    if (k === 'return' || k === 'enter') {
      const cmd = this.commandPopup.getSelectedCommand();
      this.popupActive = false;
      this.commandPopup.close();
      this.clearPopupLines();

      if (cmd) {
        const fullInput = `/${cmd} `;
        if (this.onInput) {
          await this.onInput(fullInput.trim());
        }
      }
      this.renderScreen();
      return;
    }

    if (k === 'backspace') {
      this.commandPopup.deleteChar();
      const filter = this.commandPopup.getFilterText();
      if (filter.length === 0) {
        this.clearPopupLines();
        this.popupActive = false;
        this.commandPopup.close();
        this.inputBuffer = '/';
        this.cursorPos = 1;
      }
      this.renderScreen();
      return;
    }

    if (str && str.length === 1 && str.charCodeAt(0) >= 32) {
      this.commandPopup.appendChar(str);
      this.renderScreen();
      return;
    }
  }

  private handleTab(): void {
    const match = this.inputBuffer.match(/^\/?(\w*)$/);
    if (match) {
      const partial = match[1].toLowerCase();
      const { ALL_COMMANDS } = require('./CommandPopup.js');
      const cmds = ALL_COMMANDS.map((c: CommandEntry) => c.command).filter((c: string) => c.startsWith(partial));
      if (cmds.length === 1) {
        this.inputBuffer = `/${cmds[0]} `;
        this.cursorPos = this.inputBuffer.length;
      }
    }
  }

  private async openExternalEditor(): Promise<void> {
    const editor = process.env.EDITOR || 'nano';
    const { writeFileSync, unlinkSync, existsSync, mkdirSync, readFileSync } = await import('fs');
    const tmpDir = '/tmp/ys-agent';
    if (!existsSync(tmpDir)) mkdirSync(tmpDir, { recursive: true });
    const tmpFile = `/tmp/ys-agent/input-${Date.now()}.md`;
    writeFileSync(tmpFile, this.inputBuffer, 'utf-8');

    const { execSync } = await import('child_process');
    try {
      execSync(`${editor} "${tmpFile}"`, { stdio: 'inherit' });
      const content = readFileSync(tmpFile, 'utf-8').trim();
      if (content) {
        this.inputBuffer = content;
        this.cursorPos = content.length;
      }
    } catch {}
    try { unlinkSync(tmpFile); } catch {}
    this.renderScreen();
  }

  private renderScreen(): void {
    if (!process.stdout.isTTY) return;

    const promptLine = this.buildPromptLine();

    if (this.popupActive && this.commandPopup.isVisible()) {
      if (this.popupLineCount > 0) {
        try { process.stdout.write('\x1b[' + (this.popupLineCount) + 'F'); } catch {}
      }

      const popup = this.commandPopup.render();
      const popupLines = popup.split('\n');
      this.popupLineCount = popupLines.length;

      for (const l of popupLines) {
        try {
          process.stdout.write('\r');
          clearLine(process.stdout, 1);
          process.stdout.write(l + '\n');
        } catch {}
      }
    } else if (this.popupLineCount > 0) {
      try { process.stdout.write('\x1b[' + (this.popupLineCount) + 'F'); } catch {}
      for (let i = 0; i < this.popupLineCount; i++) {
        try {
          process.stdout.write('\r');
          clearLine(process.stdout, 1);
          process.stdout.write('\n');
        } catch {}
      }
      this.popupLineCount = 0;
    }

    try {
      process.stdout.write('\r');
      clearLine(process.stdout, 1);
      process.stdout.write(promptLine);
    } catch {}
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

  private buildPromptLine(): string {
    const prefix = this.buildPromptPrefix();
    const input = this.inputBuffer || '';
    const cursor = chalk.gray('█');
    let line: string;

    if (this.cursorPos >= input.length) {
      line = prefix + input + cursor;
    } else {
      const before = input.slice(0, this.cursorPos);
      const after = input.slice(this.cursorPos);
      line = prefix + before + cursor + after;
    }

    return line;
  }

  showPrompt(): void {
    if (this.running) this.renderScreen();
  }

  private addHistory(input: string): void {
    if (this.inputHistory[this.inputHistory.length - 1] !== input) {
      this.inputHistory.push(input);
    }
    this.historyIndex = -1;
    this.saveHistory();
  }

  setOnInput(handler: (input: string) => Promise<void> | void): void {
    this.onInput = handler;
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
      cursorTo(process.stdout, 0);
      clearLine(process.stdout, 1);
      process.stdout.write(line + '\n');
    } catch {
      console.log(line);
    }
  }

  printAssistant(message: string): void {
    const rendered = renderMarkdown(message);
    for (const line of rendered.split('\n')) {
      this.printLine(line);
    }
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

  showStatusBar(_info: { status: AgentStatus; messages: number; tokens: number; task?: string; provider?: string; model?: string }): void {
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
    process.stdin.removeAllListeners('keypress');
    try { process.stdin.setRawMode?.(false); } catch {}
  }

  destroy(): void {
    this.stop();
  }

  getOutputLineCount(): number {
    return 0;
  }
}

export const tui = new TUI();
