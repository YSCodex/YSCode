# Security policy

## Reporting a vulnerability
Please **do not** open a public issue for security problems.

Use GitHub's private reporting: open the **Security** tab of https://github.com/YSCodex/YSCode and choose **Report a vulnerability**.
Include what you found, how to reproduce it, the app version and your Android version.

We will acknowledge your report, keep you updated, and credit you in the release notes if you wish.

## Supported versions
Only the latest release receives security fixes.

## Scope
In scope: the YSCode app, how it stores provider keys and sign-in tokens, the agent's permission system and the update check.
Out of scope: vulnerabilities in third-party providers, Alpine packages or the Android OS itself, and issues that need a rooted or already-compromised device.

## Secrets
YSCode keeps API keys and sign-in tokens encrypted on the device and sends them only to the provider you chose. If you ever find a key, token
or password in this repository, report it privately in the same way so it can be revoked.
