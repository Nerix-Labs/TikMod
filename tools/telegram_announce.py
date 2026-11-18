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
import io, json, mimetypes, os, re, sys, urllib.parse, urllib.request, uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BMC = "https://buymeacoffee.com/nerixlabs"


def http(url, headers=None, data=None, timeout=300):
    req = urllib.request.Request(url, data=data, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def emoji(spec) -> str:
    """A plain emoji string, or a premium/custom one when an id is given: {"e":"🚀","id":"123"}."""
    if isinstance(spec, str):
        return esc(spec)
    ch = esc(spec["e"])
    return f'<tg-emoji emoji-id="{spec["id"]}">{ch}</tg-emoji>' if spec.get("id") else ch


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

    caption = build(cfg, version)
    kb = buttons(cfg, version)
    # Telegram's 1024 caption limit counts the visible text, not the HTML tags. Past it, send the file
    # with a short caption and the full post as a follow-up message.
    visible = len(re.sub(r"<[^>]+>", "", caption))
    short = visible <= 1024

    if dry:
        print("=== chat:", os.environ.get("TELEGRAM_CHAT_ID", "(unset)"))
        print("=== visible length:", visible, "(single message)" if short else "(file + follow-up)")
        print("=== buttons:", json.dumps(kb, ensure_ascii=False))
        print("=== caption HTML ===")
        print(caption)
        return

    chat = os.environ["TELEGRAM_CHAT_ID"]
    fname, apk = release_apk(version)
    if short:
        tg("sendDocument",
           {"chat_id": chat, "caption": caption, "parse_mode": "HTML", "reply_markup": json.dumps(kb)},
           {"document": (fname, apk)})
    else:
        head = build({"en": cfg["en"], "footer": cfg.get("footer")}, version)[:1000]
        msg = tg("sendDocument",
                 {"chat_id": chat, "caption": head, "parse_mode": "HTML", "reply_markup": json.dumps(kb)},
                 {"document": (fname, apk)})
        tg("sendMessage", {"chat_id": chat, "text": caption, "parse_mode": "HTML",
                           "reply_to_message_id": msg["message_id"], "reply_markup": json.dumps(kb)})
    print(f"Announced v{version} to {chat}")


if __name__ == "__main__":
    main()
