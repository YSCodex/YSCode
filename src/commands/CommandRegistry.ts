import chalk from 'chalk';
import { getLogger } from '../logger/index.js';
import { tui, ApprovalMode, AgentMode } from '../ui/index.js';
import { configManager } from '../config/index.js';
import { agent } from '../agent/index.js';
import { memoryManager } from '../memory/index.js';
import { sessionManager } from '../session/index.js';
import { phoneConfig } from '../ui/phoneOptimizer.js';
import { handleRead, handleEdit, handleCreate, handleDelete, handleSearch, handleList, handleRefactor } from './handlers/file.js';
import { handleGit } from './handlers/git.js';
import { handleMemory, handleRemember, handleForget, handleInit, handleDream } from './handlers/memory.js';
import { handleAgents, handleArena, handleTasks, handleBackground } from './handlers/agents.js';
import { ALL_COMMANDS, CATEGORY_ICONS } from '../ui/CommandPopup.js';

const logger = getLogger('commands');

export type CommandHandler = (args: string[], raw: string) => Promise<boolean>;

interface CommandDef {
  name: string;
  aliases: string[];
  description: string;
  handler: CommandHandler;
  category: string;
  minArgs?: number;
  usage?: string;
}

const commands = new Map<string, CommandDef>();

function reg(def: CommandDef): void {
  commands.set(def.name, def);
  for (const alias of def.aliases) {
    commands.set(alias, def);
  }
}

reg({
  name: 'help',
  aliases: ['h', '?'],
  description: 'Show this help',
  category: 'system',
  handler: async (args) => {
    if (args.length > 0) {
      const category = args[0].toLowerCase();
      const catMap: Record<string, string> = {
        system: 'system', files: 'files', file: 'files',
        git: 'git', agents: 'agents', agent: 'agents',
        memory: 'memory', settings: 'settings', config: 'settings',
        code: 'code', review: 'code', debug: 'code',
      };
      const resolvedCat = catMap[category];
      if (resolvedCat) {
        showCategoryHelp(resolvedCat);
      } else {
        showSpecificHelp(category);
      }
    } else {
      tui.printHelp();
    }
    return true;
  },
});

reg({
  name: 'status',
  aliases: ['stats', 'info'],
  description: 'Agent status dashboard',
  category: 'system',
  handler: async () => {
    const config = configManager.getConfig();
    const provider = configManager.getActiveProvider();
    const state = agent.getState();
    const msgs = agent.getMessages();
    const session = sessionManager.getCurrentSession();

    const w = Math.min(phoneConfig.terminalWidth - 2, 54);
    const top = `╔${'═'.repeat(w)}╗`;
    const bottom = `╚${'═'.repeat(w)}╝`;

    tui.printLine('');
    tui.printLine(chalk.cyan(top));
    tui.printLine(`║  ${chalk.white('Status')}: ${statusBadge(state.status)}${' '.repeat(Math.max(0, w - 12 - state.status.length))}║`);
    tui.printLine(`║  ${chalk.white('Provider')}: ${chalk.yellow(provider.name.padEnd(w - 15))}║`);
    tui.printLine(`║  ${chalk.white('Model')}: ${chalk.yellow((config.model.model.length > 30 ? config.model.model.slice(0, 28) + '…' : config.model.model).padEnd(w - 12))}║`);
    tui.printLine(`║  ${chalk.white('Messages')}: ${chalk.yellow(String(msgs.length).padEnd(w - 15))}║`);
    tui.printLine(`║  ${chalk.white('Session')}: ${chalk.yellow((session ? session.id.slice(0, 8) : 'none').padEnd(w - 14))}║`);
    tui.printLine(`║  ${chalk.white('Mode')}: ${modeBadge(tui.getAgentMode())}${' '.repeat(Math.max(0, w - 11))}║`);
    tui.printLine(`║  ${chalk.white('Approval')}: ${approvalBadge(tui.getApprovalMode())}${' '.repeat(Math.max(0, w - 15))}║`);
    if (phoneConfig.isTermux) {
      tui.printLine(`║  ${chalk.white('Termux')}: ${chalk.green('✓ Detected')}${' '.repeat(Math.max(0, w - 21))}║`);
    }
    tui.printLine(chalk.cyan(bottom));
    return true;
  },
});

reg({
  name: 'clear',
  aliases: ['cls'],
  description: 'Clear screen',
  category: 'system',
  handler: async () => {
    tui.clear();
    tui.printWelcome();
    return true;
  },
});

reg({
  name: 'exit',
  aliases: ['quit', 'q'],
  description: 'Exit YS Code Agent',
  category: 'system',
  handler: async () => {
    tui.printLine(chalk.yellow('\nShutting down...'));
    tui.stop();
    process.exit(0);
  },
});

reg({
  name: 'reset',
  aliases: ['clear-state'],
  description: 'Reset agent state',
  category: 'system',
  handler: async () => {
    agent.reset();
    tui.printLine(chalk.green('✓ Agent state reset'));
    return true;
  },
});

reg({
  name: 'history',
  aliases: ['hist'],
  description: 'Show message history',
  category: 'system',
  handler: async (args) => {
    const messages = agent.getMessages();
    const count = args[0] ? parseInt(args[0], 10) || 10 : 10;
    const recent = messages.slice(-count);
    tui.printLine(chalk.cyan(`\nRecent history (${recent.length} messages):`));
    for (const msg of recent) {
      if (msg.role === 'system') continue;
      const role = msg.role === 'user' ? chalk.gray('User:') : chalk.green('Asst:');
      const content = msg.content.slice(0, 120) + (msg.content.length > 120 ? '…' : '');
      tui.printLine(`  ${role} ${content}`);
    }
    return true;
  },
});

reg({
  name: 'model',
  aliases: ['m'],
  description: 'Show current model info',
  category: 'system',
  handler: async () => {
    const config = configManager.getConfig();
    tui.printLine(chalk.cyan(`\nModel Configuration:`));
    tui.printLine(`  Provider:    ${chalk.yellow(config.activeProvider)}`);
    tui.printLine(`  Model:       ${chalk.yellow(config.model.model)}`);
    tui.printLine(`  Temperature: ${config.model.temperature}`);
    tui.printLine(`  Max Tokens:  ${config.model.maxTokens.toLocaleString()}`);
    return true;
  },
});

