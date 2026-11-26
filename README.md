# YSCode

**An AI coding agent that runs entirely on your Android phone.**
A real Linux shell (Alpine via PRoot), Git, Python, Node, MCP servers and skills. Bring your own API key, your ChatGPT plan or Google Antigravity.
No account and no cloud backend.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

- Website and download: https://yscode.yscodex.workers.dev
- Releases (signed APK + SHA-256): https://github.com/YSCodex/YSCode/releases
- Bugs and ideas: [open an issue](https://github.com/YSCodex/YSCode/issues/new/choose)

## Install

Most people should install the official APK from the website or [Releases](https://github.com/YSCodex/YSCode/releases).

1. Download `YSCode-<version>.apk`.
2. Allow *Install unknown apps* for your browser or file manager, then open the APK.
3. Verify it (optional): `sha256sum YSCode-*.apk` must match the `.sha256` file.

Requirements: Android 7.0+ and a 64-bit ARM (arm64) device. The first launch downloads about 170 MB of Linux packages.

## Build from source

You need a JDK (17 or newer, CI uses 21), the Android SDK (platform 36) and [Gradle](https://gradle.org/) 9.3.1.

```sh
git clone https://github.com/YSCodex/YSCode.git
cd YSCode
gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

No secrets are needed. `assembleDebug` signs with the default Android debug key. `assembleRelease` produces an unsigned APK unless
you provide your own keystore through the environment variables `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

Notes:
- Your build will be signed with your own key, so it cannot update (or be updated by) the official app. Uninstall the official app first.
- The bundled PRoot binaries are arm64 only (`app/src/main/jniLibs/arm64-v8a`).
- The official release pipeline, signing key, website and update server are **not** part of this repository.

## Project layout

| Path | What is in it |
|---|---|
| `app/src/main/java/com/example/agent` | The agent loop, tool calling, permissions and budgets |
| `app/src/main/java/com/example/llm` | Providers: OpenAI-compatible, Anthropic, Gemini, ChatGPT plan (Codex), Antigravity |
| `app/src/main/java/com/example/linux` | Alpine rootfs, PRoot runner, built-in shell commands |
| `app/src/main/java/com/example/tools` | Tools the agent can call (files, search, git, web, tasks, ...) |
| `app/src/main/java/com/example/skills`, `mcp` | Skills and MCP servers |
| `app/src/main/java/com/example/ui` | Jetpack Compose screens and theme |

## Contributing

Bug reports, fixes and ideas are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first. Security problems: see [SECURITY.md](SECURITY.md).
Everyone taking part must follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

YSCode is free software under the **GNU General Public License v3.0 or later** (`GPL-3.0-or-later`). See [LICENSE](LICENSE).

- The name "YSCode" and the logo are not covered by the GPL. See [TRADEMARK.md](TRADEMARK.md).
- Bundled and downloaded third-party software keeps its own license. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Privacy: [PRIVACY.md](PRIVACY.md). Terms of use: [TERMS.md](TERMS.md).
