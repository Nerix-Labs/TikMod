#!/usr/bin/env python3
"""
Announce a TikMod release to the Telegram channel: upload the APK and post a formatted
message with an expandable "what's new" quote, premium (custom) emoji and inline buttons.

Runs in GitHub Actions (see .github/workflows/telegram-announce.yml), only on manual trigger,
so nothing is ever sent without an explicit human go-ahead.

Config (environment):
  TELEGRAM_BOT_TOKEN   the bot token from @BotFather            (GitHub secret)
  TELEGRAM_CHAT_ID     the channel, e.g. @nerixlabs or -100123  (GitHub secret)
  GITHUB_TOKEN         optional, to raise the GitHub API limit  (provided by Actions)
  GITHUB_REPOSITORY    owner/name (provided by Actions; default Nerix-Labs/TikMod)

Usage:
  python tools/telegram_announce.py <version>            # e.g. 4.5.0  -> reads tools/announce/4.5.0.json
  python tools/telegram_announce.py <version> --dry-run  # build and print, send nothing
"""
import io, json, mimetypes, os, re, sys, urllib.error, urllib.parse, urllib.request, uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BMC = "https://buymeacoffee.com/nerixlabs"


def http(url, headers=None, data=None, timeout=300):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


# Premium (custom) emoji, by the plain emoji they stand for, loaded from the packs named in
# tools/announce/emoji_packs.json (see load_packs). Off when PLAIN is set (the fallback send).
PACK: dict = {}
PLAIN = False


def _key(ch: str) -> str:
    return ch.replace("️", "")


def emoji(spec) -> str:
    """A plain emoji, or a premium one: {"e":"🚀","id":"123"}, or {"e":"🚀"} when a loaded pack has a 🚀."""
    if isinstance(spec, str):
        spec = {"e": spec}
    ch = esc(spec["e"])
    cid = spec.get("id") or PACK.get(_key(spec["e"]))
    return f'<tg-emoji emoji-id="{cid}">{ch}</tg-emoji>' if cid and not PLAIN else ch


def load_packs():
    """
    Premium emoji without hunting for ids: list public emoji packs by name (the part after t.me/addemoji/) in
    tools/announce/emoji_packs.json, e.g. ["MoonEmoji", "Decoration_Pack"]. Every plain emoji used in a post
    that one of those packs has is sent as that pack's premium version. Earlier packs win.
    """
    path = os.path.join(ROOT, "tools", "announce", "emoji_packs.json")
    names = json.load(io.open(path, encoding="utf-8")) if os.path.exists(path) else []
    for name in names:
        try:
            res = tg("getStickerSet", {"name": name})
        except SystemExit as e:
            print(f"Emoji pack {name!r} skipped: {e}")
            continue
        n = 0
        for s in res.get("stickers", []):
            cid, ch = s.get("custom_emoji_id"), s.get("emoji")
            if cid and ch and _key(ch) not in PACK:
                PACK[_key(ch)] = cid
                n += 1
        print(f"Emoji pack {name!r}: {n} emoji")


def render(block: dict) -> str:
    """One language block -> HTML lines (title, one-liner, then the bullets as an expandable quote)."""
    out = [f"<b>{emoji(block.get('badge', '🚀'))} {esc(block['title'])}</b>"]
    if block.get("summary"):
        out.append(esc(block["summary"]))
    bullets = block.get("bullets", [])
    if bullets:
        quoted = "\n".join(f"{emoji(b.get('icon', '•')) if isinstance(b, dict) else '•'} "
                           f"{esc(b['text'] if isinstance(b, dict) else b)}" for b in bullets)
        out.append(f"<blockquote expandable>{quoted}</blockquote>")
    return "\n".join(out)


def build(cfg: dict, version: str) -> str:
    parts = [render(cfg["en"])]              # English first
    if cfg.get("he"):
        parts.append(render(cfg["he"]))      # then Hebrew
    if cfg.get("footer"):
        parts.append(esc(cfg["footer"]))
    return "\n\n".join(parts)


LIMIT = 1024  # Telegram's caption limit for bots, counted on the visible text (tags don't count)


def visible(html: str) -> int:
    return len(re.sub(r"<[^>]+>", "", html).replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">"))


def fit(cfg: dict, version: str) -> tuple[str, list[str]]:
    """
    The post as ONE caption under the file: if it's too long, drop the one-line summaries first, then the last
    bullets (in both languages together) until it fits. Returns the caption and what was dropped.
    """
    cfg = json.loads(json.dumps(cfg))
    dropped = []
    cap = build(cfg, version)
    if visible(cap) > LIMIT:
        for lang in ("en", "he"):
            if cfg.get(lang, {}).pop("summary", None): dropped.append(f"{lang} summary")
        cap = build(cfg, version)
    while visible(cap) > LIMIT and any(len(cfg.get(l, {}).get("bullets", [])) > 1 for l in ("en", "he")):
        for lang in ("en", "he"):
            b = cfg.get(lang, {}).get("bullets", [])
            if len(b) > 1:
                last = b.pop()
                dropped.append(f"{lang}: {last['text'] if isinstance(last, dict) else last}")
        cap = build(cfg, version)
    if visible(cap) > LIMIT:
        raise SystemExit(f"Post is {visible(cap)} characters even trimmed; the caption limit is {LIMIT}. Shorten the titles.")
    return cap, dropped