reg({
  name: 'models',
  aliases: ['list-models'],
  description: 'List available models',
  category: 'system',
  handler: async () => {
    const config = configManager.getConfig();
    const provider = configManager.getActiveProvider();
    tui.printLine(chalk.cyan(`\nModels for ${provider.name}:`));
    for (const model of provider.models) {
      const isCurrent = model === config.model.model ? chalk.green(' ◀ current') : '';
      tui.printLine(`  ${chalk.yellow(model)}${isCurrent}`);
    }
    return true;
  },
});

reg({
  name: 'provider',
  aliases: ['prov'],
  description: 'List/switch providers',
  category: 'system',
  handler: async (args) => {
    if (args.length === 0) {
      const config = configManager.getConfig();
      tui.printLine(chalk.cyan('\nConfigured Providers:'));
      for (const p of config.providers) {
        const active = p.name === config.activeProvider ? chalk.green(' (active)') : '';
        const hasKey = p.apiKey ? chalk.gray(' [key set]') : chalk.red(' [no key]');
        tui.printLine(`  ${chalk.yellow(p.name)}${active}${hasKey}`);
      }
      return true;
    }
    const name = args[0].toLowerCase();
    if (agent.switchProvider(name)) {
      tui.printLine(chalk.green(`✓ Switched to provider: ${name}`));
    } else {
      tui.printLine(chalk.red(`✗ Provider not found: ${name}`));
    }
    return true;
  },
});

reg({
  name: 'doctor',
  aliases: ['diagnostics', 'diag'],
  description: 'Run system diagnostics',
  category: 'system',
  handler: async () => {
    tui.printLine(chalk.cyan('\n═══ YS Code Agent Diagnostics ═══'));

    tui.printLine(chalk.yellow('\nSystem:'));
    tui.printLine(`  Node:  ${process.version}`);
    tui.printLine(`  OS:    ${process.platform} ${process.arch}`);
    tui.printLine(`  Termux: ${phoneConfig.isTermux ? chalk.green('✓') : chalk.gray('Not detected')}`);

    tui.printLine(chalk.yellow('\nProvider:'));
    const config = configManager.getConfig();
    const activeProv = configManager.getActiveProvider();
    const keySet = activeProv.apiKey || process.env[`${activeProv.type.toUpperCase()}_API_KEY`];
    tui.printLine(`  Active:  ${chalk.cyan(config.activeProvider)}`);
    tui.printLine(`  Model:   ${chalk.cyan(config.model.model)}`);
    tui.printLine(`  API Key: ${keySet ? chalk.green('✓ Set') : chalk.red('✕ Not set')}`);

    tui.printLine(chalk.yellow('\nTools:'));
    const { toolRegistry } = await import('../tools/index.js');
    const tools = toolRegistry.getToolNames();
    tui.printLine(`  Registered: ${chalk.green(String(tools.length))}`);

    tui.printLine(chalk.yellow('\nGit:'));
    const { gitManager } = await import('../git/index.js');
    tui.printLine(`  Available: ${gitManager.isAvailable() ? chalk.green('✓') : chalk.red('✕')}`);
    tui.printLine(`  Repository: ${gitManager.isRepo() ? chalk.green('✓') : chalk.gray('Not a git repo')}`);

    tui.printLine(chalk.yellow('\nMemory:'));
    const context = memoryManager.getContext();
    tui.printLine(`  Messages: ${context.shortTerm.messages.length}`);
    tui.printLine(`  Summaries: ${context.longTerm.summaries.length}`);

    tui.printLine(chalk.green('\n✓ Diagnostics complete\n'));
    return true;
  },
});

reg({
  name: 'recap',
  aliases: ['summary'],
  description: 'Session summary',
  category: 'system',
  handler: async () => {
    const session = sessionManager.getCurrentSession();
    if (!session) {
      tui.printLine(chalk.yellow('No active session'));
      return true;
    }
    const messages = agent.getMessages();
    const userMsgs = messages.filter((m) => m.role === 'user');
    const asstMsgs = messages.filter((m) => m.role === 'assistant');
    const duration = Date.now() - (session.metadata?.createdAt || Date.now());
    tui.printLine(chalk.cyan(`\nSession Recap:`));
    tui.printLine(`  ID:       ${chalk.yellow(session.id.slice(0, 8))}`);
    tui.printLine(`  Name:     ${chalk.yellow(session.metadata?.title || session.id)}`);
    tui.printLine(`  Duration: ${chalk.yellow(formatDuration(duration))}`);
    tui.printLine(`  Messages: ${chalk.yellow(String(messages.length))} (${userMsgs.length} user, ${asstMsgs.length} assistant)`);
    return true;
  },
});

reg({
  name: 'rewind',
  aliases: ['undo'],
  description: 'Undo last turn',
  category: 'system',
  handler: async () => {
    const messages = agent.getMessages();
    if (messages.length < 2) {
      tui.printLine(chalk.yellow('Nothing to rewind'));
      return true;
    }
    let removed = 0;
    while (messages.length > 0) {
      const last = messages[messages.length - 1];
      if (last.role === 'user') break;
      messages.pop();
      removed++;
    }
    if (messages.length > 0 && messages[messages.length - 1].role === 'user') {
      messages.pop();
      removed++;
    }
    tui.printLine(chalk.green(`✓ Rewound ${removed} messages`));
    return true;
  },
});

reg({
  name: 'plan',
  aliases: [],
  description: 'Enter plan mode',
  category: 'code',
  handler: async (args) => {
    tui.setAgentMode('plan');
    if (args.length > 0) {
      const goal = args.join(' ');
      tui.printLine(chalk.blue(`\nPlan mode activated for: ${chalk.white(goal)}`));
      await handlePlanMode(goal);
    } else {
      tui.printLine(chalk.blue('\nPlan mode activated. Type your goal to create a plan.'));
      tui.printLine(chalk.gray('  Use /apply to execute the plan once created.'));
    }
    return true;
  },
});

reg({
  name: 'goal',
  aliases: ['auto', 'autonomous'],
  description: 'Autonomous agent mode',
  category: 'code',
  handler: async (args) => {
    if (args.length === 0) {
      tui.printLine(chalk.red('Usage: /goal <task description>'));
      return true;
    }
    const task = args.join(' ');
    tui.setAgentMode('goal');
    tui.printLine(chalk.magenta(`\n◆ Goal: ${chalk.white(task)}`));
    await handleGoalMode(task);
    tui.setAgentMode('chat');
    return true;
  },
});

