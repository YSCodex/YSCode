# YSCode

**An AI coding agent that runs entirely on your Android phone.**
Real Linux shell (Alpine), Git, Python, Node, MCP servers and skills, with your own API key. No account and no cloud backend.

- Website and downloads: see the project website (set up from `cloudflare/`)
- Latest release: [Releases](https://github.com/YSCodex/YSCode/releases)
- Problems or ideas: [open an issue](https://github.com/YSCodex/YSCode/issues/new/choose)

> This repository hosts the public releases, website and backend of YSCode. The app itself is distributed as a free
> binary and is **not open source**.

## Install
1. Download the latest `YSCode-<version>.apk` from [Releases](https://github.com/YSCodex/YSCode/releases).
2. Allow *Install unknown apps* for your browser or file manager, then open the APK.
3. Verify the download (optional): `sha256sum YSCode-*.apk` must match the `.sha256` file.

Requirements: Android 7.0+, 64-bit ARM (arm64).

## Repository layout
| Path | What |
|---|---|
| `latest.json` | Release manifest read by the app and website (written by the release pipeline) |
| `config.json` | Backend URL read by the app (written by the deploy workflow) |
| `cloudflare/` | Website (`site/`) and Worker (`src/`) with D1: updates, downloads, opt-in stats, feedback, admin dashboard |
| `.github/workflows/deploy.yml` | Deploys `cloudflare/` to Cloudflare |
| `PRIVACY.md`, `TERMS.md`, `THIRD_PARTY_NOTICES.md` | Legal |

See [SETUP.md](SETUP.md) for the one-time Cloudflare and release setup.
