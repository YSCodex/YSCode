import {
  readFileSync, writeFileSync, existsSync, mkdirSync, unlinkSync,
  renameSync, statSync, readdirSync, appendFileSync, copyFileSync,
  readlinkSync, symlinkSync, chmodSync, rmSync
} from 'fs';
import { join, extname, basename, dirname, relative, resolve, isAbsolute } from 'path';
import fg from 'fast-glob';
import ignore from 'ignore';
import { getLogger } from '../logger/index.js';
import { configManager } from '../config/index.js';
import { FileNode, SearchResult } from '../types.js';
import { isBinaryFile, detectEncoding, getFileExtension, hashContent, formatBytes } from '../utils/index.js';

const logger = getLogger('filesystem');

export class FileSystem {
  private basePath: string;
  private cache: Map<string, { content: string; hash: string; timestamp: number }> = new Map();
  private cacheEnabled: boolean;
  private cacheSize: number;
  private maxFileSize: number;
  private ignoreFilter: ignore.Ignore;

  constructor(basePath: string = process.cwd()) {
    this.basePath = resolve(basePath);
    const config = configManager.getConfig();
    this.cacheEnabled = config.fileSystem.cacheEnabled;
    this.cacheSize = config.fileSystem.cacheSize;
    this.maxFileSize = config.fileSystem.maxFileSize;
    this.ignoreFilter = ignore();
    this.loadIgnorePatterns();
  }

  private loadIgnorePatterns(): void {
    const config = configManager.getConfig();
    this.ignoreFilter.add(config.fileSystem.ignorePatterns);

    const gitignorePath = join(this.basePath, '.gitignore');
    if (existsSync(gitignorePath)) {
      try {
        const content = readFileSync(gitignorePath, 'utf-8');
        this.ignoreFilter.add(content);
      } catch (err) {
        logger.warn('Failed to read .gitignore', err);
      }
    }

    const ysIgnorePath = join(this.basePath, '.ysignore');
    if (existsSync(ysIgnorePath)) {
      try {
        const content = readFileSync(ysIgnorePath, 'utf-8');
        this.ignoreFilter.add(content);
      } catch {
      }
    }
  }

  isIgnored(relativePath: string): boolean {
    return this.ignoreFilter.ignores(relativePath);
  }

  private ensureSafePath(targetPath: string): string {
    const resolved = resolve(this.basePath, targetPath);
    const relative_ = relative(this.basePath, resolved);
    if (relative_.startsWith('..') || isAbsolute(relative_)) {
      throw new Error(`Path traversal detected: ${targetPath}`);
    }
    return resolved;
  }

  readFile(filePath: string, encoding?: string): string {
    const fullPath = this.ensureSafePath(filePath);

    if (!existsSync(fullPath)) {
      throw new Error(`File not found: ${filePath}`);
    }

    const stat = statSync(fullPath);
    if (stat.size > this.maxFileSize) {
      throw new Error(`File too large: ${filePath} (${formatBytes(stat.size)} > ${formatBytes(this.maxFileSize)})`);
    }

    if (isBinaryFile(fullPath)) {
      throw new Error(`Cannot read binary file: ${filePath}`);
    }

    if (this.cacheEnabled) {
      const cached = this.cache.get(fullPath);
      const currentHash = hashContent(readFileSync(fullPath, 'utf-8'));
      if (cached && cached.hash === currentHash) {
        return cached.content;
      }
    }

    const enc = encoding || detectEncoding(fullPath);
    const content = readFileSync(fullPath, enc as BufferEncoding);
    const fileHash = hashContent(content);

    if (this.cacheEnabled) {
      this.cache.set(fullPath, { content, hash: fileHash, timestamp: Date.now() });
      this.trimCache();
    }

    return content;
  }

  readFileLines(filePath: string, startLine: number, endLine?: number): string[] {
    const content = this.readFile(filePath);
    const lines = content.split('\n');
    const start = Math.max(0, startLine - 1);
    const end = endLine ? Math.min(lines.length, endLine) : lines.length;
    return lines.slice(start, end);
  }