reg({
  name: 'debug',
  aliases: ['fix'],
  description: 'Auto debug',
  category: 'code',
  handler: async (args) => {
    const errorText = args.join(' ') || '';
    if (!errorText) {
      tui.printLine(chalk.yellow('Paste your error message or use /debug <file>:<line>'));
      tui.printLine(chalk.gray('  Example: /debug src/app.ts:42'));
      return true;
    }
    tui.printLine(chalk.cyan('\n╔═ Debug Analysis ═╗'));
    tui.printLine(chalk.white(`  Error: ${errorText}`));
    tui.printLine(chalk.gray('  Analyzing...'));
    const result = await agent.chat(`Debug this error and find the root cause:\n\n${errorText}`);
    if (result.content) {
      tui.printLine(chalk.green(`\n${result.content}`));
    }
    return true;
  },
});

reg({
  name: 'context',
  aliases: ['ctx', 'tokens'],
  description: 'Context window usage',
  category: 'system',
  handler: async () => {
    const messages = agent.getMessages();
    const config = configManager.getConfig();
    const maxCtx = config.context.maxTokens;
    let totalTokens = 0;
    for (const m of messages) {
      totalTokens += countTokens(m.content);
    }
    const pct = Math.round((totalTokens / maxCtx) * 100);
    const bar = getProgressBar(pct, 20);
    tui.printLine(chalk.cyan('\nContext Window Usage:'));
    tui.printLine(`  Max:     ${chalk.white(maxCtx.toLocaleString())} tokens`);
    tui.printLine(`  Used:    ${chalk.yellow(totalTokens.toLocaleString())} tokens`);
    tui.printLine(`  Usage:   ${bar} ${chalk.yellow(`${pct}%`)}`);
    tui.printLine(`  Messages: ${chalk.white(String(messages.length))}`);
    if (pct > 80) {
      tui.printLine(chalk.yellow('  ⚠ Context usage high. Use /compress to free tokens.'));
    }
    return true;
  },
});

reg({
  name: 'compress',
  aliases: ['compress-context'],
  description: 'Compress context',
  category: 'system',
  handler: async () => {
    tui.printLine(chalk.cyan('\nCompressing context...'));
    const messages = agent.getMessages();
    if (messages.length <= 5) {
      tui.printLine(chalk.yellow('Not enough messages to compress'));
      return true;
    }
    const toCompress = messages.slice(1, -3);
    const lastMessages = messages.slice(-3);
    const summary = `[Context compressed: ${toCompress.length} messages summarized]`;
    memoryManager.addSummary(summary, 'conversation');
    agent.reset();
    (agent as any).state.messages.push(...lastMessages);
    tui.printLine(chalk.green(`✓ Compressed ${toCompress.length} messages into summary`));
    return true;
  },
});

reg({
  name: 'read',
  aliases: ['cat', 'show'],
  description: 'Read file or directory',
  category: 'files',
  handler: handleRead,
});

reg({
  name: 'edit',
  aliases: ['modify'],
  description: 'AI-powered file editing',
  category: 'files',
  handler: handleEdit,
});

reg({
  name: 'create',
  aliases: ['new', 'touch'],
  description: 'Create file with template',
  category: 'files',
  handler: handleCreate,
});

reg({
  name: 'delete',
  aliases: ['rm', 'remove'],
  description: 'Safe delete to trash',
  category: 'files',
  handler: handleDelete,
});

reg({
  name: 'search',
  aliases: ['find', 'grep'],
  description: 'Search codebase',
  category: 'files',
  handler: handleSearch,
});

reg({
  name: 'list',
  aliases: ['ls', 'dir', 'tree'],
  description: 'Directory tree view',
  category: 'files',
  handler: handleList,
});

reg({
  name: 'refactor',
  aliases: [],
  description: 'AI refactor a file',
  category: 'code',
  handler: handleRefactor,
});

reg({
  name: 'git',
  aliases: ['g'],
  description: 'Git operations',
  category: 'git',
  handler: handleGit,
});

reg({
  name: 'memory',
  aliases: ['mem', 'recall'],
  description: 'View project memory',
  category: 'memory',
  handler: handleMemory,
});

reg({
  name: 'remember',
  aliases: ['save', 'store'],
  description: 'Save a memory',
  category: 'memory',
  handler: handleRemember,
});

reg({
  name: 'forget',
  aliases: ['remove-mem', 'unremember'],
  description: 'Remove memories',
  category: 'memory',
  handler: handleForget,
});

reg({
  name: 'init',
  aliases: ['setup', 'bootstrap'],
  description: 'Scan & setup project',
  category: 'memory',
  handler: handleInit,
});

reg({
  name: 'dream',
  aliases: ['consolidate'],
  description: 'Consolidate memory',
  category: 'memory',
  handler: handleDream,
});

reg({
  name: 'resume',
  aliases: ['load'],
  description: 'Resume old session',
  category: 'system',
  handler: async (args) => {
    const sessions = sessionManager.listSessions();
    if (sessions.length === 0) {
      tui.printLine(chalk.yellow('No saved sessions'));
      return true;
    }
    if (args.length > 0) {
      const id = args[0];
      const ok = sessionManager.setCurrentSession(id);
      if (ok) {
        const s = sessionManager.getSession(id);
        tui.printLine(chalk.green(`✓ Resumed session: ${s?.metadata?.title || id}`));
      } else {
        tui.printLine(chalk.red(`Session not found: ${id}`));
      }
      return true;
    }
    tui.printLine(chalk.cyan('\nRecent Sessions:'));
    for (let i = 0; i < sessions.length; i++) {
      const s = sessions[i];
      tui.printLine(`  ${chalk.yellow(String(i + 1))}. [${chalk.white(s.id.slice(0, 4))}] ${s.metadata?.title || s.id} — ${new Date(s.metadata.createdAt).toLocaleDateString()}, ${s.metadata.messageCount} msgs`);
    }
    tui.printLine(chalk.gray('\nResume which? (number or session ID):'));
    return true;
  },
});

reg({
  name: 'rename',
  aliases: ['rename-session'],
  description: 'Rename current session',
  category: 'system',
  handler: async (args) => {
    if (args.length === 0) {
      tui.printLine(chalk.red('Usage: /rename <title>'));
      return true;
    }
    const session = sessionManager.getCurrentSession();
    if (session) {
      sessionManager.updateMetadata(session.id, { title: args.join(' ') });
      tui.printLine(chalk.green(`✓ Session renamed: ${args.join(' ')}`));
    } else {
      tui.printLine(chalk.yellow('No active session'));
    }
    return true;
  },
});

