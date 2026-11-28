# Changelog

## 0.0.4
- **Agent browser:** the agent has its own headless Chromium browser (`browser` tool) to test web apps on localhost and read JavaScript pages: open, snapshot (numbered elements), click, type, tap, drag, key, hover, scroll, wait, eval, html, network, console, viewport (mobile, tablet, desktop), screenshot, reload, back, close.
- **Screenshots you can see:** every browser screenshot is saved in your project (`screenshots/`) and shown in the chat. Tap it to enlarge, save to Gallery or share. Options: full page, one element, numbered boxes that match the snapshot indexes.
- **Safe testing:** dry-run mode blocks form submits and POST/PUT/DELETE requests and lists what would have been sent; `forms`, `download_check` (status, size, SHA-256, APK signature; nothing is saved or installed) and `audit` (broken links, redirects, SEO notes).
- **Better memory:** new `recall` tool searches the full chat history (and other chats of the project), so the agent finds exact earlier details instead of asking again. Compaction now keeps the previous summary in full, uses a structured summary, lists touched files and commands, and trims old messages less. The agent saves your preferences and decisions to memory right away.
- Thinking card: Codex reasoning is formatted text, summary parts are separated, and a one-line hint shows what the agent was thinking.

## 0.0.3
- Fix the "JsonArray is not a JsonPrimitive" error with some OpenAI-compatible providers (list or object content, reasoning and tool arguments).
- Malformed streaming chunks from a provider are skipped instead of stopping the reply.

## 0.0.2
- Sign in with your ChatGPT plan (Codex): device-code login, several accounts, model list and usage bars. No API key needed.
- Antigravity provider: Google's official Antigravity CLI runs inside YSCode's Linux, with live tool cards while it works.
- In-app preview: the globe button in chat opens your local site (localhost); static sites are served automatically.
- Smarter agent: higher step limits, softer budget nudges, fewer false loop stops.
- More reliable Linux setup: git and ripgrep are installed automatically if missing; the harmless apk "failed to write database" warning no longer fails installs.
- Fixes: crash when opening a project, setup Continue button during install, longer model names in the chip.
- Polish: compact tool cards, accent navigation bar, cleaner activity heatmap (light and dark), one-line message box hint.

## 0.0.1
First public release.
- AI coding agent with a real Linux environment (Alpine, Git, Python, Node) running inside the app. No account, no cloud backend.
- Works with OpenAI-compatible, Anthropic and Gemini providers using your own API key.
- Skills and MCP servers installable from GitHub, plus image viewing for vision models.
- Local folder projects stay in sync with the folder you picked.
- Open real device folders in place from your file manager (/sdcard works inside Linux), toolchain registry (JDK/Node on PATH), on-device APK analysis skill, and a Permissions screen.
- Update checks, optional anonymous usage stats (off by default) and in-app feedback.

## 1.6.0
- Vision for the agent: new `view_image` tool (and `read_file` on images) attaches the picture to the model (EXIF-rotated, downscaled, optional crop). Binary files give a clear error instead of garbage. Needs a model marked "Vision".
- Local folders are now linked: after you pick a folder, the agent's edits, new files and deletions are written back to it, and outside changes are pulled in (Files > Refresh syncs on demand). .git, node_modules and build folders are skipped.
- Step budget nudges at 12/24/40 steps and capability-honesty rules stop expensive workaround rabbit holes on small tasks.
- Agent knows apk's harmless "failed to write database" warning and the Alpine OCR language package.

## 1.5.1
- `pkg status` and the diagnostic report now show whether hardlinks are real or emulated on this phone.
- Agent no longer wastes tokens on hardlink-identity errors when Android blocks real link(); it registers MCP servers directly instead.

## 1.5.0
- Linux: PRoot now auto-detects REAL hardlink support (no more fake `.proot.l2s` placeholders that broke tools such as REA/git identity checks). Existing installs re-probe once on first launch.
- Work log: new "Log" button in chat shows every file changed, command run, skill and MCP tool used in the whole session (including work before context compaction).
- Token counter is now compact: 950, 12.5k, 100k, 4.5M, 1.2B.
- New built-in slash commands: /review, /security-review, /fix, /explain, /refactor, /docs, /optimize, /pr, /todo, /deps.
- Release assets now include a SHA-256 checksum and these notes.

## 1.4.1
- Context compaction no longer deletes chat history; a marker card is shown instead.
- Long sessions send far fewer tokens (old reasoning, tool outputs and file contents are trimmed in requests only).

## 1.4.0
- Output-limit / empty / broken-stream recovery with auto-continue; cut-off tool calls are never executed.
- Friendly provider error messages; adaptive max_tokens; reactive compaction.

## 1.3.x
- Diagnostics report, local crash capture, graceful fallback when PRoot is incompatible.

## 1.2.x
- Real MCP (stdio/HTTP), GitHub plugin/skill installer, Alpine edge (Node 24), loop guard.
