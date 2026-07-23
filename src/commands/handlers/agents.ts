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
  const results: Array<{ name: string; content: string; time: number }> = [];
  for (const m of models) {
    tui.printLine(`║  ${chalk.yellow(m.name.padEnd(16))} ${chalk.cyan('⟳')} ${chalk.gray('querying...')}${' '.repeat(Math.max(0, w - 37))}║`);
  }
  tui.printLine(chalk.cyan(`╚${'═'.repeat(w)}╝`));
  for (let i = 0; i < models.length; i++) {
    const m = models[i];
    tui.printLine(chalk.gray(`  [${i + 1}/${models.length}] ${m.name}...`));
    const startTime = Date.now();
    try {
      const { createProvider } = await import('../../providers/index.js');
      const provConfig = configManager.getProvider(m.provider);
      if (!provConfig) {
        tui.printLine(chalk.red(`  ✗ Provider ${m.provider} not configured`));
        continue;
      }
      const tempConfig = { ...provConfig, defaultModel: m.model };
      const modelCfg = { provider: provConfig.type, model: m.model, temperature: 0.7, maxTokens: 2048, topP: 1, frequencyPenalty: 0, presencePenalty: 0, stop: [] as string[] };
      const provider = createProvider(tempConfig, modelCfg);
      const response = await provider.generate([{ role: 'user', content: prompt }]);
      const duration = Date.now() - startTime;
      results.push({ name: m.name, content: response.content, time: duration });
      tui.printLine(chalk.green(`  ✓ ${m.name} responded in ${(duration / 1000).toFixed(1)}s`));
    } catch (e) {
      tui.printLine(chalk.red(`  ✗ ${m.name}: ${e instanceof Error ? e.message : String(e)}`));
    }
  }
  tui.printLine(chalk.cyan(`\n╔═ Arena Results ${'═'.repeat(Math.max(0, w - 15))}╗`));
  for (const r of results) {
    const preview = r.content.slice(0, 200).replace(/\n/g, ' ');
    tui.printLine(`║  ${chalk.yellow(r.name.padEnd(16))} ${chalk.gray(`(${(r.time / 1000).toFixed(1)}s)`)} ║`);
    tui.printLine(`║  ${chalk.white(preview)}${preview.length >= 200 ? '…' : ''}${' '.repeat(Math.max(0, w - Math.min(preview.length, 200) - 5))}║`);
    tui.printLine(`║${' '.repeat(w)}║`);
  }
  tui.printLine(chalk.cyan(`╚${'═'.repeat(w)}╝`));
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

interface BgTask {
  id: string;
  command: string;
  pid: number | null;
  status: 'running' | 'completed' | 'failed';
  startTime: number;
  output: string[];
}

const bgTasks: BgTask[] = [];
let bgTaskId = 0;

export async function handleBackground(args: string[]): Promise<boolean> {
  if (args.length === 0) {
    tui.printLine(chalk.red('Usage: /background <command>'));
    tui.printLine(chalk.gray('  Example: /background npm test'));
    tui.printLine(chalk.gray('  /background list — List background tasks'));
    return true;
  }
  if (args[0] === 'list' || args[0] === 'ls') {
    if (bgTasks.length === 0) {
      tui.printLine(chalk.gray('No background tasks'));
      return true;
    }
    tui.printLine(chalk.cyan('\nBackground Tasks:'));
    for (const t of bgTasks) {
      const elapsed = ((Date.now() - t.startTime) / 1000).toFixed(1);
      const statusColor = t.status === 'running' ? chalk.cyan : t.status === 'completed' ? chalk.green : chalk.red;
      tui.printLine(`  [${t.id}] ${statusColor(t.status)} ${chalk.white(t.command.slice(0, 40))} ${chalk.gray(`(${elapsed}s)`)}`);
      if (t.output.length > 0) {
        for (const line of t.output.slice(-3)) {
          tui.printLine(`       ${chalk.gray(line.slice(0, 80))}`);
        }
      }
    }
    return true;
  }
  const cmd = args.join(' ');
  tui.printLine(chalk.gray(`\n  Running in background: ${cmd}`));
  const task: BgTask = { id: String(++bgTaskId), command: cmd, pid: null, status: 'running', startTime: Date.now(), output: [] };
  bgTasks.push(task);
  const { spawn } = await import('child_process');
  const proc = spawn('bash', ['-c', cmd], { stdio: ['ignore', 'pipe', 'pipe'], detached: true });
  task.pid = proc.pid ?? null;
  proc.stdout?.on('data', (data: Buffer) => {
    task.output.push(data.toString().trim());
    if (task.output.length > 100) task.output.shift();
  });
  proc.stderr?.on('data', (data: Buffer) => {
    task.output.push(data.toString().trim());
    if (task.output.length > 100) task.output.shift();
  });
  proc.on('exit', (code) => {
    task.status = code === 0 ? 'completed' : 'failed';
    tui.printLine(chalk.gray(`  [${task.id}] ${task.command.slice(0, 40)} → ${task.status} (exit code ${code})`));
  });
  proc.on('error', (err) => {
    task.status = 'failed';
    task.output.push(`Error: ${err.message}`);
    tui.printLine(chalk.red(`  [${task.id}] Error: ${err.message}`));
  });
  proc.unref();
  tui.printLine(chalk.green(`  ✓ Task [${task.id}] started (PID: ${task.pid})`));
  tui.printLine(chalk.gray(`  /background list — check status`));
  return true;
}
