"""Deploy and update the server on the NAS over SSH (the NAS has no sftp, so files are streamed through the shell).

    NAS_USER=... NAS_PASSWORD=... python tools/nas.py publish   # push data/updates (apk + version.json) -- what a new app release needs
    NAS_USER=... NAS_PASSWORD=... python tools/nas.py deploy    # push server code, rebuild the image, restart the container

Layout on the NAS: /data_s001/docker_appstore/gongzhang/{.env,Dockerfile,server/app,data/{gongzhang.db,backups,updates}}.
The container runs on its own docker network (not the default bridge): on the default bridge the IPv6 port mapping does not work,
and the phones reach the NAS over IPv6 through the DDNS name."""
import base64
import os
import posixpath
import sys
import warnings
from pathlib import Path

import paramiko

warnings.filterwarnings("ignore")
ROOT = Path(__file__).resolve().parent.parent
HOST, PORT = os.environ.get("NAS_HOST", "192.168.31.131"), int(os.environ.get("NAS_PORT", "10000"))
BASE = "/data_s001/docker_appstore/gongzhang"
DOCKERFILE = """FROM ledger-api:arm64
WORKDIR /srv
RUN pip install --no-cache-dir openpyxl==3.1.5
COPY server/app ./app
ENV GZ_DATA_DIR=/data TZ=Asia/Shanghai
EXPOSE 8100
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8100"]
"""

user, pw = os.environ["NAS_USER"], os.environ["NAS_PASSWORD"]
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, port=PORT, username=user, password=pw, timeout=15)


def sh(cmd, timeout=600):
    _, o, e = c.exec_command(cmd.replace("SUDO", f"echo '{pw}' | sudo -S -p ''"), timeout=timeout)
    return (o.read().decode(errors="replace") + e.read().decode(errors="replace")).replace(pw, "***")


def put(dest, data):
    stdin, stdout, stderr = c.exec_command(f"mkdir -p '{posixpath.dirname(dest)}' && base64 -d > '{dest}'")
    stdin.write(base64.b64encode(data).decode())
    stdin.channel.shutdown_write()
    err = stderr.read().decode()
    if stdout.channel.recv_exit_status():
        raise SystemExit(f"upload failed {dest}: {err}")
    print("up", dest.replace(BASE + "/", ""))


def publish():
    folder = ROOT / "data" / "updates"
    version = (folder / "version.json").read_bytes()
    import json
    apk = json.loads(version)["apk"]
    put(f"{BASE}/data/updates/{apk}", (folder / apk).read_bytes())
    put(f"{BASE}/data/updates/version.json", version)  # last, so the app never sees a version without its apk
    print(sh(f"ls {BASE}/data/updates; for f in {BASE}/data/updates/gongzhang-*.apk; do case $f in *{apk}) ;; *) rm -f $f;; esac; done"))


def deploy():
    for f in (ROOT / "server" / "app").rglob("*"):
        if f.is_file() and "__pycache__" not in f.parts:
            put(f"{BASE}/server/app/{f.relative_to(ROOT / 'server' / 'app').as_posix()}", f.read_bytes())
    put(f"{BASE}/Dockerfile", DOCKERFILE.encode())
    print(sh(f"cd {BASE} && SUDO docker build -t gongzhang:latest . 2>&1 | tail -2; SUDO docker network create gongzhang_default >/dev/null 2>&1; "
             f"SUDO docker rm -f gongzhang >/dev/null 2>&1; SUDO docker run -d --name gongzhang --restart unless-stopped --network gongzhang_default "
             f"-p 8100:8100 --env-file .env -v {BASE}/data:/data gongzhang:latest"))


if __name__ == "__main__":
    {"publish": publish, "deploy": deploy}[sys.argv[1] if len(sys.argv) > 1 else "publish"]()
