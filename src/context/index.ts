import { getLogger } from '../logger/index.js';
import { configManager } from '../config/index.js';
import { AgentMessage, ContextData, FileNode } from '../types.js';
import { countTokens, truncate } from '../utils/index.js';
import { fileSystem } from '../filesystem/index.js';

const logger = getLogger('context');

export class ContextEngine {
  private maxTokens: number;
  private compressionEnabled: boolean;
  private summarizationEnabled: boolean;
  private relevanceThreshold: number;
  private maxFiles: number;
  private openFiles: string[] = [];
  private recentFiles: string[] = [];
  private projectStructure: FileNode[] = [];

  constructor() {
    const config = configManager.getConfig();
    this.maxTokens = config.context.maxTokens;
    this.compressionEnabled = config.context.compressionEnabled;
    this.summarizationEnabled = config.context.summarizationEnabled;
    this.relevanceThreshold = config.context.relevanceThreshold;
    this.maxFiles = config.context.maxFiles;
  }

  getContext(): ContextData {
    return {
      currentDirectory: fileSystem.getBasePath(),
      openFiles: [...this.openFiles],
      recentFiles: [...this.recentFiles],
      projectStructure: [...this.projectStructure],
      environment: this.getEnvironment(),
      tokenCount: 0,
    };
  }

  private getEnvironment(): Record<string, string> {
    const env: Record<string, string> = {};
    const allowedVars = ['HOME', 'USER', 'SHELL', 'PATH', 'NODE_ENV', 'PWD'];
    for (const key of allowedVars) {
      if (process.env[key]) {
        env[key] = process.env[key]!;
      }
    }
    return env;
  }

  async updateProjectStructure(): Promise<void> {
    try {
      this.projectStructure = fileSystem.listDirectory('.');
    } catch (error) {
      logger.error('Failed to update project structure', error);
    }
  }

  addOpenFile(filePath: string): void {
    const normalized = filePath.replace(/\\/g, '/');
    this.openFiles = this.openFiles.filter((f) => f !== normalized);
    this.openFiles.push(normalized);

    if (this.openFiles.length > this.maxFiles) {
      this.openFiles.shift();
    }

    this.recentFiles = this.recentFiles.filter((f) => f !== normalized);
    this.recentFiles.unshift(normalized);

    if (this.recentFiles.length > 20) {
      this.recentFiles.pop();
    }
  }

  removeOpenFile(filePath: string): void {
    const normalized = filePath.replace(/\\/g, '/');
    this.openFiles = this.openFiles.filter((f) => f !== normalized);
  }

  async compressMessages(messages: AgentMessage[]): Promise<AgentMessage[]> {
    if (!this.compressionEnabled) return messages;

    const totalTokens = messages.reduce((acc, m) => acc + countTokens(m.content), 0);

    if (totalTokens <= this.maxTokens) return messages;

    logger.info(`Compressing ${messages.length} messages (${totalTokens} tokens > ${this.maxTokens} tokens)`);

    const compressed: AgentMessage[] = [];
    let currentTokens = 0;

    const systemMessages = messages.filter((m) => m.role === 'system');
    for (const msg of systemMessages) {
      compressed.push(msg);
      currentTokens += countTokens(msg.content);
    }

    const latestMessages = messages.filter((m) => m.role !== 'system').slice(-20);
    for (const msg of latestMessages) {
      compressed.push(msg);
      currentTokens += countTokens(msg.content);
    }

    const remaining = messages.filter((m) => m.role !== 'system').slice(0, -20);

    if (this.summarizationEnabled && remaining.length > 0) {
      const summary = this.createSummary(remaining);
      const summaryTokens = countTokens(summary);

      if (currentTokens + summaryTokens <= this.maxTokens) {
        compressed.unshift({
          role: 'system',
          content: `[Previous conversation summary]:\n${summary}`,
          timestamp: Date.now(),
        });
        currentTokens += summaryTokens;
      }
    }

    if (currentTokens > this.maxTokens) {
      while (compressed.length > 0 && currentTokens > this.maxTokens) {
        const removed = compressed.shift();
        if (removed && removed.role !== 'system') {
          currentTokens -= countTokens(removed.content);
        }
      }
    }

    logger.info(`Compressed to ${compressed.length} messages (${currentTokens} tokens)`);

    return compressed;
  }

