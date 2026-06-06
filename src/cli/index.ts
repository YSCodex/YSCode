declare const __APP_VERSION__: string;

import { Command } from 'commander';
import chalk from 'chalk';
import { configManager } from '../config/index.js';
import { getLogger, destroyAll } from '../logger/index.js';
import { initializeTools } from '../tools/index.js';
import { AgentManager } from './agent.js';
import { InteractiveMode } from './interactive.js';
import { readFileSync, existsSync } from 'fs';

const logger = getLogger('cli');
const APP_VERSION = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : '1.0.0';

const program = new Command();

program
  .name('ys')
  .description('YS Code Agent - AI-Powered Terminal Coding Agent')
  .version(APP_VERSION)
  .option('-c, --config <path>', 'Path to config file')
  .option('-m, --model <model>', 'Model to use')
  .option('-p, --provider <provider>', 'Provider to use')
  .option('-d, --directory <path>', 'Working directory')
  .option('--read-only', 'Enable read-only mode')
  .option('--sandbox', 'Enable sandbox mode')
  .option('--verbose', 'Enable verbose logging')
  .option('--non-interactive', 'Run in non-interactive mode')
  .hook('preAction', () => {
    initializeTools();
  });

program
  .command('chat')
  .description('Start interactive chat session')
  .argument('[message]', 'Initial message')
  .action(async (message?: string) => {
    try {
      const interactive = new InteractiveMode();
      await interactive.start(message);
    } catch (error) {
      console.error(chalk.red('Fatal error:'), error);
      process.exit(1);
    }
  });

program
  .command('run <task>')
  .description('Execute a task and exit')
  .option('-t, --timeout <ms>', 'Task timeout in milliseconds', '300000')
  .action(async (task: string, options: { timeout: string }) => {
    try {
      const agent = new AgentManager();
      const result = await agent.executeTask(task, parseInt(options.timeout, 10));
      if (result) {
        console.log(chalk.green('\nResult:'));
        console.log(result);
      }
      process.exit(0);
    } catch (error) {
      console.error(chalk.red('Error:'), error);
      process.exit(1);
    }
  });

program
  .command('config')
  .description('Manage configuration')
  .option('-s, --set <key=value>', 'Set a config value')
  .option('-g, --get <key>', 'Get a config value')
  .option('-l, --list', 'List all config')
  .option('--reset', 'Reset to default config')
  .option('--export', 'Export config')
  .option('--import <file>', 'Import config from file')
  .action((options) => {
    if (options.reset) {
      configManager.reset();
      console.log(chalk.green('Configuration reset to defaults'));
      return;
    }

    if (options.export) {
      console.log(configManager.exportConfig());
      return;
    }

    if (options.import) {
      try {
        const content = readFileSync(options.import, 'utf-8');
        if (configManager.importConfig(content)) {
          console.log(chalk.green('Configuration imported successfully'));
        } else {
          console.error(chalk.red('Failed to import configuration'));
          process.exit(1);
        }
      } catch (error) {
        console.error(chalk.red('Failed to read import file:'), error);
        process.exit(1);
      }
      return;
    }

    if (options.set) {
      const eqIndex = options.set.indexOf('=');
      if (eqIndex === -1) {
        console.error(chalk.red('Invalid format. Use key=value'));
        process.exit(1);
      }
      const key = options.set.slice(0, eqIndex);
      const value = options.set.slice(eqIndex + 1);
      configManager.set(key, value);
      console.log(chalk.green(`Set ${key} = ${value}`));
      return;
    }

    if (options.get) {
      const value = configManager.get(options.get);
      console.log(value !== undefined ? value : chalk.red('Key not found'));
      return;
    }

    if (options.list) {
      console.log(configManager.exportConfig());
      return;
    }
  });

program
  .command('providers')
  .description('List configured AI providers')
  .action(() => {
    const config = configManager.getConfig();
    console.log(chalk.cyan('\nConfigured Providers:'));
    for (const provider of config.providers) {
      const active = provider.name === config.activeProvider ? chalk.green(' (active)') : '';
      const hasKey = provider.apiKey ? chalk.gray(' [key set]') : chalk.red(' [no key]');
      console.log(`  ${chalk.yellow(provider.name)}${active}${hasKey}`);
      console.log(`    Type: ${provider.type}`);
      console.log(`    Models: ${provider.models.join(', ')}`);
      console.log(`    Default: ${provider.defaultModel}`);
    }
  });

program
  .command('models')
  .description('List available models for current provider')
  .action(() => {
    const config = configManager.getConfig();
    const provider = configManager.getActiveProvider();
    console.log(chalk.cyan(`\nModels for ${provider.name}:`));
    for (const model of provider.models) {
      const current = model === config.model.model ? chalk.green(' (current)') : '';
      console.log(`  ${chalk.yellow(model)}${current}`);
    }
  });

