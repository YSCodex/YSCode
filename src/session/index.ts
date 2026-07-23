import { EventEmitter } from 'events';
import * as fs from 'fs';
import * as path from 'path';
import crypto from 'crypto';


export interface SessionMessage {
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  timestamp: number;
  toolCallId?: string;
  toolName?: string;
}

export interface SessionMetadata {
  createdAt: number;
  updatedAt: number;
  lastAccessed: number;
  messageCount: number;
  totalTokens?: number;
  title?: string;
  tags?: string[];
}

export interface Session {
  id: string;
  messages: SessionMessage[];
  metadata: SessionMetadata;
  context: Record<string, unknown>;
}

export interface SessionManagerOptions {
  sessionDir?: string;
  maxSessions?: number;
  maxMessagesPerSession?: number;
  enablePersistence?: boolean;
  autoCompactThreshold?: number;
}

export class SessionManager extends EventEmitter {
  private static instance: SessionManager;
  private sessions: Map<string, Session> = new Map();
  private currentSessionId: string | null = null;
  private sessionDir: string;
  private maxSessions: number;
  private maxMessagesPerSession: number;
  private enablePersistence: boolean;
  private autoCompactThreshold: number;

  constructor(options: SessionManagerOptions = {}) {
    super();
    this.sessionDir = options.sessionDir || path.join(process.cwd(), '.ys-agent', 'sessions');
    this.maxSessions = options.maxSessions ?? 50;
    this.maxMessagesPerSession = options.maxMessagesPerSession ?? 1000;
    this.enablePersistence = options.enablePersistence ?? true;
    this.autoCompactThreshold = options.autoCompactThreshold ?? 0.8;

    if (this.enablePersistence) {
      this.ensureSessionDir();
      this.loadSessions();
    }
  }

  static getInstance(options?: SessionManagerOptions): SessionManager {
    if (!SessionManager.instance) {
      SessionManager.instance = new SessionManager(options);
    }
    return SessionManager.instance;
  }

  private ensureSessionDir(): void {
    if (!fs.existsSync(this.sessionDir)) {
      fs.mkdirSync(this.sessionDir, { recursive: true });
    }
  }

  private getSessionPath(sessionId: string): string {
    return path.join(this.sessionDir, `${sessionId}.json`);
  }

  private loadSessions(): void {
    try {
      if (!fs.existsSync(this.sessionDir)) return;

      const files = fs.readdirSync(this.sessionDir);
      for (const file of files) {
        if (!file.endsWith('.json')) continue;

        try {
          const content = fs.readFileSync(path.join(this.sessionDir, file), 'utf-8');
          const session: Session = JSON.parse(content);
          this.sessions.set(session.id, session);
        } catch (error) {
          console.error(`Failed to load session from ${file}:`, error);
        }
      }

      this.emit('sessions:loaded', this.sessions.size);
    } catch (error) {
      console.error('Failed to load sessions:', error);
    }
  }

  private saveSession(session: Session): void {
    if (!this.enablePersistence) return;

    try {
      this.ensureSessionDir();
      const sessionPath = this.getSessionPath(session.id);
      fs.writeFileSync(sessionPath, JSON.stringify(session, null, 2), 'utf-8');
    } catch (error) {
      console.error(`Failed to save session ${session.id}:`, error);
    }
  }

  private deleteSessionFile(sessionId: string): void {
    try {
      const sessionPath = this.getSessionPath(sessionId);
      if (fs.existsSync(sessionPath)) {
        fs.unlinkSync(sessionPath);
      }
    } catch (error) {
      console.error(`Failed to delete session file ${sessionId}:`, error);
    }
  }

  createSession(options?: { title?: string; tags?: string[]; context?: Record<string, unknown> }): Session {
    const id = crypto.randomUUID();
    const now = Date.now();

    const session: Session = {
      id,
      messages: [],
      metadata: {
        createdAt: now,
        updatedAt: now,
        lastAccessed: now,
        messageCount: 0,
        title: options?.title,
        tags: options?.tags,
      },
      context: options?.context || {},
    };

    this.sessions.set(id, session);
    this.currentSessionId = id;

    this.emit('session:created', session);

    if (this.sessions.size > this.maxSessions) {
      this.cleanupOldSessions();
    }

    return session;
  }

