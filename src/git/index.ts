import { execSync, exec } from 'child_process';
import { existsSync, readFileSync } from 'fs';
import { join, resolve } from 'path';
import { getLogger } from '../logger/index.js';
import { configManager } from '../config/index.js';
import { DiffResult, DiffHunk } from '../types.js';

const logger = getLogger('git');

export class GitManager {
  private basePath: string;
  private gitPath: string;

  constructor(basePath?: string) {
    this.basePath = basePath || process.cwd();
    this.gitPath = this.findGitPath();
  }

  private findGitPath(): string {
    try {
      const result = execSync('which git 2>/dev/null || where git 2>/dev/null', {
        encoding: 'utf-8',
        timeout: 5000,
      }).trim();
      return result || 'git';
    } catch {
      return 'git';
    }
  }

  isAvailable(): boolean {
    try {
      execSync(`${this.gitPath} --version`, { encoding: 'utf-8', timeout: 3000 });
      return true;
    } catch {
      return false;
    }
  }

  isRepo(dir?: string): boolean {
    const checkDir = dir || this.basePath;
    try {
      const result = execSync(`${this.gitPath} rev-parse --git-dir 2>/dev/null`, {
        cwd: checkDir,
        encoding: 'utf-8',
        timeout: 3000,
      }).trim();
      return result.length > 0;
    } catch {
      return false;
    }
  }

  private exec(args: string, options?: { workdir?: string; timeout?: number }): string {
    const workdir = options?.workdir || this.basePath;
    const timeout = options?.timeout || 30000;

    try {
      return execSync(`${this.gitPath} ${args}`, {
        cwd: resolve(workdir),
        encoding: 'utf-8',
        timeout,
        maxBuffer: 10 * 1024 * 1024,
      }).trim();
    } catch (error) {
      if (error instanceof Error) {
        throw new Error(`Git error: ${error.message}`);
      }
      throw error;
    }
  }

  private async execAsync(args: string, options?: { workdir?: string; timeout?: number }): Promise<string> {
    const workdir = options?.workdir || this.basePath;
    const timeout = options?.timeout || 30000;

    return new Promise((resolvePromise, reject) => {
      exec(`${this.gitPath} ${args}`, {
        cwd: resolve(workdir),
        encoding: 'utf-8',
        timeout,
        maxBuffer: 10 * 1024 * 1024,
      }, (error, stdout) => {
        if (error) {
          reject(new Error(`Git error: ${error.message}`));
        } else {
          resolvePromise(stdout.trim());
        }
      });
    });
  }

