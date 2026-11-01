"""Build the signed release APK and publish it as the version the app (and the download page) offer.

    python tools/release.py "改了什么;还改了什么" [--out DIR]

Bump versionCode / versionName in android/app/build.gradle.kts first. With no --out the files go to ./data/updates, the folder
the local dev server and docker-compose serve. For the NAS, point --out at the folder mounted as /data/updates (see README).
The apk is signed with the debug key of the machine that builds it, so ALWAYS build releases on the same PC (or copy
~/.android/debug.keystore along): Android refuses to install an update signed with a different key."""
import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "android" / "app" / "build.gradle.kts"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("notes", nargs="?", default="", help="what changed, separated by ;")
    ap.add_argument("--out", default=str(ROOT / "data" / "updates"))
    args = ap.parse_args()

    text = GRADLE.read_text(encoding="utf-8")
    code = int(re.search(r"versionCode\s*=\s*(\d+)", text).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1)

    gradlew = ROOT / "android" / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    subprocess.run([str(gradlew), "assembleRelease", "-q"], cwd=ROOT / "android", check=True)
    built = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    apk = f"gongzhang-{name}.apk"
    shutil.copyfile(built, out / apk)
    info = {"versionCode": code, "versionName": name, "apk": apk, "notes": [n.strip() for n in args.notes.split(";") if n.strip()]}
    (out / "version.json").write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding="utf-8")
    for old in out.glob("gongzhang-*.apk"):
        if old.name != apk:
            old.unlink()
    print(f"published {apk} (versionCode {code}) to {out}")


if __name__ == "__main__":
    main()
