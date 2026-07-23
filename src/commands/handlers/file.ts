import chalk from 'chalk';
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync, statSync, unlinkSync, renameSync } from 'fs';
import { join, relative, resolve, basename, dirname, extname, sep } from 'path';
import { createInterface } from 'readline';
import { glob } from 'fast-glob';
import { tui } from '../../ui/index.js';
import { agent } from '../../agent/index.js';
import { isBinaryFile, formatBytes, getLanguageFromExtension, truncate } from '../../utils/index.js';
import { phoneConfig } from '../../ui/phoneOptimizer.js';

export async function handleRead(args: string[]): Promise<boolean> {
  if (args.length === 0) {
    tui.printLine(chalk.red('Usage: /read <path> [--all]'));
    return true;
  }
  const path = args[0];
  const showAll = args.includes('--all');
  const fullPath = resolve(process.cwd(), path);try {
    if (!existsSync(fullPath)) {
      tui.printLine(chalk.red(`File not found: ${path}`));
      return true;
    }
    const stat = statSync(fullPath);
    if (stat.isDirectory()) {
      tui.printLine(chalk.cyan(`\n${relative(process.cwd(), fullPath) || '.'}/`));
      printTree(fullPath, '', true);
      return true;
    }
    if (isBinaryFile(fullPath)) {
      tui.printLine(chalk.yellow('Binary file detected. Cannot display.'));
      return true;
    }
    const content = readFileSync(fullPath, 'utf-8');
    const lines = content.split('\n');
    const ext = extname(fullPath);
    const lang = getLanguageFromExtension(ext);
    tui.printLine(chalk.cyan(`\n╔═ ${basename(fullPath)} (${lang}) ═${'═'.repeat(Math.max(0, Math.min(40, phoneConfig.terminalWidth - 20)))}╗`));
    tui.printLine(chalk.gray(`  Path: ${relative(process.cwd(), fullPath)} | ${lines.length} lines | ${formatBytes(stat.size)}`));
    tui.printLine('');
    const maxLines = showAll ? lines.length : Math.min(lines.length, 100);
    const startLine = 0;
    for (let i = startLine; i < maxLines; i++) {
      const lineNum = chalk.gray(String(i + 1).padStart(4, ' '));
      tui.printLine(`${lineNum} ${lines[i]}`);
    }
    if (!showAll && lines.length > 100) {
      tui.printLine(chalk.gray(`\n  ... and ${lines.length - 100} more lines. Use /read ${path} --all to show all.`));
    }
  } catch (error) {
    tui.printLine(chalk.red(`Error reading file: ${error instanceof Error ? error.message : String(error)}`));
  }
  return true;
}

export async function handleEdit(args: string[]): Promise<boolean> {
  if (args.length < 2) {
    tui.printLine(chalk.red('Usage: /edit <path> <instruction>'));
    tui.printLine(chalk.gray('  Example: /edit src/index.ts add error handling for fetch calls'));
    return true;
  }
  const path = args[0];
  const instruction = args.slice(1).join(' ');
  const fullPath = resolve(process.cwd(), path);
  if (!existsSync(fullPath)) {
    tui.printLine(chalk.red(`File not found: ${path}`));
    return true;
  }
  tui.printLine(chalk.cyan(`\nEditing: ${path}`));
  tui.printLine(chalk.gray(`  Instruction: ${instruction}`));
  const content = readFileSync(fullPath, 'utf-8');
  const backupDir = join(process.cwd(), '.ys', 'backups');
  if (!existsSync(backupDir)) mkdirSync(backupDir, { recursive: true });
  const backupPath = join(backupDir, `${basename(fullPath)}.${Date.now()}.bak`);
  writeFileSync(backupPath, content, 'utf-8');
  const result = await agent.chat(`Edit the file "${path}" with this instruction: "${instruction}". Current content:\n\n\`\`\`\n${content}\n\`\`\`\n\nReturn ONLY the complete edited file content, nothing else.`);
  if (result.content) {
    const editedContent = extractCodeContent(result.content);
    if (editedContent && editedContent !== content) {
      showDiff(content, editedContent, path);
      tui.printLine(chalk.gray('\nApply changes? [Y/n]'));
      const confirm = await promptConfirm(true);
      if (confirm) {
        writeFileSync(fullPath, editedContent, 'utf-8');
        tui.printLine(chalk.green(`✓ Saved to ${path}`));
        tui.printLine(chalk.gray(`  Backup: ${backupPath}`));
      } else {
        tui.printLine(chalk.yellow('Edit cancelled'));
      }
    } else {
      tui.printLine(chalk.yellow('No changes to apply'));
    }
  }
  return true;
}