  getSession(sessionId: string): Session | undefined {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.metadata.lastAccessed = Date.now();
      this.saveSession(session);
    }
    return session;
  }

  getCurrentSession(): Session | undefined {
    if (!this.currentSessionId) return undefined;
    return this.sessions.get(this.currentSessionId);
  }

  setCurrentSession(sessionId: string): boolean {
    if (this.sessions.has(sessionId)) {
      this.currentSessionId = sessionId;
      const session = this.sessions.get(sessionId)!;
      session.metadata.lastAccessed = Date.now();
      this.saveSession(session);
      this.emit('session:activated', session);
      return true;
    }
    return false;
  }

  deleteSession(sessionId: string): boolean {
    if (this.sessions.has(sessionId)) {
      this.sessions.delete(sessionId);
      this.deleteSessionFile(sessionId);

      if (this.currentSessionId === sessionId) {
        this.currentSessionId = null;
      }

      this.emit('session:deleted', sessionId);
      return true;
    }
    return false;
  }

  addMessage(sessionId: string, message: Omit<SessionMessage, 'timestamp'>): SessionMessage {
    const session = this.sessions.get(sessionId);
    if (!session) {
      throw new Error(`Session not found: ${sessionId}`);
    }

    const fullMessage: SessionMessage = {
      ...message,
      timestamp: Date.now(),
    };

    session.messages.push(fullMessage);
    session.metadata.messageCount++;
    session.metadata.updatedAt = Date.now();

    if (session.messages.length > this.maxMessagesPerSession) {
      session.messages = session.messages.slice(-this.maxMessagesPerSession);
    }

    this.saveSession(session);
    this.emit('message:added', { sessionId, message: fullMessage });

    return fullMessage;
  }

  getMessages(sessionId: string, options?: { limit?: number; offset?: number }): SessionMessage[] {
    const session = this.sessions.get(sessionId);
    if (!session) return [];

    let messages = session.messages;
    if (options?.offset) {
      messages = messages.slice(options.offset);
    }
    if (options?.limit) {
      messages = messages.slice(-options.limit);
    }

    return messages;
  }

  updateMetadata(sessionId: string, metadata: Partial<SessionMetadata>): boolean {
    const session = this.sessions.get(sessionId);
    if (!session) return false;

    session.metadata = { ...session.metadata, ...metadata };
    session.metadata.updatedAt = Date.now();
    this.saveSession(session);
    this.emit('session:updated', { sessionId, metadata });
    return true;
  }

  updateContext(sessionId: string, context: Record<string, unknown>): boolean {
    const session = this.sessions.get(sessionId);
    if (!session) return false;

    session.context = { ...session.context, ...context };
    this.saveSession(session);
    this.emit('session:context_updated', { sessionId, context });
    return true;
  }

  listSessions(options?: { sortBy?: 'createdAt' | 'updatedAt' | 'lastAccessed'; limit?: number }): Session[] {
    let sessions = Array.from(this.sessions.values());

    const sortBy = options?.sortBy ?? 'lastAccessed';
    sessions.sort((a, b) => b.metadata[sortBy] - a.metadata[sortBy]);

    if (options?.limit) {
      sessions = sessions.slice(0, options.limit);
    }

    return sessions;
  }

  async compactSession(sessionId: string): Promise<boolean> {
    const session = this.sessions.get(sessionId);
    if (!session) return false;

    if (session.messages.length <= 10) {
      return false;
    }

    const compactedMessages = this.performCompaction(session.messages);
    session.messages = compactedMessages;
    session.metadata.messageCount = compactedMessages.length;
    session.metadata.updatedAt = Date.now();

    this.saveSession(session);
    this.emit('session:compacted', { sessionId, originalCount: session.messages.length });
    return true;
  }

  private performCompaction(messages: SessionMessage[]): SessionMessage[] {
    if (messages.length <= 10) return messages;

    const head = messages.slice(0, 5);
    const tail = messages.slice(-5);

    return [...head, ...tail];
  }

  private cleanupOldSessions(): void {
    const sessions = this.listSessions({ sortBy: 'lastAccessed' });
    const toDelete = sessions.slice(this.maxSessions);

    for (const session of toDelete) {
      this.deleteSession(session.id);
    }

    this.emit('sessions:cleanup', toDelete.length);
  }

  async checkAndCompact(sessionId: string): Promise<boolean> {
    const session = this.sessions.get(sessionId);
    if (!session) return false;

    const utilization = session.messages.length / this.maxMessagesPerSession;
    if (utilization >= this.autoCompactThreshold) {
      return this.compactSession(sessionId);
    }

    return false;
  }

  exportSession(sessionId: string, filePath: string): boolean {
    const session = this.sessions.get(sessionId);
    if (!session) return false;

    try {
      fs.writeFileSync(filePath, JSON.stringify(session, null, 2), 'utf-8');
      this.emit('session:exported', { sessionId, filePath });
      return true;
    } catch (error) {
      console.error(`Failed to export session ${sessionId}:`, error);
      return false;
    }
  }

  importSession(filePath: string): Session | null {
    try {
      const content = fs.readFileSync(filePath, 'utf-8');
      const session: Session = JSON.parse(content);

      if (this.sessions.has(session.id)) {
        session.id = crypto.randomUUID();
      }

      this.sessions.set(session.id, session);
      this.saveSession(session);
      this.emit('session:imported', session);
      return session;
    } catch (error) {
      console.error('Failed to import session:', error);
      return null;
    }
  }

  clear(): void {
    this.sessions.clear();
    this.currentSessionId = null;
    this.emit('sessions:cleared');
  }

  getStats(): {
    totalSessions: number;
    currentSessionId: string | null;
    totalMessages: number;
  } {
    let totalMessages = 0;
    for (const session of this.sessions.values()) {
      totalMessages += session.messages.length;
    }

    return {
      totalSessions: this.sessions.size,
      currentSessionId: this.currentSessionId,
      totalMessages,
    };
  }
}

export const sessionManager = SessionManager.getInstance();