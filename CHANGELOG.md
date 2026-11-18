# Changelog

All notable changes to TikMod. Versions follow [semantic versioning](https://semver.org).

## 4.1.0 — 2026-11-18

### Added
- **10 languages.** TikMod and its card inside TikTok follow the phone's language: English, Hebrew, Arabic,
  Chinese, French, Spanish, Russian, German, Portuguese and Turkish. Unsupported languages fall back to English.
- **Record now.** Start recording any live stream instantly from the Live screen, followed or not.
- **Parallel recordings.** Record several streams at once, with a live panel showing time and size for each and a
  "stop and save all" button. Optional limit on parallel recordings.
- **Language picker.** Choose the app language manually (Home → System), or keep following the phone.
- **In-app updates.** Check for updates, read what changed in every version and update straight from the app.
  A "What's new" sheet opens once after each update.

### Changed
- **Ready for TikTok updates.** Hooks that relied on obfuscated class names now locate their targets by structure
  (readable method names, parameter and field types) using DexKit, across all of TikTok's split APKs. Results are
  cached per TikTok build, so the search runs once per update.
- **Storage guard.** A recording only starts with at least 1 GB free and stops and saves itself before storage runs out.
- Interrupted recordings (process killed, module updated) are recovered and saved automatically, and a channel that
  was being recorded resumes when the service restarts.

- **New entry in TikTok's settings.** A TikMod card in the app's own style replaces the old floating label.

### Fixed
- Text was black and hard to read in dark mode on some screens.
- Cards in the System area of the home screen overlapped each other.

## 4.0.0 — 2026-11-17

First public release.

- Watermark-free downloads in the best available quality, photo carousels, original sound, HD cover and profile picture
- Smart share: send the actual file (or file + link) instead of just a link
- LIVE recording to a standard MP4, and auto-recording of followed channels in a background service
- Chat privacy: "seen" only after replying, hidden typing indicator and online status
- Deleted messages stay visible, marked with a small trash sign
- Feed cleaning: ad blocking and optional filters for LIVE, shop and photo posts
- Settings app rebuilt with Jetpack Compose and Material 3
