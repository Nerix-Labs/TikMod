# TikMod

An LSPosed module that adds the features TikTok leaves out: clean downloads, live-stream recording,
message privacy and a quieter feed.

Made by **Nerix Labs**.

[עברית למטה](#בעברית)

---

## Features

**Downloads**
- Videos without a watermark, picked from every available rendition (best quality first, H.265 or H.264)
- Photo carousels in original resolution, optionally converted to JPEG
- Original sound or the video's audio track as a separate file
- HD cover frame and full-size profile picture
- Smart share: send the actual file (or file + link) to WhatsApp, Telegram etc. instead of just a link
- Custom file-name patterns, per-creator folders, duplicate detection and a download history

**LIVE**
- Record any live stream from the share sheet into a standard MP4 that plays everywhere
- Auto-record: follow channels and recording starts by itself when they go live, even when TikTok is closed
- Recordings run in TikMod's own foreground service, so they keep going after TikTok is swiped away

**Chat**
- Read receipts: normal, never, or only after you reply in that chat
- Hide the typing indicator and online status
- Deleted messages stay visible, marked with a small trash sign; deleted media can be saved

**Feed**
- Removes feed, splash and comment ads
- Optional filters for LIVE previews, shop videos, paid partnerships and photo posts

## Requirements

- Rooted Android 7.0+ device with [LSPosed](https://github.com/LSPosed/LSPosed)
- TikTok `com.zhiliaoapp.musically` — developed and tested on **47.7.3**

TikTok's code is obfuscated and class names change between versions. Most hooks locate their targets by
signature, but a few are pinned to 47.7.3; other versions may need small adjustments.

## Installation

1. Download the APK from [Releases](../../releases) and install it.
2. Enable **TikMod** in LSPosed Manager and tick TikTok in its scope.
3. Force-stop TikTok and open it again.
4. Open the TikMod app to configure everything. Changes reach TikTok instantly.

## Building

```bash
git clone https://github.com/neriyaomesi/TikMod.git
cd TikMod
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/`. JDK 17+ and the Android SDK are required.

## How it works

| Part | Where |
|------|-------|
| Hook entry and registration | `HookEntry.kt`, `ModuleManager.kt` |
| Settings (single registry, shared with TikTok through a ContentProvider) | `Prefs.kt`, `TikModProvider.kt` |
| Media resolution and download engine | `utils/MediaResolver.kt`, `utils/TikModDownloader.kt` |
| LIVE: FLV reader, timestamp rebasing and a small MP4 muxer | `utils/FlvRemuxer.kt` |
| Auto-recorder service | `live/LiveWatchService.kt`, `utils/LiveApi.kt` |
| Chat privacy and deleted messages | `hooks/ChatPrivacyHook.kt`, `hooks/AntiRecallHook.kt`, `hooks/DeletedBubble.kt` |
| Feed cleaning | `hooks/AdsHook.kt` |
| Settings app (Jetpack Compose, Material 3) | `MainActivity.kt`, `ui/` |

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).
All changes go through pull requests and are reviewed before they are merged; releases are published by the
maintainer only.

## Disclaimer

TikMod is an independent project and is not affiliated with, endorsed by or connected to TikTok or ByteDance.
It is meant for personal use. Respect creators: don't re-upload their content without permission.
Modifying apps may violate their terms of service — use at your own risk.

## License

[GPL-3.0](LICENSE) © Nerix Labs

---

<div dir="rtl">

## בעברית

**TikMod** הוא מודול LSPosed לטיקטוק, מבית **Nerix Labs**.

- **הורדות:** סרטונים בלי סימן מים באיכות הגבוהה ביותר, קרוסלות תמונות, שמע, קאבר ותמונת פרופיל.
- **שיתוף חכם:** שליחת הקובץ עצמו לוואטסאפ ולטלגרם במקום קישור בלבד.
- **לייב:** הקלטת שידורים חיים ל-MP4 תקין, כולל הקלטה אוטומטית של ערוצים במעקב, שממשיכה גם כשטיקטוק סגור.
- **צ'אט:** "נצפה" רק אחרי שעונים, הסתרת "מקליד/ה" ו"פעיל/ה עכשיו", והודעות שנמחקו נשארות עם סימן פח קטן.
- **פיד:** חסימת פרסומות וסינון שידורים, סרטוני קניות ופוסטי תמונות.

**התקנה:** מתקינים את ה-APK מעמוד ה-Releases, מפעילים ב-LSPosed עם טיקטוק בטווח, ומפעילים מחדש את טיקטוק.
נבדק על טיקטוק 47.7.3.

</div>