  writeFile(filePath: string, content: string): void {
    const fullPath = this.ensureSafePath(filePath);
    const dir = dirname(fullPath);

    if (!existsSync(dir)) {
      mkdirSync(dir, { recursive: true });
    }

    writeFileSync(fullPath, content, 'utf-8');

    if (this.cacheEnabled) {
      this.cache.set(fullPath, { content, hash: hashContent(content), timestamp: Date.now() });
      this.trimCache();
    }

    logger.info(`Wrote file: ${filePath}`);
  }

  appendFile(filePath: string, content: string): void {
    const fullPath = this.ensureSafePath(filePath);

    if (!existsSync(fullPath)) {
      this.writeFile(filePath, content);
      return;
    }

    appendFileSync(fullPath, content, 'utf-8');

    if (this.cacheEnabled) {
      this.cache.delete(fullPath);
    }

    logger.info(`Appended to file: ${filePath}`);
  }

  deleteFile(filePath: string): boolean {
    const fullPath = this.ensureSafePath(filePath);

    if (!existsSync(fullPath)) {
      return false;
    }

    const stat = statSync(fullPath);
    if (stat.isDirectory()) {
      rmSync(fullPath, { recursive: true, force: true });
    } else {
      unlinkSync(fullPath);
    }

    this.cache.delete(fullPath);
    logger.info(`Deleted: ${filePath}`);
    return true;
  }

  renameFile(oldPath: string, newPath: string): void {
    const fullOldPath = this.ensureSafePath(oldPath);
    const fullNewPath = this.ensureSafePath(newPath);
    const dir = dirname(fullNewPath);

    if (!existsSync(dir)) {
      mkdirSync(dir, { recursive: true });
    }

    renameSync(fullOldPath, fullNewPath);
    this.cache.delete(fullOldPath);
    this.cache.delete(fullNewPath);
    logger.info(`Renamed: ${oldPath} -> ${newPath}`);
  }

  copyFile(source: string, destination: string): void {
    const fullSource = this.ensureSafePath(source);
    const fullDest = this.ensureSafePath(destination);
    const dir = dirname(fullDest);

    if (!existsSync(dir)) {
      mkdirSync(dir, { recursive: true });
    }

    copyFileSync(fullSource, fullDest);
    this.cache.delete(fullDest);
    logger.info(`Copied: ${source} -> ${destination}`);
  }

  createDirectory(dirPath: string): void {
    const fullPath = this.ensureSafePath(dirPath);

    if (existsSync(fullPath)) {
      return;
    }

    mkdirSync(fullPath, { recursive: true });
    logger.info(`Created directory: ${dirPath}`);
  }

  exists(filePath: string): boolean {
    try {
      const fullPath = this.ensureSafePath(filePath);
      return existsSync(fullPath);
    } catch {
      return false;
    }
  }

  getFileInfo(filePath: string): {
    name: string;
    path: string;
    size: number;
    extension: string;
    modifiedAt: Date;
    createdAt: Date;
    isDirectory: boolean;
    isSymlink: boolean;
  } | null {
    try {
      const fullPath = this.ensureSafePath(filePath);
      if (!existsSync(fullPath)) return null;

      const stat = statSync(fullPath);
      return {
        name: basename(fullPath),
        path: fullPath,
        size: stat.size,
        extension: extname(fullPath),
        modifiedAt: stat.mtime,
        createdAt: stat.birthtime,
        isDirectory: stat.isDirectory(),
        isSymlink: stat.isSymbolicLink(),
      };
    } catch {
      return null;
    }
  }

  listDirectory(dirPath: string = '.'): FileNode[] {
    const fullPath = this.ensureSafePath(dirPath);

    if (!existsSync(fullPath)) {
      throw new Error(`Directory not found: ${dirPath}`);
    }

    return this.buildFileTree(fullPath, 0, 1);
  }

