import { existsSync, readFileSync, readdirSync, statSync } from 'fs';
import { join, basename, extname, resolve } from 'path';
import { getLogger } from '../logger/index.js';
import { ProjectInfo } from '../types.js';
import { fileSystem } from '../filesystem/index.js';
import { formatBytes } from '../utils/index.js';

const logger = getLogger('project');

interface FrameworkDetector {
  name: string;
  detect: (root: string) => boolean;
  priority: number;
}

const LANGUAGE_DETECTORS: Array<{ name: string; detect: (files: string[]) => boolean }> = [
  {
    name: 'TypeScript',
    detect: (files) => files.some((f) => f.endsWith('.ts') || f.endsWith('.tsx')),
  },
  {
    name: 'JavaScript',
    detect: (files) => files.some((f) => f.endsWith('.js') || f.endsWith('.jsx') || f.endsWith('.mjs')),
  },
  {
    name: 'Python',
    detect: (files) => files.some((f) => f.endsWith('.py') || f === 'requirements.txt' || f === 'Pipfile' || f === 'setup.py'),
  },
  {
    name: 'Java',
    detect: (files) => files.some((f) => f.endsWith('.java') || f === 'pom.xml' || f === 'build.gradle' || f === 'build.gradle.kts'),
  },
  {
    name: 'Kotlin',
    detect: (files) => files.some((f) => f.endsWith('.kt') || f.endsWith('.kts')),
  },
  {
    name: 'Dart',
    detect: (files) => files.some((f) => f.endsWith('.dart') || f === 'pubspec.yaml'),
  },
  {
    name: 'Rust',
    detect: (files) => files.some((f) => f.endsWith('.rs') || f === 'Cargo.toml'),
  },
  {
    name: 'Go',
    detect: (files) => files.some((f) => f.endsWith('.go') || f === 'go.mod'),
  },
  {
    name: 'C++',
    detect: (files) => files.some((f) => f.endsWith('.cpp') || f.endsWith('.hpp') || f.endsWith('.cc')),
  },
  {
    name: 'C',
    detect: (files) => files.some((f) => f.endsWith('.c') || f.endsWith('.h')),
  },
  {
    name: 'Ruby',
    detect: (files) => files.some((f) => f.endsWith('.rb') || f === 'Gemfile'),
  },
  {
    name: 'PHP',
    detect: (files) => files.some((f) => f.endsWith('.php') || f === 'composer.json'),
  },
  {
    name: 'Swift',
    detect: (files) => files.some((f) => f.endsWith('.swift') || f === 'Package.swift'),
  },
  {
    name: 'HTML',
    detect: (files) => files.some((f) => f.endsWith('.html') || f.endsWith('.htm')),
  },
  {
    name: 'CSS',
    detect: (files) => files.some((f) => f.endsWith('.css') || f.endsWith('.scss') || f.endsWith('.less')),
  },
];

