import chalk from 'chalk';
import { phoneConfig } from './phoneOptimizer.js';

export interface CommandEntry {
  command: string;
  description: string;
  category: 'system' | 'files' | 'git' | 'agents' | 'memory' | 'settings' | 'code';
  usage?: string;
}

const ALL_COMMANDS: CommandEntry[] = [
  { command: 'help', description: 'Show all commands', category: 'system', usage: '/help [category]' },
  { command: 'status', description: 'Agent status dashboard', category: 'system' },
  { command: 'doctor', description: 'Run system diagnostics', category: 'system' },
  { command: 'clear', description: 'Clear screen', category: 'system' },
  { command: 'exit', description: 'Exit YS Code Agent', category: 'system' },
  { command: 'reset', description: 'Reset agent state', category: 'system' },
  { command: 'history', description: 'Show message history', category: 'system' },
  { command: 'recap', description: 'Session summary', category: 'system' },
  { command: 'rewind', description: 'Undo last turn', category: 'system' },
  { command: 'compress', description: 'Compress context', category: 'system' },
  { command: 'context', description: 'Context window usage', category: 'system' },
  { command: 'stats', description: 'Usage statistics', category: 'system' },
  { command: 'model', description: 'Show current model info', category: 'system' },
  { command: 'models', description: 'List available models', category: 'system' },
  { command: 'provider', description: 'List/switch providers', category: 'system' },
  { command: 'key', description: 'Set API key', category: 'system' },
  { command: 'plan', description: 'Enter plan mode', category: 'code', usage: '/plan [goal]' },
  { command: 'goal', description: 'Autonomous agent mode', category: 'code', usage: '/goal <task>' },
  { command: 'review', description: 'Code review', category: 'code', usage: '/review [file/pr]' },
  { command: 'debug', description: 'Auto debug', category: 'code', usage: '/debug [error]' },
  { command: 'refactor', description: 'AI refactoring', category: 'code', usage: '/refactor <file> <ins>' },
  { command: 'apply', description: 'Apply last AI suggestion', category: 'code' },
  { command: 'read', description: 'Read file or directory', category: 'files', usage: '/read <path>' },
  { command: 'edit', description: 'AI-powered file editing', category: 'files', usage: '/edit <path> <ins>' },
  { command: 'create', description: 'Create file with template', category: 'files', usage: '/create <path>' },
  { command: 'delete', description: 'Safe delete to trash', category: 'files', usage: '/delete <path>' },
  { command: 'search', description: 'Search codebase', category: 'files', usage: '/search <query>' },
  { command: 'list', description: 'Directory tree view', category: 'files', usage: '/list [dir]' },
  { command: 'git', description: 'Git operations', category: 'git', usage: '/git <subcommand>' },
  { command: 'agents', description: 'Manage subagents', category: 'agents' },
  { command: 'arena', description: 'Multi-model competition', category: 'agents' },
  { command: 'tasks', description: 'Background task manager', category: 'agents' },
  { command: 'background', description: 'Run command in background', category: 'agents' },
  { command: 'memory', description: 'View project memory', category: 'memory' },
  { command: 'remember', description: 'Save a memory', category: 'memory', usage: '/remember <text>' },
  { command: 'forget', description: 'Remove memories', category: 'memory', usage: '/forget <query>' },
  { command: 'init', description: 'Scan & setup project', category: 'memory' },
  { command: 'dream', description: 'Consolidate memory', category: 'memory' },
  { command: 'resume', description: 'Resume old session', category: 'memory', usage: '/resume [id]' },
  { command: 'rename', description: 'Rename current session', category: 'memory', usage: '/rename <title>' },
  { command: 'delete', description: 'Delete a session', category: 'memory', usage: '/delete <id>' },
  { command: 'export', description: 'Export session', category: 'memory', usage: '/export [fmt]' },
  { command: 'config', description: 'Open settings menu', category: 'settings' },
  { command: 'theme', description: 'Change color theme', category: 'settings', usage: '/theme [dark|light|matrix]' },
  { command: 'lang', description: 'Language settings', category: 'settings' },
  { command: 'permissions', description: 'Tool permissions', category: 'settings' },
  { command: 'approval-mode', description: 'Change approval mode', category: 'settings' },
  { command: 'tools', description: 'List available tools', category: 'settings' },
  { command: 'vim', description: 'Toggle vim mode', category: 'settings' },
];

const CATEGORY_ICONS: Record<string, string> = {
  system: '🔧',
  files: '📁',
  git: '🌿',
  agents: '🤖',
  memory: '💾',
  settings: '⚙️',
  code: '💻',
};