export async function handleCreate(args: string[]): Promise<boolean> {
  if (args.length === 0) {
    tui.printLine(chalk.red('Usage: /create <path> [content]'));
    tui.printLine(chalk.gray('  Example: /create src/utils.ts'));
    tui.printLine(chalk.gray('  Example: /create component Button'));
    return true;
  }
  const path = args[0];
  const fullPath = resolve(process.cwd(), path);
  if (existsSync(fullPath)) {
    tui.printLine(chalk.yellow(`File exists: ${path}`));
    tui.printLine(chalk.gray('Overwrite? [y/N]'));
    const confirm = await promptConfirm(false);
    if (!confirm) {
      tui.printLine(chalk.yellow('Cancelled'));
      return true;
    }
  }
  const dir = dirname(fullPath);
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  const initialContent = args.length > 1 ? args.slice(1).join(' ') : '';
  if (initialContent) {
    writeFileSync(fullPath, initialContent, 'utf-8');
    tui.printLine(chalk.green(`✓ Created ${path}`));
  } else {
    const ext = extname(fullPath);
    const template = getTemplate(ext);
    writeFileSync(fullPath, template, 'utf-8');
    tui.printLine(chalk.green(`✓ Created ${path} with template`));
  }
  return true;
}

export async function handleDelete(args: string[]): Promise<boolean> {
  if (args.length === 0) {
    tui.printLine(chalk.red('Usage: /delete <path> [--hard]'));
    return true;
  }
  const path = args[0];
  const hardDelete = args.includes('--hard');
  const fullPath = resolve(process.cwd(), path);
  if (!existsSync(fullPath)) {
    tui.printLine(chalk.red(`File not found: ${path}`));
    return true;
  }
  tui.printLine(chalk.yellow(`Delete ${path}? This cannot be undone. [y/N]`));
  const confirm = await promptConfirm(false);
  if (!confirm) {
    tui.printLine(chalk.yellow('Cancelled'));
    return true;
  }
  if (hardDelete) {
    unlinkSync(fullPath);
    tui.printLine(chalk.green(`✓ Permanently deleted ${path}`));
  } else {
    const trashDir = join(process.cwd(), '.ys', 'trash');
    if (!existsSync(trashDir)) mkdirSync(trashDir, { recursive: true });
    const trashPath = join(trashDir, `${basename(fullPath)}.${Date.now()}`);
    renameSync(fullPath, trashPath);
    tui.printLine(chalk.green(`✓ Moved to trash: ${path}`));
    tui.printLine(chalk.gray(`  Trash: ${trashPath}`));
  }
  return true;
}