const FRAMEWORK_DETECTORS: FrameworkDetector[] = [
  { name: 'Next.js', priority: 100,
    detect: (root) => existsSync(join(root, 'next.config.js')) || existsSync(join(root, 'next.config.mjs')) || existsSync(join(root, 'next.config.ts')) },
  { name: 'React', priority: 90,
    detect: (root) => hasDependency(root, 'react') },
  { name: 'Vue.js', priority: 90,
    detect: (root) => hasDependency(root, 'vue') },
  { name: 'Angular', priority: 90,
    detect: (root) => hasDependency(root, '@angular/core') || existsSync(join(root, 'angular.json')) },
  { name: 'Svelte', priority: 90,
    detect: (root) => hasDependency(root, 'svelte') || existsSync(join(root, 'svelte.config.js')) },
  { name: 'Express', priority: 80,
    detect: (root) => hasDependency(root, 'express') },
  { name: 'Fastify', priority: 80,
    detect: (root) => hasDependency(root, 'fastify') },
  { name: 'NestJS', priority: 80,
    detect: (root) => hasDependency(root, '@nestjs/core') },
  { name: 'Django', priority: 80,
    detect: (root) => hasDependency(root, 'django') || existsSync(join(root, 'manage.py')) },
  { name: 'Flask', priority: 80,
    detect: (root) => hasDependency(root, 'flask') || existsSync(join(root, 'app.py')) },
  { name: 'FastAPI', priority: 80,
    detect: (root) => hasDependency(root, 'fastapi') },
  { name: 'Spring Boot', priority: 80,
    detect: (root) => existsSync(join(root, 'pom.xml')) || existsSync(join(root, 'build.gradle')) || existsSync(join(root, 'build.gradle.kts')) },
  { name: 'Flutter', priority: 90,
    detect: (root) => existsSync(join(root, 'pubspec.yaml')) && hasDependency(root, 'flutter') },
  { name: 'React Native', priority: 85,
    detect: (root) => hasDependency(root, 'react-native') },
  { name: 'Electron', priority: 85,
    detect: (root) => hasDependency(root, 'electron') },
  { name: 'Astro', priority: 85,
    detect: (root) => hasDependency(root, 'astro') || existsSync(join(root, 'astro.config.mjs')) },
  { name: 'Nuxt.js', priority: 85,
    detect: (root) => hasDependency(root, 'nuxt') || existsSync(join(root, 'nuxt.config.js')) },
  { name: 'Gatsby', priority: 85,
    detect: (root) => hasDependency(root, 'gatsby') },
  { name: 'Tailwind CSS', priority: 70,
    detect: (root) => hasDependency(root, 'tailwindcss') || existsSync(join(root, 'tailwind.config.js')) },
  { name: 'Bootstrap', priority: 70,
    detect: (root) => hasDependency(root, 'bootstrap') },
  { name: 'Prisma', priority: 70,
    detect: (root) => hasDependency(root, 'prisma') || existsSync(join(root, 'prisma/schema.prisma')) },
  { name: 'TypeORM', priority: 70,
    detect: (root) => hasDependency(root, 'typeorm') },
  { name: 'Mongoose', priority: 70,
    detect: (root) => hasDependency(root, 'mongoose') },
  { name: 'Jest', priority: 70,
    detect: (root) => hasDependency(root, 'jest') },
  { name: 'Playwright', priority: 70,
    detect: (root) => hasDependency(root, '@playwright/test') || hasDependency(root, 'playwright') },
  { name: 'PyTorch', priority: 70,
    detect: (root) => hasDependency(root, 'torch') || hasDependency(root, 'pytorch') },
  { name: 'TensorFlow', priority: 70,
    detect: (root) => hasDependency(root, 'tensorflow') },
];

const PACKAGE_MANAGER_DETECTORS: Array<{ name: string; detect: (root: string) => boolean }> = [
  { name: 'npm', detect: (root) => existsSync(join(root, 'package-lock.json')) || existsSync(join(root, 'package.json')) },
  { name: 'yarn', detect: (root) => existsSync(join(root, 'yarn.lock')) || existsSync(join(root, '.yarnrc')) },
  { name: 'pnpm', detect: (root) => existsSync(join(root, 'pnpm-lock.yaml')) || existsSync(join(root, 'pnpm-workspace.yaml')) },
  { name: 'pip', detect: (root) => existsSync(join(root, 'requirements.txt')) },
  { name: 'pipenv', detect: (root) => existsSync(join(root, 'Pipfile')) },
  { name: 'poetry', detect: (root) => existsSync(join(root, 'pyproject.toml')) },
  { name: 'cargo', detect: (root) => existsSync(join(root, 'Cargo.toml')) },
  { name: 'go mod', detect: (root) => existsSync(join(root, 'go.mod')) },
  { name: 'maven', detect: (root) => existsSync(join(root, 'pom.xml')) },
  { name: 'gradle', detect: (root) => existsSync(join(root, 'build.gradle')) || existsSync(join(root, 'build.gradle.kts')) },
  { name: 'bundler', detect: (root) => existsSync(join(root, 'Gemfile')) },
  { name: 'composer', detect: (root) => existsSync(join(root, 'composer.json')) },
  { name: 'pub', detect: (root) => existsSync(join(root, 'pubspec.yaml')) },
];

