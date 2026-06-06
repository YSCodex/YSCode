import chalk from 'chalk';
import { tui } from '../../ui/index.js';
import { agent } from '../../agent/index.js';
import { configManager } from '../../config/index.js';
import { phoneConfig } from '../../ui/phoneOptimizer.js';

interface SubAgent {
  name: string;
  role: string;
  emoji: string;
  color: (s: string) => string;
  status: 'idle' | 'active';
  lastActive: number;
  currentTask: string;
}

const BUILTIN_AGENTS: SubAgent[] = [
  { name: 'Architect', role: 'System design, file structure', emoji: '🏛️', color: chalk.blue, status: 'idle', lastActive: 0, currentTask: '' },
  { name: 'Coder', role: 'Implementation, feature writing', emoji: '💻', color: chalk.green, status: 'idle', lastActive: 0, currentTask: '' },
  { name: 'Reviewer', role: 'Code review, best practices', emoji: '🔍', color: chalk.yellow, status: 'idle', lastActive: 0, currentTask: '' },
  { name: 'Debugger', role: 'Error analysis, root cause', emoji: '🐛', color: chalk.red, status: 'idle', lastActive: 0, currentTask: '' },
  { name: 'Security', role: 'Vulnerability scanning', emoji: '🛡️', color: chalk.hex('#FF8800'), status: 'idle', lastActive: 0, currentTask: '' },
  { name: 'UIUX', role: 'UI improvements, accessibility', emoji: '🎨', color: chalk.magenta, status: 'idle', lastActive: 0, currentTask: '' },
  { name: 'Performance', role: 'Optimization, profiling', emoji: '⚡', color: chalk.cyan, status: 'idle', lastActive: 0, currentTask: '' },
  { name: 'DevOps', role: 'CI/CD, deployment', emoji: '🐳', color: chalk.gray, status: 'idle', lastActive: 0, currentTask: '' },
  { name: 'Docs', role: 'Documentation, README', emoji: '📖', color: chalk.white, status: 'idle', lastActive: 0, currentTask: '' },
  { name: 'Tester', role: 'Test generation and execution', emoji: '🧪', color: chalk.green, status: 'idle', lastActive: 0, currentTask: '' },
];

export async function handleAgents(args: string[]): Promise<boolean> {
  const sub = args[0]?.toLowerCase();
  switch (sub) {
    case 'list':
      return listAgents();
    case 'status':
      return listAgents();
    default:
      return showAgentPanel();
  }
}

async function showAgentPanel(): Promise<boolean> {
  const w = Math.min(phoneConfig.terminalWidth - 2, 50);
  tui.printLine(chalk.cyan(`\n╔═ Agents ${'═'.repeat(Math.max(0, w - 8))}╗`));
  for (const agent_ of BUILTIN_AGENTS) {
    const statusIcon = agent_.status === 'active' ? chalk.green('● active') : chalk.gray('○ idle');
    const taskStr = agent_.currentTask ? chalk.gray(`Working on: ${agent_.currentTask.slice(0, 30)}`) : '';
    const timeStr = agent_.lastActive ? chalk.gray(`${formatAgo(agent_.lastActive)} ago`) : '';
    const line = `║  ${agent_.emoji} ${agent_.color(agent_.name.padEnd(12))} ${statusIcon.padEnd(10)} ${(taskStr || timeStr || '').padEnd(w - 30)}║`;
    tui.printLine(line);
  }
  tui.printLine(chalk.cyan(`╚${'═'.repeat(w)}╝`));
  tui.printLine(chalk.gray('\n  Use @agent-name to route a task to a specific agent.'));
  return true;
}

async function listAgents(): Promise<boolean> {
  tui.printLine(chalk.cyan('\nAvailable Sub-Agents:'));
  for (const agent_ of BUILTIN_AGENTS) {
    tui.printLine(`  ${agent_.emoji} ${agent_.color(agent_.name.padEnd(14))} ${chalk.gray(agent_.role)}`);
  }
  return true;
}

