# Changelog

All notable changes to TikMod. Versions follow [semantic versioning](https://semver.org).

## 4.7.0 — 2026-11-20

### Added
- **Stop the addiction.** A dial for how much of your feed shouldn't hook you, from 0 to 100% (at 90%, about
  9 posts in 10 are ordinary rather than the kind you can't stop watching). Apply it at once, or let it build
  up gradually over 5–60 minutes. Each post gets an attraction score from what you tend to watch to the end,
  creators you follow, how viral it is and how short. TikMod learns from how long you stay on each post, and
  that profile never leaves the phone. Start it for 15 minutes to 3 hours, or with no limit, from TikMod or
  from a Quick Settings tile; TikTok shows a short note when it turns on or off. The screen has swipeable
  tabs: Mix (the dial with a live 10-post preview), Topics (topics that always pass, an "only these topics"
  mode, blocked words), Activity (every post checked: shown or hidden, and why), Learned (what hooks you and
  what you skip, with a reset) and Automatic (focus hours, bedtime, session limit).

### Fixed
- **Ads inserted on the phone.** Besides the ads in feed pages, ads TikTok adds to the feed on the phone
  afterwards ("ad re-ranking") are now removed, and paid partnerships are detected more reliably.
- **Progress bar on every video.** On TikTok 47.9 the seek bar was hidden on many videos (short clips
  especially); it shows on every video again when the setting is on.
- **"Verify you're not a robot" in LIVE.** Live captions opened a second stream connection too quickly and too
  often, which triggered TikTok's robot checks. Connections are now spaced out and limited, and the
  auto-LIVE watcher checks channels more gently.

## 4.6.0 — 2026-11-19

### Added
- **The chat inside your LIVE recordings.** Record a stream while you watch it in TikTok and the comments are
  burned into the video, the way TikTok shows them — newest at the bottom-left, names in colour, emoji,
  Hebrew and Arabic right-to-left — each appearing at the moment it was written. After the recording ends
  TikMod re-renders it once (about 2–3× faster than real time on a recent phone); if anything fails the
  recording is saved without the chat, exactly as before. Setting: Live → Record the live chat → "Show the
  chat in recordings" (on by default).
- **Gifts in your recordings.** Gifts sent during the stream appear as TikTok-style banners right above the
  chat — the sender, "sent X", the gift's picture and the combo count (a whole combo is one banner with its
  final count). Three levels under "Gifts in recordings": all, animated only (gifts with a full-screen effect,
  not small ones like Rose), or none.
- **Gift animations in your recordings.** A gift's full-screen animation plays in the recording at the moment
  it was sent, with its transparency, one after another like in TikTok. TikMod uses the same animation file
  TikTok plays (downloaded once and kept). Gifts that TikTok renders as live 3D / face effects can't be
  reproduced and appear as a banner only.

### Fixed
- **Notifications that contradicted each other.** A recording now has one notification from start to finish:
  recording → saving / adding the chat → saved. Previously the save added a second notification, the
  "recording" notification of minimal mode was never removed, and the chat transcript posted its own
  "LIVE saved • 1 KB" notification and toast from TikTok. The transcript is now saved quietly.
- **A recording you stopped started again by itself.** Stopping an auto-recorded channel keeps it stopped
  until that stream ends — it was only remembered in memory, so any restart of TikMod (an update, the system
  closing it) started recording it again. The watcher's notification also says plainly that it *will* record
  when a channel goes live, instead of reading as if it were recording.

### Changed
- Chat is matched to the exact stream by its room id, so multi-guest and music lives no longer mix rooms.
- The text transcript is saved once when you leave the live (not every time you open the gift panel or start
  a recording).

## 4.5.1 — 2026-11-19

### Fixed
- **Live chat recording saved nothing.** The chat was captured through the message pipeline's typed listener,
  which didn't deliver on recent TikTok builds, so no transcript was ever written. It now taps the chat
  reliably (through the live SDK's message interceptor and the chat view itself) and writes one transcript per
  stream to **Download/TikMod/** when you leave the live. Multi-guest / music lives no longer produce a pile of
  fragment files.

## 4.5.0 — 2026-11-19

Tested and working on TikTok 47.9.1 (the obfuscated hooks re-resolve themselves on each TikTok update).

### Added — 10 general updates
- **Feed: filter by keywords** — hide posts whose caption or hashtags contain your words.
- **Feed: mute creators** — hide posts from a list of @handles.
- **Feed: hide reposts** reposted into your feed by people you follow.
- **Feed: hide by length** — hide videos shorter than / longer than N seconds.
- **Feed: hide suggestion cards** — the "suggested accounts" and search cards mixed into the feed.
- **Playback speed control** (0.25×–3×) with a proper setting.
- **Keep videos muted** (start videos without sound).
- **Keep the screen on** while TikTok is open.
- **Disable vibration** (haptic feedback) across the app.
- **Open links in your browser** — outside links skip TikTok's in-app browser (internal pages stay in-app).
- **Progress bar on every video** — a seek bar on all videos, on by default, now with its own setting.

## 4.4.0 — 2026-11-19

### Added
- **Record the live chat.** While you watch a stream, its chat is collected and saved as a text file in
  Downloads when you leave. Turn it on in Live → Record the live chat. (Only works while TikTok is open on the
  stream; the background auto-recorder can't see chat.) TikMod reads the chat through the live SDK's own
  message listener, so your own chat is never affected.
- **Move the captions anywhere.** Drag the bubble to any spot on screen (not just up and down).
- **Pinch to resize the captions** live, with a wider size range than the presets.
- **Double-tap to pause a caption** so you can read it; double-tap again to resume.
- **Caption background:** solid, dimmed or none.
- **High-contrast caption text** (a heavier outline for bright streams).
- **Keep captions on screen** option (no fading out between sentences).
- **Lines on screen** setting (1–4) for the current sentence.
- **Reset position & size** from the quick menu.
- Caption position and size are remembered separately for portrait and landscape.

## 4.3.0 — 2026-11-18

### Added
- **Live captions with translation.** Translated subtitles over the LIVE stream you're watching. TikMod pulls the
  stream's audio (the audio-only variant when the room has one), runs it through Android's on-device speech
  recognizer and translates the text. The caption bubble can be dragged, tapped for a quick menu and held to
  hide it for that stream.
  - Sentence by sentence: the recognizer's running text is cut into finished sentences (with punctuation), each
    translated once. The previous sentence stays above the one being spoken.
  - Quick menu on the stream: spoken language, caption language, text size, original sentence, copy the
    transcript, hide, turn off.
  - Quiet streams are raised to a normal speaking level before recognition, and the channel name and stream
    title are given to the recognizer as vocabulary hints.
  - Translation falls back to a second endpoint when the first one fails or rate-limits.
  - Spoken language: automatic (remembers the language picked for each channel and follows the recognizer's
    language detection once it is consistent) or a fixed one. Speech packs are downloaded from the app.
  - Caption language: the app's language by default, or any of the 10 supported ones.
  - Needs Android 13+. Which speech languages exist depends on the phone's recognizer.
  - Speech is recognized on the phone; only the recognized text is sent to Google Translate.

### Fixed
- **LIVE history stayed empty.** The stream on screen is now taken from TikTok's own "enter room" request
  instead of guessing from the player, which also makes it follow swipes to the next stream.

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
