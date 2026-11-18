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

## Premium (custom) emoji

You have Telegram Premium, so custom animated emoji render for you. To use one, put its id in a bullet's
`"id"` (keep `"e"` as the normal fallback for non-premium viewers):

```json
{ "icon": { "e": "🚀", "id": "5368324170671202286" }, "text": "…" }
```

To get an id: send a message containing the custom emoji to [@RawDataBot](https://t.me/RawDataBot) and copy
the `custom_emoji_id` from the entity it shows. The bot sends it as a `<tg-emoji>`, so it only animates if the
emoji is accessible to the bot/channel; otherwise viewers see the normal `"e"` emoji.

## Local preview

```bash
python tools/telegram_announce.py 4.5.0 --dry-run
```

Prints the rendered caption and buttons without sending anything.