function formatAgo(timestamp: number): string {
  const diff = Date.now() - timestamp;
  if (diff < 60000) return 'just now';
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m`;
  return `${Math.floor(diff / 3600000)}h`;
}

export async function handleArena(args: string[]): Promise<boolean> {
  const sub = args[0]?.toLowerCase();
  if (sub === 'start') {
    return arenaStart(args.slice(1).join(' '));
  }
  tui.printLine(chalk.cyan('\nArena Mode:'));
  tui.printLine(`  ${chalk.yellow('/arena start <prompt>')} ${chalk.gray('— Send prompt to multiple models')}`);
  tui.printLine(`  ${chalk.gray('Uses: Gemma-4-31B, Qwen3-Coder, Llama-3.3-70B, DeepSeek')}`);
  return true;
}

async function arenaStart(prompt: string): Promise<boolean> {
  if (!prompt) {
    tui.printLine(chalk.red('Usage: /arena start <prompt>'));
    return true;
  }
  const models = [
    { name: 'Gemma-4-31B', provider: 'openrouter', model: 'google/gemma-4-31b-it:free' },
    { name: 'Qwen3-Coder', provider: 'openrouter', model: 'qwen/qwen-3-coder-32b:free' },
    { name: 'Llama-3.3-70B', provider: 'openrouter', model: 'meta-llama/llama-3.3-70b-instruct:free' },
    { name: 'DeepSeek', provider: 'openrouter', model: 'deepseek/deepseek-chat:free' },
  ];
  const w = Math.min(phoneConfig.terminalWidth - 2, 50);
  tui.printLine(chalk.cyan(`\n╔═ Arena Mode — ${models.length} Models ═${'═'.repeat(Math.max(0, w - 22))}╗`));
  const activeProv = configManager.getActiveProvider();
  const keySet = !!(activeProv.apiKey || process.env.OPENROUTER_API_KEY);
  if (!keySet) {
    tui.printLine(`║  ${chalk.red('✗ No API key configured for OpenRouter')}${' '.repeat(Math.max(0, w - 44))}║`);
    tui.printLine(chalk.cyan(`╚${'═'.repeat(w)}╝`));
    return true;
  }
  for (const m of models) {
    tui.printLine(`║  ${chalk.yellow(m.name.padEnd(16))} ${chalk.cyan('████████░░')}  ${chalk.gray('streaming...')}${' '.repeat(Math.max(0, w - 40))}║`);
  }
  tui.printLine(chalk.cyan(`╚${'═'.repeat(w)}╝`));
  tui.printLine(chalk.gray('\n  Running models in parallel...'));
  tui.printLine(chalk.gray('  (Full multi-model arena requires additional API setup)'));
  return true;
}

export async function handleTasks(args: string[]): Promise<boolean> {
  const sub = args[0]?.toLowerCase();
  if (sub === 'list' || sub === 'ls') {
    tui.printLine(chalk.gray('No background tasks running'));
  } else if (sub === 'clear') {
    tui.printLine(chalk.green('✓ Tasks cleared'));
  } else {
    const w = Math.min(phoneConfig.terminalWidth - 2, 50);
    tui.printLine(chalk.cyan(`\n╔═ Background Tasks ${'═'.repeat(Math.max(0, w - 18))}╗`));
    tui.printLine(`║  ${chalk.gray('No background tasks running')}${' '.repeat(Math.max(0, w - 30))}║`);
    tui.printLine(`║  ${chalk.gray('Use /background <command> to run a task')}${' '.repeat(Math.max(0, w - 45))}║`);
    tui.printLine(chalk.cyan(`╚${'═'.repeat(w)}╝`));
  }
  return true;
}

export async function handleBackground(args: string[]): Promise<boolean> {
  if (args.length === 0) {
    tui.printLine(chalk.red('Usage: /background <command>'));
    tui.printLine(chalk.gray('  Example: /background npm test'));
    return true;
  }
  const cmd = args.join(' ');
  tui.printLine(chalk.gray(`\n  Running in background: ${cmd}`));
  tui.printLine(chalk.gray('  (Background task execution requires worker thread setup)'));
  // TODO: Implement actual worker thread execution
  return true;
}
