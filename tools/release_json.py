#!/usr/bin/env python3
"""
Adds (or refreshes) a release in updates.json from the built APK: size and SHA-256, which the in-app updater checks
before installing. Run it after building the release APK and before committing updates.json:

  python tools/release_json.py 4.8.0 app/build/outputs/apk/release/app-release.apk "- note one\\n- note two"

The APK must be the exact file attached to the GitHub release as TikMod-<version>.apk.
Notes are optional when the version is already in updates.json (only size and sha256 are refreshed then).
"""
import datetime
import hashlib
import json
import os
import sys

REPO = "Nerix-Labs/TikMod"


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    version, apk = sys.argv[1], sys.argv[2]
    notes = sys.argv[3].replace("\\n", "\n") if len(sys.argv) > 3 else None
    data = open(apk, "rb").read()
    sha = hashlib.sha256(data).hexdigest()
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "updates.json")
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    rel = next((r for r in doc["releases"] if r.get("version") == version), None)
    if rel is None:
        if notes is None:
            sys.exit(f"{version} isn't in updates.json yet: pass the release notes as the third argument")
        rel = {"version": version, "date": datetime.date.today().isoformat(),
               "apk": f"https://github.com/{REPO}/releases/download/v{version}/TikMod-{version}.apk"}
        doc["releases"].insert(0, rel)
    if notes is not None:
        rel["notes"] = notes
    rel["size"] = len(data)
    rel["sha256"] = sha
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"updates.json: {version}  size={len(data)}  sha256={sha}")


if __name__ == "__main__":
    main()