program
  .command('session')
  .description('Manage sessions')
  .option('-l, --list', 'List sessions')
  .option('-s, --show <id>', 'Show session details')
  .option('-d, --delete <id>', 'Delete a session')
  .option('--export <id>', 'Export a session')
  .option('--import <file>', 'Import a session from file')
  .action(async (options) => {
    const { sessionManager } = await import('../session/index.js');

    if (options.list) {
      const sessions = sessionManager.listSessions();
      console.log(chalk.cyan('\nSessions:'));
      if (sessions.length === 0) {
        console.log(chalk.gray('  No sessions found'));
      }
      for (const s of sessions) {
        console.log(`  ${chalk.yellow(s.id)} ${chalk.gray(s.name)}`);
        console.log(`    Created: ${s.createdAt}, Messages: ${s.messageCount}`);
      }
      return;
    }

    if (options.show) {
      const session = sessionManager.getSession(options.show);
      if (session) {
        console.log(chalk.cyan(`\nSession: ${session.name}`));
        console.log(`  ID: ${session.id}`);
        console.log(`  Created: ${new Date(session.createdAt).toISOString()}`);
        console.log(`  Messages: ${session.state.messages.length}`);
        console.log(`  Status: ${session.state.status}`);
      } else {
        console.error(chalk.red('Session not found'));
      }
      return;
    }

    if (options.delete) {
      if (sessionManager.deleteSession(options.delete)) {
        console.log(chalk.green('Session deleted'));
      } else {
        console.error(chalk.red('Session not found'));
      }
      return;
    }

    if (options.export) {
      const exported = sessionManager.exportSession(options.export);
      if (exported) {
        console.log(exported);
      } else {
        console.error(chalk.red('Session not found'));
      }
      return;
    }

    if (options.import) {
      try {
        const content = readFileSync(options.import, 'utf-8');
        const session = sessionManager.importSession(content);
        if (session) {
          console.log(chalk.green(`Session imported: ${session.name} (${session.id})`));
        } else {
          console.error(chalk.red('Failed to import session'));
        }
      } catch (error) {
        console.error(chalk.red('Failed to read import file:'), error);
      }
      return;
    }
  });

program
  .command('log')
  .description('View agent logs')
  .option('-n, --lines <count>', 'Number of lines to show', '50')
  .option('-l, --level <level>', 'Filter by log level')
  .action((options) => {
    const logger_ = getLogger('cli');
    const logs = logger_.getRecentLogs(parseInt(options.lines, 10));

    let filtered = logs;
    if (options.level) {
      filtered = filtered.filter((l) => l.level === options.level);
    }

    console.log(chalk.cyan(`\nRecent Logs (${filtered.length} entries):`));
    for (const entry of filtered.slice(-parseInt(options.lines, 10))) {
      const color = entry.level === 'error' ? chalk.red :
                    entry.level === 'warn' ? chalk.yellow :
                    entry.level === 'debug' ? chalk.gray : chalk.white;
      console.log(color(`[${new Date(entry.timestamp).toISOString()}] [${entry.level}] [${entry.module}] ${entry.message}`));
    }
  });

program
  .command('doctor')
  .description('Run system diagnostics')
  .action(async () => {
    console.log(chalk.cyan('\nYS Code Agent Diagnostics'));
    console.log(chalk.gray('─'.repeat(50)));

    console.log(chalk.yellow('\nSystem:'));
    console.log(`  Node.js: ${process.version}`);
    console.log(`  Platform: ${process.platform}`);
    console.log(`  Arch: ${process.arch}`);
    console.log(`  CWD: ${process.cwd()}`);

    console.log(chalk.yellow('\nConfiguration:'));
    const config = configManager.getConfig();
    console.log(`  Config path: ${chalk.gray('~/.ys-code-agent/config.json')}`);
    console.log(`  Active provider: ${chalk.cyan(config.activeProvider)}`);
    console.log(`  Model: ${chalk.cyan(config.model.model)}`);
    const activeProv = configManager.getActiveProvider();
    console.log(`  API Key: ${activeProv.apiKey ? chalk.green('✓ Set') : chalk.red('✕ Not set')}`);

    console.log(chalk.yellow('\nTools:'));
    const { toolRegistry } = await import('../tools/index.js');
    const tools = toolRegistry.getToolNames();
    console.log(`  Registered tools (${tools.length}): ${tools.join(', ')}`);

    console.log(chalk.yellow('\nGit:'));
    const { gitManager } = await import('../git/index.js');
    console.log(`  Available: ${gitManager.isAvailable() ? chalk.green('✓') : chalk.red('✕')}`);
    console.log(`  Repository: ${gitManager.isRepo() ? chalk.green('✓') : chalk.gray('Not a git repo')}`);

    console.log(chalk.yellow('\nMemory:'));
    const { memoryManager } = await import('../memory/index.js');
    const context = memoryManager.getContext();
    console.log(`  Short-term messages: ${context.shortTerm.messages.length}`);
    console.log(`  Long-term summaries: ${context.longTerm.summaries.length}`);
    console.log(`  Previous tasks: ${context.longTerm.previousTasks.length}`);

    console.log(chalk.green('\n✓ Diagnostics complete\n'));
  });

async function main() {
  program.parse(process.argv);

  const options = program.opts();

  if (options.config) {
    const { ConfigManager } = await import('../config/index.js');
    const customConfig = new ConfigManager(options.config);
  }

  if (options.model) {
    configManager.set('model.model', options.model);
  }

  if (options.provider) {
    configManager.setActiveProvider(options.provider);
  }

  if (options.directory) {
    process.chdir(options.directory);
  }

  if (options.readOnly) {
    configManager.set('security.readOnlyMode', true);
  }

  if (options.sandbox) {
    configManager.set('security.sandboxMode', true);
  }

  if (options.verbose) {
    configManager.set('logging.level', 'debug');
  }

  const noCommand = process.argv.length <= 2 || process.argv[2]?.startsWith('-');

  if (noCommand) {
    const interactive = new InteractiveMode();
    await interactive.start();
  }
}

main().catch((error) => {
  console.error(chalk.red('Fatal error:'), error);
  process.exit(1);
});

process.on('exit', () => {
  destroyAll();
});

process.on('SIGINT', () => {
  console.log(chalk.yellow('\nShutting down...'));
  destroyAll();
  process.exit(0);
});

process.on('SIGTERM', () => {
  destroyAll();
  process.exit(0);
});

process.on('uncaughtException', (error) => {
  console.error(chalk.red('Uncaught exception:'), error);
  destroyAll();
  process.exit(1);
});

process.on('unhandledRejection', (reason) => {
  console.error(chalk.red('Unhandled rejection:'), reason);
});
