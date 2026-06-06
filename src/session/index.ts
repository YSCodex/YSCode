import { existsSync, mkdirSync, readFileSync, writeFileSync, readdirSync, unlinkSync } from 'fs';
import { join, dirname } from 'path';
import { getLogger } from '../logger/index.js';
import { configManager } from '../config/index.js';
import { SessionData, AgentState } from '../types.js';
import { generateId, formatDate } from '../utils/index.js';

const logger = getLogger('session');

export class SessionManager {
  private sessionDir: string;
  private maxSessions: number;
  private autoSave: boolean;
  private saveInterval: number;
  private currentSession: SessionData | null = null;
  private sessions: Map<string, SessionData> = new Map();
  private saveTimer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    const config = configManager.getConfig();
    this.sessionDir = config.session.sessionDir;
    this.maxSessions = config.session.maxSessions;
    this.autoSave = config.session.autoSave;
    this.saveInterval = config.session.saveInterval;

    this.init();
  }

  private init(): void {
    if (!existsSync(this.sessionDir)) {
      mkdirSync(this.sessionDir, { recursive: true });
    }

    this.loadSessions();

    if (this.autoSave) {
      this.saveTimer = setInterval(() => {
        this.saveCurrentSession();
      }, this.saveInterval);
    }

    logger.info(`Session manager initialized (${this.sessions.size} sessions)`);
  }

  private loadSessions(): void {
    try {
      const files = readdirSync(this.sessionDir)
        .filter((f) => f.endsWith('.json'))
        .sort()
        .reverse();

      for (const file of files.slice(0, this.maxSessions)) {
        try {
          const content = readFileSync(join(this.sessionDir, file), 'utf-8');
          const session = JSON.parse(content) as SessionData;
          this.sessions.set(session.id, session);
        } catch (err) {
          logger.warn(`Failed to load session file: ${file}`, err);
        }
      }
    } catch (error) {
      logger.error('Failed to load sessions', error);
    }
  }

  createSession(name?: string): SessionData {
    const session: SessionData = {
      id: generateId(24),
      name: name || `Session ${formatDate(Date.now())}`,
      state: this.createEmptyState(),
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };

    this.sessions.set(session.id, session);
    this.currentSession = session;
    this.saveSession(session);

    logger.info(`Created session: ${session.id} (${session.name})`);
    return session;
  }

  private createEmptyState(): AgentState {
    return {
      task: '',
      plan: [],
      currentStep: 0,
      messages: [],
      context: {
        currentDirectory: process.cwd(),
        openFiles: [],
        recentFiles: [],
        projectStructure: [],
        environment: {},
        tokenCount: 0,
      },
      memory: {
        shortTerm: { messages: [], maxSize: 100 },
        longTerm: { summaries: [], preferences: {}, previousTasks: [] },
      },
      status: 'idle',
      startTime: Date.now(),
    };
  }

  getCurrentSession(): SessionData | null {
    return this.currentSession;
  }

  setCurrentSession(sessionId: string): SessionData | null {
    const session = this.sessions.get(sessionId);
    if (session) {
      this.currentSession = session;
      return session;
    }
    return null;
  }

  getSession(sessionId: string): SessionData | null {
    return this.sessions.get(sessionId) || null;
  }

  getAllSessions(): SessionData[] {
    return [...this.sessions.values()]
      .sort((a, b) => b.updatedAt - a.updatedAt);
  }

  updateSessionState(state: AgentState): void {
    if (this.currentSession) {
      this.currentSession.state = state;
      this.currentSession.updatedAt = Date.now();
    }
  }

  saveCurrentSession(): void {
    if (this.currentSession) {
      this.saveSession(this.currentSession);
    }
  }

  private saveSession(session: SessionData): void {
    try {
      const filePath = join(this.sessionDir, `${session.id}.json`);
      writeFileSync(filePath, JSON.stringify(session, null, 2), 'utf-8');
    } catch (error) {
      logger.error('Failed to save session', error);
    }
  }

  deleteSession(sessionId: string): boolean {
    const session = this.sessions.get(sessionId);
    if (!session) return false;

    try {
      const filePath = join(this.sessionDir, `${sessionId}.json`);
      if (existsSync(filePath)) {
        unlinkSync(filePath);
      }
      this.sessions.delete(sessionId);

      if (this.currentSession?.id === sessionId) {
        this.currentSession = null;
      }

      logger.info(`Deleted session: ${sessionId}`);
      return true;
    } catch (error) {
      logger.error('Failed to delete session', error);
      return false;
    }
  }

  renameSession(sessionId: string, newName: string): boolean {
    const session = this.sessions.get(sessionId);
    if (session) {
      session.name = newName;
      session.updatedAt = Date.now();
      this.saveSession(session);
      return true;
    }
    return false;
  }

  exportSession(sessionId: string): string | null {
    const session = this.sessions.get(sessionId);
    if (session) {
      return JSON.stringify(session, null, 2);
    }
    return null;
  }

  importSession(json: string): SessionData | null {
    try {
      const session = JSON.parse(json) as SessionData;
      session.id = generateId(24);
      session.createdAt = Date.now();
      session.updatedAt = Date.now();

      this.sessions.set(session.id, session);
      this.saveSession(session);

      logger.info(`Imported session: ${session.id} (${session.name})`);
      return session;
    } catch (error) {
      logger.error('Failed to import session', error);
      return null;
    }
  }

  getSessionCount(): number {
    return this.sessions.size;
  }

  listSessions(): Array<{ id: string; name: string; createdAt: string; messageCount: number }> {
    return this.getAllSessions().map((s) => ({
      id: s.id,
      name: s.name,
      createdAt: formatDate(s.createdAt),
      messageCount: s.state.messages.length,
    }));
  }

  destroy(): void {
    if (this.saveTimer) {
      clearInterval(this.saveTimer);
    }
    this.saveCurrentSession();
  }
}

export const sessionManager = new SessionManager();