  private createSummary(messages: AgentMessage[]): string {
    const lines: string[] = [];
    for (const msg of messages.slice(-30)) {
      const truncated = truncate(msg.content.replace(/\n/g, ' '), 200);
      lines.push(`[${msg.role}] ${truncated}`);
    }
    return lines.join('\n');
  }

  rankFilesByRelevance(task: string, files: string[]): Array<{ file: string; score: number }> {
    const taskTokens = task.toLowerCase().split(/\s+/);
    const taskSet = new Set(taskTokens);
    const taskWords = [...taskSet].filter((w) => w.length > 3);

    const scored: Array<{ file: string; score: number }> = [];
    const stopWords = new Set(['the', 'and', 'for', 'are', 'but', 'not', 'you', 'all', 'can', 'had', 'her', 'was', 'one', 'our', 'out', 'has', 'have', 'been', 'some', 'them', 'than', 'that', 'this', 'very', 'just', 'with', 'without', 'from', 'about', 'into', 'over', 'also', 'other', 'more', 'then']);

    for (const filePath of files) {
      let score = 0;
      const fileName = filePath.split('/').pop() || '';
      const fileLower = filePath.toLowerCase();
      const nameLower = fileName.toLowerCase();

      for (const word of taskWords) {
        if (stopWords.has(word)) continue;

        if (nameLower.includes(word)) {
          score += 3;
        }
        if (fileLower.includes(word)) {
          score += 1;
        }
      }

      if (fileLower.includes('test')) score -= 0.5;
      if (fileLower.includes('node_modules')) score -= 5;

      scored.push({ file: filePath, score });
    }

    return scored
      .filter((s) => s.score >= this.relevanceThreshold)
      .sort((a, b) => b.score - a.score);
  }

  async getRelevantFiles(task: string, maxFiles = 10): Promise<string[]> {
    try {
      const allFiles = await fileSystem.globSearch('**/*.{ts,js,tsx,jsx,json,md,py,java,cpp,c,h,hpp,rs,go,yaml,yml,toml,xml,html,css,scss,less,sql,sh,bash}');

      const ranked = this.rankFilesByRelevance(task, allFiles);
      return ranked.slice(0, maxFiles).map((r) => r.file);
    } catch (error) {
      logger.error('Failed to get relevant files', error);
      return [];
    }
  }

  getReadableContext(task: string, maxTokens = 4000): string {
    const context = this.getContext();
    const lines: string[] = [];

    lines.push(`Working Directory: ${context.currentDirectory}`);
    lines.push('');

    if (context.projectStructure.length > 0) {
      lines.push('Project Structure:');
      const tree = fileSystem.getDirectoryTree('.', 3);
      lines.push(tree);
      lines.push('');
    }

    if (context.openFiles.length > 0) {
      lines.push(`Open Files: ${context.openFiles.join(', ')}`);
      lines.push('');
    }

    try {
      const relevantFiles = this.rankFilesByRelevance(task, context.recentFiles.length > 0 ? context.recentFiles : ['package.json', 'tsconfig.json']);
      if (relevantFiles.length > 0) {
        lines.push('Relevant Files:');
        for (const { file, score } of relevantFiles.slice(0, 5)) {
          lines.push(`  ${file} (relevance: ${score.toFixed(2)})`);
        }
        lines.push('');
      }
    } catch {
    }

    let result = lines.join('\n');
    if (countTokens(result) > maxTokens) {
      result = truncate(result, maxTokens * 4);
    }

    return result;
  }

  tokenCount(text: string): number {
    return countTokens(text);
  }

  isWithinLimit(text: string): boolean {
    return countTokens(text) <= this.maxTokens;
  }

  getMaxTokens(): number {
    return this.maxTokens;
  }

  setMaxTokens(tokens: number): void {
    this.maxTokens = tokens;
  }
}

export const contextEngine = new ContextEngine();
