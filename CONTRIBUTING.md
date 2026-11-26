# Contributing to YSCode

Thanks for helping. Small, focused changes are easiest to review.

## Before you start
- Search the [issues](https://github.com/YSCodex/YSCode/issues) first. For anything bigger than a small fix, open an issue to discuss it.
- By contributing you agree that your work is released under **GPL-3.0-or-later**, the same license as the project.

## Sign your commits (DCO)
We use the [Developer Certificate of Origin](https://developercertificate.org/). Add a sign-off line to every commit:

```sh
git commit -s -m "Fix crash when opening a project"
```

This certifies that you wrote the change or have the right to submit it under the project license.

## Pull requests
1. Fork the repository and create a branch from `main`.
2. Build and run it: `gradle :app:assembleDebug` (see the README).
3. Keep each pull request to one topic, and describe what changed and how you tested it.
4. Match the existing Kotlin style. New `.kt` files start with `// SPDX-License-Identifier: GPL-3.0-or-later`.

## How changes are merged
The maintainers build and sign releases from a private tree. Accepted pull requests are applied there and appear here with the next
release, keeping your authorship. Review can therefore take a little while.

## Never commit
API keys, tokens, passwords, keystores, personal data, or logs that contain them. If you find a secret in this repository, report it
privately as described in [SECURITY.md](SECURITY.md).

## Not accepted
Code you do not have the right to relicense, copied code with an incompatible license, and changes that add tracking or hidden network calls.
