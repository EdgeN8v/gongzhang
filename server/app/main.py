import json
import os
import re
import sqlite3
import uuid
from datetime import date as Date, timedelta
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, Field

from app import auth, db as dbm, report
from app.db import daily_rate, get_db
from app.export import to_xlsx

VERSION = "0.1.0"
STATIC = Path(__file__).parent / "static"
UPDATE_DIR = Path(os.environ.get("GZ_UPDATE_DIR", str(dbm.DATA_DIR / "updates")))
KINDS = {"fuel", "repair", "hired", "other", "overtime"}

app = FastAPI(title="工账", docs_url=None, redoc_url=None, openapi_url=None)


@app.on_event("startup")
def _startup():
    dbm.init()
    with dbm.connect() as conn:
        auth.seed_users(conn)
    dbm.backup_loop()


def _date(value: str) -> str:
    try:
        return Date.fromisoformat(value).isoformat()
    except ValueError:
        raise HTTPException(400, "日期格式不对")


def _range(d1: str, d2: str) -> tuple[str, str]:
    d1, d2 = _date(d1), _date(d2)
    if d1 > d2:
        d1, d2 = d2, d1
    if (Date.fromisoformat(d2) - Date.fromisoformat(d1)).days > 800:
        raise HTTPException(400, "时间范围太长")
    return d1, d2


@app.get("/health")
def health():
    return {"status": "ok", "version": VERSION}


# ---------------------------------------------------------------- login
class Login(BaseModel):
    username: str
    password: str


@app.post("/api/login")
def login(body: Login, request: Request, conn=Depends(get_db)):
    key = f"{request.client.host if request.client else '?'}|{body.username}"
    auth.throttle(key)
    row = conn.execute("SELECT * FROM users WHERE username=?", (body.username.strip(),)).fetchone()
    if not row or not auth.check_password(body.password, row["salt"], row["pw_hash"]):
        auth.note_failure(key)
        raise HTTPException(401, "用户名或密码不对")
    return {"token": auth.make_token(row["id"]), "username": row["username"], "version": VERSION}


class NewPassword(BaseModel):
    old: str
    new: str = Field(min_length=6)


@app.post("/api/password")
def change_password(body: NewPassword, user=Depends(auth.current_user), conn=Depends(get_db)):
    row = conn.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone()
    if not auth.check_password(body.old, row["salt"], row["pw_hash"]):
        raise HTTPException(400, "原密码不对")
    salt, digest = auth.hash_password(body.new)
    conn.execute("UPDATE users SET salt=?, pw_hash=? WHERE id=?", (salt, digest, user["id"]))
    return {"ok": True}


@app.get("/api/me")
def me(user=Depends(auth.current_user)):
    return {"username": user["username"], "version": VERSION}


# ---------------------------------------------------------------- 人员 / 车辆
class ResourceIn(BaseModel):
    name: str = Field(min_length=1, max_length=40)
    category: str = ""
    is_vehicle: bool = False
    pay_type: str = Field("month", pattern="^(month|day)$")
    amount: float = Field(0, ge=0)
    ot_free_trips: int | None = None
    ot_rate: float | None = None
    active: bool = True
    note: str = ""


def _resource(row) -> dict:
    d = dict(row)
    d["is_vehicle"] = bool(d["is_vehicle"])
    d["active"] = bool(d["active"])
    d["rate"] = daily_rate(d["pay_type"], d["amount"])
    return d


@app.get("/api/resources")
def list_resources(user=Depends(auth.current_user), conn=Depends(get_db)):
    return [_resource(r) for r in conn.execute("SELECT * FROM resources ORDER BY active DESC, sort, id")]


@app.post("/api/resources")
def add_resource(body: ResourceIn, user=Depends(auth.current_user), conn=Depends(get_db)):
    nxt = conn.execute("SELECT COALESCE(MAX(sort), 0) + 1 FROM resources").fetchone()[0]
    cur = conn.execute(
        "INSERT INTO resources(name, category, is_vehicle, pay_type, amount, ot_free_trips, ot_rate, active, sort, note) VALUES (?,?,?,?,?,?,?,?,?,?)",
        (body.name.strip(), body.category.strip(), int(body.is_vehicle), body.pay_type, body.amount, body.ot_free_trips, body.ot_rate, int(body.active), nxt, body.note))
    return _resource(conn.execute("SELECT * FROM resources WHERE id=?", (cur.lastrowid,)).fetchone())