const BUILD_SYSTEM_DETECTORS: Array<{ name: string; detect: (root: string) => boolean }> = [
  { name: 'TypeScript (tsc)', detect: (root) => existsSync(join(root, 'tsconfig.json')) },
  { name: 'Webpack', detect: (root) => hasDependency(root, 'webpack') || existsSync(join(root, 'webpack.config.js')) },
  { name: 'Vite', detect: (root) => hasDependency(root, 'vite') || existsSync(join(root, 'vite.config.ts')) || existsSync(join(root, 'vite.config.js')) },
  { name: 'ESBuild', detect: (root) => hasDependency(root, 'esbuild') },
  { name: 'Rollup', detect: (root) => hasDependency(root, 'rollup') || existsSync(join(root, 'rollup.config.js')) },
  { name: 'Parcel', detect: (root) => hasDependency(root, 'parcel') },
  { name: 'Turbopack', detect: (root) => hasDependency(root, 'turbo') },
  { name: 'Babel', detect: (root) => existsSync(join(root, '.babelrc')) || existsSync(join(root, 'babel.config.js')) },
  { name: 'Make', detect: (root) => existsSync(join(root, 'Makefile')) },
  { name: 'CMake', detect: (root) => existsSync(join(root, 'CMakeLists.txt')) },
  { name: 'Gradle', detect: (root) => existsSync(join(root, 'build.gradle')) || existsSync(join(root, 'build.gradle.kts')) },
  { name: 'Maven', detect: (root) => existsSync(join(root, 'pom.xml')) },
];

function hasDependency(root: string, depName: string): boolean {
  const pkgPath = join(root, 'package.json');
  if (existsSync(pkgPath)) {
    try {
      const content = readFileSync(pkgPath, 'utf-8');
      const pkg = JSON.parse(content);
      return (
        (pkg.dependencies && depName in pkg.dependencies) ||
        (pkg.devDependencies && depName in pkg.devDependencies) ||
        (pkg.peerDependencies && depName in pkg.peerDependencies)
      );
    } catch {
      return false;
    }
  }

  const requirementsPath = join(root, 'requirements.txt');
  if (existsSync(requirementsPath) && depName !== 'django') {
    try {
      const content = readFileSync(requirementsPath, 'utf-8');
      return content.includes(depName);
    } catch {
      return false;
    }
  }

  const pubspecPath = join(root, 'pubspec.yaml');
  if (existsSync(pubspecPath) && (depName === 'flutter' || depName === 'dart')) {
    try {
      const content = readFileSync(pubspecPath, 'utf-8');
      return content.includes(depName);
    } catch {
      return false;
    }
  }

  return false;
}

function getAllFilesRecursive(dir: string, maxDepth = 3): string[] {
  const files: string[] = [];
  const walk = (currentDir: string, depth: number) => {
    if (depth > maxDepth) return;
    try {
      const entries = readdirSync(currentDir, { withFileTypes: true });
      for (const entry of entries) {
        const fullPath = join(currentDir, entry.name);
        if (entry.name.startsWith('.') && entry.name !== '.gitignore') continue;
        if (entry.name === 'node_modules' || entry.name === '.git') continue;
        if (entry.isDirectory()) {
          walk(fullPath, depth + 1);
        } else {
          files.push(fullPath);
        }
      }
    } catch {
    }
  };
  walk(dir, 0);
  return files;
}

function readJsonFile(filePath: string): Record<string, unknown> | null {
  try {
    if (existsSync(filePath)) {
      return JSON.parse(readFileSync(filePath, 'utf-8'));
    }
  } catch {
  }
  return null;
}

export class ProjectAnalyzer {
  private rootPath: string;
  private cachedInfo: ProjectInfo | null = null;

  constructor(rootPath?: string) {
    this.rootPath = rootPath || process.cwd();
  }

