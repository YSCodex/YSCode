import chalk from 'chalk';
import { execSync } from 'child_process';
import { existsSync, readFileSync } from 'fs';
import { tui } from '../../ui/index.js';
import { agent } from '../../agent/index.js';

export async function handleGit(args: string[]): Promise<boolean> {
  if (args.length === 0) {
    showGitHelp();
    return true;
  }
  const subcommand = args[0].toLowerCase();
  const gitArgs = args.slice(1).join(' ');

  if (!isGitRepo() && !['init', 'help', 'clone'].includes(subcommand)) {
    tui.printLine(chalk.red('Not a git repository'));
    return true;
  }

  switch (subcommand) {
    case 'status':
      return gitStatus();
    case 'diff':
      return gitDiff(gitArgs);
    case 'commit':
      return gitCommit(gitArgs);
    case 'log':
      return gitLog(gitArgs);
    case 'branch':
      return gitBranch(gitArgs);
    case 'checkout':
      return gitCheckout(gitArgs);
    case 'push':
      return gitPush(gitArgs);
    case 'pull':
      return gitPull(gitArgs);
    case 'add':
      return gitAdd(gitArgs);
    case 'stash':
      return gitStash(gitArgs);
    case 'init':
      return gitInit();
    case 'help':
      showGitHelp();
      return true;
    default:
      tui.printLine(chalk.yellow(`Unknown git subcommand: ${subcommand}`));
      showGitHelp();
      return true;
  }
}