@app.put("/api/resources/{rid}")
def edit_resource(rid: int, body: ResourceIn, user=Depends(auth.current_user), conn=Depends(get_db)):
    if not conn.execute("SELECT 1 FROM resources WHERE id=?", (rid,)).fetchone():
        raise HTTPException(404)
    conn.execute(
        "UPDATE resources SET name=?, category=?, is_vehicle=?, pay_type=?, amount=?, ot_free_trips=?, ot_rate=?, active=?, note=? WHERE id=?",
        (body.name.strip(), body.category.strip(), int(body.is_vehicle), body.pay_type, body.amount, body.ot_free_trips, body.ot_rate, int(body.active), body.note, rid))
    return _resource(conn.execute("SELECT * FROM resources WHERE id=?", (rid,)).fetchone())


@app.delete("/api/resources/{rid}")
def delete_resource(rid: int, force: bool = False, user=Depends(auth.current_user), conn=Depends(get_db)):
    """A person or vehicle with no records goes away at once. One with records needs force=true: its attendance and haul rows are
    deleted with it, while money entries (fuel, repairs, overtime) stay in the totals, only without the name. Stopping someone
    (active=false) is what keeps the history intact."""
    if not conn.execute("SELECT 1 FROM resources WHERE id=?", (rid,)).fetchone():
        raise HTTPException(404)
    used = conn.execute(
        "SELECT (SELECT COUNT(*) FROM attendance WHERE resource_id=?1) + (SELECT COUNT(*) FROM entries WHERE resource_id=?1)"
        " + (SELECT COUNT(*) FROM hauls WHERE resource_id=?1 OR driver_id=?1)", (rid,)).fetchone()[0]
    if used and not force:
        raise HTTPException(409, f"已有 {used} 条记录")
    conn.execute("DELETE FROM attendance WHERE resource_id=?", (rid,))
    conn.execute("DELETE FROM hauls WHERE resource_id=?", (rid,))
    conn.execute("UPDATE hauls SET driver_id=NULL WHERE driver_id=?", (rid,))
    conn.execute("UPDATE entries SET resource_id=NULL WHERE resource_id=?", (rid,))
    conn.execute("DELETE FROM resources WHERE id=?", (rid,))
    return {"deleted": rid, "records": used}


# ---------------------------------------------------------------- one day
def _day(conn, d: str) -> dict:
    att = [dict(r) for r in conn.execute(
        "SELECT a.id, a.resource_id, a.factor, a.rate, a.note, r.name, r.category, r.is_vehicle "
        "FROM attendance a JOIN resources r ON r.id=a.resource_id WHERE a.date=? ORDER BY r.sort, r.id", (d,))]
    entries = [dict(r) for r in conn.execute(
        "SELECT e.*, r.name AS resource_name FROM entries e LEFT JOIN resources r ON r.id=e.resource_id WHERE e.date=? ORDER BY e.id", (d,))]
    hauls = [dict(r) for r in conn.execute(
        "SELECT h.*, r.name AS resource_name FROM hauls h JOIN resources r ON r.id=h.resource_id WHERE h.date=? ORDER BY h.id", (d,))]
    wage = round(sum(a["rate"] * a["factor"] for a in att), 2)
    spend = {k: round(sum(e["amount"] for e in entries if e["kind"] == k), 2) for k in KINDS}
    return {"date": d, "attendance": att, "entries": entries, "hauls": hauls,
            "totals": {"labor": wage, **spend, "total": round(wage + sum(spend.values()), 2)}}


@app.get("/api/day/{d}")
def get_day(d: str, user=Depends(auth.current_user), conn=Depends(get_db)):
    return _day(conn, _date(d))


class Confirm(BaseModel):
    from_prev: bool = False


