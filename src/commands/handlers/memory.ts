import chalk from 'chalk';
import { existsSync, mkdirSync, writeFileSync, readFileSync } from 'fs';
import { join, dirname } from 'path';
import { homedir } from 'os';
import { tui } from '../../ui/index.js';
import { memoryManager } from '../../memory/index.js';
import { agent } from '../../agent/index.js';

const YS_DIR = join(process.cwd(), '.ys');
const YS_MD_PATH = join(YS_DIR, 'YS.md');
const MEMORY_JSON_PATH = join(YS_DIR, 'memory.json');

interface StoredMemory {
  version: number;
  project: string;
  entries: MemoryEntry[];
  summary: string;
}

interface MemoryEntry {
  id: string;
  type: string;
  content: string;
  timestamp: string;
  tags: string[];
}

export async function handleMemory(args: string[]): Promise<boolean> {
  const sub = args[0]?.toLowerCase();
  switch (sub) {
    case 'list':
      return listMemory();
    case 'add':
      return addMemory(args.slice(1).join(' '));
    case 'clear':
      return clearMemory();
    default:
      return showMemory();
  }
}

export async function handleRemember(args: string[]): Promise<boolean> {
  if (args.length === 0) {
    tui.printLine(chalk.red('Usage: /remember <text>'));
    return true;
  }
  const text = args.join(' ');
  memoryManager.store(`manual_${Date.now()}`, text, 'preference');
  tui.printLine(chalk.green(`✓ Remembered: ${text}`));
  return true;
}

export async function handleForget(args: string[]): Promise<boolean> {
  if (args.length === 0) {
    tui.printLine(chalk.red('Usage: /forget <query>'));
    return true;
  }
  const query = args.join(' ');
  const results = memoryManager.search(query);
  if (results.length === 0) {
    tui.printLine(chalk.yellow('No matching memories found'));
    return true;
  }
  for (const r of results) {
    memoryManager.delete(r.key);
  }
  tui.printLine(chalk.green(`✓ Removed ${results.length} memory entries`));
  return true;
}

export async function handleInit(): Promise<boolean> {
  if (!existsSync(YS_DIR)) {
    mkdirSync(YS_DIR, { recursive: true });
    mkdirSync(join(YS_DIR, 'sessions'), { recursive: true });
    mkdirSync(join(YS_DIR, 'backups'), { recursive: true });
    mkdirSync(join(YS_DIR, 'trash'), { recursive: true });
  }
  const projectName = basename(process.cwd());
  const ysMd = `# YS Project Context\n\n## Project Info\n- Name: ${projectName}\n- Type: Unknown\n- Framework: Unknown\n\n## Architecture\n- Storage: TBD\n- API: TBD\n- Auth: TBD\n\n## Preferences\n- Language: English\n- Theme: Default\n\n## Important Files\n- \n\n## Notes\n- \n`;
  if (!existsSync(YS_MD_PATH)) {
    writeFileSync(YS_MD_PATH, ysMd, 'utf-8');
  }
  const memoryJson: StoredMemory = {
    version: 1,
    project: projectName,
    entries: [],
    summary: '',
  };
  if (!existsSync(MEMORY_JSON_PATH)) {
    writeFileSync(MEMORY_JSON_PATH, JSON.stringify(memoryJson, null, 2), 'utf-8');
  }
  tui.printLine(chalk.green('✓ Project initialized'));
  tui.printLine(chalk.gray(`  ${YS_DIR}/`));
  tui.printLine(chalk.gray(`  ├── YS.md`));
  tui.printLine(chalk.gray(`  ├── memory.json`));
  tui.printLine(chalk.gray(`  ├── sessions/`));
  tui.printLine(chalk.gray(`  ├── backups/`));
  tui.printLine(chalk.gray(`  └── trash/`));
  return true;
}

export async function handleDream(): Promise<boolean> {
  tui.printLine(chalk.cyan('\n◆ Consolidating memory...'));
  const messages = agent.getMessages();
  const summaries = memoryManager.getSummaries();
  tui.printLine(chalk.gray(`  Messages: ${messages.length}`));
  tui.printLine(chalk.gray(`  Summaries: ${summaries.length}`));
  const recentMsgs = messages.slice(-5).map((m) => `${m.role}: ${m.content.slice(0, 100)}`).join('\n');
  const result = await agent.chat(`Summarize this conversation for memory:\n\n${recentMsgs}\n\nProvide a 2-3 sentence summary.`);
  if (result.content) {
    memoryManager.addSummary(result.content.trim(), 'conversation');
    tui.printLine(chalk.green('✓ Memory consolidated'));
    tui.printLine(chalk.gray(`  Summary: ${result.content.slice(0, 200)}`));
  }
  return true;
}

async function showMemory(): Promise<boolean> {
  const context = memoryManager.getContext();
  tui.printLine(chalk.cyan('\nMemory Dashboard:'));
  tui.printLine(`  Short-term messages: ${chalk.white(String(context.shortTerm.messages.length))}`);
  tui.printLine(`  Summaries: ${chalk.white(String(context.longTerm.summaries.length))}`);
  tui.printLine(`  Preferences: ${chalk.white(String(Object.keys(context.longTerm.preferences).length))}`);
  tui.printLine(`  Previous tasks: ${chalk.white(String(context.longTerm.previousTasks.length))}`);
  if (context.longTerm.summaries.length > 0) {
    tui.printLine(chalk.gray('\n  Recent summaries:'));
    for (const s of context.longTerm.summaries.slice(0, 3)) {
      tui.printLine(`    ${chalk.gray('•')} ${s.content.slice(0, 120)}`);
    }
  }
  const inMem = memoryManager.list();
  const storedMem = inMem.filter((e) => e.type === 'preference');
  if (storedMem.length > 0) {
    tui.printLine(chalk.gray('\n  Stored preferences:'));
    for (const m of storedMem) {
      tui.printLine(`    ${chalk.gray('•')} ${m.key}: ${m.value.slice(0, 80)}`);
    }
  }
  return true;
}

async function listMemory(): Promise<boolean> {
  const entries = memoryManager.list();
  if (entries.length === 0) {
    tui.printLine(chalk.yellow('No memory entries'));
    return true;
  }
  tui.printLine(chalk.cyan('\nAll Memory Entries:'));
  for (const e of entries) {
    const typeColor = e.type === 'preference' ? chalk.yellow :
      e.type === 'project' ? chalk.blue : chalk.gray;
    tui.printLine(`  ${typeColor(`[${e.type}]`)} ${chalk.white(e.key)}: ${e.value.slice(0, 80)}`);
  }
  return true;
}

async function addMemory(text: string): Promise<boolean> {
  if (!text) {
    tui.printLine(chalk.red('Usage: /memory add <text>'));
    return true;
  }
  memoryManager.store(`manual_${Date.now()}`, text, 'preference');
  tui.printLine(chalk.green(`✓ Added memory entry`));
  return true;
}

async function clearMemory(): Promise<boolean> {
  memoryManager.clear();
  tui.printLine(chalk.green('✓ Memory cleared'));
  return true;
}

function basename(p: string): string {
  return p.split(/[/\\]/).pop() || 'project';
}