function isGitRepo(): boolean {
  try {
    execSync('git rev-parse --git-dir 2>/dev/null', { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

function runGit(args: string): { stdout: string; stderr: string; code: number } {
  try {
    const stdout = execSync(`git ${args}`, { encoding: 'utf-8', maxBuffer: 10 * 1024 * 1024 });
    return { stdout: stdout.trim(), stderr: '', code: 0 };
  } catch (error: any) {
    return {
      stdout: error.stdout?.toString().trim() || '',
      stderr: error.stderr?.toString().trim() || error.message,
      code: error.status || 1,
    };
  }
}

async function gitStatus(): Promise<boolean> {
  const result = runGit('status --porcelain');
  const branchResult = runGit('branch --show-current');
  const branch = branchResult.stdout || 'unknown';
  const aheadResult = runGit('log --oneline @{upstream}..HEAD 2>/dev/null');
  const ahead = aheadResult.stdout ? ` (${aheadResult.stdout.split('\n').length} ahead of origin)` : '';
  tui.printLine(chalk.cyan(`\n🌿 Branch: ${chalk.white(branch)}${chalk.gray(ahead)}`));
  if (!result.stdout) {
    tui.printLine(chalk.gray('  Working tree clean'));
    return true;
  }
  const lines = result.stdout.split('\n');
  const modified: string[] = [];
  const untracked: string[] = [];
  const staged: string[] = [];
  for (const line of lines) {
    const status = line.slice(0, 2);
    const file = line.slice(3);
    if (status.trim() === 'M') modified.push(file);
    else if (status === '??') untracked.push(file);
    else if (status.includes('A') || status.includes('M')) staged.push(file);
  }
  if (staged.length > 0) {
    tui.printLine(chalk.gray('\n  Staged:'));
    for (const f of staged) tui.printLine(`    ${chalk.green('A')}  ${f}`);
  }
  if (modified.length > 0) {
    tui.printLine(chalk.gray('\n  Modified:'));
    for (const f of modified) tui.printLine(`    ${chalk.yellow('M')}  ${f}`);
  }
  if (untracked.length > 0) {
    tui.printLine(chalk.gray('\n  Untracked:'));
    for (const f of untracked) tui.printLine(`    ${chalk.red('?')}  ${f}`);
  }
  return true;
}

async function gitDiff(args: string): Promise<boolean> {
  const result = runGit(`diff ${args}`);
  if (!result.stdout) {
    tui.printLine(chalk.gray('No changes'));
    return true;
  }
  const lines = result.stdout.split('\n');
  tui.printLine(chalk.cyan('\nDiff:'));
  for (const line of lines) {
    if (line.startsWith('+') && !line.startsWith('+++')) {
      tui.printLine(chalk.green(line));
    } else if (line.startsWith('-') && !line.startsWith('---')) {
      tui.printLine(chalk.red(line));
    } else if (line.startsWith('@@')) {
      tui.printLine(chalk.cyan(line));
    } else {
      tui.printLine(chalk.gray(line));
    }
  }
  return true;
}

async function gitCommit(args: string): Promise<boolean> {
  if (args === '--ai' || args === '--auto') {
    const diffResult = runGit('diff --cached');
    if (!diffResult.stdout) {
      tui.printLine(chalk.yellow('Nothing staged for commit'));
      tui.printLine(chalk.gray('  Use /git add <file> to stage files first'));
      return true;
    }
    tui.printLine(chalk.cyan('\nGenerating commit message...'));
    const result = await agent.chat(`Generate a conventional commit message for these changes:\n\n${diffResult.stdout.slice(0, 4000)}\n\nRespond with ONLY the commit message, nothing else.`);
    if (result.content) {
      const msg = result.content.trim().replace(/^["']|["']$/g, '');
      tui.printLine(chalk.gray(`\nSuggested message: ${chalk.white(msg)}`));
      tui.printLine(chalk.gray('Use this message? [Y/n/edit]'));
      const confirm = await promptWithOptions();
      if (confirm === 'yes') {
        runGit(`commit -m "${msg.replace(/"/g, '\\"')}"`);
        tui.printLine(chalk.green('✓ Committed'));
      } else if (confirm === 'edit') {
        tui.printLine(chalk.yellow('Enter custom message:'));
        const custom = await promptLine();
        if (custom) {
          runGit(`commit -m "${custom.replace(/"/g, '\\"')}"`);
          tui.printLine(chalk.green('✓ Committed'));
        }
      } else {
        tui.printLine(chalk.yellow('Commit cancelled'));
      }
    }
  } else if (args) {
    const result = runGit(`commit -m "${args.replace(/"/g, '\\"')}"`);
    if (result.code === 0) tui.printLine(chalk.green('✓ Committed'));
    else tui.printLine(chalk.red(`Commit failed: ${result.stderr}`));
  } else {
    tui.printLine(chalk.red('Usage: /git commit "message" or /git commit --ai'));
  }
  return true;
}

async function gitLog(args: string): Promise<boolean> {
  const fmt = args.includes('--oneline') ? '--oneline' : '--oneline --graph --decorate --all';
  const result = runGit(`log ${fmt} -20`);
  if (result.stdout) {
    tui.printLine(chalk.cyan('\nGit Log:'));
    for (const line of result.stdout.split('\n')) {
      if (line.includes('*')) {
        tui.printLine(chalk.yellow(line));
      } else {
        tui.printLine(chalk.white(line));
      }
    }
  } else {
    tui.printLine(chalk.gray('No commits yet'));
  }
  return true;
}

async function gitBranch(args: string): Promise<boolean> {
  if (args) {
    const result = runGit(`branch ${args}`);
    if (result.code === 0) tui.printLine(chalk.green(`✓ Created branch: ${args}`));
    else tui.printLine(chalk.red(`Error: ${result.stderr}`));
  } else {
    const result = runGit('branch -a');
    if (result.stdout) {
      tui.printLine(chalk.cyan('\nBranches:'));
      for (const line of result.stdout.split('\n')) {
        if (line.startsWith('*')) {
          tui.printLine(chalk.green(`  ${line}`));
        } else {
          tui.printLine(chalk.gray(`  ${line}`));
        }
      }
    }
  }
  return true;
}

async function gitCheckout(args: string): Promise<boolean> {
  if (!args) {
    tui.printLine(chalk.red('Usage: /git checkout <branch>'));
    return true;
  }
  const result = runGit(`checkout ${args}`);
  if (result.code === 0) tui.printLine(chalk.green(`✓ Switched to branch: ${args}`));
  else tui.printLine(chalk.red(`Error: ${result.stderr}`));
  return true;
}

async function gitPush(args: string): Promise<boolean> {
  const result = runGit(`push ${args || 'origin HEAD'}`);
  if (result.code === 0) tui.printLine(chalk.green('✓ Pushed'));
  else tui.printLine(chalk.red(`Push error: ${result.stderr}`));
  return true;
}

async function gitPull(args: string): Promise<boolean> {
  const result = runGit(`pull ${args || 'origin HEAD'}`);
  if (result.code === 0) tui.printLine(chalk.green('✓ Pulled'));
  else tui.printLine(chalk.red(`Pull error: ${result.stderr}`));
  return true;
}

async function gitAdd(args: string): Promise<boolean> {
  if (!args) {
    tui.printLine(chalk.red('Usage: /git add <file> or /git add .'));
    return true;
  }
  const result = runGit(`add ${args}`);
  if (result.code === 0) tui.printLine(chalk.green(`✓ Staged: ${args}`));
  else tui.printLine(chalk.red(`Error: ${result.stderr}`));
  return true;
}

async function gitStash(args: string): Promise<boolean> {
  if (args === 'pop') {
    const result = runGit('stash pop');
    if (result.code === 0) tui.printLine(chalk.green('✓ Stash popped'));
    else tui.printLine(chalk.red(`Error: ${result.stderr}`));
  } else if (args === 'list') {
    const result = runGit('stash list');
    if (result.stdout) tui.printLine(chalk.gray(result.stdout));
    else tui.printLine(chalk.gray('No stashes'));
  } else {
    const msg = args ? ` -m "${args}"` : '';
    const result = runGit(`stash push${msg}`);
    if (result.code === 0) tui.printLine(chalk.green('✓ Changes stashed'));
    else tui.printLine(chalk.red(`Error: ${result.stderr}`));
  }
  return true;
}

async function gitInit(): Promise<boolean> {
  const result = runGit('init');
  if (result.code === 0) tui.printLine(chalk.green('✓ Git repository initialized'));
  else tui.printLine(chalk.red(`Error: ${result.stderr}`));
  return true;
}

function showGitHelp(): void {
  tui.printLine(chalk.cyan('\nGit Commands:'));
  const cmds = [
    ['/git status', 'Show working tree status'],
    ['/git diff', 'Show changes'],
    ['/git add <file>', 'Stage changes'],
    ['/git commit "msg"', 'Commit with message'],
    ['/git commit --ai', 'AI-generated commit message'],
    ['/git log', 'View commit history'],
    ['/git branch', 'List branches'],
    ['/git checkout <b>', 'Switch branch'],
    ['/git push', 'Push to remote'],
    ['/git pull', 'Pull from remote'],
    ['/git stash', 'Stash changes'],
    ['/git stash pop', 'Restore stashed changes'],
    ['/git init', 'Initialize repository'],
  ];
  for (const [cmd, desc] of cmds) {
    tui.printLine(`  ${chalk.yellow(cmd.padEnd(28))} ${chalk.gray(desc)}`);
  }
}

function promptWithOptions(): Promise<string> {
  return new Promise((resolve) => {
    const { createInterface } = require('readline');
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    rl.question('', (answer: string) => {
      rl.close();
      const a = answer.trim().toLowerCase();
      if (a === 'y' || a === 'yes' || a === '') resolve('yes');
      else if (a === 'e' || a === 'edit') resolve('edit');
      else resolve('no');
    });
  });
}

function promptLine(): Promise<string> {
  return new Promise((resolve) => {
    const { createInterface } = require('readline');
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    rl.question('> ', (answer: string) => {
      rl.close();
      resolve(answer.trim());
    });
  });
}