  async analyze(): Promise<ProjectInfo> {
    if (this.cachedInfo) return this.cachedInfo;

    const startTime = Date.now();
    logger.info(`Analyzing project at: ${this.rootPath}`);

    const allFiles = getAllFilesRecursive(this.rootPath, 4);
    const fileNames = allFiles.map((f) => basename(f));
    const fileExtensions = [...new Set(allFiles.map((f) => extname(f)).filter(Boolean))];

    const name = basename(this.rootPath);

    const languages = LANGUAGE_DETECTORS
      .filter((d) => d.detect(fileNames))
      .map((d) => d.name);

    const frameworks = FRAMEWORK_DETECTORS
      .filter((d) => d.detect(this.rootPath))
      .sort((a, b) => b.priority - a.priority)
      .map((d) => d.name);

    const packageManager = PACKAGE_MANAGER_DETECTORS
      .find((d) => d.detect(this.rootPath))?.name || 'unknown';

    const buildSystem = BUILD_SYSTEM_DETECTORS
      .find((d) => d.detect(this.rootPath))?.name || 'unknown';

    let dependencies: string[] = [];
    let devDependencies: string[] = [];
    let scripts: Record<string, string> = {};

    const pkg = readJsonFile(join(this.rootPath, 'package.json'));
    if (pkg) {
      dependencies = Object.keys((pkg.dependencies as Record<string, string>) || {});
      devDependencies = Object.keys((pkg.devDependencies as Record<string, string>) || {});
      scripts = (pkg.scripts as Record<string, string>) || {};
    }

    if (languages.length === 0 && fileExtensions.length > 0) {
      const extToLang: Record<string, string> = {
        '.ts': 'TypeScript', '.tsx': 'TypeScript', '.js': 'JavaScript', '.jsx': 'JavaScript',
        '.py': 'Python', '.java': 'Java', '.kt': 'Kotlin', '.dart': 'Dart',
        '.rs': 'Rust', '.go': 'Go', '.cpp': 'C++', '.c': 'C',
        '.rb': 'Ruby', '.php': 'PHP', '.swift': 'Swift',
      };
      for (const ext of fileExtensions) {
        const lang = extToLang[ext];
        if (lang && !languages.includes(lang)) {
          languages.push(lang);
        }
      }
    }

    const fileCount = allFiles.length;
    const totalSize = allFiles.reduce((acc, f) => {
      try {
        return acc + statSync(f).size;
      } catch {
        return acc;
      }
    }, 0);

    const info: ProjectInfo = {
      name,
      rootPath: this.rootPath,
      languages,
      frameworks,
      packageManager,
      buildSystem,
      dependencies,
      devDependencies,
      scripts,
      fileCount,
      totalSize,
    };

    this.cachedInfo = info;
    logger.info(`Project analysis complete in ${Date.now() - startTime}ms`, { name, languages, frameworks });

    return info;
  }

  getProjectName(): string {
    const pkg = readJsonFile(join(this.rootPath, 'package.json'));
    if (pkg?.name) return pkg.name as string;

    try {
      const pyproj = readFileSync(join(this.rootPath, 'pyproject.toml'), 'utf-8');
      const match = pyproj.match(/name\s*=\s*"([^"]+)"/);
      if (match) return match[1];
    } catch {
    }

    return basename(this.rootPath);
  }

  getProjectSummary(): string {
    const info = this.cachedInfo;
    if (!info) {
      return 'Project not analyzed yet. Run analyze() first.';
    }

    const lines: string[] = [
      `# ${info.name}`,
      '',
      `**Root:** ${info.rootPath}`,
      `**Files:** ${info.fileCount} (${formatBytes(info.totalSize)})`,
      '',
    ];

    if (info.languages.length > 0) {
      lines.push(`**Languages:** ${info.languages.join(', ')}`);
    }
    if (info.frameworks.length > 0) {
      lines.push(`**Frameworks:** ${info.frameworks.join(', ')}`);
    }
    if (info.packageManager) {
      lines.push(`**Package Manager:** ${info.packageManager}`);
    }
    if (info.buildSystem) {
      lines.push(`**Build System:** ${info.buildSystem}`);
    }

    if (info.dependencies.length > 0) {
      lines.push('');
      lines.push(`**Dependencies (${info.dependencies.length}):**`);
      for (const dep of info.dependencies.slice(0, 20)) {
        lines.push(`- ${dep}`);
      }
      if (info.dependencies.length > 20) {
        lines.push(`- ... and ${info.dependencies.length - 20} more`);
      }
    }

    if (Object.keys(info.scripts).length > 0) {
      lines.push('', '**Scripts:**');
      for (const [name, cmd] of Object.entries(info.scripts)) {
        lines.push(`- \`${name}\`: \`${cmd}\``);
      }
    }

    return lines.join('\n');
  }

  clearCache(): void {
    this.cachedInfo = null;
  }
}

export async function analyzeProject(rootPath?: string): Promise<ProjectInfo> {
  const analyzer = new ProjectAnalyzer(rootPath);
  return analyzer.analyze();
}

export function getProjectSummary(rootPath?: string): string {
  const analyzer = new ProjectAnalyzer(rootPath);
  const info = analyzer.analyze();
  return analyzer.getProjectSummary();
}

export { ProjectAnalyzer as ProjectAnalyzerClass };