reg({
  name: 'export',
  aliases: ['export-session'],
  description: 'Export session',
  category: 'memory',
  handler: async (args) => {
    const fmt = args[0]?.toLowerCase() || 'md';
    const session = sessionManager.getCurrentSession();
    if (!session) {
      tui.printLine(chalk.yellow('No active session'));
      return true;
    }
    const messages = agent.getMessages();
    const { writeFileSync, existsSync, mkdirSync } = await import('fs');
    const { join } = await import('path');
    const exportDir = join(process.cwd(), '.ys', 'exports');
    if (!existsSync(exportDir)) mkdirSync(exportDir, { recursive: true });
    const fileName = `session-${session.id.slice(0, 8)}-${Date.now()}`;
    if (fmt === 'html' || fmt === 'htm') {
      const html = generateHtmlExport({ id: session.id, name: session.metadata?.title || session.id }, messages);
      writeFileSync(join(exportDir, `${fileName}.html`), html, 'utf-8');
    } else if (fmt === 'json') {
      const json = JSON.stringify({ session, messages }, null, 2);
      writeFileSync(join(exportDir, `${fileName}.json`), json, 'utf-8');
    } else if (fmt === 'jsonl') {
      const lines = messages.map((m) => JSON.stringify({ role: m.role, content: m.content, timestamp: m.timestamp }));
      writeFileSync(join(exportDir, `${fileName}.jsonl`), lines.join('\n'), 'utf-8');
    } else {
      const md = generateMdExport({ id: session.id, name: session.metadata?.title || session.id }, messages);
      writeFileSync(join(exportDir, `${fileName}.md`), md, 'utf-8');
    }
    tui.printLine(chalk.green(`✓ Exported session as ${fmt}`));
    tui.printLine(chalk.gray(`  ${join(exportDir, fileName)}.${fmt}`));
    return true;
  },
});

reg({
  name: 'tools',
  aliases: ['list-tools'],
  description: 'List available tools',
  category: 'settings',
  handler: async () => {
    const { toolRegistry } = await import('../tools/index.js');
    const tools = toolRegistry.getAll();
    const w = Math.min(phoneConfig.terminalWidth - 2, 50);
    tui.printLine(chalk.cyan(`\n╔═ Available Tools (${tools.length} active) ${'═'.repeat(Math.max(0, w - 24 - String(tools.length).length))}╗`));
    for (const tool of tools) {
      const name = tool.getName();
      const desc = tool.getDescription();
      const padding = ' '.repeat(Math.max(0, w - name.length - desc.length - 6));
      tui.printLine(`║  ${chalk.white(name.padEnd(18))} ${chalk.gray(desc)}${padding} ║`);
    }
    tui.printLine(chalk.cyan(`╚${'═'.repeat(w)}╝`));
    return true;
  },
});

reg({
  name: 'key',
  aliases: ['api-key', 'set-key'],
  description: 'Set API key',
  category: 'system',
  handler: async (args) => {
    if (args.length < 1) {
      tui.printLine(chalk.red('Usage: /key <provider> <api_key>'));
      return true;
    }
    const providerName = args[0];
    const apiKey = args.slice(1).join(' ');
    if (!apiKey) {
      tui.printLine(chalk.red('API key is required'));
      return true;
    }
    configManager.setApiKey(providerName, apiKey);
    tui.printLine(chalk.green(`✓ API key set for ${providerName}`));
    return true;
  },
});

reg({
  name: 'approval-mode',
  aliases: ['approval', 'mode'],
  description: 'Change approval mode',
  category: 'settings',
  handler: async (args) => {
    const mode = args[0]?.toLowerCase();
    if (mode === 'safe' || mode === 'normal' || mode === 'yolo') {
      tui.setApprovalMode(mode);
      if (mode === 'yolo') {
        tui.printWarning('YOLO mode active. Agent will execute actions without confirmation.');
      }
      tui.printLine(chalk.green(`✓ Approval mode: ${mode}`));
    } else {
      tui.printLine(chalk.cyan(`Current approval mode: ${tui.getApprovalMode()}`));
      tui.printLine(chalk.gray('  /approval-mode safe   — Always ask before actions'));
      tui.printLine(chalk.gray('  /approval-mode normal — Ask for important actions'));
      tui.printLine(chalk.gray('  /approval-mode yolo   — Auto-execute everything'));
    }
    return true;
  },
});

reg({
  name: 'theme',
  aliases: [],
  description: 'Change color theme',
  category: 'settings',
  handler: async (args) => {
    const theme = args[0]?.toLowerCase();
    if (theme === 'dark' || theme === 'light' || theme === 'matrix') {
      configManager.set('theme.mode', theme);
      tui.printLine(chalk.green(`✓ Theme: ${theme}`));
    } else {
      tui.printLine(chalk.cyan('Usage: /theme [dark|light|matrix]'));
      tui.printLine(chalk.gray('  Current: ' + configManager.getConfig().theme.mode));
    }
    return true;
  },
});

reg({
  name: 'config',
  aliases: ['settings', 'preferences'],
  description: 'View configuration',
  category: 'settings',
  handler: async () => {
    const config = configManager.getConfig();
    tui.printLine(chalk.cyan('\nConfiguration:'));
    const { providers, ...rest } = config as any;
    for (const [key, val] of Object.entries(rest)) {
      if (typeof val === 'object') {
        tui.printLine(`  ${chalk.white(key)}:`);
        for (const [sk, sv] of Object.entries(val as any)) {
          const valStr = sk === 'apiKey' ? (sv ? '***' : '') : String(sv);
          tui.printLine(`    ${chalk.gray(sk)}: ${chalk.yellow(valStr)}`);
        }
      } else {
        tui.printLine(`  ${chalk.white(key)}: ${chalk.yellow(String(val))}`);
      }
    }
    return true;
  },
});

