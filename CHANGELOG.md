# Changelog

All notable changes to TikMod. Versions follow [semantic versioning](https://semver.org).

## 4.10.1 — 2026-11-23 (HotFix)

### Fixed
- **After an update, TikTok kept running the old TikMod.** The old code then read its texts by id from the new APK,
  where the ids had moved: notifications said "System" instead of "Downloading", and downloads reported "No video
  found". The cleanup after an update couldn't fix it (`su` isn't granted to TikMod, and `killBackgroundProcesses`
  can't stop other apps since Android 14). Now TikTok notices it's running an older TikMod than the one installed
  (version code, or the APK it loaded is gone) the next time it comes to the front, and restarts itself once — it
  reopens by itself in a fresh process with the new version.

### Notes
- The restart lives in the new code, so the update *to* 4.10.1 still needs TikTok swiped away once by hand; every
  update after that is automatic. Checked on a phone: an update while TikTok was open, then TikTok brought back to
  the front, restarted itself and came back with the hooks loaded; downloads and their notifications are correct.

## 4.10.0 — 2026-11-23

### Added
- **A whole new look, in four themes that really differ.** **Neon** (default): true black, electric cyan / magenta /
  violet, cards that glow in their colors, gradient headlines, a synthwave floor grid rolling at the bottom, and a
  glowing capsule for the current tab. **Studio**: the same layout, calm — deep ink, iris / rose / lavender, a faint
  dot grid. **You** (Material You) and **System** (Studio, light or dark like the phone). Everywhere: editorial
  headlines with small-caps overlines, compact quick toggles with mini switches, a docked bottom bar that names every
  section.
- **A control center instead of lists.** Home is a grid of live tiles: LIVE (pulses and shows the stream while
  recording, with Watch), Stories and Watch history as mosaics of the latest covers, ads blocked and downloads as big
  numbers, dynamic stop and chat privacy — each with its own switch, each opening its section. Every tab starts with
  tiles too: tap a switch tile anywhere to flip it (it dims when off), tap a choice tile to step to the next option
  (quality, codec, share mode, folder, LIVE quality, check interval, read receipts, playback speed). Settings sit
  behind a gear on Home; the rarer options are in collapsed sections below the tiles.
- **Search every setting from Home.** Type a word in any of the 10 languages: matching settings show up with their
  switch, flip them right there, or jump to where they live. Round shortcuts below it (watch history, stories, record
  LIVE, stop the addiction, downloads, chat).
- **Feels better everywhere.** Every switch, tile and button sinks a little and ticks under your finger; one tab
  indicator slides between tabs; a compact title bar appears once a page's big title scrolls away (tap it to jump
  back up); in Neon the hero cards' gradient edge slowly travels around them.
- **Dynamic stop the addiction.** Always on, nothing to start: free scrolling for the first minutes of a session
  (5–30), then the feed calms down step by step until your full dial (over 15–60 minutes); a 10-minute break resets
  it, and between 23:00 and 05:00 it kicks in twice as fast. A small chart shows the curve; it's on Home, in Feed and
  in Stop the addiction → Automatic.
- **Tap a channel to open it in TikTok**: a followed channel opens its LIVE when it's live, its profile otherwise;
  auto-save channels and archived stories open the profile too.
- **Story archive as a grid** of 9:16 tiles (three per row, like a profile), at the top of the Stories tab: cover,
  name, time, a 🗑 mark on deleted ones; tap to open, ⋮ or long-press for save / share / profile / remove.
- **Home shows your day.** A greeting, a "Today" card (minutes watched in the feed as a ring, videos, creators,
  stories kept, deletions caught) and, while something is recording, a "Recording now" card.
- **Stories have their own tab**, with archive / deleted / creator counts at the top (it used to live inside
  Downloads).
- **Watch what you record.** Every stream being recorded (Home and LIVE) and every followed channel that's live has a
  Watch button that opens that LIVE in TikTok; tapping a live channel's row does the same.
- **Watch history.** Every video and photo post the feed puts on screen (`VideoViewCell.onPageSelected`, the same
  target "Stop the addiction" learns from) is kept on the phone (Downloads → Watch history): author, caption, a small
  cover, how many times you saw it and how long it was on screen. Search by name, @handle or caption, grouped by day;
  tap to open it again in TikTok, or copy / share the link. Ads, stories and LIVE previews are left out. The newest
  1,000 are kept; one switch turns it off.
- **Know when a story is deleted.** When a story you have in the archive gets deleted, a notification says who
  deleted it (one per creator, with the cover when TikMod has it); tapping it opens the archive. On by default, with
  its own switch in Stories.