export async function handleSearch(args: string[]): Promise<boolean> {
  if (args.length === 0) {
    tui.printLine(chalk.red('Usage: /search <query> [--ext <extension>]'));
    return true;
  }
  const query = args[0];
  const extFilter = args.indexOf('--ext') >= 0 ? args[args.indexOf('--ext') + 1] : null;
  tui.printLine(chalk.cyan(`\nSearching for: ${chalk.white(query)}`));
  try {
    const patterns = extFilter ? [`**/*.${extFilter}`] : ['**/*.ts', '**/*.tsx', '**/*.js', '**/*.jsx', '**/*.py', '**/*.json', '**/*.md', '**/*.html', '**/*.css'];
    const ignore = ['**/node_modules/**', '**/dist/**', '**/.git/**', '**/build/**'];
    const files = await glob(patterns, { ignore, cwd: process.cwd() });
    let totalMatches = 0;
    const results: Array<{ file: string; line: number; content: string }> = [];
    for (const file of files.slice(0, 200)) {
      try {
        const content = readFileSync(resolve(process.cwd(), file), 'utf-8');
        const lines = content.split('\n');
        for (let i = 0; i < lines.length; i++) {
          if (lines[i].toLowerCase().includes(query.toLowerCase())) {
            results.push({ file, line: i + 1, content: lines[i].trim() });
            totalMatches++;
            if (totalMatches >= 30) break;
          }
        }
      } catch {}
      if (totalMatches >= 30) break;
    }
    if (results.length === 0) {
      tui.printLine(chalk.yellow('No matches found'));
    } else {
      tui.printLine(chalk.gray(`\nFound ${results.length} matches:`));
      for (const r of results) {
        const line = chalk.yellow(String(r.line));
        const file = chalk.white(r.file);
        const match = highlightMatch(r.content, query);
        tui.printLine(`  ${file}:${line}:  ${match}`);
      }
    }
  } catch (error) {
    tui.printLine(chalk.red(`Search error: ${error instanceof Error ? error.message : String(error)}`));
  }
  return true;
}

export async function handleList(args: string[]): Promise<boolean> {
  const dir = args[0] || '.';
  const fullPath = resolve(process.cwd(), dir);
  if (!existsSync(fullPath)) {
    tui.printLine(chalk.red(`Directory not found: ${dir}`));
    return true;
  }
  const stat = statSync(fullPath);
  if (!stat.isDirectory()) {
    tui.printLine(chalk.yellow('Not a directory'));
    return true;
  }
  tui.printLine(chalk.cyan(`\n${relative(process.cwd(), fullPath) || '.'}/`));
  printTree(fullPath, '', true);
  return true;
}

export async function handleRefactor(args: string[]): Promise<boolean> {
  if (args.length < 2) {
    tui.printLine(chalk.red('Usage: /refactor <file> <instruction>'));
    return true;
  }
  const path = args[0];
  const instruction = args.slice(1).join(' ');
  const fullPath = resolve(process.cwd(), path);
  if (!existsSync(fullPath)) {
    tui.printLine(chalk.red(`File not found: ${path}`));
    return true;
  }
  tui.printLine(chalk.cyan(`\nRefactoring: ${path}`));
  tui.printLine(chalk.gray(`  ${instruction}`));
  const content = readFileSync(fullPath, 'utf-8');
  const backupDir = join(process.cwd(), '.ys', 'backups');
  if (!existsSync(backupDir)) mkdirSync(backupDir, { recursive: true });
  writeFileSync(join(backupDir, `${basename(fullPath)}.${Date.now()}.bak`), content, 'utf-8');
  const result = await agent.chat(`Refactor the file "${path}" with this instruction: "${instruction}". Current content:\n\n\`\`\`\n${content}\n\`\`\`\n\nReturn ONLY the complete refactored file content, nothing else.`);
  if (result.content) {
    const refactored = extractCodeContent(result.content);
    if (refactored && refactored !== content) {
      showDiff(content, refactored, path);
      tui.printLine(chalk.gray('\nApply refactoring? [Y/n]'));
      const confirm = await promptConfirm(true);
      if (confirm) {
        writeFileSync(fullPath, refactored, 'utf-8');
        tui.printLine(chalk.green(`✓ Refactored ${path}`));
      } else {
        tui.printLine(chalk.yellow('Refactoring cancelled'));
      }
    } else {
      tui.printLine(chalk.yellow('No changes to apply'));
    }
  }
  return true;
}

