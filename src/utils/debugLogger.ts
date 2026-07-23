import * as fs from 'fs';
import * as path from 'path';

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

export interface LogEntry {
  timestamp: number;
  level: LogLevel;
  message: string;
  data?: unknown;
}

export interface DebugLoggerOptions {
  enabled?: boolean;
  logLevel?: LogLevel;
  logFile?: string;
  maxEntries?: number;
  consoleOutput?: boolean;
}

const LOG_LEVELS: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
};

export class DebugLogger {
  private static instance: DebugLogger;
  private enabled: boolean;
  private logLevel: LogLevel;
  private logFile?: string;
  private maxEntries: number;
  private consoleOutput: boolean;
  private entries: LogEntry[] = [];
  private buffer: string[] = [];

  constructor(options: DebugLoggerOptions = {}) {
    this.enabled = options.enabled ?? process.env.YS_AGENT_DEBUG === 'true';
    this.logLevel = (options.logLevel ?? process.env.YS_AGENT_LOG_LEVEL ?? 'info') as LogLevel;
    this.logFile = options.logFile ?? process.env.YS_AGENT_LOG_FILE;
    this.maxEntries = options.maxEntries ?? 1000;
    this.consoleOutput = options.consoleOutput ?? false;
  }

  static getInstance(options?: DebugLoggerOptions): DebugLogger {
    if (!DebugLogger.instance) {
      DebugLogger.instance = new DebugLogger(options);
    }
    return DebugLogger.instance;
  }

  private shouldLog(level: LogLevel): boolean {
    if (!this.enabled) return false;
    return LOG_LEVELS[level] >= LOG_LEVELS[this.logLevel];
  }

  log(level: LogLevel, message: string, data?: unknown): void {
    if (!this.shouldLog(level)) return;

    const entry: LogEntry = {
      timestamp: Date.now(),
      level,
      message,
      data,
    };

    this.entries.push(entry);
    if (this.entries.length > this.maxEntries) {
      this.entries.shift();
    }

    if (this.logFile) {
      this.buffer.push(JSON.stringify(entry));
      if (this.buffer.length >= 10) {
        this.flushToFile();
      }
    }

    if (this.consoleOutput) {
      const prefix = `[${level.toUpperCase()}] ${new Date(entry.timestamp).toISOString()}`;
      if (data) {
        console.log(`${prefix} ${message}`, data);
      } else {
        console.log(`${prefix} ${message}`);
      }
    }
  }

  debug(message: string, data?: unknown): void {
    this.log('debug', message, data);
  }

  info(message: string, data?: unknown): void {
    this.log('info', message, data);
  }

  warn(message: string, data?: unknown): void {
    this.log('warn', message, data);
  }

  error(message: string, data?: unknown): void {
    this.log('error', message, data);
  }

  private flushToFile(): void {
    if (!this.logFile || this.buffer.length === 0) return;

    try {
      const dir = path.dirname(this.logFile);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }

      const content = this.buffer.join('\n') + '\n';
      fs.appendFileSync(this.logFile, content, 'utf-8');
      this.buffer = [];
    } catch (error) {
      if (this.consoleOutput) {
        console.error('Failed to write to log file:', error);
      }
    }
  }

  getEntries(): LogEntry[] {
    return [...this.entries];
  }

  getEntriesByLevel(level: LogLevel): LogEntry[] {
    return this.entries.filter((e) => e.level === level);
  }

  clear(): void {
    this.entries = [];
    this.buffer = [];
  }

  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
  }

  setLogLevel(level: LogLevel): void {
    this.logLevel = level;
  }

  setLogFile(logFile: string): void {
    this.logFile = logFile;
  }

  setConsoleOutput(enabled: boolean): void {
    this.consoleOutput = enabled;
  }

  getConfig(): DebugLoggerOptions {
    return {
      enabled: this.enabled,
      logLevel: this.logLevel,
      logFile: this.logFile,
      maxEntries: this.maxEntries,
      consoleOutput: this.consoleOutput,
    };
  }
}

export const debugLogger = DebugLogger.getInstance();