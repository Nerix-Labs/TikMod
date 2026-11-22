#!/usr/bin/env python3
"""
Checks whether TikMod's hooks still find their targets on the TikTok version installed on a phone, before users
report breakage. Needs adb, a rooted phone with TikMod active in LSPosed, and TikTok installed.

  python tools/check_resolver.py            # normal start: cache -> known names -> structural search
  python tools/check_resolver.py --force    # ignore cache and known names: tests the DexKit searches themselves

It restarts TikTok, waits for the hooks to go in, then prints the diagnostics TikMod wrote
(hooks ✓/✗ and how every obfuscated target was found) and any "NOT FOUND" from the log.
Exit code 1 when a hook failed or a target is missing.
"""
import argparse
import re
import subprocess
import sys
import time

PKG = "com.zhiliaoapp.musically"
DIAG = "/data/user/0/com.mts.tikmod/files/diag_report.txt"


def adb(*args, check=False):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def su(cmd):
    return adb("shell", "su", "-c", f"'{cmd}'")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="skip cache and known names (debug.tikmod.forcesearch)")
    ap.add_argument("--wait", type=int, default=40, help="seconds to wait for TikTok to start (default 40)")
    a = ap.parse_args()

    adb("get-state", check=True)
    ver = re.search(r"versionName=(\S+)", adb("shell", "dumpsys", "package", PKG))
    print(f"TikTok {ver.group(1) if ver else '(not installed?)'}")

    su(f"rm -f {DIAG}")
    su(f"setprop debug.tikmod.forcesearch {'1' if a.force else '0'}")
    adb("logcat", "-c")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")

    report = ""
    for _ in range(a.wait):
        time.sleep(1)
        report = su(f"cat {DIAG} 2>/dev/null").strip()
        if report:
            break
    if a.force:
        su("setprop debug.tikmod.forcesearch 0")

    log = adb("logcat", "-d", "-s", "TikMod:*", "LSPosed-Bridge:*")
    missing = sorted(set(re.findall(r"Resolver: \[([^\]]+)\] NOT FOUND", log)))
    slow = re.findall(r"Resolver: \[([^\]]+)\] structural search took (\d+) ms", log)

    if not report:
        print("No diagnostics after %d s: is TikMod enabled in LSPosed with TikTok in its scope?" % a.wait)
        sys.exit(2)
    print(report)
    if slow:
        print("\nStructural searches: " + ", ".join(f"{k} {ms} ms" for k, ms in slow))
    failed = [l for l in report.splitlines() if l.strip().startswith("✗")]
    if missing:
        print("\nNOT FOUND in the log: " + ", ".join(missing))
    print("\nRESULT: " + ("all good" if not failed and not missing else f"{len(failed)} problem(s)"))
    sys.exit(1 if failed or missing else 0)


if __name__ == "__main__":
    main()
