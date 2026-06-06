# ◆ YS Code Agent

**AI-Powered Terminal Coding Assistant — Built for Termux, Engineered for Production**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Node.js](https://img.shields.io/badge/node-%3E%3D22.0.0-brightgreen)](package.json)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

YS Code Agent is a **production-grade, AI-powered terminal coding assistant** that combines the best of Claude Code, Qwen Code, and Gemini CLI into a single, optimized package. Designed for **Android Termux** (low-RAM phones), Linux, and macOS.

---

## ✨ Features

### 🎯 Core Capabilities
- **Multi-Provider AI**: OpenRouter, Anthropic (Claude), OpenAI (GPT-4o), Google (Gemini), Groq, DeepSeek, Ollama
- **40+ Slash Commands**: File ops, git, memory, code review, debugging, planning
- **Rich Terminal UI**: Boxed dashboard, command popup, syntax highlighting, colored diffs

### 📁 File System
- `/read` — Read files with line numbers and directory trees
- `/edit` — AI-powered editing with colored diff preview & confirm
- `/create` — Smart file creation with language templates
- `/search` — Ripgrep-style codebase search with filters
- `/refactor` — AI-driven code refactoring

### 🌿 Git Integration
- `/git status diff commit log branch` — Full git workflow
- `/git commit --ai` — AI generates conventional commit messages from diffs

### 🧠 Project Memory
- `/init` — Creates `.ys/` folder with YS.md project context
- `/memory` — Persistent memory across sessions
- `/remember` / `/forget` — Quick save/remove knowledge

### 🤖 AI Modes
- `/plan` — Plan-only mode (AI creates steps, no code)
- `/goal` — Fully autonomous execution with progress tracking
- `/review` — Code review with categorized findings
- `/debug` — Auto-debug with root cause analysis
- `/arena` — Multi-model competition (compare outputs)
- `/agents` — 10 built-in sub-agents (Architect, Coder, Reviewer, etc.)

### ⚙️ Power Features
- **Approval Modes**: `safe` / `normal` / `yolo` (control autonomy)
- **Background Tasks**: Run commands in parallel while chatting
- **Session Export**: HTML, Markdown, JSON, JSONL
- **Context Management**: Token usage tracking, auto-compression
- **Themes**: Dark, Light, Matrix (green-on-black)

### 📱 Termux Optimized
- Automatic detection of Android Termux
- Low-RAM mode (< 2GB detected automatically)
- Narrow/portrait terminal support (< 60 chars)
- Battery-friendly (animations disabled on low-end devices)
- SIGWINCH handling for orientation changes

---

## 🚀 Quick Start

```bash
# Install globally
npm install -g .

# Set your API key
export OPENROUTER_API_KEY="sk-or-v1-xxxxxxxx"

# Start coding!
ys
```

[📖 Full Setup Guide →](SETUP.md)

---

## 🎮 Usage Examples

```bash
# Start interactive session
ys

# Read and edit files
/read src/index.ts
/edit src/auth.ts add JWT validation

# Git workflow
/git status
/git add .
/git commit --ai

# Plan and execute
/plan Add dark mode toggle
/goal Create REST API for todo app

# Debug errors
/debug "TypeError: Cannot read property 'id'"

# Export your work
/export html
```

[📖 Complete Usage Guide →](USAGE.md)

---

## 🖥️ Screenshots

```
╔══════════════════════════════════════════════════════╗
║                  ◆ YS CODE AGENT ◆                  ║
║          AI-Powered Terminal Coding Assistant        ║
╠══════════════════════════════════════════════════════╣
║  Provider  : OpenRouter          Model: gemma-4-31b ║
║  Tools     : 18 Active           Mode : Normal      ║
║  Memory    : ✓ Enabled           Git  : ✓ Connected ║
╠══════════════════════════════════════════════════════╣
║  💡 Type / for commands  |  ? for shortcuts         ║
╚══════════════════════════════════════════════════════╝

◆ ys [gemma-4-31b-it:free] ›
```

---

## 📦 Architecture

```
ys-agent/
├── src/
│   ├── cli/          # CLI entry, interactive mode
│   ├── ui/           # Terminal UI (welcome, popup, themes)
│   ├── commands/     # 40+ slash command handlers
│   ├── agent/        # Core AI agent loop
│   ├── providers/    # 9 API providers
│   ├── tools/        # 12 tool implementations
│   ├── memory/       # Memory management
│   ├── session/      # Session persistence
│   └── config/       # Configuration system
├── .ys/              # Auto-created project memory
├── SETUP.md          # Setup guide
├── USAGE.md          # Usage guide
└── LICENSE           # MIT License
```

---

## 🔧 Requirements

- **Node.js** ≥ 22.0.0
- **npm** ≥ 10.0
- One API key (OpenRouter recommended for free tier)

---

## 🧪 Testing

```bash
npm test
npm run lint
npm run typecheck
```

---

## 📄 License

MIT © 2026 YS Code Agent. See [LICENSE](LICENSE).

---

## 🙏 Acknowledgments

- Inspired by Claude Code, Qwen Code, and Gemini CLI
- Built with Node.js, TypeScript, and Chalk
- Optimized for Termux Android community

---

*Built for developers who code from their phones.*
