# Contributing to TikMod

Thanks for taking the time to help.

## Reporting a bug

Open an issue and include:

- TikTok version (Settings → About) and TikMod version
- Android version, device, and LSPosed version
- What you did, what you expected, and what happened
- The relevant lines from the LSPosed log (filter by `TikMod`)

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on one change.
3. Build with `./gradlew assembleDebug` and test on a real device before opening the PR.
4. Describe what changed and which TikTok version you tested on.

The `main` branch is protected: every change is reviewed and merged by the maintainer, and only the maintainer
publishes releases.

## Code style

- Kotlin, 4-space indentation, standard Kotlin conventions.
- Every new setting is declared once in `Prefs.SPECS`.
- Hooks should find their targets by signature where possible rather than by obfuscated class names.
- Hooks must fail quietly: a missing class or method must never crash TikTok.

By contributing you agree that your contributions are licensed under the GPL-3.0.
