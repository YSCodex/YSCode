import chalk from 'chalk';
import { configManager } from '../config/index.js';
import { CommandPopup, ALL_COMMANDS, CATEGORY_ICONS, CommandEntry } from './CommandPopup.js';
import { generateWelcome } from './WelcomeScreen.js';
import { AgentStatus } from '../types.js';
import { cursorTo, clearLine, emitKeypressEvents, createInterface } from 'readline';

const INDICATORS: Record<AgentStatus, string> = {
  idle: '○', thinking: '◐', planning: '◑', executing: '◓', waiting: '◒', completed: '●', error: '✕',
};
const STATUS_COLORS: Record<AgentStatus, (s: string) => string> = {
  idle: chalk.gray, thinking: chalk.cyan, planning: chalk.yellow, executing: chalk.magenta, waiting: chalk.blue, completed: chalk.green, error: chalk.red,
};

export type ApprovalMode = 'safe' | 'normal' | 'yolo';
export type AgentMode = 'chat' | 'plan' | 'goal' | 'review' | 'arena';

function styleForType(text: string, type: string): string {
  switch (type) {
    case 'error': return chalk.red(text);
    case 'warning': return chalk.yellow(text);
    case 'tool': return chalk.gray(text);
    case 'result': return chalk.green(text);
    case 'system': return chalk.cyan(text);
    case 'user': return chalk.white(text);
    case 'assistant': return chalk.white(text);
    default: return text;
  }
}