function printTree(dir: string, prefix: string, isRoot: boolean): void {
  try {
    const entries = readdirSync(dir).filter((e) => !e.startsWith('.'));
    for (let i = 0; i < entries.length; i++) {
      const entry = entries[i];
      const fullPath = join(dir, entry);
      const isLast = i === entries.length - 1;
      const stat = statSync(fullPath);
      const connector = isLast ? '└── ' : '├── ';
      if (stat.isDirectory()) {
        tui.printLine(`${prefix}${connector}${chalk.cyan(entry)}/`);
        const newPrefix = prefix + (isLast ? '    ' : '│   ');
        printTree(fullPath, newPrefix, false);
      } else {
        const ext = extname(entry);
        const color = getFileColor(ext);
        tui.printLine(`${prefix}${connector}${color(entry)}`);
      }
    }
  } catch {}
}

function getFileColor(ext: string): (s: string) => string {
  const map: Record<string, (s: string) => string> = {
    '.ts': chalk.blue, '.tsx': chalk.blue, '.js': chalk.yellow, '.jsx': chalk.yellow,
    '.json': chalk.gray, '.md': chalk.white, '.py': chalk.green, '.css': chalk.magenta,
    '.html': chalk.red, '.yml': chalk.gray, '.yaml': chalk.gray, '.sh': chalk.green,
  };
  return map[ext] || chalk.white;
}

function showDiff(oldContent: string, newContent: string, path: string): void {
  const oldLines = oldContent.split('\n');
  const newLines = newContent.split('\n');
  tui.printLine(chalk.cyan(`\nChanges for ${path}:`));
  const maxLen = Math.max(oldLines.length, newLines.length);
  for (let i = 0; i < maxLen; i++) {
    if (oldLines[i] !== newLines[i]) {
      if (i < oldLines.length) {
        tui.printLine(chalk.red(`  - ${oldLines[i]}`));
      }
      if (i < newLines.length) {
        tui.printLine(chalk.green(`  + ${newLines[i]}`));
      }
    }
  }
}

function extractCodeContent(text: string): string | null {
  const codeBlockRegex = /```(?:\w+)?\n([\s\S]*?)```/;
  const match = text.match(codeBlockRegex);
  if (match) return match[1].trim();
  const lines = text.split('\n');
  if (lines.length > 1) return text.trim();
  return null;
}

function highlightMatch(line: string, query: string): string {
  const lower = line.toLowerCase();
  const qLower = query.toLowerCase();
  const idx = lower.indexOf(qLower);
  if (idx === -1) return line;
  return line.slice(0, idx) + chalk.yellow(line.slice(idx, idx + query.length)) + line.slice(idx + query.length);
}

function getTemplate(ext: string): string {
  const templates: Record<string, string> = {
    '.ts': '// Generated by YS Code Agent\n\nexport function main(): void {\n  console.log("Hello, World!");\n}\n\nmain();\n',
    '.tsx': 'import React from "react";\n\ninterface Props {}\n\nexport const Component: React.FC<Props> = () => {\n  return <div>Hello, World!</div>;\n};\n',
    '.js': '// Generated by YS Code Agent\n\nfunction main() {\n  console.log("Hello, World!");\n}\n\nmain();\n',
    '.py': '# Generated by YS Code Agent\n\ndef main():\n    print("Hello, World!")\n\nif __name__ == "__main__":\n    main()\n',
    '.html': '<!DOCTYPE html>\n<html lang="en">\n<head>\n  <meta charset="UTF-8">\n  <meta name="viewport" content="width=device-width, initial-scale=1.0">\n  <title>Document</title>\n</head>\n<body>\n  <h1>Hello, World!</h1>\n</body>\n</html>\n',
    '.json': '{\n  "name": "project",\n  "version": "1.0.0",\n  "description": ""\n}\n',
    '.md': '# Project\n\n## Description\n\n## Usage\n\n## Notes\n',
  };
  return templates[ext] || `// Generated by YS Code Agent\n`;
}

function promptConfirm(defaultYes: boolean): Promise<boolean> {
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    const prompt = defaultYes ? '[Y/n] ' : '[y/N] ';
    rl.question(prompt, (answer: string) => {
      rl.close();
      const a = answer.trim().toLowerCase();
      if (defaultYes) {
        resolve(a !== 'n' && a !== 'no');
      } else {
        resolve(a === 'y' || a === 'yes');
      }
    });
  });
}
