# ◆ YS Code Agent

**AI-Powered Terminal Coding Assistant — Built for Termux, Engineered for Production**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Node.js](https://img.shields.io/badge/node-%3E%3D22.0.0-brightgreen)](package.json)
[![npm](https://img.shields.io/npm/v/ys-code-agent)](https://www.npmjs.com/package/ys-code-agent)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/YSCodex/YSCode/pulls)

---

## 🚀 One-Line Install

```bash
# Termux / Linux / macOS — install globally instantly
npm install -g ys-code-agent

# Or directly from GitHub
npm install -g git+https://github.com/YSCodex/YSCode.git

# Then just run:
ys
```

**Termux one-liner (full setup):**
```bash
pkg update -y && pkg install nodejs git -y && npm install -g ys-code-agent && ys
```

---

## ✨ Features

### 🎯 Core Capabilities
- **Multi-Provider AI**: OpenRouter (free), Anthropic Claude, OpenAI GPT-4o, Google Gemini, Groq, DeepSeek, Ollama
- **50+ Slash Commands**: File ops, git, memory, code review, debugging, planning, workspace management
- **Rich Terminal UI**: Boxed dashboard, command popup, syntax highlighting, colored diffs
- **Global Install**: `npm install -g ys-code-agent` — use `ys` anywhere

### 📁 File System
- `/read` — Read files with line numbers and directory trees
- `/edit` — AI-powered editing with colored diff preview & confirm
- `/create` — Smart file creation with language templates
- `/search` — Ripgrep-style codebase search with filters
- `/refactor` — AI-driven code refactoring
- `/watch` — Watch files for real-time changes
- `/project-index` — Full project indexing for semantic search

### 🌿 Git Integration
- `/git status diff commit log branch push pull stash` — Full git workflow
- `/git commit --ai` — AI generates conventional commit messages from diffs

### 🧠 Project Memory
- `/init` — Creates `.ys/` folder with YS.md project context
- `/memory` — Persistent memory across sessions
- `/remember` / `/forget` — Quick save/remove knowledge
- `/dream` — AI consolidates conversation into memory

### 🤖 AI Modes
- `/plan` — Plan-only mode (AI creates steps, no code)
- `/goal` — Fully autonomous execution with progress tracking
- `/review` — Code review with categorized findings (bugs, security, performance)
- `/review --pr` — PR review mode
- `/debug` — Auto-debug with root cause analysis
- `/arena` — Multi-model competition (compare outputs side-by-side)
- `/agents` — 10 built-in sub-agents (Architect, Coder, Reviewer, etc.)

### ⚙️ Power Features
- **Approval Modes**: `safe` / `normal` / `yolo` (control autonomy)
- **Background Tasks**: Run commands in parallel while chatting
- **Session Export**: HTML, Markdown, JSON, JSONL
- **Context Management**: Token usage tracking, auto-compression
- **Workspace Manager**: Save/load/switch between project directories
- **Vim Mode**: j/k/h/l keybindings for power users
- **Self-Update**: `/update` pulls latest code and rebuilds
- **Project Index**: Full codebase scanning with semantic search
- **File Watcher**: Real-time file change monitoring
- **Themes**: Dark, Light, Matrix (green-on-black)

### 📱 Termux Optimized
- Automatic detection of Android Termux
- Low-RAM mode (< 2GB detected automatically)
- Narrow/portrait terminal support (< 60 chars)
- Battery-friendly (animations disabled on low-end devices)
- SIGWINCH handling for orientation changes

---

## 🎮 Usage Examples

```bash
# Start interactive session
ys

# Read and edit files
/read src/index.ts
/edit src/auth.ts add JWT validation

# Git workflow with AI commit messages
/git status
/git add .
/git commit --ai

# Plan and execute autonomously
/plan Add dark mode toggle
/goal Create REST API for todo app

# Debug errors with root cause analysis
/debug "TypeError: Cannot read property 'id'"

# Code review
/review src/api/routes.ts
/review --pr 42

# Project indexing
/project-index
/project-index --search "auth"

# Workspace management
/workspace save my-app
/workspace load my-app

# Watch files
/watch src/

# Export documentation
/export html

# Self-update
/update

# Vim keybindings
/vim on

# Change themes
/theme matrix
```

[📖 Complete Usage Guide →](USAGE.md)

---

## 📦 How to Install

### Global npm (recommended)

```bash
npm install -g ys-code-agent
ys
```

### From GitHub

```bash
npm install -g git+https://github.com/YSCodex/YSCode.git
ys
```

### From source

```bash
git clone https://github.com/YSCodex/YSCode.git
cd YSCode
npm install
npm run build
npm link  # makes `ys` available globally
ys
```

### Termux (Android)

```bash
pkg update -y && pkg install nodejs git -y
npm install -g ys-code-agent
ys
```

---

## 🔧 API Key Setup

Set at least one API key to use the agent:

```bash
# OpenRouter (recommended — free models available)
export OPENROUTER_API_KEY="sk-or-v1-xxxxxxxx"

# Or set via CLI
ys key openrouter sk-or-v1-xxxxxxxx
```

Free models: `google/gemma-4-31b-it:free`, `qwen/qwen-3-coder-32b:free`, `meta-llama/llama-3.3-70b-instruct:free`, `deepseek/deepseek-chat:free`

---

## 🖥️ Interface

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
║  📁 Project: /home/user/myapp   (Node.js detected)  ║
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
│   ├── commands/     # 50+ slash command handlers
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

## 📄 License

MIT © 2026 YS Code Agent. See [LICENSE](LICENSE).

---

*Built for developers who code from their phones.*
