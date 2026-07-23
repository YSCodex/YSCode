import { BaseDeclarativeTool, BaseToolInvocation } from '../declarative/ToolTypes';
import type {
  ToolResult,
  ToolInvocation,
  ToolResultDisplay,
  ToolKind,
  ShellExecutionConfig,
  ToolPermission,
  ToolCallConfirmationDetails,
  ToolLocation,
} from '../declarative/ToolTypes';
import { promises as fs } from 'fs';
import * as path from 'path';
import { glob } from 'fast-glob';

interface ReadFileParams {
  filePath: string;
  startLine?: number;
  endLine?: number;
  [key: string]: unknown;
}

interface ReadFileResult extends ToolResult {
  content: string;
  lines?: number;
}

class ReadFileInvocation extends BaseToolInvocation<ReadFileParams, ReadFileResult> {
  getDescription(): string {
    return `Read file: ${this.params.filePath}`;
  }

  async execute(
    _signal: AbortSignal,
    _updateOutput?: (output: ToolResultDisplay) => void
  ): Promise<ReadFileResult> {
    try {
      const content = await fs.readFile(this.params.filePath, 'utf-8');
      const allLines = content.split('\n');
      const start = this.params.startLine ?? 1;
      const end = this.params.endLine ?? allLines.length;
      const slicedLines = allLines.slice(start - 1, end);
      const resultContent = slicedLines.join('\n');

      return {
        success: true,
        llmContent: resultContent,
        returnDisplay: resultContent,
        content: resultContent,
        lines: slicedLines.length,
      };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return {
        success: false,
        llmContent: `Error reading file: ${errorMessage}`,
        returnDisplay: errorMessage,
        content: '',
        error: { message: errorMessage, type: 'FILE_NOT_FOUND' },
      };
    }
  }
}

export class ReadFileTool extends BaseDeclarativeTool<ReadFileParams, ReadFileResult> {
  constructor() {
    super(
      'read_file', 'Read File', 'Reads a file from the filesystem.',
      'file_read' as ToolKind,
      {
        type: 'object',
        properties: {
          filePath: { type: 'string', description: 'The path to the file to read.' },
          startLine: { type: 'integer', description: 'The starting line number (1-indexed).', minimum: 1 },
          endLine: { type: 'integer', description: 'The ending line number (1-indexed).', minimum: 1 },
        },
        required: ['filePath'],
      },
      true, false, false, true, 'read file'
    );
  }

  protected createInvocation(params: ReadFileParams): ToolInvocation<ReadFileParams, ReadFileResult> {
    return new ReadFileInvocation(params);
  }
}

interface WriteFileParams {
  filePath: string;
  content: string;
  createDirs?: boolean;
  [key: string]: unknown;
}

interface WriteFileResult extends ToolResult {
  bytesWritten: number;
}

class WriteFileInvocation extends BaseToolInvocation<WriteFileParams, WriteFileResult> {
  getDescription(): string {
    return `Write file: ${this.params.filePath}`;
  }

  async execute(
    _signal: AbortSignal,
    _updateOutput?: (output: ToolResultDisplay) => void
  ): Promise<WriteFileResult> {
    try {
      if (this.params.createDirs) {
        const dir = path.dirname(this.params.filePath);
        await fs.mkdir(dir, { recursive: true });
      }
      await fs.writeFile(this.params.filePath, this.params.content, 'utf-8');
      return {
        success: true,
        llmContent: `File written successfully: ${this.params.filePath}`,
        returnDisplay: `File written: ${this.params.filePath}`,
        bytesWritten: Buffer.byteLength(this.params.content),
      };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return {
        success: false,
        llmContent: `Error writing file: ${errorMessage}`,
        returnDisplay: errorMessage,
        bytesWritten: 0,
        error: { message: errorMessage, type: 'PERMISSION_DENIED' },
      };
    }
  }
}

export class WriteFileTool extends BaseDeclarativeTool<WriteFileParams, WriteFileResult> {
  constructor() {
    super(
      'write_file', 'Write File', 'Writes content to a file.',
      'file_write' as ToolKind,
      {
        type: 'object',
        properties: {
          filePath: { type: 'string', description: 'The path to the file to write.' },
          content: { type: 'string', description: 'The content to write to the file.' },
          createDirs: { type: 'boolean', description: 'Whether to create parent directories if they do not exist.' },
        },
        required: ['filePath', 'content'],
      },
      false, false, false, true, 'write file'
    );
  }

  protected createInvocation(params: WriteFileParams): ToolInvocation<WriteFileParams, WriteFileResult> {
    return new WriteFileInvocation(params);
  }
}

