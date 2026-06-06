import { getLogger } from '../logger/index.js';
import { configManager } from '../config/index.js';
import { PermissionRequest, ToolPermissions, ToolPermission } from '../types.js';
import { isDangerousCommand } from '../utils/index.js';
import { generateId } from '../utils/index.js';

const logger = getLogger('security');

export class SecurityManager {
  private pendingPermissions: Map<string, PermissionRequest> = new Map();
  private allowedPatterns: Array<{ type: string; pattern: RegExp }> = [];
  private deniedPatterns: Array<{ type: string; pattern: RegExp }> = [];

  constructor() {
    this.initPatterns();
  }

  private initPatterns(): void {
    const config = configManager.getConfig();

    for (const cmd of config.permissions.allowedCommands) {
      this.allowedPatterns.push({
        type: 'command',
        pattern: new RegExp(`^${cmd.replace(/\*/g, '.*')}$`),
      });
    }

    for (const cmd of config.permissions.deniedCommands) {
      this.deniedPatterns.push({
        type: 'command',
        pattern: new RegExp(`^${cmd.replace(/\*/g, '.*')}$`),
      });
    }

    for (const path of config.permissions.allowedPaths) {
      this.allowedPatterns.push({
        type: 'path',
        pattern: new RegExp(`^${path.replace(/\*/g, '.*')}`),
      });
    }

    for (const path of config.permissions.deniedPaths) {
      this.deniedPatterns.push({
        type: 'path',
        pattern: new RegExp(`^${path.replace(/\*/g, '.*')}`),
      });
    }
  }

  isCommandAllowed(command: string): { allowed: boolean; reason?: string } {
    const config = configManager.getConfig();

    if (config.security.readOnlyMode) {
      return { allowed: false, reason: 'System is in read-only mode' };
    }

    if (config.security.dangerousCommandDetection && isDangerousCommand(command)) {
      return { allowed: false, reason: 'Command detected as potentially dangerous' };
    }

    for (const pattern of this.deniedPatterns) {
      if (pattern.type === 'command' && pattern.pattern.test(command.trim())) {
        return { allowed: false, reason: `Command matches denied pattern: ${command}` };
      }
    }

    for (const pattern of this.allowedPatterns) {
      if (pattern.type === 'command' && pattern.pattern.test(command.trim())) {
        return { allowed: true };
      }
    }

    if (config.permissions.autoApprove) {
      return { allowed: true };
    }

    return { allowed: config.permissions.askForConfirmation ? true : true };
  }

  isPathAllowed(filePath: string): { allowed: boolean; reason?: string } {
    const config = configManager.getConfig();

    if (config.security.readOnlyMode && !filePath.match(/^\.(git|ys)/)) {
      return { allowed: false, reason: 'System is in read-only mode' };
    }

    for (const pattern of this.deniedPatterns) {
      if (pattern.type === 'path' && pattern.pattern.test(filePath)) {
        return { allowed: false, reason: `Path matches denied pattern: ${filePath}` };
      }
    }

    if (config.permissions.autoApprove) {
      return { allowed: true };
    }

    return { allowed: true };
  }

  isToolAllowed(toolName: string, permissions: ToolPermission[]): boolean {
    const config = configManager.getConfig();

    if (config.tools.disabledTools.includes(toolName)) {
      return false;
    }

    if (config.tools.enabledTools.length > 0 && !config.tools.enabledTools.includes(toolName)) {
      return false;
    }

    return true;
  }

  createPermissionRequest(action: string, resource: string, details: string): PermissionRequest {
    const request: PermissionRequest = {
      id: generateId(),
      action,
      resource,
      details,
      timestamp: Date.now(),
      status: 'pending',
    };

    this.pendingPermissions.set(request.id, request);
    logger.info(`Permission request created: ${action} on ${resource}`);

    return request;
  }

  approvePermission(id: string): boolean {
    const request = this.pendingPermissions.get(id);
    if (request && request.status === 'pending') {
      request.status = 'approved';
      logger.info(`Permission approved: ${id}`);
      return true;
    }
    return false;
  }

  denyPermission(id: string): boolean {
    const request = this.pendingPermissions.get(id);
    if (request && request.status === 'pending') {
      request.status = 'denied';
      logger.info(`Permission denied: ${id}`);
      return true;
    }
    return false;
  }

  getPendingPermissions(): PermissionRequest[] {
    return [...this.pendingPermissions.values()].filter((p) => p.status === 'pending');
  }

  resolvePermission(id: string, approved: boolean): boolean {
    return approved ? this.approvePermission(id) : this.denyPermission(id);
  }

  setReadOnlyMode(enabled: boolean): void {
    configManager.set('security.readOnlyMode', enabled);
  }

  isReadOnly(): boolean {
    return configManager.getConfig().security.readOnlyMode;
  }

  setSandboxMode(enabled: boolean): void {
    configManager.set('security.sandboxMode', enabled);
  }

  isSandbox(): boolean {
    return configManager.getConfig().security.sandboxMode;
  }
}

export const securityManager = new SecurityManager();
