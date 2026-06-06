import chalk from 'chalk';
import { configManager } from '../config/index.js';
import { phoneConfig } from './phoneOptimizer.js';

let animateState = 0;

export function generateWelcome(): string {
  if (phoneConfig.showCompactWelcome()) {
    return generateCompactWelcome();
  }
  return generateFullWelcome();
}

function generateFullWelcome(): string {
  const config = configManager.getConfig();
  const provider = configManager.getActiveProvider();
  const model = config.model.model;
  const modelShort = model.length > 20 ? model.slice(0, 18) + '…' : model;

  const w = phoneConfig.getWelcomeBoxWidth();
  const top = `╔${'═'.repeat(w)}╗`;
  const bottom = `╚${'═'.repeat(w)}╝`;

  const diamond = animateState ? '◆' : '◇';

  const lines: string[] = [top];

  const titleRaw = `  ${diamond} YS CODE AGENT ${diamond}  `;
  const titlePad = Math.floor((w - titleRaw.length) / 2);
  lines.push(`║${' '.repeat(titlePad)}${chalk.cyan(titleRaw)}${' '.repeat(w - titlePad - titleRaw.length)}║`);

  const subtitle = chalk.gray('AI-Powered Terminal Coding Assistant');
  const subPad = Math.floor((w - subtitle.length) / 2);
  lines.push(`║${' '.repeat(subPad)}${subtitle}${' '.repeat(w - subPad - subtitle.length)}║`);

  lines.push(`╠${'═'.repeat(w)}╣`);

  const rows: [string, string, string, string][] = [
    ['Provider', chalk.yellow(provider.name), 'Model', chalk.yellow(modelShort)],
    ['Context', chalk.gray(`${config.context.maxTokens.toLocaleString()} tokens`), 'Temp', chalk.gray(`${config.model.temperature}`)],
    ['Tools', chalk.green(`${countActiveTools()} Active`), 'Mode', chalk.cyan(getModeString())],
    ['Memory', chalk.green('✓ Enabled'), 'Git', chalk.green(isGitRepo() ? '✓ Connected' : '○ None')],
    ['Tasks', chalk.gray('0 Running'), 'Session', chalk.yellow(`#${getSessionId()}`)],
  ];

  for (const [label1, val1, label2, val2] of rows) {
    const part1 = `║  ${chalk.white(label1)}: ${val1}`;
    const part2 = `${chalk.white(label2)}: ${val2}`;
    const spacing = w - part1.length - part2.length - 1;
    const spaceStr = spacing > 0 ? ' '.repeat(spacing) : '  ';
    lines.push(`${part1}${spaceStr}${part2} ${chalk.gray('│')}`);
  }

  lines.push(`╠${'═'.repeat(w)}╣`);

  const tipLine = `  ${chalk.gray('Type')} ${chalk.cyan('/')}${chalk.gray(' for commands')}  ${chalk.gray('|')}  ${chalk.cyan('?')}${chalk.gray(' for shortcuts')}`;
  const tipPad = Math.floor((w - tipLine.length) / 2);
  lines.push(`║${' '.repeat(tipPad)}${tipLine}${' '.repeat(w - tipPad - tipLine.length)}║`);

  const projectLine = getProjectLine(w);
  lines.push(`║${projectLine}${' '.repeat(w - projectLine.length)}║`);

  lines.push(bottom);
  return lines.join('\n');
}

function generateCompactWelcome(): string {
  const config = configManager.getConfig();
  const model = config.model.model;
  const modelShort = model.length > 14 ? model.slice(0, 12) + '…' : model;
  const w = phoneConfig.getWelcomeBoxWidth();

  const top = `╔${'═'.repeat(w)}╗`;
  const bottom = `╚${'═'.repeat(w)}╝`;

  const title = `${chalk.cyan('◆ YS AGENT')} ${chalk.yellow(modelShort)}`;
  const titlePad = Math.floor((w - title.length) / 2);

  return [
    top,
    `║${' '.repeat(titlePad)}${title}${' '.repeat(w - titlePad - title.length)}║`,
    `║  ${chalk.gray('Tools:')} ${chalk.green(`${countActiveTools()} Active`)}  ${chalk.gray('Mode:')} ${chalk.cyan(getModeString())}  ║`,
    `║  ${chalk.gray('/ for commands  |  ? for shortcuts')}  ║`,
    bottom,
  ].join('\n');
}

function getProjectLine(maxWidth: number): string {
  try {
    const cwd = process.cwd();
    const home = process.env.HOME || '/home';
    const displayPath = cwd.startsWith(home) ? '~' + cwd.slice(home.length) : cwd;
    const projectType = detectProjectType();
    const gitBranch = getGitBranch();
    let line = `  ${chalk.gray('📁')} ${chalk.white(displayPath)}`;
    if (projectType) line += `  ${chalk.gray(`(${projectType})`)}`;
    if (gitBranch) line += `  ${chalk.gray('⎇')} ${chalk.yellow(gitBranch)}`;
    return line;
  } catch {
    return '';
  }
}

function detectProjectType(): string | null {
  try {
    const fs = require('fs');
    if (fs.existsSync('package.json')) return 'Node.js';
    if (fs.existsSync('Cargo.toml')) return 'Rust';
    if (fs.existsSync('pyproject.toml') || fs.existsSync('requirements.txt')) return 'Python';
    if (fs.existsSync('build.gradle') || fs.existsSync('build.gradle.kts')) return 'Android';
    if (fs.existsSync('go.mod')) return 'Go';
    if (fs.existsSync('Gemfile')) return 'Ruby';
    if (fs.existsSync('composer.json')) return 'PHP';
    if (fs.existsSync('CMakeLists.txt')) return 'C/C++';
    if (fs.existsSync('pubspec.yaml')) return 'Flutter/Dart';
    if (fs.existsSync('mix.exs')) return 'Elixir';
    if (fs.existsSync('Cargo.lock')) return 'Rust';
    return null;
  } catch {
    return null;
  }
}

function getGitBranch(): string | null {
  try {
    const fs = require('fs');
    const head = fs.readFileSync('.git/HEAD', 'utf-8').trim();
    const match = head.match(/ref: refs\/heads\/(.+)/);
    return match ? match[1] : null;
  } catch {
    return null;
  }
}

function isGitRepo(): boolean {
  try {
    const fs = require('fs');
    return fs.existsSync('.git');
  } catch {
    return false;
  }
}

function countActiveTools(): number {
  try {
    const { toolRegistry } = require('../tools/index.js');
    return toolRegistry.getToolNames().length;
  } catch {
    return 0;
  }
}

function getModeString(): string {
  const config = configManager.getConfig();
  if (config.security.readOnlyMode) return 'Read-Only';
  if (config.security.sandboxMode) return 'Sandbox';
  return 'Normal';
}

function getSessionId(): string {
  try {
    const { sessionManager } = require('../session/index.js');
    const session = sessionManager.getCurrentSession();
    return session ? session.id.slice(0, 4) : 'none';
  } catch {
    return 'none';
  }
}

export function animateDiamond(): void {
  animateState = 1;
  setTimeout(() => { animateState = 0; }, 600);
}