@app.post("/api/day/{d}/confirm")
def confirm_day(d: str, body: Confirm, user=Depends(auth.current_user), conn=Depends(get_db)):
    """Everyone not yet recorded for that day is marked present. 'from_prev' copies the closest earlier day instead
    (same people, same wages, same half-days), otherwise it is every active person/vehicle at today's wage."""
    d = _date(d)
    have = {r[0] for r in conn.execute("SELECT resource_id FROM attendance WHERE date=?", (d,))}
    template: dict[int, tuple[float, float]] = {}
    if body.from_prev:
        prev = conn.execute("SELECT MAX(date) FROM attendance WHERE date<?", (d,)).fetchone()[0]
        if prev:
            template = {r["resource_id"]: (r["factor"], r["rate"]) for r in conn.execute("SELECT * FROM attendance WHERE date=?", (prev,))}
    for r in conn.execute("SELECT * FROM resources WHERE active=1 ORDER BY sort, id"):
        if r["id"] in have:
            continue
        if template and r["id"] not in template:
            continue
        factor, rate = template.get(r["id"], (1, daily_rate(r["pay_type"], r["amount"])))
        conn.execute("INSERT INTO attendance(date, resource_id, factor, rate) VALUES (?,?,?,?)", (d, r["id"], factor, rate))
    return _day(conn, d)


class AttendanceIn(BaseModel):
    resource_id: int
    factor: float = Field(1, ge=0, le=3)
    rate: float | None = Field(None, ge=0)
    note: str = ""


@app.put("/api/day/{d}/attendance")
def set_attendance(d: str, body: AttendanceIn, user=Depends(auth.current_user), conn=Depends(get_db)):
    d = _date(d)
    r = conn.execute("SELECT * FROM resources WHERE id=?", (body.resource_id,)).fetchone()
    if not r:
        raise HTTPException(404, "没有这个人员/车辆")
    old = conn.execute("SELECT rate FROM attendance WHERE date=? AND resource_id=?", (d, r["id"])).fetchone()
    rate = body.rate if body.rate is not None else (old["rate"] if old else daily_rate(r["pay_type"], r["amount"]))
    conn.execute(
        "INSERT INTO attendance(date, resource_id, factor, rate, note) VALUES (?,?,?,?,?) "
        "ON CONFLICT(date, resource_id) DO UPDATE SET factor=excluded.factor, rate=excluded.rate, note=excluded.note",
        (d, r["id"], body.factor, rate, body.note))
    return _day(conn, d)


@app.delete("/api/day/{d}/attendance/{rid}")
def drop_attendance(d: str, rid: int, user=Depends(auth.current_user), conn=Depends(get_db)):
    d = _date(d)
    conn.execute("DELETE FROM attendance WHERE date=? AND resource_id=?", (d, rid))
    return _day(conn, d)


# ---------------------------------------------------------------- 加油 / 维修 / 其他 / 加班
class EntryIn(BaseModel):
    date: str
    kind: str
    resource_id: int | None = None
    qty: float | None = Field(None, ge=0)
    price: float | None = Field(None, ge=0)
    amount: float | None = None
    note: str = ""


def _entry_amount(body: EntryIn) -> float:
    if body.amount is not None:
        return round(body.amount, 2)
    if body.qty is not None and body.price is not None:
        return round(body.qty * body.price, 2)
    raise HTTPException(400, "请填金额")


def _check_entry(body: EntryIn, conn) -> None:
    if body.kind not in KINDS:
        raise HTTPException(400, "未知的费用类型")
    _date(body.date)
    if body.resource_id and not conn.execute("SELECT 1 FROM resources WHERE id=?", (body.resource_id,)).fetchone():
        raise HTTPException(404, "没有这个人员/车辆")


@app.post("/api/entries")
def add_entry(body: EntryIn, user=Depends(auth.current_user), conn=Depends(get_db)):
    _check_entry(body, conn)
    conn.execute("INSERT INTO entries(date, kind, resource_id, qty, price, amount, note, uid) VALUES (?,?,?,?,?,?,?,?)",
                 (body.date, body.kind, body.resource_id, body.qty, body.price, _entry_amount(body), body.note.strip(), uuid.uuid4().hex))
    return _day(conn, body.date)


@app.put("/api/entries/{eid}")
def edit_entry(eid: int, body: EntryIn, user=Depends(auth.current_user), conn=Depends(get_db)):
    _check_entry(body, conn)
    if not conn.execute("SELECT 1 FROM entries WHERE id=?", (eid,)).fetchone():
        raise HTTPException(404)
    conn.execute("UPDATE entries SET date=?, kind=?, resource_id=?, qty=?, price=?, amount=?, note=? WHERE id=?",
                 (body.date, body.kind, body.resource_id, body.qty, body.price, _entry_amount(body), body.note.strip(), eid))
    return _day(conn, body.date)