- **Story archive by creator.** Chips with every creator in the archive, how many of their stories are kept and how
  many were deleted; tap one to see only theirs.
- **Search the download history**, and filter it by kind (videos, photos, audio, covers, profile pictures, LIVE).

### Notes
- **Beta.** Watch history was checked in TikTok 47.9.2 on a phone (posts, covers and time on screen recorded; the
  feed ad left out), and the new look on the phone in Hebrew. Notifications, the archive by creator and download
  search were tested on an emulator with TikTok's calls played over adb (debug builds only accept them from the
  shell). The Watch button was tried on a stream being recorded and opened that channel's LIVE in TikTok. Dynamic
  mode was checked in the settings only, not over a long session.

## 4.9.0 — 2026-11-22

### Added
- **Stories: download.** The TikMod card in a story's share sheet now says "Download story" and saves it without a
  watermark (video or photos).
- **Story archive with deleted stories marked.** Every story you watch is handed to TikMod and kept in its own
  storage for 30 days (Downloads → Stories). When TikTok loads a creator's complete, fresh story list and a story
  you archived is missing before its 24 hours are up, it's marked 🗑 deleted (kept 90 days). Open, share, save to
  the gallery or remove each one.
- **Auto-save stories** of chosen channels to the gallery (Movies / Pictures › TikMod › Stories) as soon as TikTok
  shows them.
- **Watch stories anonymously**: blocks `tiktok/story/view/report/v1` (IStoryApi.reportStoryViewed), so you're not
  on the story's viewer list. The story is archived before the report is dropped.
- **Hide my profile visits**: blocks `tiktok/user/profile/view_record/add/v1` (IProfileViewerApi.reportView), so you
  don't appear in other people's "Profile views". Seeing who visited you still works.

### Fixed
- "Don't report views" missed TikTok's second play report, `aweme/v1/fast/stats/` (awemeFastStatsReport). Both are
  blocked now.

### Notes
- **Beta.** Checked on a phone with TikTok 47.9.2: story download, the archive (video and photo stories), anonymous
  story viewing and hidden profile visits work. The 🗑 mark and auto-save haven't been seen live yet, and 47.9.2
  sent no `aweme/v1/fast/stats/` report to block. Problems: About → Report a bug.
- Targets were found in TikTok 47.1.4's Retrofit interfaces and Gson models (`UserStory.getStories`,
  `Aweme.getIsTikTokStory`, `Story.setViewed`), whose names aren't obfuscated.

## 4.8.0 — 2026-11-22

### Added
- **Private accounts you follow, auto-recorded.** Their stream is only given to TikTok signed in as you, never to the
  web lookup the watcher uses. Now, when TikTok loads the LIVE of a channel on your auto-record list — in the feed,
  the Following LIVE bar or when you open it — the stream is handed to TikMod and recording starts by itself, with
  the same rules (a stopped recording stays stopped, parallel limit, free space). When the watcher sees a private
  account go live, a notification opens it in TikTok with one tap. Reconnects reuse the addresses TikTok loaded.
- **Stop one recording, keep the others.** The record button in a LIVE now records or stops *that* stream only
  (it used to stop every recording). Other streams being recorded are listed under it, each with its own ⏹.
- **Quality per channel.** Each channel in the auto-record list has a quality chip: Auto (the global setting),
  Source, 1080p, 720p or 480p.
- **Report a bug** (About): versions, the hooks that went in on the last TikTok start, how every obfuscated target
  was found, and the recent log — to the share sheet. Nothing is sent by itself.
- **`tools/check_resolver.py`**: restarts TikTok on a connected phone and reports which hooks and obfuscated targets
  were found (`--force` tests the DexKit searches themselves), so a new TikTok version can be checked before users
  notice.
- Live captions: when both Google web endpoints fail, ML Kit translates on the phone (the model for a language pair
  downloads once in the background).

### Security
- TikMod's provider answered any app on the phone (settings, chat lines, Focus profile, starting recordings). It now
  answers only TikTok and TikMod. The exported recorder service accepts start/stop commands only with a random token
  that TikMod shares with TikTok through the provider.
- In-app updates check the APK against the `sha256` in `updates.json` before installing it.

### Changed
- APK: the launcher icon was one 465×401 PNG copied into every density (2.3 MB); it's now sized per density
  (0.2 MB). Release builds are shrunk with R8 (TikMod's own classes are kept whole).
- The "private account is live" notification isn't repeated after the service restarts.

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
