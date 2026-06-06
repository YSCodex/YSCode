import { mkdirSync, existsSync, appendFileSync, writeFileSync, readFileSync, statSync } from 'fs';
import { dirname } from 'path';
import { configManager } from '../config/index.js';
import { LogEntry } from '../types.js';

type LogLevel = 'debug' | 'info' | 'warn' | 'error';

const LOG_LEVELS: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
};

let consoleSuppressed = true;

export function setConsoleSuppressed(v: boolean): void {
  consoleSuppressed = v;
}

export class Logger {
  private level: LogLevel;
  private filePath: string;
  private maxSize: number;
  private maxFiles: number;
  private consoleOutput: boolean;
  private buffer: LogEntry[] = [];
  private bufferSize = 100;
  private writeInterval: ReturnType<typeof setInterval> | null = null;
  private module: string;

  constructor(module: string) {
    this.module = module;
    const config = configManager.getConfig();
    this.level = config.logging.level;
    this.filePath = config.logging.file;
    this.maxSize = config.logging.maxSize;
    this.maxFiles = config.logging.maxFiles;
    this.consoleOutput = config.logging.consoleOutput;

    this.init();
  }

  private init(): void {
    const dir = dirname(this.filePath);
    if (!existsSync(dir)) {
      mkdirSync(dir, { recursive: true });
    }

    this.writeInterval = setInterval(() => {
      this.flush();
    }, 5000);
  }

  private shouldLog(level: LogLevel): boolean {
    return LOG_LEVELS[level] >= LOG_LEVELS[this.level];
  }

  private formatLog(entry: LogEntry): string {
    const date = new Date(entry.timestamp).toISOString();
    const data = entry.data ? ` ${JSON.stringify(entry.data)}` : '';
    return `[${date}] [${entry.level.toUpperCase()}] [${entry.module}] ${entry.message}${data}`;
  }

  private writeToFile(entry: LogEntry): void {
    try {
      this.buffer.push(entry);
      if (this.buffer.length >= this.bufferSize) {
        this.flush();
      }
    } catch {
    }
  }

  private flush(): void {
    if (this.buffer.length === 0) return;

    try {
      this.rotateIfNeeded();

      const lines = this.buffer.map((e) => this.formatLog(e)).join('\n') + '\n';
      appendFileSync(this.filePath, lines, 'utf-8');
      this.buffer = [];
    } catch {
    }
  }

  private rotateIfNeeded(): void {
    try {
      if (!existsSync(this.filePath)) return;
      const size = statSync(this.filePath).size;
      if (size < this.maxSize) return;

      for (let i = this.maxFiles - 1; i > 0; i--) {
        const oldPath = `${this.filePath}.${i}`;
        const newPath = `${this.filePath}.${i + 1}`;
        if (existsSync(oldPath)) {
          try {
            renameSync(oldPath, newPath);
          } catch {
          }
        }
      }

      const firstPath = `${this.filePath}.1`;
      try {
        renameSync(this.filePath, firstPath);
      } catch {
      }

      writeFileSync(this.filePath, '', 'utf-8');
    } catch {
    }
  }

  private log(level: LogLevel, message: string, data?: unknown): void {
    if (!this.shouldLog(level)) return;

    const entry: LogEntry = {
      timestamp: Date.now(),
      level,
      module: this.module,
      message,
      data,
    };

    if (this.consoleOutput && !consoleSuppressed) {
      const formatted = this.formatLog(entry);
      switch (level) {
        case 'error':
          console.error(formatted);
          break;
        case 'warn':
          console.warn(formatted);
          break;
        case 'info':
          console.log(formatted);
          break;
        case 'debug':
          console.debug(formatted);
          break;
      }
    }

    this.writeToFile(entry);
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

  getRecentLogs(count = 50): LogEntry[] {
    try {
      if (!existsSync(this.filePath)) return [];
      const content = readFileSync(this.filePath, 'utf-8');
      const lines = content.trim().split('\n').filter(Boolean);
      const recent = lines.slice(-count);

      return recent.map((line) => {
        const match = line.match(/^\[([^\]]+)\] \[([^\]]+)\] \[([^\]]+)\] (.+)$/);
        if (match) {
          return {
            timestamp: new Date(match[1]).getTime(),
            level: match[2],
            module: match[3],
            message: match[4],
          };
        }
        return {
          timestamp: Date.now(),
          level: 'info',
          module: 'unknown',
          message: line,
        };
      });
    } catch {
      return [];
    }
  }

  setLevel(level: LogLevel): void {
    this.level = level;
  }

  destroy(): void {
    if (this.writeInterval) {
      clearInterval(this.writeInterval);
    }
    this.flush();
  }
}

const loggers = new Map<string, Logger>();

export function getLogger(module: string): Logger {
  if (!loggers.has(module)) {
    loggers.set(module, new Logger(module));
  }
  return loggers.get(module)!;
}

function renameSync(oldPath: string, newPath: string): void {
  try {
    const { renameSync: fsRename } = require('fs');
    fsRename(oldPath, newPath);
  } catch {
  }
}

export function setAllLevels(level: LogLevel): void {
  for (const logger of loggers.values()) {
    logger.setLevel(level);
  }
}

export function destroyAll(): void {
  for (const logger of loggers.values()) {
    logger.destroy();
  }
  loggers.clear();
}