@app.delete("/api/entries/{eid}")
def delete_entry(eid: int, user=Depends(auth.current_user), conn=Depends(get_db)):
    row = conn.execute("SELECT date FROM entries WHERE id=?", (eid,)).fetchone()
    if not row:
        raise HTTPException(404)
    conn.execute("DELETE FROM entries WHERE id=?", (eid,))
    return _day(conn, row["date"])


@app.get("/api/entries")
def list_entries(from_: str = Query(alias="from"), to: str = Query(), kind: str = "", user=Depends(auth.current_user), conn=Depends(get_db)):
    d1, d2 = _range(from_, to)
    sql = "SELECT e.*, r.name AS resource_name FROM entries e LEFT JOIN resources r ON r.id=e.resource_id WHERE e.date BETWEEN ? AND ?"
    args: list = [d1, d2]
    if kind:
        sql += " AND e.kind=?"
        args.append(kind)
    return [dict(r) for r in conn.execute(sql + " ORDER BY e.date DESC, e.id DESC", args)]


@app.get("/api/hints")
def hints(user=Depends(auth.current_user), conn=Depends(get_db)):
    """Last fuel price and the notes typed most often, so the daily form is mostly taps."""
    price = conn.execute("SELECT price FROM entries WHERE kind='fuel' AND price IS NOT NULL ORDER BY date DESC, id DESC LIMIT 1").fetchone()
    def common(kind):
        return [r[0] for r in conn.execute(
            "SELECT note FROM entries WHERE kind=? AND note<>'' GROUP BY note ORDER BY COUNT(*) DESC, MAX(date) DESC LIMIT 8", (kind,))]
    return {"fuel_price": price[0] if price else None, "other": common("other"), "repair": common("repair"), "overtime": common("overtime")}


# ---------------------------------------------------------------- 吨数
class HaulIn(BaseModel):
    date: str
    resource_id: int
    driver_id: int | None = None
    tons: float = Field(0, ge=0)
    trips: int = Field(0, ge=0)
    note: str = ""


@app.post("/api/hauls")
def add_haul(body: HaulIn, user=Depends(auth.current_user), conn=Depends(get_db)):
    _date(body.date)
    conn.execute("INSERT INTO hauls(date, resource_id, driver_id, tons, trips, note, uid) VALUES (?,?,?,?,?,?,?)", (body.date, body.resource_id, body.driver_id, body.tons, body.trips, body.note, uuid.uuid4().hex))
    return _day(conn, body.date)


@app.put("/api/hauls/{hid}")
def edit_haul(hid: int, body: HaulIn, user=Depends(auth.current_user), conn=Depends(get_db)):
    _date(body.date)
    conn.execute("UPDATE hauls SET date=?, resource_id=?, driver_id=?, tons=?, trips=?, note=? WHERE id=?", (body.date, body.resource_id, body.driver_id, body.tons, body.trips, body.note, hid))
    return _day(conn, body.date)


@app.delete("/api/hauls/{hid}")
def delete_haul(hid: int, user=Depends(auth.current_user), conn=Depends(get_db)):
    row = conn.execute("SELECT date FROM hauls WHERE id=?", (hid,)).fetchone()
    if not row:
        raise HTTPException(404)
    conn.execute("DELETE FROM hauls WHERE id=?", (hid,))
    return _day(conn, row["date"])



# ---------------------------------------------------------------- sync (the phone keeps a full copy and works without a connection)
@app.get("/api/dump")
def dump(user=Depends(auth.current_user), conn=Depends(get_db)):
    """Everything, for the phone's local copy. A few thousand rows a year, so no incremental protocol is needed."""
    rows = lambda sql: [dict(r) for r in conn.execute(sql)]
    return {
        "resources": [_resource(r) for r in conn.execute("SELECT * FROM resources ORDER BY sort, id")],
        "attendance": rows("SELECT date, resource_id, factor, rate, note FROM attendance ORDER BY date, resource_id"),
        "entries": rows("SELECT uid, date, kind, resource_id, qty, price, amount, note FROM entries ORDER BY date, id"),
        "hauls": rows("SELECT uid, date, resource_id, driver_id, tons, trips, note FROM hauls ORDER BY date, id"),
    }


class Push(BaseModel):
    ops: list[dict]


