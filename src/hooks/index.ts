import { EventEmitter } from 'events';

export type HookEventType =
  | 'PreToolUse'
  | 'PostToolUse'
  | 'UserPrompt'
  | 'AgentStart'
  | 'AgentStop'
  | 'BeforePrompt'
  | 'AfterPrompt'
  | 'ToolError'
  | 'SessionStart'
  | 'SessionEnd';

export interface HookContext {
  eventType: HookEventType;
  timestamp: number;
  data: unknown;
  metadata?: Record<string, unknown>;
}

export interface HookHandler<T = unknown> {
  id: string;
  name: string;
  description?: string;
  eventType: HookEventType;
  priority: number;
  handler: (context: HookContext & { data: T }) => Promise<void> | void;
  enabled?: boolean;
}

export interface HookRegistration {
  id: string;
  eventType: HookEventType;
  handler: HookHandler['handler'];
  priority?: number;
  name?: string;
  description?: string;
}

export interface HookExecutionResult {
  hookId: string;
  success: boolean;
  error?: string;
  duration: number;
}

export class HookSystem extends EventEmitter {
  private static instance: HookSystem;
  private hooks: Map<string, HookHandler[]> = new Map();
  private executionHistory: HookExecutionResult[] = [];
  private maxHistory: number;

  constructor(options: { maxHistory?: number } = {}) {
    super();
    this.maxHistory = options.maxHistory ?? 1000;
  }

  static getInstance(): HookSystem {
    if (!HookSystem.instance) {
      HookSystem.instance = new HookSystem();
    }
    return HookSystem.instance;
  }

  register(registration: HookRegistration): string {
    const id = registration.id || `${registration.eventType}_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

    const handler: HookHandler = {
      id,
      name: registration.name || id,
      description: registration.description,
      eventType: registration.eventType,
      priority: registration.priority ?? 0,
      handler: registration.handler,
      enabled: true,
    };

    if (!this.hooks.has(registration.eventType)) {
      this.hooks.set(registration.eventType, []);
    }

    const handlers = this.hooks.get(registration.eventType)!;
    handlers.push(handler);
    handlers.sort((a, b) => a.priority - b.priority);

    this.emit('hook:registered', handler);
    return id;
  }

  unregister(id: string): boolean {
    for (const [eventType, handlers] of this.hooks) {
      const index = handlers.findIndex((h) => h.id === id);
      if (index !== -1) {
        handlers.splice(index, 1);
        this.emit('hook:unregistered', id);
        return true;
      }
    }
    return false;
  }

  enable(id: string): boolean {
    for (const handlers of this.hooks.values()) {
      const handler = handlers.find((h) => h.id === id);
      if (handler) {
        handler.enabled = true;
        this.emit('hook:enabled', id);
        return true;
      }
    }
    return false;
  }

  disable(id: string): boolean {
    for (const handlers of this.hooks.values()) {
      const handler = handlers.find((h) => h.id === id);
      if (handler) {
        handler.enabled = false;
        this.emit('hook:disabled', id);
        return true;
      }
    }
    return false;
  }

  getHooks(eventType?: HookEventType): HookHandler[] {
    if (eventType) {
      return this.hooks.get(eventType) || [];
    }
    const all: HookHandler[] = [];
    for (const handlers of this.hooks.values()) {
      all.push(...handlers);
    }
    return all;
  }

  getHook(id: string): HookHandler | undefined {
    for (const handlers of this.hooks.values()) {
      const handler = handlers.find((h) => h.id === id);
      if (handler) return handler;
    }
    return undefined;
  }

  async execute(eventType: HookEventType, data: unknown, metadata?: Record<string, unknown>): Promise<HookExecutionResult[]> {
    const handlers = this.hooks.get(eventType)?.filter((h) => h.enabled) || [];
    const results: HookExecutionResult[] = [];

    const context: HookContext = {
      eventType,
      timestamp: Date.now(),
      data,
      metadata,
    };

    this.emit('hook:execute:start', { eventType, context });

    for (const handler of handlers) {
      const startTime = Date.now();
      try {
        await handler.handler({ ...context, data });
        const duration = Date.now() - startTime;
        const result: HookExecutionResult = {
          hookId: handler.id,
          success: true,
          duration,
        };
        results.push(result);
        this.emit('hook:execute:success', result);
      } catch (error) {
        const duration = Date.now() - startTime;
        const errorMessage = error instanceof Error ? error.message : String(error);
        const result: HookExecutionResult = {
          hookId: handler.id,
          success: false,
          error: errorMessage,
          duration,
        };
        results.push(result);
        this.emit('hook:execute:error', result);
      }
    }

    this.executionHistory.push(...results);
    if (this.executionHistory.length > this.maxHistory) {
      this.executionHistory = this.executionHistory.slice(-this.maxHistory);
    }

    this.emit('hook:execute:end', { eventType, results });
    return results;
  }

  getExecutionHistory(eventType?: HookEventType, hookId?: string): HookExecutionResult[] {
    let results = this.executionHistory;
    if (eventType) {
      const hookIds = this.hooks.get(eventType)?.map((h) => h.id) || [];
      results = results.filter((r) => hookIds.includes(r.hookId));
    }
    if (hookId) {
      results = results.filter((r) => r.hookId === hookId);
    }
    return [...results];
  }

  clear(): void {
    this.hooks.clear();
    this.executionHistory = [];
    this.emit('hooks:cleared');
  }

  getStats(): {
    totalHooks: number;
    hooksByType: Record<string, number>;
    totalExecutions: number;
    successfulExecutions: number;
    failedExecutions: number;
  } {
    const hooksByType: Record<string, number> = {};
    let totalHooks = 0;

    for (const [eventType, handlers] of this.hooks) {
      hooksByType[eventType] = handlers.length;
      totalHooks += handlers.length;
    }

    const successfulExecutions = this.executionHistory.filter((r) => r.success).length;
    const failedExecutions = this.executionHistory.filter((r) => !r.success).length;

    return {
      totalHooks,
      hooksByType,
      totalExecutions: this.executionHistory.length,
      successfulExecutions,
      failedExecutions,
    };
  }

  async reset(): Promise<void> {
    this.clear();
    HookSystem.instance = undefined as unknown as HookSystem;
  }
}

export const hookSystem = HookSystem.getInstance();