  private buildFileTree(dirPath: string, depth: number, maxDepth: number): FileNode[] {
    if (depth > maxDepth) return [];

    const nodes: FileNode[] = [];
    try {
      const entries = readdirSync(dirPath, { withFileTypes: true });

      for (const entry of entries) {
        const fullPath = join(dirPath, entry.name);
        const relativePath = relative(this.basePath, fullPath);

        if (this.isIgnored(relativePath)) continue;

        if (entry.isDirectory()) {
          const children = this.buildFileTree(fullPath, depth + 1, maxDepth);
          nodes.push({
            name: entry.name,
            path: relativePath,
            type: 'directory',
            children,
          });
        } else {
          try {
            const stat = statSync(fullPath);
            nodes.push({
              name: entry.name,
              path: relativePath,
              type: 'file',
              size: stat.size,
              extension: extname(entry.name),
            });
          } catch {
            nodes.push({
              name: entry.name,
              path: relativePath,
              type: 'file',
            });
          }
        }
      }

      nodes.sort((a, b) => {
        if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
        return a.name.localeCompare(b.name);
      });
    } catch (err) {
      logger.error(`Failed to list directory: ${dirPath}`, err);
    }

    return nodes;
  }

  async globSearch(pattern: string, cwd?: string): Promise<string[]> {
    const searchPath = cwd || this.basePath;
    const results = await fg(pattern, {
      cwd: searchPath,
      dot: true,
      ignore: configManager.getConfig().fileSystem.ignorePatterns,
      absolute: false,
    });

    return results.map((r) => r.split('/').join('/'));
  }

