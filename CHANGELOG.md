# Changelog

All notable changes to TikMod. Versions follow [semantic versioning](https://semver.org).

## 4.2.2 — 2026-11-18

### Fixed
- **"Couldn't reach GitHub" when checking for updates.** The updater used GitHub's API, which allows only 60
  anonymous requests per hour per IP address (often shared between many people). It now reads a release index
  from the repository (`updates.json`), which has no such limit, and only falls back to the API.
- The background update check runs at most once an hour.

## 4.2.1 — 2026-11-18

### Changed
- **Recordings continue after a restart.** When TikMod's process is replaced mid-recording (module update, crash,
  low memory) and the channel is still live, the recording continues into the same file: a torn tag at the end is
  dropped and the clock carries on, so the result is one recording. If the channel is no longer live, what was
  recorded is saved as before.

### Fixed
- **Repeated "verify you're human" checks in LIVE, and subscriptions failing.** Two leftovers from the first
  version were removed: a region override that reported a US SIM by default with no setting on screen, and a
  hook that rewrote fields in every JSON payload the app parsed. Existing installs are reset to "no region override".

## 4.2.0 — 2026-11-18

### Added
- **LIVE history.** The streams you entered in TikTok, listed in the Live screen and stored on the phone only.
  A stream counts once the LIVE room screen has been playing it for a few seconds, so previews and preloaded
  rooms are ignored. Any channel can be added to auto-recording from the list.
- **Don't report views** (experimental, off by default). Per-video play reports are not sent.

### Changed
- **"Active now" is hidden at the HTTP layer.** Only your own status report is blocked; other people's status
  still loads.

### Notes
- Hiding that you entered a LIVE room was researched and is not possible from Java hooks: the room only opens
  with a valid answer to the enter request, which is also what registers the viewer.

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