export class CommandPopup {
  private visible = false;
  private selectedIndex = 0;
  private filterText = '';
  private filteredCommands: CommandEntry[] = ALL_COMMANDS;
  private onSelect: ((command: string) => void) | null = null;
  private onCancel: (() => void) | null = null;

  setOnSelect(cb: (command: string) => void): void {
    this.onSelect = cb;
  }

  setOnCancel(cb: () => void): void {
    this.onCancel = cb;
  }

  open(): void {
    this.visible = true;
    this.selectedIndex = 0;
    this.filterText = '';
    this.filteredCommands = ALL_COMMANDS;
  }

  close(): void {
    this.visible = false;
  }

  isVisible(): boolean {
    return this.visible;
  }

  getFilterText(): string {
    return this.filterText;
  }

  setFilter(text: string): void {
    this.filterText = text.toLowerCase();
    this.filteredCommands = ALL_COMMANDS.filter((c) =>
      c.command.includes(this.filterText) || c.description.toLowerCase().includes(this.filterText)
    );
    this.selectedIndex = 0;
  }

  appendChar(char: string): void {
    this.setFilter(this.filterText + char);
  }

  deleteChar(): void {
    if (this.filterText.length > 0) {
      this.setFilter(this.filterText.slice(0, -1));
    }
  }

  moveUp(): void {
    if (this.filteredCommands.length > 0) {
      this.selectedIndex = Math.max(0, this.selectedIndex - 1);
    }
  }

  moveDown(): void {
    if (this.filteredCommands.length > 0) {
      this.selectedIndex = Math.min(this.filteredCommands.length - 1, this.selectedIndex + 1);
    }
  }

  getSelectedCommand(): string | null {
    if (this.filteredCommands.length === 0) return null;
    return this.filteredCommands[this.selectedIndex].command;
  }

  selectCurrent(): void {
    const cmd = this.getSelectedCommand();
    if (cmd && this.onSelect) {
      this.visible = false;
      this.onSelect(cmd);
    }
  }

  render(): string {
    if (!this.visible) return '';

    const pw = phoneConfig.getPopupHeight();
    const maxHeight = Math.min(pw + 2, process.stdout.rows ? process.stdout.rows - 4 : 12);
    const width = Math.min(process.stdout.columns ? process.stdout.columns - 4 : 60, 60);

    const top = `┌─${chalk.cyan(' Commands ')}${'─'.repeat(Math.max(0, width - 11))}┐`;

    const categorized = this.getCategorizedCommands();
    const lines: string[] = [];

    for (const { category, commands } of categorized) {
      if (lines.length >= maxHeight) break;
      const icon = CATEGORY_ICONS[category] || ' ';
      lines.push(` ${chalk.gray(icon)} ${chalk.white(category.toUpperCase())}`);

      for (const cmd of commands) {
        if (lines.length >= maxHeight) break;
        const isSelected = cmd === this.filteredCommands[this.selectedIndex];
        const prefix = isSelected ? chalk.cyan('▶ ') : '  ';
        const cmdName = isSelected ? chalk.cyan(cmd.command) : chalk.white(cmd.command);
        const desc = chalk.gray(cmd.description);
        const padding = ' '.repeat(Math.max(0, width - cmd.command.length - cmd.description.length - 8));
        lines.push(`${prefix}/${cmdName}${padding}${desc}`);
      }
    }

    const visibleLines = lines.slice(0, maxHeight);
    const bottomLine = `└${'─'.repeat(width)}┘`;

    return [top, ...visibleLines.map((l) => `│${l}${' '.repeat(Math.max(0, width - l.length))}│`), bottomLine].join('\n');
  }

  private getCategorizedCommands(): Array<{ category: string; commands: CommandEntry[] }> {
    const cats: Array<{ category: string; commands: CommandEntry[] }> = [];
    const seen = new Set<string>();

    for (const cmd of this.filteredCommands) {
      if (seen.has(cmd.command)) continue;
      seen.add(cmd.command);
    }

    const order = ['system', 'files', 'git', 'agents', 'code', 'memory', 'settings'] as const;
    for (const cat of order) {
      const cmds = this.filteredCommands.filter((c) => c.category === cat && !seen.has(c.command));
      if (cmds.length > 0) {
        cats.push({ category: cat, commands: cmds });
      }
    }

    return cats;
  }
}

export { ALL_COMMANDS, CATEGORY_ICONS };
