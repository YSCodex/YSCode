# YS Code Agent — Setup Guide

> Version 2.0 | MIT License 2026

---

## Prerequisites

- **Node.js** >= 22.0.0 (recommended: 22.12+)
- **npm** >= 10.0
- A terminal emulator (Termux on Android, or any standard terminal on Linux/macOS)

---

## Quick Install

### Option 1: Global npm install (recommended)

```bash
# Clone the repository
git clone https://github.com/YSCodex/YSCode.git
cd YSCode

# Install dependencies
npm install

# Build the project
npm run build

# Link globally (makes `ys` available everywhere)
npm link

# Or install globally directly
npm install -g .
```

### Option 2: Direct execution

```bash
# Run without installing globally
node dist/cli/index.cjs
```

### Option 3: Termux (Android)

```bash
# Install Node.js in Termux
pkg update && pkg upgrade
pkg install nodejs git

# Clone and setup
git clone https://github.com/YSCodex/YSCode.git
cd YSCode
npm install
npm run build
npm link

# Optional: run setup script for Termux optimizations
bash scripts/setup-termux.sh
```

---

## API Key Configuration

YS Code Agent needs at least one API key to function. Set one of these environment variables:

### OpenRouter (recommended — free models available)

```bash
export OPENROUTER_API_KEY="sk-or-v1-xxxxxxxxxxxxxxxx"
```

Free models available:
- `google/gemma-4-31b-it:free` (default)
- `qwen/qwen-3-coder-32b:free`
- `meta-llama/llama-3.3-70b-instruct:free`
- `deepseek/deepseek-chat:free`

### Other providers

```bash
# Anthropic (Claude)
export ANTHROPIC_API_KEY="sk-ant-xxxxxxxx"

# OpenAI (GPT-4o, GPT-4o-mini)
export OPENAI_API_KEY="sk-xxxxxxxx"

# Google (Gemini)
export GOOGLE_API_KEY="AIzaxxxxxxxx"

# Groq (fastest inference)
export GROQ_API_KEY="gsk_xxxxxxxx"

# DeepSeek
export DEEPSEEK_API_KEY="sk-xxxxxxxx"

# Local Ollama
export OLLAMA_HOST="http://localhost:11434"
```

### Set key via CLI (alternative)

```bash
ys key openrouter sk-or-v1-xxxxxxxxxxxx
```

---

## Quick Start

```bash
# Start interactive mode
ys

# Or with a specific model
ys --model google/gemma-4-31b-it:free

# Or with a specific provider
ys --provider openrouter

# Execute a one-shot task
ys run "Create a React component for a login form"

# Run diagnostics
ys doctor
```

---

## First Run

When you start `ys` for the first time:

1. You'll see the welcome dashboard with system info
2. Type `/` to see available commands
3. Type your question or task to start coding
4. Use `/help` for complete command reference

### Tips for first-time users

- Type `/?` or `/help files` to see file-related commands
- Type `/provider` to check which AI providers are configured
- Type `/doctor` to run a system diagnostic check
- Type `/init` to create a `.ys/` project memory folder

---

## Platform-Specific Notes

### Termux (Android)

- YS Code Agent automatically detects Termux and optimizes UI
- Narrow terminal mode activates if width < 60 characters
- Animations are disabled on low-RAM devices (< 2GB)
- Use portrait mode for best experience on phones

### Linux / macOS

- All features fully supported
- 256-color terminal recommended
- `Ctrl+X` opens external editor ($EDITOR or nano)

### Windows

- Best used via WSL2 or Git Bash
- Some ANSI features may require Windows Terminal

---

## Updating

```bash
# Pull latest changes
git pull origin main

# Rebuild
npm run build
```

---

## Troubleshooting

### "No API key configured"
Set one of the environment variables listed above or use `/key <provider> <api_key>`.

### "Provider not found"
Run `/provider` to list available providers. Use `/provider <name>` to switch.

### Build fails
- Ensure Node.js >= 22.0.0: `node --version`
- Clear npm cache: `npm cache clean --force`
- Delete node_modules and reinstall: `rm -rf node_modules && npm install`

### Termux issues
- Run `bash scripts/setup-termux.sh` for Android-specific fixes
- Ensure `pkg update` was run recently

---

## Project Structure

```
ys-agent/
├── src/
│   ├── cli/          # CLI entry, interactive mode, agent manager
│   ├── ui/           # Terminal UI (welcome, popup, themes)
│   ├── commands/     # Slash command handlers
│   ├── agent/        # Core AI agent loop
│   ├── providers/    # API providers (OpenRouter, Anthropic, etc.)
│   ├── tools/        # Tool system (read/write/edit/git/search)
│   ├── memory/       # Memory management
│   ├── session/      # Session persistence
│   ├── config/       # Configuration system
│   └── utils/        # Utilities
├── dist/             # Compiled output
├── .ys/              # Auto-created project memory
└── scripts/          # Build & setup scripts
```

---

## Need Help?

- Type `/?` or `/help` inside the agent
- Run `/doctor` for system diagnostics
- Visit the GitHub repository for issues
