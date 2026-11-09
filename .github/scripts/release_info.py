#!/usr/bin/env python3
"""Writes version.json, the file the app reads for in-app updates and public stats.

GitHub counts downloads per release file, and the count resets when a file is replaced.
The "latest" release replaces ChargeWatch.apk on every build, so this script carries the
running total in version.json ("downloadsBaseline") and adds the live count on top.

  release_info.py baseline              print the baseline to use for a new build
  release_info.py write APK BASELINE    write version.json for the APK being released
  release_info.py refresh               update downloads and stars in the published version.json

Needs the gh CLI with GH_TOKEN, and GITHUB_REPOSITORY.
"""
import datetime
import hashlib
import json
import os
import subprocess
import sys

REPO = os.environ["GITHUB_REPOSITORY"]
COUNTED = ("ChargeWatch.apk", "ChargeWatch.zip")


def gh(*args):
    return subprocess.run(["gh", *args], capture_output=True, text=True)


def live_downloads():
    r = gh("api", f"repos/{REPO}/releases/tags/latest")
    if r.returncode != 0:
        return 0
    return sum(a["download_count"] for a in json.loads(r.stdout).get("assets", []) if a["name"] in COUNTED)


def published_info():
    r = gh("release", "download", "latest", "-p", "version.json", "-O", "-", "--repo", REPO)
    try:
        return json.loads(r.stdout) if r.returncode == 0 else {}
    except ValueError:
        return {}


def stars():
    r = gh("api", f"repos/{REPO}")
    return json.loads(r.stdout).get("stargazers_count", 0) if r.returncode == 0 else 0


def now():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main():
    mode = sys.argv[1]
    if mode == "baseline":
        # The current APK and zip are about to be replaced: fold their counts into the total.
        print(published_info().get("downloadsBaseline", 0) + live_downloads())
    elif mode == "write":
        apk, baseline = sys.argv[2], int(sys.argv[3])
        with open(apk, "rb") as f:
            sha = hashlib.sha256(f.read()).hexdigest()
        code = int(os.environ["GITHUB_RUN_NUMBER"])
        notes = subprocess.run(["git", "log", "-1", "--format=%s"], capture_output=True, text=True).stdout.strip()
        info = {
            "versionCode": code,
            "versionName": f"1.0.{code}",
            "url": f"https://github.com/{REPO}/releases/download/latest/ChargeWatch.apk",
            "sha256": sha,
            "size": os.path.getsize(apk),
            "notes": notes,
            "released": now(),
            "downloads": baseline,
            "downloadsBaseline": baseline,
            "stars": stars(),
            "statsUpdated": now(),
        }
        with open("version.json", "w") as f:
            json.dump(info, f, indent=2)
        print(json.dumps(info, indent=2))
    elif mode == "refresh":
        info = published_info()
        if not info:
            print("No version.json published yet.")
            return
        info["downloads"] = info.get("downloadsBaseline", 0) + live_downloads()
        info["stars"] = stars()
        info["statsUpdated"] = now()
        with open("version.json", "w") as f:
            json.dump(info, f, indent=2)
        r = gh("release", "upload", "latest", "version.json", "--clobber", "--repo", REPO)
        print(r.stdout, r.stderr)
        print(f"downloads={info['downloads']} stars={info['stars']}")
        if r.returncode != 0:
            sys.exit(1)
    else:
        sys.exit(f"unknown mode {mode}")


if __name__ == "__main__":
    main()