reg({
  name: 'permissions',
  aliases: ['perms'],
  description: 'Tool permissions',
  category: 'settings',
  handler: async () => {
    const config = configManager.getConfig();
    tui.printLine(chalk.cyan('\nPermissions Configuration:'));
    tui.printLine(`  Read-only mode: ${config.security.readOnlyMode ? chalk.green('ON') : chalk.gray('OFF')}`);
    tui.printLine(`  Sandbox mode:   ${config.security.sandboxMode ? chalk.green('ON') : chalk.gray('OFF')}`);
    tui.printLine(`  Auto-approve:   ${config.permissions.autoApprove ? chalk.green('ON') : chalk.gray('OFF')}`);
    tui.printLine(`  Confirm before: ${config.permissions.askForConfirmation ? chalk.green('ON') : chalk.red('OFF')}`);
    tui.printLine(chalk.gray('\n  Denied commands:'));
    for (const cmd of config.permissions.deniedCommands) {
      tui.printLine(`    ${chalk.red('✕')} ${cmd}`);
    }
    return true;
  },
});

reg({
  name: 'review',
  aliases: ['code-review'],
  description: 'Code review',
  category: 'code',
  handler: async (args) => {
    const filePath = args[0];
    if (filePath) {
      const { readFileSync, existsSync } = await import('fs');
      const { resolve } = await import('path');
      const fullPath = resolve(process.cwd(), filePath);
      if (!existsSync(fullPath)) {
        tui.printLine(chalk.red(`File not found: ${filePath}`));
        return true;
      }
      const content = readFileSync(fullPath, 'utf-8');
      tui.printLine(chalk.cyan(`\nReviewing: ${filePath}`));
      const result = await agent.chat(`Code review this file. Find bugs, security issues, performance problems, and readability improvements:\n\n\`\`\`\n${content}\n\`\`\`\n\nFormat as:\n🐛 BUGS (count)\nSECURITY (count)\n⚡ PERFORMANCE (count)\n📖 READABILITY (count)`);
      if (result.content) {
        tui.printLine(chalk.green(`\n${result.content}`));
      }
    } else {
      const { execSync } = await import('child_process');
      try {
        const diff = execSync('git diff --cached 2>/dev/null || git diff 2>/dev/null', { encoding: 'utf-8' });
        if (diff.trim()) {
          tui.printLine(chalk.cyan('\nReviewing staged changes...'));
          const result = await agent.chat(`Code review these changes:\n\n${diff.slice(0, 8000)}\n\nFind bugs, security issues, and improvements.`);
          if (result.content) {
            tui.printLine(chalk.green(`\n${result.content}`));
          }
        } else {
          tui.printLine(chalk.yellow('No staged changes to review. Provide a file: /review <path>'));
        }
      } catch {
        tui.printLine(chalk.yellow('Not a git repo. Provide a file: /review <path>'));
      }
    }
    return true;
  },
});

reg({
  name: 'agents',
  aliases: ['subagents', 'agent-list'],
  description: 'Manage subagents',
  category: 'agents',
  handler: handleAgents,
});

reg({
  name: 'arena',
  aliases: ['compete'],
  description: 'Multi-model competition',
  category: 'agents',
  handler: handleArena,
});

reg({
  name: 'tasks',
  aliases: ['task-list', 'jobs'],
  description: 'Background task manager',
  category: 'agents',
  handler: handleTasks,
});

reg({
  name: 'background',
  aliases: ['bg', 'spawn'],
  description: 'Run command in background',
  category: 'agents',
  handler: handleBackground,
});

reg({
  name: 'apply',
  aliases: ['execute-plan'],
  description: 'Apply last AI suggestion',
  category: 'code',
  handler: async () => {
    const messages = agent.getMessages();
    const lastAssistant = [...messages].reverse().find((m) => m.role === 'assistant');
    if (!lastAssistant) {
      tui.printLine(chalk.yellow('No suggestion to apply'));
      return true;
    }
    tui.printLine(chalk.cyan('\nApplying last suggestion...'));
    return true;
  },
});

function statusBadge(s: string): string {
  const colors: Record<string, (x: string) => string> = {
    idle: chalk.gray, thinking: chalk.cyan, planning: chalk.yellow,
    executing: chalk.magenta, completed: chalk.green, error: chalk.red,
  };
  return (colors[s] || chalk.white)(s);
}

function modeBadge(m: AgentMode): string {
  const map: Record<AgentMode, string> = {
    chat: chalk.gray('chat'), plan: chalk.blue('plan'),
    goal: chalk.magenta('goal'), review: chalk.green('review'),
    arena: chalk.yellow('arena'),
  };
  return map[m] || chalk.gray(m);
}

function approvalBadge(m: ApprovalMode): string {
  const map: Record<ApprovalMode, string> = {
    safe: chalk.cyan('safe'), normal: chalk.gray('normal'), yolo: chalk.red('yolo'),
  };
  return map[m] || chalk.gray(m);
}

function getProgressBar(pct: number, width: number): string {
  const filled = Math.max(0, Math.min(Math.round((pct / 100) * width), width));
  const empty = Math.max(0, width - filled);
  const fillChar = '█';
  const emptyChar = '░';
  const color = pct > 80 ? chalk.red : pct > 50 ? chalk.yellow : chalk.green;
  return color(fillChar.repeat(filled)) + chalk.gray(emptyChar.repeat(empty));
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms % 60000) / 1000);
  return `${m}m ${s}s`;
}