interface EditFileParams {
  filePath: string;
  oldString: string;
  newString: string;
  replaceAll?: boolean;
  [key: string]: unknown;
}

interface EditFileResult extends ToolResult {
  replaced: boolean;
}

class EditFileInvocation extends BaseToolInvocation<EditFileParams, EditFileResult> {
  getDescription(): string {
    return `Edit file: ${this.params.filePath}`;
  }

  async execute(
    _signal: AbortSignal,
    _updateOutput?: (output: ToolResultDisplay) => void
  ): Promise<EditFileResult> {
    try {
      const content = await fs.readFile(this.params.filePath, 'utf-8');
      let newContent: string;
      if (this.params.replaceAll) {
        newContent = content.split(this.params.oldString).join(this.params.newString);
      } else {
        if (!content.includes(this.params.oldString)) {
          return {
            success: false,
            llmContent: 'Old string not found in file',
            returnDisplay: 'Old string not found',
            replaced: false,
            error: { message: 'Old string not found in file', type: 'FILE_NOT_FOUND' },
          };
        }
        newContent = content.replace(this.params.oldString, this.params.newString);
      }
      await fs.writeFile(this.params.filePath, newContent, 'utf-8');
      return {
        success: true,
        llmContent: `File edited successfully: ${this.params.filePath}`,
        returnDisplay: `File edited: ${this.params.filePath}`,
        replaced: true,
      };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return {
        success: false,
        llmContent: `Error editing file: ${errorMessage}`,
        returnDisplay: errorMessage,
        replaced: false,
        error: { message: errorMessage, type: 'PERMISSION_DENIED' },
      };
    }
  }
}

export class EditFileTool extends BaseDeclarativeTool<EditFileParams, EditFileResult> {
  constructor() {
    super(
      'edit_file', 'Edit File', 'Edits a file by replacing old text with new text.',
      'file_edit' as ToolKind,
      {
        type: 'object',
        properties: {
          filePath: { type: 'string', description: 'The path to the file to edit.' },
          oldString: { type: 'string', description: 'The old string to replace.' },
          newString: { type: 'string', description: 'The new string to replace with.' },
          replaceAll: { type: 'boolean', description: 'Whether to replace all occurrences.' },
        },
        required: ['filePath', 'oldString', 'newString'],
      },
      false, false, false, true, 'edit file'
    );
  }

  protected createInvocation(params: EditFileParams): ToolInvocation<EditFileParams, EditFileResult> {
    return new EditFileInvocation(params);
  }
}

interface DeleteFileParams {
  filePath: string;
  recursive?: boolean;
  [key: string]: unknown;
}

interface DeleteFileResult extends ToolResult {
  deleted: boolean;
}

class DeleteFileInvocation extends BaseToolInvocation<DeleteFileParams, DeleteFileResult> {
  getDescription(): string {
    return `Delete file: ${this.params.filePath}`;
  }

  async execute(
    _signal: AbortSignal,
    _updateOutput?: (output: ToolResultDisplay) => void
  ): Promise<DeleteFileResult> {
    try {
      const stat = await fs.stat(this.params.filePath);
      if (stat.isDirectory() && !this.params.recursive) {
        return {
          success: false,
          llmContent: 'Cannot delete directory without recursive flag',
          returnDisplay: 'Use recursive=true for directories',
          deleted: false,
          error: { message: 'Cannot delete directory without recursive flag', type: 'PERMISSION_DENIED' },
        };
      }
      await fs.rm(this.params.filePath, { recursive: this.params.recursive });
      return {
        success: true,
        llmContent: `File deleted successfully: ${this.params.filePath}`,
        returnDisplay: `File deleted: ${this.params.filePath}`,
        deleted: true,
      };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return {
        success: false,
        llmContent: `Error deleting file: ${errorMessage}`,
        returnDisplay: errorMessage,
        deleted: false,
        error: { message: errorMessage, type: 'FILE_NOT_FOUND' },
      };
    }
  }
}

export class DeleteFileTool extends BaseDeclarativeTool<DeleteFileParams, DeleteFileResult> {
  constructor() {
    super(
      'delete_file', 'Delete File', 'Deletes a file or directory.',
      'file_edit' as ToolKind,
      {
        type: 'object',
        properties: {
          filePath: { type: 'string', description: 'The path to the file to delete.' },
          recursive: { type: 'boolean', description: 'Whether to recursively delete directories.' },
        },
        required: ['filePath'],
      },
      false, false, false, true, 'delete file'
    );
  }