@app.post("/api/push")
def push(body: Push, user=Depends(auth.current_user), conn=Depends(get_db)):
    """Applies what the phone did offline, in order. Every op is an upsert or a delete by key, so sending a batch twice is harmless,
    and one bad op is skipped instead of blocking the phone's whole queue."""
    applied = skipped = 0
    for op in body.ops:
        try:
            kind = op.get("op")
            if kind == "att":
                conn.execute(
                    "INSERT INTO attendance(date, resource_id, factor, rate, note) VALUES (?,?,?,?,?) "
                    "ON CONFLICT(date, resource_id) DO UPDATE SET factor=excluded.factor, rate=excluded.rate, note=excluded.note",
                    (_date(op["date"]), int(op["resource_id"]), float(op["factor"]), float(op["rate"]), op.get("note") or ""))
            elif kind == "att_del":
                conn.execute("DELETE FROM attendance WHERE date=? AND resource_id=?", (_date(op["date"]), int(op["resource_id"])))
            elif kind == "entry":
                if op["kind"] not in KINDS:
                    raise ValueError("kind")
                conn.execute(
                    "INSERT INTO entries(uid, date, kind, resource_id, qty, price, amount, note) VALUES (?,?,?,?,?,?,?,?) "
                    "ON CONFLICT(uid) DO UPDATE SET date=excluded.date, kind=excluded.kind, resource_id=excluded.resource_id, qty=excluded.qty, "
                    "price=excluded.price, amount=excluded.amount, note=excluded.note",
                    (str(op["uid"]), _date(op["date"]), op["kind"], op.get("resource_id"), op.get("qty"), op.get("price"), round(float(op["amount"]), 2), (op.get("note") or "").strip()))
            elif kind == "entry_del":
                conn.execute("DELETE FROM entries WHERE uid=?", (str(op["uid"]),))
            elif kind == "haul":
                conn.execute(
                    "INSERT INTO hauls(uid, date, resource_id, driver_id, tons, trips, note) VALUES (?,?,?,?,?,?,?) "
                    "ON CONFLICT(uid) DO UPDATE SET date=excluded.date, resource_id=excluded.resource_id, driver_id=excluded.driver_id, tons=excluded.tons, trips=excluded.trips, note=excluded.note",
                    (str(op["uid"]), _date(op["date"]), int(op["resource_id"]), op.get("driver_id"), float(op["tons"]), int(op["trips"]), op.get("note") or ""))
            elif kind == "haul_del":
                conn.execute("DELETE FROM hauls WHERE uid=?", (str(op["uid"]),))
            else:
                raise ValueError("op")
            applied += 1
        except (KeyError, ValueError, TypeError, HTTPException, sqlite3.DatabaseError):
            skipped += 1
    return {"applied": applied, "skipped": skipped}

# ---------------------------------------------------------------- 汇总
@app.get("/api/report")
def get_report(from_: str = Query(alias="from"), to: str = Query(), user=Depends(auth.current_user), conn=Depends(get_db)):
    return report.build(conn, *_range(from_, to))


@app.get("/api/report.xlsx")
def get_report_xlsx(from_: str = Query(alias="from"), to: str = Query(), user=Depends(auth.current_user), conn=Depends(get_db)):
    d1, d2 = _range(from_, to)
    data = to_xlsx(report.build(conn, d1, d2))
    name = f"gongzhang_{d1}_{d2}.xlsx"
    return Response(data, media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    headers={"Content-Disposition": f'attachment; filename="{name}"'})


# ---------------------------------------------------------------- 一键更新 (the Android app asks here; nothing secret in the apk)
def _latest():
    path = UPDATE_DIR / "version.json"
    if not path.is_file():
        raise HTTPException(404)
    info = json.loads(path.read_text(encoding="utf-8"))
    apk = str(info.get("apk", ""))
    if not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", apk) or not (UPDATE_DIR / apk).is_file():
        raise HTTPException(404)
    return info


@app.get("/app-update/latest")
def update_latest():
    return _latest()


@app.get("/app-update/apk")
def update_apk():
    info = _latest()
    return FileResponse(UPDATE_DIR / info["apk"], media_type="application/vnd.android.package-archive", filename=info["apk"])


# ---------------------------------------------------------------- download page: what mom opens in her phone's browser the first time
@app.get("/")
def index():
    return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-cache"})
