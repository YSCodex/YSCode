import { platform, homedir } from 'os';
import { existsSync, readFileSync } from 'fs';

export const phoneConfig = {
  get isTermux(): boolean {
    return !!(
      process.env.PREFIX?.includes('termux') ||
      process.env.HOME?.includes('com.termux') ||
      process.env.TERMUX_VERSION
    );
  },

  get terminalWidth(): number {
    return process.stdout.columns ?? 80;
  },

  get terminalHeight(): number {
    return process.stdout.rows ?? 24;
  },

  get isNarrow(): boolean {
    return this.terminalWidth < 60;
  },

  get isPortrait(): boolean {
    return this.terminalHeight > this.terminalWidth;
  },

  get lowMemory(): boolean {
    if (this.isTermux) return true;
    try {
      const memInfo = readFileSync('/proc/meminfo', 'utf-8');
      const match = memInfo.match(/MemTotal:\s+(\d+)/);
      if (match) {
        const totalKb = parseInt(match[1], 10);
        return totalKb < 2_000_000;
      }
    } catch {}
    return false;
  },

  get platform(): string {
    return platform();
  },

  get homeDir(): string {
    return homedir();
  },

  getWelcomeBoxWidth(): number {
    return Math.min(this.terminalWidth - 4, 56);
  },

  getPopupHeight(): number {
    if (this.isNarrow) return 6;
    if (this.isPortrait) return 8;
    return 10;
  },

  shouldAnimate(): boolean {
    return !this.isTermux || !this.lowMemory;
  },

  showCompactWelcome(): boolean {
    return this.isNarrow || this.terminalWidth < 50;
  },
};
