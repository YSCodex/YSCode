# YS Code Agent — Usage Guide

> Version 2.0 | How to use YS Code Agent effectively

---

## Basic Usage

### Starting the Agent

```bash
# Interactive mode (recommended)
ys

# One-shot task
ys run "Explain how promises work in JavaScript"

# Check version
ys --version

# Run diagnostics
ys doctor
```

### The Interface

When you start the agent, you'll see:

```
╔══════════════════════════════════════════════════════╗
║                  ◆ YS CODE AGENT ◆                  ║
║          AI-Powered Terminal Coding Assistant        ║
╠══════════════════════════════════════════════════════╣
║  Provider  : OpenRouter          Model: gemma-4-31b ║
║  Context   : 262K tokens         Temp : 0.7         ║
║  Tools     : 18 Active           Mode : Normal      ║
║  ...                                               ║
╚══════════════════════════════════════════════════════╝

◆ ys [gemma-4-31b-it:free] ›
  💡 / for commands  |  ↑↓ history  |  Tab complete
```

---

## Slash Commands

### System Commands

| Command | Description |
|---------|-------------|
| `/help` | Show all commands |
| `/help git` | Show git commands only |
| `/status` | Show agent status dashboard |
| `/doctor` | Run system diagnostics |
| `/clear` | Clear screen |
| `/reset` | Reset agent state |
| `/history` | Show message history |
| `/recap` | Session summary |
| `/rewind` | Undo last exchange |
| `/context` | Show token usage |
| `/compress` | Compress context to free tokens |
| `/exit` | Exit the agent |

### File Operations

| Command | Description |
|---------|-------------|
| `/read src/index.ts` | Read file with line numbers |
| `/read . --all` | Show directory tree |
| `/edit file.ts remove console.logs` | AI edit with diff preview |
| `/create component.tsx` | Create file from template |
| `/delete old.ts` | Safe move to `.ys/trash/` |
| `/search "TODO" --ext ts` | Search codebase |
| `/list src/` | Directory tree view |
| `/refactor utils.ts split into modules` | AI refactoring |

### Git Commands

| Command | Description |
|---------|-------------|
| `/git status` | Show working tree status |
| `/git diff` | Show colored changes |
| `/git add .` | Stage files |
| `/git commit "fix: resolve auth bug"` | Commit with message |
| `/git commit --ai` | AI generates commit message |
| `/git log --oneline` | View commit history |
| `/git branch` | List branches |
| `/git checkout main` | Switch branch |
| `/git push` | Push to remote |
| `/git pull` | Pull from remote |
| `/git stash` | Stash changes |
| `/git stash pop` | Restore stashed changes |

### Memory Commands

| Command | Description |
|---------|-------------|
| `/memory` | View memory dashboard |
| `/memory list` | List all memory entries |
| `/remember "Always use Gson"` | Save a preference |
| `/forget Gson` | Remove matching memories |
| `/init` | Create `.ys/` project folder |
| `/dream` | Consolidate conversation into memory |

### Code Intelligence

| Command | Description |
|---------|-------------|
| `/plan` | Enter plan-only mode |
| `/plan Add auth system` | Generate step-by-step plan |
| `/goal Add dark mode` | Fully autonomous execution |
| `/review src/app.ts` | Code review with categories |
| `/debug "TypeError: null"` | Auto debug with root cause |
| `/refactor file.ts extract class` | AI refactoring |
| `/apply` | Execute last AI suggestion |

### Multi-Agent & Tasks

| Command | Description |
|---------|-------------|
| `/agents` | Show agent panel |
| `/agents list` | List all 10 built-in agents |
| `/arena start "build form"` | Multi-model competition |
| `/tasks` | Show background tasks |
| `/background npm test` | Run command in background |

### Settings

| Command | Description |
|---------|-------------|
| `/config` | View full configuration |
| `/theme dark` | Switch to dark theme |
| `/theme matrix` | Green-on-black Matrix style |
| `/approval-mode safe` | Always confirm before actions |
| `/approval-mode yolo` | Auto-execute (no confirm) |
| `/permissions` | View tool permissions |
| `/tools` | List all available tools |
| `/provider` | List/switch AI providers |
| `/model` | Show current model info |
| `/models` | List all models for provider |
| `/key openrouter sk-...` | Set API key |

---

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `↑` / `↓` | Navigate command history |
| `Tab` | Autocomplete slash commands |
| `Esc` | Cancel / clear input |
| `Enter` | Send message |
| `Ctrl+C` | Cancel running request |
| `Ctrl+L` | Clear screen |
| `Ctrl+A` | Go to line start |
| `Ctrl+E` | Go to line end |
| `Ctrl+X` | Open external editor |
| `Shift+Tab` | Cycle approval modes |
| `?` (at prompt) | Show quick help |

---

## Working with Files

### Reading files

```bash
# Read a file with syntax highlighting
/read src/index.ts

# Show entire file (if truncated)
/read src/large.ts --all

# Browse a directory
/read src/
# Output:
# src/
# ├── index.ts
# ├── agent/
# │   ├── Agent.ts
# │   └── PlanMode.ts
# └── ui/
#     └── App.tsx
```

