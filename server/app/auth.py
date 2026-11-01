"""Login for a public-facing server: PBKDF2 passwords, HMAC-signed tokens, and a throttle on wrong passwords.
No third-party crypto packages, so the Docker image stays tiny."""
import base64
import hashlib
import hmac
import json
import os
import secrets
import time
from collections import defaultdict

from fastapi import Depends, HTTPException, Request

from app.db import get_db

TOKEN_DAYS = 60
_ITER = 200_000
_FAILS: dict[str, list[float]] = defaultdict(list)


def _secret() -> bytes:
    s = os.environ.get("GZ_SECRET", "")
    if not s:
        raise RuntimeError("GZ_SECRET must be set in .env to a long random string")
    return s.encode()


def hash_password(password: str, salt: str | None = None) -> tuple[str, str]:
    salt = salt or secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), _ITER).hex()
    return salt, digest


def check_password(password: str, salt: str, pw_hash: str) -> bool:
    return hmac.compare_digest(hash_password(password, salt)[1], pw_hash)


def make_token(user_id: int) -> str:
    body = base64.urlsafe_b64encode(json.dumps({"u": user_id, "exp": int(time.time()) + TOKEN_DAYS * 86400}).encode()).decode()
    sig = hmac.new(_secret(), body.encode(), hashlib.sha256).hexdigest()
    return f"{body}.{sig}"


def read_token(token: str) -> int | None:
    try:
        body, sig = token.rsplit(".", 1)
        if not hmac.compare_digest(hmac.new(_secret(), body.encode(), hashlib.sha256).hexdigest(), sig):
            return None
        data = json.loads(base64.urlsafe_b64decode(body.encode()))
        return data["u"] if data["exp"] > time.time() else None
    except Exception:
        return None


def throttle(key: str) -> None:
    """At most 8 wrong passwords per 10 minutes from one address+name."""
    now = time.time()
    _FAILS[key] = [t for t in _FAILS[key] if now - t < 600]
    if len(_FAILS[key]) >= 8:
        raise HTTPException(429, "密码错误次数太多，请 10 分钟后再试")


def note_failure(key: str) -> None:
    _FAILS[key].append(time.time())


def current_user(request: Request, db=Depends(get_db)):
    header = request.headers.get("authorization", "")
    token = header[7:] if header.lower().startswith("bearer ") else request.query_params.get("access_token", "")
    uid = read_token(token) if token else None
    row = db.execute("SELECT id, username FROM users WHERE id=?", (uid,)).fetchone() if uid else None
    if not row:
        raise HTTPException(401, "请先登录")
    return row


def seed_users(db) -> None:
    """GZ_USERS="妈妈:密码1,我:密码2": created if missing, never overwritten (change it from the 我的 page)."""
    for pair in filter(None, (p.strip() for p in os.environ.get("GZ_USERS", "").split(","))):
        name, _, password = pair.partition(":")
        if not name or not password:
            continue
        if not db.execute("SELECT 1 FROM users WHERE username=?", (name,)).fetchone():
            salt, digest = hash_password(password)
            db.execute("INSERT INTO users(username, salt, pw_hash) VALUES (?,?,?)", (name, salt, digest))