type Out = { text: string; type: string };

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
  abortController: AbortController | null = null;
  private inputBuffer = '';
  private cursorPos = 0;

  private buf: Out[] = [];
  private maxBuf = 5000;

  private streamText = '';
  private streamActive = false;

  private rows = 24;
  private cols = 80;

  private spinnerFrames = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'];
  private spinnerIdx = 0;
  private spinnerTimer: ReturnType<typeof setInterval> | null = null;

  constructor() { this.commandPopup = new CommandPopup(); }

  start(): void {
    this.running = true;
    if (!process.stdin.isTTY) { this.startLineMode(); return; }
    this.startTTYMode();
  }

  private startLineMode(): void {
    const rl = createInterface({ input: process.stdin, output: process.stdout, prompt: '' });
    rl.on('line', (line: string) => { if (line.trim() && this.onInput) this.onInput(line.trim()); });
    rl.on('SIGINT', () => process.exit(0));
  }

  private startTTYMode(): void {
    this.updateSize();
    try { process.stdin.setRawMode?.(true); } catch {}
    emitKeypressEvents(process.stdin);
    process.stdin.on('keypress', this.handleKeypress.bind(this));
    process.stdout.on('resize', () => { this.updateSize(); this.redrawAll(); });
    this.initScreen();
    this.paintBar();
    this.paintPrompt();
  }

  private updateSize(): void { this.rows = process.stdout.rows || 24; this.cols = Math.min(process.stdout.columns || 80, 120); }
  private get tw(): number { return this.cols; }
  private get tr(): number { return this.rows; }
  private writeAt(row: number, text: string): void {
    if (row < 0 || row >= this.tr) return;
    cursorTo(process.stdout, 0, row);
    clearLine(process.stdout, 0);
    process.stdout.write(text.slice(0, this.tw));
  }

  private initScreen(): void {
    console.clear();
    for (let i = 0; i < this.tr; i++) this.writeAt(i, '');
  }

  private statusBarText(): string {
    const s = STATUS_COLORS[this.status](INDICATORS[this.status]);
    const cfg = configManager.getConfig();
    const m = cfg.model.model; const ms = m.length > 20 ? m.slice(0, 18) + '…' : m;
    const p = configManager.getActiveProvider(); const ps = p.name.length > 10 ? p.name.slice(0, 8) + '…' : p.name;
    let mb = ''; if (this.agentMode === 'plan') mb = chalk.blue(' plan'); else if (this.agentMode === 'goal') mb = chalk.magenta(' goal'); else if (this.agentMode === 'arena') mb = chalk.yellow(' arena');
    const left = `${s} ${chalk.cyan('ys')} ${chalk.yellow(ms)} ${chalk.gray(ps)}${mb}`;
    const right = `${chalk.gray(`${this.buf.length} msgs`)}`;
    return `${left}${' '.repeat(Math.max(0, this.tw - left.length - right.length - 2))}${right}`.slice(0, this.tw);
  }

  private paintBar(): void {
    this.writeAt(this.tr - 2, this.statusBarText());
    if (this.popupActive) {
      const popupStr = this.commandPopup.render();
      if (popupStr) {
        const lines = popupStr.split('\n');
        const startRow = Math.max(0, this.tr - 3 - lines.length);
        for (let i = 0; i < lines.length && startRow + i < this.tr - 2; i++) {
          this.writeAt(startRow + i, lines[i]);
        }
      }
    }
  }
  private paintPrompt(): void {
    const p = this.buildPrefix();
    const i = this.inputBuffer || '';
    const c = chalk.gray('█');
    const line = this.cursorPos >= i.length ? p + i + c : p + i.slice(0, this.cursorPos) + c + i.slice(this.cursorPos);
    this.writeAt(this.tr - 1, line.slice(0, this.tw));
  }
  private buildPrefix(): string {
    const s = STATUS_COLORS[this.status](INDICATORS[this.status]);
    const cfg = configManager.getConfig();
    const m = cfg.model.model; const ms = m.length > 14 ? m.slice(0, 12) + '…' : m;
    const p = configManager.getActiveProvider(); const ps = p.name.length > 10 ? p.name.slice(0, 8) + '…' : p.name;
    let mb = ''; if (this.agentMode === 'plan') mb = chalk.blue(' [plan]'); else if (this.agentMode === 'goal') mb = chalk.magenta(' [goal]');
    return `${STATUS_COLORS[this.status](INDICATORS[this.status])} ${chalk.cyan('ys')} ${chalk.gray(`[${ms}]`)}${mb} ${chalk.gray('›')} `;
  }

  // ──  Append-only output: write new text at bar row, then restore bar + prompt  ──
  // This never touches old output lines (rows 0 .. tr-3), so no flicker.

  addOutput(text: string, type: string = 'normal'): void {
    const lines = text ? text.split('\n') : [''];
    for (const l of lines) this.buf.push({ text: l, type });
    if (this.buf.length > this.maxBuf) this.buf = this.buf.slice(-this.maxBuf);
    if (!process.stdout.isTTY || !this.running) return;

    const r = this.tr;
    // Write at the status-bar row (overwrites bar temporarily)
    for (const l of lines) {
      this.writeAt(r - 2, styleForType(l, type));
      process.stdout.write('\n');
    }
    // Restore bar + prompt
    this.paintBar();
    this.paintPrompt();
  }

  // ──  Streaming: write content at the last output row (r-3), update in-place  ──

  writeStream(chunk: string): void {
    if (!this.running || !process.stdout.isTTY) return;
    this.streamActive = true;
    this.streamText += chunk;
    const r = this.tr;
    const lines = this.streamText.split('\n');
    const lastLines = lines.slice(-(r - 3));
    // Write from row 1 up to r-3, then restore bar + prompt
    for (let i = 0; i < Math.max(r - 3, lastLines.length); i++) {
      if (i < lastLines.length) this.writeAt(i, lastLines[i]);
      else this.writeAt(i, '');
    }
    this.paintBar();
    this.paintPrompt();
  }

  finalizeStream(): void {
    if (!this.streamActive) return;
    this.streamActive = false;
    if (this.streamText) {
      for (const l of this.streamText.split('\n')) this.buf.push({ text: l, type: 'assistant' });
    }
    this.streamText = '';
    if (this.buf.length > this.maxBuf) this.buf = this.buf.slice(-this.maxBuf);
    this.redrawAll();
  }

  private redrawAll(): void {
    if (!process.stdout.isTTY) return;
    const r = this.tr;
    const maxOut = Math.min(this.buf.length, r - 2);
    const start = Math.max(0, this.buf.length - maxOut);
    for (let i = 0; i < maxOut; i++) this.writeAt(i, styleForType(this.buf[start + i].text, this.buf[start + i].type));
    for (let i = maxOut; i < r - 2; i++) this.writeAt(i, '');
    this.paintBar();
    this.paintPrompt();
  }

  setOnInput(h: (input: string) => Promise<void> | void): void { this.onInput = h; }
  setOnCancelRequest(cb: () => void): void { this.onCancelRequest = cb; }

  setStatus(status: AgentStatus): void {
    this.status = status;
    if (status === 'thinking' || status === 'executing') this.startSpinner(); else this.stopSpinner();
    if (!process.stdout.isTTY) return;
    this.paintBar();
    this.paintPrompt();
  }

  setApprovalMode(m: ApprovalMode): void { this.approvalMode = m; this.refreshUI(); }
  setAgentMode(m: AgentMode): void { this.agentMode = m; this.refreshUI(); }
  getApprovalMode(): ApprovalMode { return this.approvalMode; }
  getAgentMode(): AgentMode { return this.agentMode; }

  printLine(l: string): void { this.addOutput(l, 'normal'); }
  printError(e: string): void { this.addOutput(`✕ ${e}`, 'error'); }
  printWarning(m: string): void { this.addOutput(`⚠ ${m}`, 'warning'); }
  printToolCall(name: string, args: Record<string, unknown>): void {
    const a = Object.entries(args).slice(0, 3).map(([k, v]) => `${k}=${String(v).slice(0, 50)}`).join(', ');
    this.addOutput(`⚡ ${name}(${a}${Object.keys(args).length > 3 ? ', ...' : ''})`, 'tool');
  }
  printToolResult(ok: boolean, err?: string): void { this.addOutput(ok ? '  ✓ success' : `  ✕ ${err || 'failed'}`, ok ? 'result' : 'error'); }
  printStatus(t: string): void { this.addOutput(t, 'system'); }
  printAssistant(m: string): void { for (const l of m.split('\n')) this.addOutput(l, 'assistant'); }

  printWelcome(): void {
    const w = generateWelcome();
    for (const l of w.split('\n')) this.addOutput(l, 'system');
    if (configManager.getConfig().security.readOnlyMode) this.printWarning('Read-only mode active.');
    if (this.approvalMode === 'yolo') this.printWarning('YOLO mode active.');
  }

  printHelp(): void {
    const w = Math.max(Math.min(this.tw, 72), 30);
    this.addOutput(`┌${'─'.repeat(w)}┐`, 'system');
    this.addOutput(`│${' '.repeat(Math.floor((w - 30) / 2))}${chalk.cyan(' YS Code Agent v4.0 — Commands ')}${' '.repeat(Math.ceil((w - 30) / 2))}│`, 'system');
    this.addOutput(`├${'─'.repeat(w)}┤`, 'system');
    const cats: Record<string, CommandEntry[]> = {};
    for (const cmd of ALL_COMMANDS) { if (!cats[cmd.category]) cats[cmd.category] = []; cats[cmd.category].push(cmd); }
    for (const [cat, cmds] of Object.entries(cats)) {
      this.addOutput(`  ${CATEGORY_ICONS[cat] || ' '} ${cat.toUpperCase()}`, 'system');
      for (const cmd of cmds) this.addOutput(`  ${chalk.yellow(cmd.usage || `/${cmd.command}`)}  ${chalk.gray(cmd.description)}`, 'system');
    }
    this.addOutput(`└${'─'.repeat(w)}┘`, 'system');
  }

  clear(): void { this.buf = []; this.streamActive = false; this.streamText = ''; console.clear(); this.initScreen(); this.paintBar(); this.paintPrompt(); }
  stop(): void { this.running = false; this.stopSpinner(); process.stdin.removeAllListeners('keypress'); try { process.stdin.setRawMode?.(false); } catch {} cursorTo(process.stdout, 0, this.tr - 1); clearLine(process.stdout, 0); }
  destroy(): void { this.stop(); }
  private refreshUI(): void { if (!process.stdout.isTTY) return; this.paintBar(); this.paintPrompt(); }
  getOutputLineCount(): number { return this.buf.length; }
  private getSpinner(): string { return this.spinnerFrames[this.spinnerIdx % this.spinnerFrames.length]; }
  private startSpinner(): void {
    if (this.spinnerTimer) return;
    this.spinnerTimer = setInterval(() => {
      this.spinnerIdx++;
      if (!process.stdout.isTTY) return;
      this.writeAt(this.tr - 2, this.statusBarText() + chalk.gray(` ${this.getSpinner()}`));
      this.paintPrompt();
    }, 120);
  }
  private stopSpinner(): void { if (this.spinnerTimer) { clearInterval(this.spinnerTimer); this.spinnerTimer = null; } }

  // ──  Key handling  ──

  private async handleKeypress(str: string, key: { name?: string; ctrl?: boolean; meta?: boolean; shift?: boolean }): Promise<void> {
    if (!this.running) return;
    if (!key) key = {};
    const k = key.name || ''; const c = key.ctrl || false;
    if (this.popupActive) { await this.handlePopupKeypress(str, key); return; }
    if (k === 'escape') { this.inputBuffer = ''; this.cursorPos = 0; this.paintPrompt(); return; }
    if (k === 'return' || k === 'enter') {
      const input = this.inputBuffer.trim();
      this.inputBuffer = ''; this.cursorPos = 0;
      this.paintPrompt();
      if (input && this.onInput) this.onInput(input);
      return;
    }
    if (k === 'backspace') {
      if (this.cursorPos > 0) { this.inputBuffer = this.inputBuffer.slice(0, this.cursorPos - 1) + this.inputBuffer.slice(this.cursorPos); this.cursorPos--; }
      this.paintPrompt(); return;
    }
    if (k === 'up') {
      if (this.inputHistory.length > 0) {
        if (this.historyIndex === -1) this.historyIndex = this.inputHistory.length - 1;
        else if (this.historyIndex > 0) this.historyIndex--;
        this.inputBuffer = this.inputHistory[this.historyIndex]; this.cursorPos = this.inputBuffer.length;
      }
      this.paintPrompt(); return;
    }
    if (k === 'down') {
      if (this.historyIndex >= 0) {
        this.historyIndex++;
        if (this.historyIndex >= this.inputHistory.length) { this.historyIndex = -1; this.inputBuffer = ''; }
        else this.inputBuffer = this.inputHistory[this.historyIndex];
        this.cursorPos = this.inputBuffer.length;
      }
      this.paintPrompt(); return;
    }
    if (k === 'left') { if (this.cursorPos > 0) this.cursorPos--; this.paintPrompt(); return; }
    if (k === 'right') { if (this.cursorPos < this.inputBuffer.length) this.cursorPos++; this.paintPrompt(); return; }
    if (c && k === 'c') {
      if (this.cancelRequested || this.status === 'thinking' || this.status === 'executing') {
        if (this.abortController) { this.abortController.abort(); this.abortController = null; }
        if (this.onCancelRequest) this.onCancelRequest();
      } else {
        this.addOutput('Use /exit to quit, or press Ctrl+C again', 'warning');
        this.cancelRequested = true; setTimeout(() => { this.cancelRequested = false; }, 2000);
      }
      this.paintPrompt(); return;
    }
    if (c && k === 'l') { this.clear(); return; }
    if (c && k === 'a') { this.cursorPos = 0; this.paintPrompt(); return; }
    if (c && k === 'e') { this.cursorPos = this.inputBuffer.length; this.paintPrompt(); return; }
    if (k === 'tab') {
      const m = this.inputBuffer.match(/^\/?(\w*)$/);
      if (m) {
        const partial = m[1].toLowerCase();
        const cmds = ALL_COMMANDS.map((x: CommandEntry) => x.command).filter((x: string) => x.startsWith(partial));
        if (cmds.length === 1) { this.inputBuffer = `/${cmds[0]} `; this.cursorPos = this.inputBuffer.length; }
      }
      this.paintPrompt(); return;
    }
    if (str && str.charCodeAt(0) >= 32) {
      this.inputBuffer = this.inputBuffer.slice(0, this.cursorPos) + str + this.inputBuffer.slice(this.cursorPos);
      this.cursorPos++;
      if (str === '/' || this.inputBuffer.startsWith('/')) {
        this.popupActive = true; this.commandPopup.open();
        this.commandPopup.setFilter(this.inputBuffer.startsWith('/') ? this.inputBuffer.slice(1) : '');
        this.paintBar(); this.paintPrompt(); return;
      }
      this.paintPrompt(); return;
    }
    this.paintPrompt();
  }

  private async handlePopupKeypress(_str: string, key: { name?: string; ctrl?: boolean }): Promise<void> {
    const k = key.name || '';
    if (k === 'escape') { this.popupActive = false; this.commandPopup.close(); this.inputBuffer = ''; this.cursorPos = 0; this.redrawAll(); return; }
    if (k === 'up') { this.commandPopup.moveUp(); this.paintBar(); this.paintPrompt(); return; }
    if (k === 'down') { this.commandPopup.moveDown(); this.paintBar(); this.paintPrompt(); return; }
    if (k === 'return' || k === 'enter') {
      const cmd = this.commandPopup.getSelectedCommand();
      this.popupActive = false; this.commandPopup.close();
      if (cmd && this.onInput) this.onInput(`/${cmd}`);
      this.inputBuffer = ''; this.cursorPos = 0; this.redrawAll(); return;
    }
    if (k === 'backspace') {
      this.commandPopup.deleteChar();
      if (this.commandPopup.getFilterText().length === 0) { this.popupActive = false; this.commandPopup.close(); this.inputBuffer = '/'; this.cursorPos = 1; this.redrawAll(); return; }
      this.paintBar(); this.paintPrompt(); return;
    }
    if (_str && _str.charCodeAt(0) >= 32) { this.commandPopup.appendChar(_str); this.paintBar(); this.paintPrompt(); return; }
  }
}

export const tui = new TUI();