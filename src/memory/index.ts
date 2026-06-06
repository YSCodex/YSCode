import { configManager } from '../config/index.js';
import { getLogger } from '../logger/index.js';
import { AgentMessage, Summary, MemoryData, TaskRecord } from '../types.js';
import { generateId, countTokens } from '../utils/index.js';

const logger = getLogger('memory');

interface MemoryEntry {
  key: string;
  value: string;
  type: string;
  timestamp: number;
}

export class MemoryManager {
  private shortTerm: AgentMessage[] = [];
  private maxShortTerm: number;
  private longTermEnabled: boolean;
  private inMemory: Map<string, MemoryEntry> = new Map();
  private summaries: Summary[] = [];
  private preferences: Record<string, unknown> = {};
  private previousTasks: TaskRecord[] = [];

  constructor() {
    const config = configManager.getConfig();
    this.maxShortTerm = config.memory.shortTermSize;
    this.longTermEnabled = config.memory.longTermEnabled;
  }

  addMessage(message: AgentMessage): void {
    this.shortTerm.push(message);
    if (this.shortTerm.length > this.maxShortTerm) {
      this.shortTerm.shift();
    }
  }

  getMessages(count?: number): AgentMessage[] {
    if (count) {
      return this.shortTerm.slice(-count);
    }
    return [...this.shortTerm];
  }

  clearMessages(): void {
    this.shortTerm = [];
  }

  getContext(): MemoryData {
    return {
      shortTerm: {
        messages: [...this.shortTerm],
        maxSize: this.maxShortTerm,
      },
      longTerm: {
        summaries: [...this.summaries],
        preferences: { ...this.preferences },
        previousTasks: [...this.previousTasks],
      },
    };
  }

  store(key: string, value: string, type: 'project' | 'conversation' | 'task' | 'preference' = 'conversation'): void {
    const entry: MemoryEntry = { key, value, type, timestamp: Date.now() };
    this.inMemory.set(key, entry);

    if (type === 'preference') {
      this.preferences[key] = value;
    }

    logger.debug(`Stored memory: ${key} (${type})`);
  }

  retrieve(key: string): { value: string; type: string } | null {
    const entry = this.inMemory.get(key);
    if (entry) {
      return { value: entry.value, type: entry.type };
    }
    return null;
  }

  search(query: string): Array<{ key: string; value: string; type: string; timestamp: number; relevance: number }> {
    const results: Array<{ key: string; value: string; type: string; timestamp: number; relevance: number }> = [];
    const lowerQuery = query.toLowerCase();

    for (const [key, entry] of this.inMemory) {
      let relevance = 0;
      if (key.toLowerCase().includes(lowerQuery)) relevance += 2;
      if (entry.value.toLowerCase().includes(lowerQuery)) relevance += 1;
      if (relevance > 0) {
        results.push({ ...entry, relevance });
      }
    }

    return results.sort((a, b) => b.relevance - a.relevance);
  }

  delete(key: string): void {
    this.inMemory.delete(key);
  }

  clear(): void {
    this.shortTerm = [];
    this.inMemory.clear();
    this.summaries = [];
    this.previousTasks = [];
    this.preferences = {};
  }

  list(type?: string): MemoryEntry[] {
    if (type) {
      return [...this.inMemory.values()].filter((e) => e.type === type);
    }
    return [...this.inMemory.values()];
  }

  addSummary(content: string, type: 'project' | 'conversation' | 'task', metadata?: Record<string, unknown>): void {
    const summary: Summary = {
      id: generateId(),
      content,
      type,
      timestamp: Date.now(),
      metadata,
    };

    this.summaries.unshift(summary);
    if (this.summaries.length > 100) {
      this.summaries.pop();
    }
  }

  getSummaries(type?: string, limit = 10): Summary[] {
    let filtered = this.summaries;
    if (type) {
      filtered = filtered.filter((s) => s.type === type);
    }
    return filtered.slice(0, limit);
  }

  getSessionSummary(): string {
    const msgCount = this.shortTerm.length;
    const totalTokens = this.shortTerm.reduce((acc, m) => acc + countTokens(m.content), 0);
    const taskCount = this.previousTasks.length;
    const memoryCount = this.inMemory.size;

    return [
      'Session Statistics:',
      `- Messages: ${msgCount}`,
      `- Total tokens: ${totalTokens}`,
      `- Tasks completed: ${taskCount}`,
      `- Memory entries: ${memoryCount}`,
      `- Summaries: ${this.summaries.length}`,
    ].join('\n');
  }

  addTask(description: string, status: 'completed' | 'failed' | 'cancelled', duration: number, result?: string): void {
    const task: TaskRecord = {
      id: generateId(),
      description,
      status,
      timestamp: Date.now(),
      duration,
      result,
    };

    this.previousTasks.unshift(task);
    if (this.previousTasks.length > 100) {
      this.previousTasks.pop();
    }
  }

  getRecentTasks(count = 10): TaskRecord[] {
    return this.previousTasks.slice(0, count);
  }

  close(): void {
  }
}

export const memoryManager = new MemoryManager();