def buttons(cfg: dict, version: str):
    repo = os.environ.get("GITHUB_REPOSITORY", "Nerix-Labs/TikMod")
    rows = cfg.get("buttons") or [
        [{"text": "⬇️ Download", "url": f"https://github.com/{repo}/releases/tag/v{version}"}],
        [{"text": "☕ Buy Me a Coffee", "url": BMC},
         {"text": "⭐ GitHub", "url": f"https://github.com/{repo}"}],
    ]
    return {"inline_keyboard": rows}


def release_apk(version: str):
    """(filename, bytes) of the release's .apk asset, downloaded from the GitHub release."""
    repo = os.environ.get("GITHUB_REPOSITORY", "Nerix-Labs/TikMod")
    headers = {"User-Agent": "TikMod-Announce", "Accept": "application/vnd.github+json"}
    tok = os.environ.get("GITHUB_TOKEN")
    if tok:
        headers["Authorization"] = f"Bearer {tok}"
    rel = json.loads(http(f"https://api.github.com/repos/{repo}/releases/tags/v{version}", headers))
    asset = next((a for a in rel.get("assets", []) if a["name"].lower().endswith(".apk")), None)
    if not asset:
        raise SystemExit(f"No .apk asset on release v{version}")
    data = http(asset["browser_download_url"], {"User-Agent": "TikMod-Announce"})
    return asset["name"], data


def tg(method: str, fields: dict, files: dict | None = None):
    try:
        return _tg(method, fields, files)
    except urllib.error.HTTPError as e:
        # Telegram answers errors with a 4xx and a JSON body; surface it the same way as ok=false.
        body = e.read().decode("utf-8", "replace")
        raise SystemExit(f"Telegram error on {method}: {e.code} {body[:300]}")


def _tg(method: str, fields: dict, files: dict | None = None):
    token = os.environ["TELEGRAM_BOT_TOKEN"]
    url = f"https://api.telegram.org/bot{token}/{method}"
    if not files:
        data = urllib.parse.urlencode(fields).encode()
        raw = http(url, {"Content-Type": "application/x-www-form-urlencoded"}, data)
    else:
        boundary = uuid.uuid4().hex
        body = io.BytesIO()
        for k, v in fields.items():
            body.write(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
        for k, (fn, content) in files.items():
            ctype = mimetypes.guess_type(fn)[0] or "application/octet-stream"
            body.write(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"; filename="{fn}"\r\n'
                       f'Content-Type: {ctype}\r\n\r\n'.encode())
            body.write(content)
            body.write(b"\r\n")
        body.write(f"--{boundary}--\r\n".encode())
        raw = http(url, {"Content-Type": f"multipart/form-data; boundary={boundary}"}, body.getvalue())
    res = json.loads(raw)
    if not res.get("ok"):
        raise SystemExit(f"Telegram error on {method}: {res}")
    return res["result"]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    dry = "--dry-run" in sys.argv
    if not args:
        raise SystemExit("usage: telegram_announce.py <version> [--dry-run]")
    version = args[0].lstrip("v")
    cfg = json.load(io.open(os.path.join(ROOT, "tools", "announce", f"{version}.json"), encoding="utf-8"))

    if os.environ.get("TELEGRAM_BOT_TOKEN"):
        load_packs()
    caption, dropped = fit(cfg, version)
    kb = buttons(cfg, version)

    if dry:
        print("=== chat:", os.environ.get("TELEGRAM_CHAT_ID", "(unset)"))
        print("=== visible length:", visible(caption), f"/ {LIMIT} — one message, file attached")
        if dropped: print("=== trimmed to fit:", "; ".join(dropped))
        print("=== premium emoji from packs:", len(PACK))
        print("=== buttons:", json.dumps(kb, ensure_ascii=False))
        print("=== caption HTML ===")
        print(caption)
        return

    chat = os.environ["TELEGRAM_CHAT_ID"]
    fname, apk = release_apk(version)
    if dropped: print("Trimmed to fit one caption:", "; ".join(dropped))
    # One message: the APK with the whole post as its caption, and the buttons.
    fields = {"chat_id": chat, "caption": caption, "parse_mode": "HTML", "reply_markup": json.dumps(kb)}
    try:
        tg("sendDocument", fields, {"document": (fname, apk)})
    except SystemExit as e:
        # Telegram lets bots post premium emoji only in some chats (not channels, unless the bot has a Fragment
        # username). If it refuses them, post the same message with the plain emoji instead.
        if "<tg-emoji" not in caption:
            raise
        print(f"Premium emoji refused ({e}); sending with plain emoji")
        global PLAIN
        PLAIN = True
        fields["caption"], _ = fit(cfg, version)
        tg("sendDocument", fields, {"document": (fname, apk)})
    print(f"Announced v{version} to {chat}")


if __name__ == "__main__":
    main()
