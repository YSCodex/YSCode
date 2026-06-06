import crypto from 'crypto';
import { readFileSync, existsSync } from 'fs';
import { extname, basename, relative, sep } from 'path';

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function generateId(length = 16): string {
  const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
  let result = '';
  const array = new Uint8Array(length);
  crypto.getRandomValues(array);
  for (let i = 0; i < length; i++) {
    result += chars[array[i] % chars.length];
  }
  return result;
}

export function hashContent(content: string): string {
  return crypto.createHash('sha256').update(content).digest('hex').slice(0, 12);
}

export function truncate(str: string, maxLength: number): string {
  if (str.length <= maxLength) return str;
  return str.slice(0, maxLength - 3) + '...';
}

export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const k = 1024;
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${units[i]}`;
}

export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;
  const minutes = Math.floor(ms / 60000);
  const seconds = ((ms % 60000) / 1000).toFixed(0);
  return `${minutes}m ${seconds}s`;
}

export function formatDate(date: Date | number): string {
  const d = typeof date === 'number' ? new Date(date) : date;
  return d.toISOString().replace('T', ' ').slice(0, 19);
}

export function isValidUrl(str: string): boolean {
  try {
    new URL(str);
    return true;
  } catch {
    return false;
  }
}

export function sanitizePath(input: string): string {
  return input.replace(/\.\.\//g, '').replace(/\.\.\\/g, '');
}

export function isBinaryFile(filePath: string): boolean {
  try {
    if (!existsSync(filePath)) return false;
    const buffer = readFileSync(filePath);
    const sample = buffer.slice(0, 8192);
    for (let i = 0; i < sample.length; i++) {
      if (sample[i] === 0) return true;
    }
    return false;
  } catch {
    return false;
  }
}

export function detectEncoding(filePath: string): string {
  try {
    if (!existsSync(filePath)) return 'utf-8';
    const buffer = readFileSync(filePath);
    const sample = buffer.slice(0, 4096);

    if (sample.length >= 3 && sample[0] === 0xEF && sample[1] === 0xBB && sample[2] === 0xBF) {
      return 'utf-8';
    }
    if (sample.length >= 2) {
      if (sample[0] === 0xFF && sample[1] === 0xFE) return 'utf-16le';
      if (sample[0] === 0xFE && sample[1] === 0xFF) return 'utf-16be';
    }

    return 'utf-8';
  } catch {
    return 'utf-8';
  }
}

export function getLanguageFromExtension(ext: string): string {
  const langMap: Record<string, string> = {
    '.ts': 'typescript',
    '.tsx': 'typescriptreact',
    '.js': 'javascript',
    '.jsx': 'javascriptreact',
    '.json': 'json',
    '.md': 'markdown',
    '.py': 'python',
    '.java': 'java',
    '.cpp': 'cpp',
    '.c': 'c',
    '.h': 'c',
    '.hpp': 'cpp',
    '.rs': 'rust',
    '.go': 'go',
    '.rb': 'ruby',
    '.php': 'php',
    '.swift': 'swift',
    '.kt': 'kotlin',
    '.dart': 'dart',
    '.yaml': 'yaml',
    '.yml': 'yaml',
    '.toml': 'toml',
    '.xml': 'xml',
    '.html': 'html',
    '.css': 'css',
    '.scss': 'scss',
    '.less': 'less',
    '.sql': 'sql',
    '.sh': 'shell',
    '.bash': 'shell',
    '.zsh': 'shell',
    '.env': 'dotenv',
    '.gitignore': 'gitignore',
    '.dockerfile': 'dockerfile',
    '.vue': 'vue',
    '.svelte': 'svelte',
  };
  return langMap[ext.toLowerCase()] || 'plaintext';
}

export function escapeRegExp(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function debounce<T extends (...args: unknown[]) => unknown>(
  fn: T,
  delay: number
): (...args: Parameters<T>) => void {
  let timeout: ReturnType<typeof setTimeout>;
  return (...args: Parameters<T>) => {
    clearTimeout(timeout);
    timeout = setTimeout(() => fn(...args), delay);
  };
}

export function throttle<T extends (...args: unknown[]) => unknown>(
  fn: T,
  limit: number
): (...args: Parameters<T>) => void {
  let inThrottle = false;
  return (...args: Parameters<T>) => {
    if (!inThrottle) {
      fn(...args);
      inThrottle = true;
      setTimeout(() => {
        inThrottle = false;
      }, limit);
    }
  };
}

export function isDangerousCommand(command: string): boolean {
  const dangerous = [
    /^rm\s+-rf\s+\/\s*$/,
    /^sudo\s+/,
    /^shutdown/,
    /^reboot/,
    /^init\s+/,
    /^dd\s+/,
    /^mkfs\s+/,
    /^fdisk\s+/,
    /^mkfs\.\w+/,
    /^:\(\)\s*\{/,
    /^>\s*\/dev\/sda/,
    /^chmod\s+-R\s+777\s+\//,
    /^chown\s+-R/,
    /^wget\s+.*\||curl\s+.*\|/,
    /\/dev\/null;/,
  ];

  for (const pattern of dangerous) {
    if (pattern.test(command.trim())) {
      return true;
    }
  }

  if (command.includes('|') && (command.includes('sh') || command.includes('bash'))) {
    const parts = command.split('|');
    for (const part of parts) {
      if (part.includes('curl') || part.includes('wget')) {
        if (part.includes('sh') || part.includes('bash')) {
          return true;
        }
      }
    }
  }

  return false;
}

export function countTokens(text: string): number {
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

export function splitIntoChunks(text: string, maxChunkSize: number): string[] {
  const chunks: string[] = [];
  let current = '';
  const lines = text.split('\n');

  for (const line of lines) {
    if (current.length + line.length + 1 > maxChunkSize && current.length > 0) {
      chunks.push(current);
      current = line;
    } else {
      current += (current ? '\n' : '') + line;
    }
  }

  if (current) {
    chunks.push(current);
  }

  return chunks;
}

export function extractCodeBlocks(text: string): { language: string; code: string }[] {
  const blocks: { language: string; code: string }[] = [];
  const regex = /```(\w+)?\n([\s\S]*?)```/g;
  let match;

  while ((match = regex.exec(text)) !== null) {
    blocks.push({
      language: match[1] || 'text',
      code: match[2].trim(),
    });
  }

  return blocks;
}

export function relativePath(from: string, to: string): string {
  return relative(from, to).split(sep).join('/');
}

export function getFileExtension(filePath: string): string {
  return extname(filePath).toLowerCase();
}

export function getFileName(filePath: string): string {
  return basename(filePath);
}