  protected createInvocation(params: DeleteFileParams): ToolInvocation<DeleteFileParams, DeleteFileResult> {
    return new DeleteFileInvocation(params);
  }
}

interface SearchParams {
  query: string;
  path?: string;
  include?: string;
  exclude?: string;
  maxResults?: number;
  [key: string]: unknown;
}

interface SearchResult extends ToolResult {
  matches: Array<{ file: string; line: number; content: string }>;
}

class SearchInvocation extends BaseToolInvocation<SearchParams, SearchResult> {
  getDescription(): string {
    return `Search: ${this.params.query}`;
  }

  async execute(
    _signal: AbortSignal,
    _updateOutput?: (output: ToolResultDisplay) => void
  ): Promise<SearchResult> {
    try {
      const searchPath = this.params.path || process.cwd();
      const matches: Array<{ file: string; line: number; content: string }> = [];
      const files = await glob('**/*', { cwd: searchPath, onlyFiles: true });
      const maxResults = this.params.maxResults || 100;
      let count = 0;

      for (const file of files) {
        if (count >= maxResults) break;
        try {
          const content = await fs.readFile(path.join(searchPath, file), 'utf-8');
          const lines = content.split('\n');
          for (let i = 0; i < lines.length; i++) {
            if (count >= maxResults) break;
            if (lines[i].includes(this.params.query)) {
              matches.push({ file, line: i + 1, content: lines[i] });
              count++;
            }
          }
        } catch { continue; }
      }

      const display = matches.map(m => `${m.file}:${m.line}: ${m.content}`).join('\n');
      return {
        success: true,
        llmContent: display || 'No matches found',
        returnDisplay: display || 'No matches found',
        matches,
      };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return {
        success: false,
        llmContent: `Error searching: ${errorMessage}`,
        returnDisplay: errorMessage,
        matches: [],
        error: { message: errorMessage, type: 'EXECUTION_FAILED' },
      };
    }
  }
}

export class SearchTool extends BaseDeclarativeTool<SearchParams, SearchResult> {
  constructor() {
    super(
      'search', 'Search', 'Searches for text in files.',
      'search' as ToolKind,
      {
        type: 'object',
        properties: {
          query: { type: 'string', description: 'The search query.' },
          path: { type: 'string', description: 'The path to search in.' },
          include: { type: 'string', description: 'File pattern to include.' },
          exclude: { type: 'string', description: 'File pattern to exclude.' },
          maxResults: { type: 'integer', description: 'Maximum number of results.' },
        },
        required: ['query'],
      },
      true, false, false, true, 'search files'
    );
  }

  protected createInvocation(params: SearchParams): ToolInvocation<SearchParams, SearchResult> {
    return new SearchInvocation(params);
  }
}

interface GlobParams {
  pattern: string;
  path?: string;
  maxResults?: number;
  [key: string]: unknown;
}

interface GlobResult extends ToolResult {
  matches: string[];
}

class GlobInvocation extends BaseToolInvocation<GlobParams, GlobResult> {
  getDescription(): string {
    return `Glob: ${this.params.pattern}`;
  }

  async execute(
    _signal: AbortSignal,
    _updateOutput?: (output: ToolResultDisplay) => void
  ): Promise<GlobResult> {
    try {
      const searchPath = this.params.path || process.cwd();
      const matches = await glob(this.params.pattern, { cwd: searchPath, onlyFiles: true });
      const limited = matches.slice(0, this.params.maxResults || 100);
      return {
        success: true,
        llmContent: limited.join('\n') || 'No matches found',
        returnDisplay: limited.join('\n') || 'No matches found',
        matches: limited,
      };
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : String(error);
      return {
        success: false,
        llmContent: `Error globbing: ${errorMessage}`,
        returnDisplay: errorMessage,
        matches: [],
        error: { message: errorMessage, type: 'EXECUTION_FAILED' },
      };
    }
  }
}

export class GlobTool extends BaseDeclarativeTool<GlobParams, GlobResult> {
  constructor() {
    super(
      'glob', 'Glob', 'Finds files by glob pattern.',
      'search' as ToolKind,
      {
        type: 'object',
        properties: {
          pattern: { type: 'string', description: 'The glob pattern to match.' },
          path: { type: 'string', description: 'The path to search in.' },
          maxResults: { type: 'integer', description: 'Maximum number of results.' },
        },
        required: ['pattern'],
      },
      true, false, false, true, 'find files'
    );
  }

  protected createInvocation(params: GlobParams): ToolInvocation<GlobParams, GlobResult> {
    return new GlobInvocation(params);
  }
}