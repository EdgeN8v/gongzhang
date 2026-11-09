"""Publish the release built by tools/release.py (data/updates: apk + version.json) to the server that is running now.

    python tools/publish.py

The server runs on the Orange Pi (HomeHub moved it there); the phones find it through the home address table, so there is
nothing else to tell them. The files go in through `docker exec` because the data folder belongs to another user. The
version file goes last, so a phone never sees a version whose apk is not there yet. (tools/nas.py is the old way, for when
the server runs on the NAS.)

Settings by environment variable: PI_HOST (default CUI@192.168.31.61), HOMEHUB_KEY (the ssh key HomeHub uses for both machines)."""
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PI = os.environ.get("PI_HOST", "CUI@192.168.31.61")
KEY = os.environ.get("HOMEHUB_KEY", r"D:\Github\HomeHub\.keys\homehub_ed25519")
CONTAINER = "gongzhang"
SSH = ["ssh", "-i", KEY, "-o", "BatchMode=yes", PI]


def run(cmd, data=None):
    return subprocess.run(SSH + [cmd], input=data, check=True, capture_output=True).stdout.decode(errors="replace")


def put(name, data):
    run(f"docker exec -i {CONTAINER} sh -c 'cat > /data/updates/{name}.part && mv /data/updates/{name}.part /data/updates/{name}'", data)
    print("up", name)


def main():
    folder = ROOT / "data" / "updates"
    version = (folder / "version.json").read_bytes()
    info = json.loads(version)
    apk = info["apk"]
    put(apk, (folder / apk).read_bytes())
    put("version.json", version)
    run(f"docker exec {CONTAINER} sh -c 'cd /data/updates && for f in gongzhang-*.apk; do [ \"$f\" = \"{apk}\" ] || rm -f \"$f\"; done'")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    live = json.load(opener.open("http://" + PI.split("@")[-1] + ":8100/app-update/latest", timeout=10))
    if live.get("versionCode") != info["versionCode"]:
        sys.exit(f"the server still offers {live}")
    print(f"live: {live['versionName']} (code {live['versionCode']})")


if __name__ == "__main__":
    main()