  async status(): Promise<string> {
    try {
      const result = this.exec('status');
      return result;
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async diff(filePath?: string): Promise<string> {
    try {
      const args = filePath ? `diff ${filePath}` : 'diff';
      return this.exec(args);
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async diffStaged(): Promise<string> {
    try {
      return this.exec('diff --cached');
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async parseDiff(filePath?: string): Promise<DiffResult[]> {
    try {
      const args = filePath ? `diff --unified=3 ${filePath}` : 'diff --unified=3';
      const output = this.exec(args);
      return this.parseDiffOutput(output);
    } catch {
      return [];
    }
  }

  private parseDiffOutput(output: string): DiffResult[] {
    if (!output) return [];

    const results: DiffResult[] = [];
    const files: string[] = [];
    const hunksMap = new Map<string, DiffHunk[]>();

    const lines = output.split('\n');
    let currentFile = '';
    let currentHunk: DiffHunk | null = null;

    for (const line of lines) {
      const fileMatch = line.match(/^diff --git a\/(.+?) b\/(.+?)$/);
      if (fileMatch) {
        currentFile = fileMatch[2];
        files.push(currentFile);
        hunksMap.set(currentFile, []);
        continue;
      }

      const hunkMatch = line.match(/^@@ -(\d+),?(\d*) \+(\d+),?(\d*) @@/);
      if (hunkMatch && currentFile) {
        currentHunk = {
          oldStart: parseInt(hunkMatch[1], 10),
          oldLines: parseInt(hunkMatch[2] || '1', 10),
          newStart: parseInt(hunkMatch[3], 10),
          newLines: parseInt(hunkMatch[4] || '1', 10),
          content: line + '\n',
        };
        hunksMap.get(currentFile)!.push(currentHunk);
        continue;
      }

      if (currentHunk) {
        currentHunk.content += line + '\n';
      }
    }

    for (const file of files) {
      const hunks = hunksMap.get(file) || [];
      let additions = 0;
      let deletions = 0;

      for (const hunk of hunks) {
        const hunkLines = hunk.content.split('\n');
        for (const hl of hunkLines) {
          if (hl.startsWith('+') && !hl.startsWith('+++')) additions++;
          else if (hl.startsWith('-') && !hl.startsWith('---')) deletions++;
        }
      }

      results.push({ file, additions, deletions, hunks });
    }

    return results;
  }

  async commit(message: string, args?: string[]): Promise<string> {
    const config = configManager.getConfig();
    const extraArgs = args ? args.join(' ') : '';
    const signFlag = config.git.signCommits ? ' -S' : '';

    try {
      return this.exec(`commit${signFlag} -m "${message.replace(/"/g, '\\"')}" ${extraArgs}`.trim());
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async autoCommit(message?: string): Promise<string> {
    const config = configManager.getConfig();
    const prefix = config.git.commitMessagePrefix || 'YS: ';
    const commitMessage = message || `${prefix}Auto-commit ${new Date().toISOString().slice(0, 10)}`;

    try {
      await this.add('.');
      const status = await this.status();
      if (status.includes('nothing to commit') || status.includes('working tree clean')) {
        return 'Nothing to commit. Working tree clean.';
      }
      return await this.commit(commitMessage);
    } catch (error) {
      return `Auto-commit failed: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async add(path: string): Promise<string> {
    try {
      return this.exec(`add ${path}`);
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async branch(args?: string[]): Promise<string> {
    try {
      const extraArgs = args ? args.join(' ') : '';
      return this.exec(`branch ${extraArgs}`.trim());
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async checkout(branch: string): Promise<string> {
    try {
      if (branch.includes('/') || branch.includes('.')) {
        return this.exec(`checkout ${branch}`);
      }
      return this.exec(`checkout ${branch}`);
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async checkoutNew(branch: string): Promise<string> {
    try {
      return this.exec(`checkout -b ${branch}`);
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async log(args?: string[]): Promise<string> {
    try {
      const extraArgs = args ? args.join(' ') : '--oneline -20';
      return this.exec(`log ${extraArgs}`);
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async reset(target?: string): Promise<string> {
    try {
      return this.exec(`reset ${target || 'HEAD'}`);
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async stash(args?: string[]): Promise<string> {
    try {
      const extraArgs = args ? args.join(' ') : '';
      return this.exec(`stash ${extraArgs}`.trim());
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async push(args?: string[]): Promise<string> {
    try {
      const extraArgs = args ? args.join(' ') : '';
      return this.exec(`push ${extraArgs}`.trim());
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async pull(args?: string[]): Promise<string> {
    try {
      const extraArgs = args ? args.join(' ') : '';
      return this.exec(`pull ${extraArgs}`.trim());
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async fetch(args?: string[]): Promise<string> {
    try {
      const extraArgs = args ? args.join(' ') : '';
      return this.exec(`fetch ${extraArgs}`.trim());
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async merge(branch: string): Promise<string> {
    try {
      return this.exec(`merge ${branch}`);
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async rebase(branch: string): Promise<string> {
    try {
      return this.exec(`rebase ${branch}`);
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async init(): Promise<string> {
    try {
      return this.exec('init');
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  async clone(url: string, directory?: string): Promise<string> {
    try {
      const dir = directory || url.split('/').pop()?.replace('.git', '') || 'repo';
      return this.exec(`clone ${url} ${dir}`);
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }

  getCurrentBranch(): string {
    try {
      return this.exec('rev-parse --abbrev-ref HEAD');
    } catch {
      return 'unknown';
    }
  }

  getShortHash(): string {
    try {
      return this.exec('rev-parse --short HEAD');
    } catch {
      return 'none';
    }
  }

  getChangedFiles(): string[] {
    try {
      const output = this.exec('diff --name-only');
      return output ? output.split('\n').filter(Boolean) : [];
    } catch {
      return [];
    }
  }

  getStagedFiles(): string[] {
    try {
      const output = this.exec('diff --cached --name-only');
      return output ? output.split('\n').filter(Boolean) : [];
    } catch {
      return [];
    }
  }

  hasChanges(): boolean {
    try {
      const status = this.exec('status --porcelain');
      return status.length > 0;
    } catch {
      return false;
    }
  }

  getConfig(key: string): string {
    try {
      return this.exec(`config ${key}`);
    } catch {
      return '';
    }
  }

  setConfig(key: string, value: string): string {
    try {
      return this.exec(`config ${key} "${value}"`);
    } catch (error) {
      return `Error: ${error instanceof Error ? error.message : String(error)}`;
    }
  }
}

export const gitManager = new GitManager();
