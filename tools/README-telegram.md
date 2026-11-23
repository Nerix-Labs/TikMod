# Telegram release announcer

A cloud publisher for the TikMod channel. A GitHub Actions workflow uploads a release's APK and posts a
formatted announcement — expandable "what's new" quote, inline buttons and (optional) premium emoji —
**only when you trigger it by hand**, so nothing is ever posted automatically.

## One-time setup

1. **Create the bot** — message [@BotFather](https://t.me/BotFather) → `/newbot` → copy the token.
2. **Add the bot to your channel as an admin** with the *Post messages* permission.
3. **Find the channel id** — `@yourchannel` for a public channel, or the `-100…` id for a private one
   (forward a channel post to [@RawDataBot](https://t.me/RawDataBot) and read `forward_from_chat.id`).
4. **Add two repo secrets** — GitHub → the repo → *Settings → Secrets and variables → Actions → New
   repository secret*:
   - `TELEGRAM_BOT_TOKEN` — the BotFather token
   - `TELEGRAM_CHAT_ID` — `@yourchannel` or the `-100…` id

The token lives only in GitHub Secrets — never in the code or anywhere else.

## Posting a release

1. Publish the GitHub release (with the `TikMod-<version>.apk` asset) as usual.
2. Add `tools/announce/<version>.json` for that version (copy an existing one; English block first, then
   Hebrew). Each bullet is `{ "icon": {"e":"🧹","id":""}, "text": "…" }`.
3. GitHub → **Actions → "Announce release on Telegram" → Run workflow**, enter the version and type `SEND`.

That manual run is the explicit approval — the bot sends nothing otherwise.

## One message

The post always goes out as **one message**: the APK with the whole post as its caption, plus the buttons.
Telegram limits a bot's caption to 1024 visible characters; `--dry-run` prints the length. If a post is longer,
the one-line summaries and then the last bullets are dropped (and listed in the log) until it fits — so keep
posts short (about 6 short bullets per language fits comfortably).

## Premium (custom) emoji

**The easy way — packs by name.** Put public emoji packs in `tools/announce/emoji_packs.json`, by the name at the
end of their link (`t.me/addemoji/<name>`):

```json
["MoonEmoji", "Decoration_Pack"]
```

When the bot posts, it loads those packs (`getStickerSet`) and every plain emoji in the post that a pack has (🚀,
🔥, ✨…) is sent as that pack's premium version. Earlier packs win. Nothing else to change in the posts. Packs to
browse: Telegram's catalog at [tg.me/g/emoji](https://tg.me/g/emoji) and community lists such as
[@CustomEmojiPacks](https://t.me/s/CustomEmojiPacks).

**One specific emoji:** put its id in `"id"` (keep `"e"` as the fallback):
`{ "icon": { "e": "🚀", "id": "5368324170671202286" }, "text": "…" }`. To get an id, send the emoji to
[@RawDataBot](https://t.me/RawDataBot) and copy `custom_emoji_id`.

**Telegram's rule for bots:** a bot may send premium emoji only in private chats, groups and supergroups when
its owner has Premium — **not in channels** — unless the bot has a username bought on Fragment
([Bot API, MessageEntity](https://core.telegram.org/bots/api#messageentity)). In a channel the post still goes
out: if Telegram refuses the premium emoji, the script resends the same single message with the plain ones.

## Local preview

```bash
python tools/telegram_announce.py 4.5.0 --dry-run
```

Prints the rendered caption and buttons without sending anything.