function countTokens(text: string): number {
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

async function handlePlanMode(goal: string): Promise<void> {
  const w = Math.min(phoneConfig.terminalWidth - 2, 56);
  const result = await agent.chat(`Create a detailed step-by-step plan for: ${goal}. Format each step as "- Step N: description" without any code. Just the plan.`);
  if (result.content) {
    const top = `╔${'═'.repeat(w)}╗`;
    const planTitle = ` Plan: ${goal.slice(0, 40)}${goal.length > 40 ? '…' : ''} `;
    tui.printLine('');
    tui.printLine(chalk.blue(top));
    tui.printLine(`║${chalk.white(planTitle)}${' '.repeat(Math.max(0, w - planTitle.length))}║`);
    tui.printLine(`╠${'═'.repeat(w)}╣`);
    const steps = result.content.split('\n').filter((l) => l.trim());
    for (const step of steps) {
      const cleanStep = step.replace(/^[-*]\s*/, '');
      tui.printLine(`║  ${chalk.white(cleanStep)}${' '.repeat(Math.max(0, w - cleanStep.length - 3))}║`);
    }
    tui.printLine(chalk.blue(`╚${'═'.repeat(w)}╝`));
    tui.printLine(chalk.gray('\nType /apply to execute this plan, or continue chatting to refine.'));
  }
}

async function handleGoalMode(task: string): Promise<void> {
  tui.setStatus('thinking');
  const w = Math.min(phoneConfig.terminalWidth - 2, 56);
  tui.printLine(chalk.magenta(`\n╔═ Autonomous Execution ${'═'.repeat(Math.max(0, w - 24))}╗`));
  tui.printLine(`║  Goal: ${chalk.white(task.slice(0, 50))}${' '.repeat(Math.max(0, w - 8 - Math.min(task.length, 50)))}║`);
  tui.printLine(chalk.magenta(`╚${'═'.repeat(w)}╝`));
  tui.printLine('');
  try {
    const result = await agent.chat(`You are in autonomous goal mode. Your task: ${task}
Execute this task step by step using the available tools. 
Work autonomously — read files, write code, run commands as needed.
When done, summarize what you accomplished.`);
    if (result.content) {
      tui.printLine(chalk.green('\n✓ Goal execution complete'));
      tui.printAssistant(result.content);
    }
  } catch (error) {
    tui.printError(`Goal execution failed: ${error instanceof Error ? error.message : String(error)}`);
  }
  tui.setStatus('idle');
}

function showCategoryHelp(category: string): void {
  const icon = CATEGORY_ICONS[category] || ' ';
  const filtered = ALL_COMMANDS.filter((c: { category: string }) => c.category === category);
  tui.printLine(chalk.cyan(`\n${icon} ${category.toUpperCase()} Commands:`));
  for (const cmd of filtered) {
    const usage = cmd.usage || `/${cmd.command}`;
    tui.printLine(`  ${chalk.yellow(usage.padEnd(28))} ${chalk.gray(cmd.description)}`);
  }
}

function showSpecificHelp(command: string): void {
  const cmd = ALL_COMMANDS.find((c: { command: string }) => c.command === command);
  if (cmd) {
    tui.printLine(chalk.cyan(`\n/${cmd.command}:`));
    tui.printLine(`  ${cmd.description}`);
    if (cmd.usage) tui.printLine(`  ${chalk.yellow('Usage')}: ${cmd.usage}`);
  } else {
    tui.printLine(chalk.yellow(`Unknown command: ${command}`));
    tui.printLine(chalk.gray('Type /help to see all commands'));
  }
}

reg({
  name: 'update',
  aliases: ['upgrade', 'self-update'],
  description: 'Self-update from git (pull + rebuild)',
  category: 'system',
  handler: async () => {
    const { execSync } = await import('child_process');
    const { existsSync } = await import('fs');
    tui.printLine(chalk.cyan('\n◆ Checking for updates...'));
    const isGit = existsSync('.git');
    if (!isGit) {
      tui.printLine(chalk.yellow('Not a git repository. Update manually via npm.'));
      tui.printLine(chalk.gray('  npm install -g git+https://github.com/YSCodex/YSCode.git'));
      return true;
    }
    try {
      tui.printLine(chalk.gray('  Pulling latest code...'));
      execSync('git pull origin main 2>/dev/null || git pull origin master 2>/dev/null', { stdio: 'pipe' });
      tui.printLine(chalk.green('  ✓ Code updated'));
    } catch {
      tui.printLine(chalk.yellow('  Already up to date or network issue'));
    }
    try {
      tui.printLine(chalk.gray('  Installing dependencies...'));
      execSync('npm install', { stdio: 'pipe' });
      tui.printLine(chalk.green('  ✓ Dependencies installed'));
    } catch (e) {
      tui.printLine(chalk.red(`  ✗ npm install failed: ${e}`));
      return true;
    }
    try {
      tui.printLine(chalk.gray('  Building...'));
      execSync('npm run build', { stdio: 'pipe' });
      tui.printLine(chalk.green('  ✓ Build complete'));
    } catch (e) {
      tui.printLine(chalk.red(`  ✗ Build failed: ${e}`));
      return true;
    }
    tui.printLine(chalk.green('\n✓ YS Code Agent updated successfully!'));
    tui.printLine(chalk.gray('  Restart the agent to use the latest version.'));
    return true;
  },
});

reg({
  name: 'vim',
  aliases: ['vim-mode'],
  description: 'Toggle vim keybindings',
  category: 'settings',
  handler: async (args) => {
    const state = args[0]?.toLowerCase();
    const current = configManager.get('vimMode') as boolean || false;
    if (state === 'on' || state === 'true' || state === '1') {
      configManager.set('vimMode', true);
      tui.printLine(chalk.green('✓ Vim mode enabled'));
      tui.printLine(chalk.gray('  j/k for up/down, h/l for left/right, dd to clear'));
    } else if (state === 'off' || state === 'false' || state === '0') {
      configManager.set('vimMode', false);
      tui.printLine(chalk.green('✓ Vim mode disabled'));
    } else {
      const newState = !current;
      configManager.set('vimMode', newState);
      tui.printLine(chalk.green(`✓ Vim mode: ${newState ? 'ON' : 'OFF'}`));
      if (newState) tui.printLine(chalk.gray('  j/k: up/down | h/l: left/right | dd: clear line'));
    }
    return true;
  },
});

reg({
  name: 'workspace',
  aliases: ['ws', 'workdir'],
  description: 'Manage workspaces',
  category: 'system',
  handler: async (args) => {
    const sub = args[0]?.toLowerCase();
    const { existsSync, readFileSync, writeFileSync, mkdirSync } = await import('fs');
    const { join } = await import('path');
    const { homedir } = await import('os');
    const wsDir = join(homedir(), '.ys', 'workspaces');
    const wsFile = join(wsDir, 'workspaces.json');
    if (!existsSync(wsDir)) mkdirSync(wsDir, { recursive: true });
    let workspaces: Record<string, string> = {};
    try {
      if (existsSync(wsFile)) workspaces = JSON.parse(readFileSync(wsFile, 'utf-8'));
    } catch {}
    if (sub === 'save' || sub === 'add') {
      const name = args[1] || basename2(process.cwd());
      workspaces[name] = process.cwd();
      writeFileSync(wsFile, JSON.stringify(workspaces, null, 2), 'utf-8');
      tui.printLine(chalk.green(`✓ Workspace saved: ${name} → ${process.cwd()}`));
    } else if (sub === 'load' || sub === 'switch') {
      const name = args[1];
      if (name && workspaces[name]) {
        process.chdir(workspaces[name]);
        tui.printLine(chalk.green(`✓ Switched to workspace: ${name}`));
        tui.printLine(chalk.gray(`  ${workspaces[name]}`));
      } else if (name) {
        tui.printLine(chalk.red(`Workspace not found: ${name}`));
      } else {
        const keys = Object.keys(workspaces);
        tui.printLine(chalk.cyan('\nSaved Workspaces:'));
        for (const k of keys) tui.printLine(`  ${chalk.yellow(k)} → ${chalk.gray(workspaces[k])}`);
      }
    } else if (sub === 'list' || sub === 'ls') {
      const keys = Object.keys(workspaces);
      if (keys.length === 0) {
        tui.printLine(chalk.yellow('No saved workspaces'));
      } else {
        tui.printLine(chalk.cyan('\nSaved Workspaces:'));
        for (const k of keys) tui.printLine(`  ${chalk.yellow(k)} → ${chalk.gray(workspaces[k])}`);
      }
    } else if (sub === 'remove' || sub === 'rm' || sub === 'delete') {
      const name = args[1];
      if (name && workspaces[name]) {
        delete workspaces[name];
        writeFileSync(wsFile, JSON.stringify(workspaces, null, 2), 'utf-8');
        tui.printLine(chalk.green(`✓ Removed workspace: ${name}`));
      } else {
        tui.printLine(chalk.red(`Workspace not found: ${name}`));
      }
    } else {
      tui.printLine(chalk.cyan('\nWorkspace Manager:'));
      tui.printLine(`  ${chalk.yellow('/workspace save [name]')}    ${chalk.gray('Save current directory')}`);
      tui.printLine(`  ${chalk.yellow('/workspace load <name>')}    ${chalk.gray('Switch to workspace')}`);
      tui.printLine(`  ${chalk.yellow('/workspace list')}           ${chalk.gray('List workspaces')}`);
      tui.printLine(`  ${chalk.yellow('/workspace remove <name>')}  ${chalk.gray('Remove workspace')}`);
    }
    return true;
  },
});

reg({
  name: 'project-index',
  aliases: ['index', 'scan', 'code-index'],
  description: 'Index project for semantic search',
  category: 'files',
  handler: async (args) => {
    const { readFileSync, statSync, readdirSync, existsSync, writeFileSync, mkdirSync } = await import('fs');
    const { join, extname, relative, resolve } = await import('path');
    tui.printLine(chalk.cyan('\n◆ Indexing project...'));
    const startTime = Date.now();
    const projectRoot = process.cwd();
    const idxDir = join(projectRoot, '.ys', 'index');
    if (!existsSync(idxDir)) mkdirSync(idxDir, { recursive: true });
    const ignore = ['node_modules', '.git', 'dist', 'build', '.next', '__pycache__', '.ys'];
    const extensions = ['.ts', '.tsx', '.js', '.jsx', '.py', '.rs', '.go', '.java', '.kt', '.swift', '.php', '.rb', '.c', '.cpp', '.h', '.hpp', '.cs', '.vue', '.svelte', '.html', '.css', '.scss', '.json', '.yaml', '.yml', '.md', '.toml'];
    let totalFiles = 0;
    let totalLines = 0;
    const index: Array<{ path: string; lines: number; size: number; lang: string }> = [];
    function scan(dir: string): void {
      let entries: string[] = [];
      try { entries = readdirSync(dir); } catch { return; }
      for (const entry of entries) {
        if (ignore.includes(entry) || entry.startsWith('.')) continue;
        const full = join(dir, entry);
        try {
          const stat = statSync(full);
          if (stat.isDirectory()) scan(full);
          else if (stat.isFile()) {
            const ext = extname(entry).toLowerCase();
            if (extensions.includes(ext)) {
              const content = readFileSync(full, 'utf-8');
              const lines = content.split('\n').length;
              const rel = relative(projectRoot, full);
              const lang = getLangFromExt(ext);
              index.push({ path: rel, lines, size: stat.size, lang });
              totalFiles++;
              totalLines += lines;
              if (totalFiles % 100 === 0) tui.printLine(chalk.gray(`  Indexed ${totalFiles} files...`));
            }
          }
        } catch {}
      }
    }
    scan(projectRoot);
    const idxData = {
      project: basename2(projectRoot),
      indexedAt: new Date().toISOString(),
      totalFiles,
      totalLines,
      files: index,
    };
    writeFileSync(join(idxDir, 'code-index.json'), JSON.stringify(idxData, null, 2), 'utf-8');
    const duration = Date.now() - startTime;
    tui.printLine(chalk.green(`\n✓ Indexed ${totalFiles} files (${totalLines.toLocaleString()} lines) in ${duration}ms`));
    tui.printLine(chalk.gray(`  Index: ${join(idxDir, 'code-index.json')}`));
    if (args.includes('--search') || args.includes('-s')) {
      const query = args.filter(a => !a.startsWith('-')).join(' ');
      if (query) {
        const results = index.filter(f =>
          f.path.toLowerCase().includes(query.toLowerCase()) ||
          f.lang.toLowerCase().includes(query.toLowerCase())
        ).slice(0, 20);
        tui.printLine(chalk.cyan(`\nSearch results for "${query}":`));
        for (const r of results) {
          tui.printLine(`  ${chalk.white(r.path)} ${chalk.gray(`(${r.lines} lines, ${r.lang})`)}`);
        }
      }
    }
    return true;
  },
});

reg({
  name: 'watch',
  aliases: ['monitor', 'fswatch'],
  description: 'Watch files for changes',
  category: 'files',
  handler: async (args) => {
    const path = args[0] || '.';
    const { watch, existsSync, statSync } = await import('fs');
    const { resolve } = await import('path');
    const fullPath = resolve(process.cwd(), path);
    if (!existsSync(fullPath)) {
      tui.printLine(chalk.red(`Path not found: ${path}`));
      return true;
    }
    tui.printLine(chalk.cyan(`\n◆ Watching: ${path}`));
    tui.printLine(chalk.gray('  Press Ctrl+C to stop watching'));
    tui.printLine('');
    const stat = statSync(fullPath);
    if (stat.isDirectory()) {
      try {
        const watcher = watch(fullPath, { recursive: true }, (eventType, filename) => {
          if (filename) {
            const ts = new Date().toLocaleTimeString();
            tui.printLine(chalk.gray(`  [${ts}] ${chalk.white(String(filename))}: ${eventType === 'change' ? chalk.yellow('modified') : chalk.green('added/removed')}`));
          }
        });
        (process as any)._ysWatcher = watcher;
      } catch {
        tui.printLine(chalk.yellow('  File watching not available on this platform'));
      }
    } else {
      try {
        const watcher = watch(fullPath, (eventType) => {
          const ts = new Date().toLocaleTimeString();
          tui.printLine(chalk.gray(`  [${ts}] ${chalk.white(path)}: ${eventType === 'change' ? chalk.yellow('modified') : chalk.green('changed')}`));
        });
        (process as any)._ysWatcher = watcher;
      } catch {
        tui.printLine(chalk.yellow('  File watching not available on this platform'));
      }
    }
    return true;
  },
});

reg({
  name: 'publish',
  aliases: ['npm-publish', 'release'],
  description: 'Publish to npm registry',
  category: 'system',
  handler: async () => {
    const { execSync } = await import('child_process');
    tui.printLine(chalk.cyan('\n◆ Publishing to npm...'));
    tui.printLine(chalk.gray('  This will:'));
    tui.printLine(chalk.gray('  1. Build the project'));
    tui.printLine(chalk.gray('  2. Publish to npm registry'));
    tui.printLine(chalk.yellow('\n  Continue? [y/N]'));
    const { createInterface } = await import('readline');
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    rl.question('', async (answer: string) => {
      rl.close();
      if (answer.trim().toLowerCase() !== 'y') {
        tui.printLine(chalk.yellow('Publish cancelled'));
        return;
      }
      try {
        tui.printLine(chalk.gray('  Building...'));
        execSync('npm run build', { stdio: 'pipe' });
        tui.printLine(chalk.green('  ✓ Build complete'));
        tui.printLine(chalk.gray('  Publishing...'));
        execSync('npm publish --access public', { stdio: 'pipe' });
        tui.printLine(chalk.green('\n✓ Published to npm!'));
        tui.printLine(chalk.gray('  npm install -g ys-code-agent'));
      } catch (e: any) {
        tui.printLine(chalk.red(`\n✗ Publish failed: ${e.stderr || e.message}`));
        tui.printLine(chalk.gray('  Make sure you are logged in: npm login'));
      }
    });
    return true;
  },
});

function generateMdExport(session: { id: string; name: string }, messages: Array<{ role: string; content: string; timestamp: number }>): string {
  const lines: string[] = [
    `# YS Code Agent — Session Export`,
    ``,
    `**Session**: ${session.name}`,
    `**ID**: ${session.id.slice(0, 8)}`,
    `**Date**: ${new Date().toISOString()}`,
    `**Messages**: ${messages.length}`,
    ``,
    `---`,
    ``,
  ];
  for (const msg of messages) {
    if (msg.role === 'system') continue;
    const role = msg.role === 'user' ? '**User**' : '**Assistant**';
    const date = new Date(msg.timestamp).toISOString().slice(0, 19).replace('T', ' ');
    lines.push(`### ${role} (${date})`);
    lines.push('');
    lines.push(msg.content);
    lines.push('');
    lines.push('---');
    lines.push('');
  }
  return lines.join('\n');
}

function generateHtmlExport(session: { id: string; name: string }, messages: Array<{ role: string; content: string; timestamp: number }>): string {
  const msgHtml = messages
    .filter((m) => m.role !== 'system')
    .map((m) => {
      const role = m.role === 'user' ? 'user' : 'assistant';
      const content = m.content
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/```(\w+)?\n([\s\S]*?)```/g, '<pre><code class="language-$1">$2</code></pre>')
        .replace(/\n/g, '<br>');
      return `<div class="message ${role}"><div class="role">${role}</div><div class="content">${content}</div></div>`;
    })
    .join('\n');
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>YS Agent — ${session.name}</title>
  <style>
    body { font-family: monospace; max-width: 800px; margin: 0 auto; padding: 20px; background: #1a1b26; color: #c0caf5; }
    h1 { color: #7dcfff; border-bottom: 2px solid #7dcfff; }
    .message { margin: 10px 0; padding: 10px; border-radius: 4px; }
    .message.user { background: #1f2335; border-left: 3px solid #7aa2f7; }
    .message.assistant { background: #1f2335; border-left: 3px solid #9ece6a; }
    .role { font-weight: bold; color: #7dcfff; margin-bottom: 5px; text-transform: uppercase; }
    .content { line-height: 1.5; }
    pre { background: #0f0f1a; padding: 10px; border-radius: 4px; overflow-x: auto; }
    code { font-family: monospace; }
    .meta { color: #565f89; font-size: 0.9em; }
  </style>
</head>
<body>
  <h1>YS Code Agent — ${session.name}</h1>
  <p class="meta">Session: ${session.id.slice(0, 8)} | Messages: ${messages.length} | ${new Date().toISOString()}</p>
  ${msgHtml}
</body>
</html>`;
}

function basename2(p: string): string {
  return p.split(/[/\\]/).pop() || 'project';
}

function getLangFromExt(ext: string): string {
  const map: Record<string, string> = {
    '.ts': 'TypeScript', '.tsx': 'TSX', '.js': 'JavaScript', '.jsx': 'JSX',
    '.py': 'Python', '.rs': 'Rust', '.go': 'Go', '.java': 'Java',
    '.kt': 'Kotlin', '.swift': 'Swift', '.php': 'PHP', '.rb': 'Ruby',
    '.c': 'C', '.cpp': 'C++', '.h': 'C Header', '.hpp': 'C++ Header',
    '.cs': 'C#', '.vue': 'Vue', '.svelte': 'Svelte',
    '.html': 'HTML', '.css': 'CSS', '.scss': 'SCSS',
    '.json': 'JSON', '.yaml': 'YAML', '.yml': 'YAML',
    '.md': 'Markdown', '.toml': 'TOML',
  };
  return map[ext] || 'Unknown';
}

export function getCommand(name: string): CommandDef | undefined {
  return commands.get(name.toLowerCase());
}

export function getAllCommands(): CommandDef[] {
  return [...new Set(commands.values())];
}

export async function executeCommand(input: string): Promise<boolean> {
  const parts = input.slice(1).split(' ');
  const cmdName = parts[0].toLowerCase();
  const args = parts.slice(1);
  const cmd = getCommand(cmdName);
  if (!cmd) {
    tui.printLine(chalk.yellow(`Unknown command: ${input}`));
    tui.printLine(chalk.gray('Type /help to see available commands'));
    return false;
  }
  logger.info(`Executing command: ${cmdName}`);
  return cmd.handler(args, input);
}