  async searchContent(
    query: string,
    pattern?: string,
    maxResults = 50
  ): Promise<SearchResult[]> {
    const results: SearchResult[] = [];
    const files = await this.globSearch(pattern || '**/*.{ts,js,tsx,jsx,json,md,py,java,cpp,c,h,hpp,rs,go,yaml,yml,toml,xml,html,css,scss,less,sql,sh,bash}');

    const regex = new RegExp(query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i');

    for (const file of files.slice(0, 500)) {
      try {
        if (this.isIgnored(file)) continue;
        const fullPath = join(this.basePath, file);
        if (!existsSync(fullPath) || isBinaryFile(fullPath)) continue;

        const content = readFileSync(fullPath, 'utf-8');
        const lines = content.split('\n');

        for (let i = 0; i < lines.length; i++) {
          const match = regex.exec(lines[i]);
          if (match) {
            results.push({
              file,
              line: i + 1,
              column: match.index + 1,
              content: lines[i].trim(),
              match: match[0],
            });

            if (results.length >= maxResults) break;
          }
        }
      } catch {
        continue;
      }

      if (results.length >= maxResults) break;
    }

    return results;
  }

  getDirectoryTree(dirPath: string = '.', maxDepth = 5): string {
    const fullPath = this.ensureSafePath(dirPath);
    if (!existsSync(fullPath)) {
      throw new Error(`Directory not found: ${dirPath}`);
    }

    return this.buildTreeString(fullPath, '', 0, maxDepth);
  }

  private buildTreeString(dirPath: string, prefix: string, depth: number, maxDepth: number): string {
    if (depth > maxDepth) return '';

    let result = '';
    const entries = readdirSync(dirPath, { withFileTypes: true })
      .filter((e) => !e.name.startsWith('.') || e.name === '.gitignore')
      .sort((a, b) => {
        if (a.isDirectory() !== b.isDirectory()) return a.isDirectory() ? -1 : 1;
        return a.name.localeCompare(b.name);
      });

    for (let i = 0; i < entries.length; i++) {
      const entry = entries[i];
      const fullPath = join(dirPath, entry.name);
      const relativePath = relative(this.basePath, fullPath);
      const isLast = i === entries.length - 1;
      const connector = isLast ? '└── ' : '├── ';

      if (this.isIgnored(relativePath)) continue;

      if (entry.isDirectory()) {
        result += `${prefix}${connector}${entry.name}/\n`;
        result += this.buildTreeString(
          fullPath,
          `${prefix}${isLast ? '    ' : '│   '}`,
          depth + 1,
          maxDepth
        );
      } else {
        const info = this.getFileInfo(relativePath);
        const size = info ? ` (${formatBytes(info.size)})` : '';
        result += `${prefix}${connector}${entry.name}${size}\n`;
      }
    }

    return result;
  }

  private trimCache(): void {
    if (this.cache.size > this.cacheSize) {
      const entries = [...this.cache.entries()]
        .sort((a, b) => a[1].timestamp - b[1].timestamp);
      const toDelete = entries.slice(0, entries.length - this.cacheSize);
      for (const [key] of toDelete) {
        this.cache.delete(key);
      }
    }
  }

  getBasePath(): string {
    return this.basePath;
  }

  setBasePath(newPath: string): void {
    this.basePath = resolve(newPath);
    this.loadIgnorePatterns();
    this.cache.clear();
  }

  getTotalSize(dirPath: string = '.'): number {
    const fullPath = this.ensureSafePath(dirPath);
    if (!existsSync(fullPath)) return 0;

    let totalSize = 0;
    try {
      const entries = readdirSync(fullPath, { withFileTypes: true, recursive: true });
      for (const entry of entries) {
        const entryPath = join(entry.parentPath, entry.name);
        const relativePath = relative(this.basePath, entryPath);
        if (!this.isIgnored(relativePath) && entry.isFile()) {
          try {
            totalSize += statSync(entryPath).size;
          } catch {
          }
        }
      }
    } catch {
      const walk = (dir: string): void => {
        try {
          const entries_ = readdirSync(dir, { withFileTypes: true });
          for (const entry of entries_) {
            const full = join(dir, entry.name);
            const relPath = relative(this.basePath, full);
            if (this.isIgnored(relPath)) continue;
            if (entry.isDirectory()) walk(full);
            else if (entry.isFile()) totalSize += statSync(full).size;
          }
        } catch {
        }
      };
      walk(fullPath);
    }

    return totalSize;
  }

  getFileCount(dirPath: string = '.'): number {
    const fullPath = this.ensureSafePath(dirPath);
    if (!existsSync(fullPath)) return 0;

    let count = 0;
    const walk = (dir: string): void => {
      try {
        const entries = readdirSync(dir, { withFileTypes: true });
        for (const entry of entries) {
          const full = join(dir, entry.name);
          const relPath = relative(this.basePath, full);
          if (this.isIgnored(relPath)) continue;
          if (entry.isFile()) count++;
          else if (entry.isDirectory()) walk(full);
        }
      } catch {
      }
    };
    walk(fullPath);

    return count;
  }

  async computeFileHash(filePath: string): Promise<string> {
    const content = this.readFile(filePath);
    return hashContent(content);
  }

  invalidateCache(filePath?: string): void {
    if (filePath) {
      const fullPath = this.ensureSafePath(filePath);
      this.cache.delete(fullPath);
    } else {
      this.cache.clear();
    }
  }

  async searchFiles(
    query: string,
    options?: { maxResults?: number; pattern?: string; caseSensitive?: boolean }
  ): Promise<SearchResult[]> {
    const maxResults = options?.maxResults || 50;
    const caseSensitive = options?.caseSensitive || false;
    const pattern = options?.pattern || '**/*';
    const results: SearchResult[] = [];

    const files = await this.globSearch(pattern);

    const regex = caseSensitive
      ? new RegExp(escapeRegex(query))
      : new RegExp(escapeRegex(query), 'i');

    for (const file of files.slice(0, 1000)) {
      try {
        if (this.isIgnored(file)) continue;
        const fullPath = join(this.basePath, file);
        if (!existsSync(fullPath) || isBinaryFile(fullPath)) continue;

        const content = readFileSync(fullPath, 'utf-8');
        const lines = content.split('\n');

        for (let i = 0; i < lines.length; i++) {
          const match = regex.exec(lines[i]);
          if (match) {
            results.push({
              file,
              line: i + 1,
              column: match.index + 1,
              content: lines[i].trim().substring(0, 200),
              match: match[0],
            });
            if (results.length >= maxResults) break;
          }
        }
      } catch {
        continue;
      }
      if (results.length >= maxResults) break;
    }

    return results;
  }
}

function escapeRegex(str: string): string {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export const fileSystem = new FileSystem();