### Editing files

```bash
# AI edits your file with preview
/edit src/auth.ts add input validation

# You'll see a diff like:
# - const email = req.body.email
# + const email = z.string().email().parse(req.body.email)
#
# Apply changes? [Y/n]
```

### Creating files

```bash
# Create from template (auto-detected by extension)
/create src/utils/helpers.ts

# Create with initial content
/create README.md # Project Title\n\n## Description
```

### Searching code

```bash
# Search all supported files
/search authentication

# Filter by extension
/search "TODO" --ext ts
/search "function" --ext py

# Output:
# src/auth.ts:42:  export function authenticateUser() {
# src/routes.ts:18:  router.post('/login', authenticateUser)
```

---

## Git Workflow

### Basic workflow

```bash
/git status         # See what changed
/git add src/file.ts  # Stage specific file
/git commit --ai    # AI writes commit message from diff
/git push           # Push to remote
```

### AI commit messages

```bash
# Stage changes first
/git add .
/git commit --ai

# Agent analyzes diff and suggests:
# Suggested message: feat(auth): add JWT token refresh with expiry check
# Use this message? [Y/n/edit]
```

---

## Plan Mode

Create structured plans before writing code:

```bash
/plan Add user authentication system
```

Output:
```
╔═ Plan: Add user authentication system ════════════╗
║  1. Install bcrypt and jsonwebtoken packages       ║
║  2. Create auth middleware with JWT verification  ║
║  3. Add login/signup endpoints                    ║
║  4. Create User model with password hashing       ║
║  5. Add protected route examples                  ║
╚════════════════════════════════════════════════════╝

Type /apply to execute this plan, or continue chatting.
```

---

## Goal Mode (Autonomous)

Give the agent a goal and let it work autonomously:

```bash
/goal Add a dark mode toggle to the settings screen
```

Output:
```
◆ Goal: Add dark mode to settings screen
██████████████████████████████ 100%
○ Scanned project structure...
○ Analyzing requirements...
○ Found SettingsScreen.kt...
○ Creating DarkTheme.kt...
✓ Goal complete
```

In `safe` mode: asks before each file write.
In `yolo` mode: fully autonomous, no confirmations.

---

## Arena Mode

Compare multiple AI models on the same task:

```bash
/arena start "Write a Python function to merge two sorted lists"
```

Output:
```
╔═ Arena Mode — 4 Models ═══════════════════════════╗
║  Gemma-4-31B    ████████░░  streaming...          ║
║  Qwen3-Coder    ██████████  Done (2.1s)           ║
║  Llama-3.3-70B  ████░░░░░░  streaming...          ║
║  DeepSeek       ██████░░░░  streaming...          ║
╚═══════════════════════════════════════════════════╝
```

---

## Session Management

```bash
# Resume a previous session
/resume
# Recent Sessions:
#   1. [a3f2] "Add dark mode" — 2h ago, 24 messages
#   2. [b7c1] "Fix auth bug" — yesterday, 12 messages

# Rename current session
/rename My Feature Implementation

# Export session
/export md        # → Markdown file
/export html      # → Styled HTML page
/export json      # → Structured JSON
/export jsonl     # → One message per line
```

---

## Approval Modes

Control how autonomously the agent acts:

```bash
# Always ask before file writes (safest)
/approval-mode safe

# Ask for important changes only (default)
/approval-mode normal

# Auto-execute everything (fastest, use with caution)
/approval-mode yolo
```

Prompt badge shows current mode:
- `[safe]` — cyan
- `[normal]` — no badge (default)
- `[yolo]` — red (warning shown on activation)

---

## Termux Tips (Android)

- Use portrait orientation for best results
- Long-press for keyboard shortcuts if touch keyboard lacks Ctrl
- The `?` key shows quick shortcuts panel
- `/help` is always available for command reference
- Low-memory mode auto-detects and disables animations

---

## Examples

### Debug an error

```bash
/debug "TypeError: Cannot read property 'id' of undefined at Object.handleRequest"
```

### Review code

```bash
/review src/api/routes.ts
```

### Refactor with AI

```bash
/refactor src/utils.ts extract date formatting into a separate module
```

### Multi-step task

```bash
/goal Create a REST API for a todo app with CRUD operations
```

### Export conversation as documentation

```bash
/export md
```

---

## Configuration

Configuration file: `~/.ys-code-agent/config.json`

```bash
# View full config
/config

# Change theme
/theme matrix

# List available providers
/provider

# Switch models
/provider openrouter
/model
/models
```

---

## Tips & Best Practices

1. **Start with a plan**: Use `/plan` before complex tasks
2. **Use memory**: `/remember` saves preferences across sessions
3. **Run `/init` first**: Creates `.ys/` project folder for persistent context
4. **Monitor tokens**: `/context` shows context usage; use `/compress` when high
5. **Use git**: `/git commit --ai` saves time writing commit messages
6. **Safe mode**: Start with `/approval-mode safe` when learning
7. **YOLO mode**: Only when you trust the agent and want speed
8. **Keyboard > mouse**: Learn shortcuts (Ctrl+L, Ctrl+A, Ctrl+E)
