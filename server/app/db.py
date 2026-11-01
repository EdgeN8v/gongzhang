"""SQLite storage. One small file on the NAS (GZ_DATA_DIR/gongzhang.db), WAL mode, copied to backups/ every night."""
import os
import sqlite3
from pathlib import Path

DATA_DIR = Path(os.environ.get("GZ_DATA_DIR", "./data"))
DB_PATH = DATA_DIR / "gongzhang.db"

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    salt TEXT NOT NULL,
    pw_hash TEXT NOT NULL
);

-- 人员 and 车辆 share one table: both have a daily wage and can be absent. Vehicles also take fuel / repairs / tonnage.
CREATE TABLE IF NOT EXISTS resources (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    category TEXT NOT NULL DEFAULT '',        -- 工种: 环卫工人 / 司机 / 货车 / 铲车 ...
    is_vehicle INTEGER NOT NULL DEFAULT 0,
    pay_type TEXT NOT NULL DEFAULT 'month',   -- 'month' (daily = amount / 30) or 'day'
    amount REAL NOT NULL DEFAULT 0,
    ot_free_trips INTEGER,                    -- 超过这么多车次开始算加班费 (货车)
    ot_rate REAL,                             -- 每多一车加多少元
    active INTEGER NOT NULL DEFAULT 1,
    sort INTEGER NOT NULL DEFAULT 0,
    note TEXT NOT NULL DEFAULT ''
);

-- factor 1 = 全天, 0.5 = 半天, 0 = 缺勤. rate is the day wage on that day, so changing a wage later never rewrites old days.
CREATE TABLE IF NOT EXISTS attendance (
    id INTEGER PRIMARY KEY,
    date TEXT NOT NULL,
    resource_id INTEGER NOT NULL REFERENCES resources(id),
    factor REAL NOT NULL DEFAULT 1,
    rate REAL NOT NULL,
    note TEXT NOT NULL DEFAULT '',
    UNIQUE(date, resource_id)
);
CREATE INDEX IF NOT EXISTS ix_attendance_date ON attendance(date);

-- kind: fuel 加油 / repair 维修 / hired 外雇车 / other 其他费用 / overtime 加班费 (counts as 人工)
-- uid: made by the phone, so an entry recorded offline can be sent again without being doubled
CREATE TABLE IF NOT EXISTS entries (
    id INTEGER PRIMARY KEY,
    date TEXT NOT NULL,
    kind TEXT NOT NULL,
    resource_id INTEGER REFERENCES resources(id),
    qty REAL,
    price REAL,
    amount REAL NOT NULL DEFAULT 0,
    note TEXT NOT NULL DEFAULT '',
    uid TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);
CREATE INDEX IF NOT EXISTS ix_entries_date ON entries(date);

CREATE TABLE IF NOT EXISTS hauls (
    id INTEGER PRIMARY KEY,
    date TEXT NOT NULL,
    resource_id INTEGER NOT NULL REFERENCES resources(id),
    tons REAL NOT NULL DEFAULT 0,
    trips INTEGER NOT NULL DEFAULT 0,
    note TEXT NOT NULL DEFAULT '',
    uid TEXT,
    driver_id INTEGER REFERENCES resources(id)   -- who drove it: the trip-based overtime goes to this person
);
CREATE INDEX IF NOT EXISTS ix_hauls_date ON hauls(date);
"""


def connect() -> sqlite3.Connection:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, check_same_thread=False, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init() -> None:
    with connect() as conn:
        conn.executescript(SCHEMA)
        for table in ("entries", "hauls"):
            cols = {r["name"] for r in conn.execute(f"PRAGMA table_info({table})")}
            if "uid" not in cols:
                conn.execute(f"ALTER TABLE {table} ADD COLUMN uid TEXT")
            if table == "hauls" and "driver_id" not in cols:
                conn.execute("ALTER TABLE hauls ADD COLUMN driver_id INTEGER")
            conn.execute(f"UPDATE {table} SET uid = lower(hex(randomblob(16))) WHERE uid IS NULL")
            conn.execute(f"CREATE UNIQUE INDEX IF NOT EXISTS ux_{table}_uid ON {table}(uid)")


def get_db():
    conn = connect()
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def daily_rate(pay_type: str, amount: float) -> float:
    """Same rule as the sheet: monthly wage / 30."""
    return round(amount / 30, 2) if pay_type == "month" else round(amount, 2)


def backup_loop() -> None:
    """Once a day copy the database to DATA_DIR/backups and keep the last 14. Uses SQLite's own backup, safe while the app runs."""
    import threading
    import time
    from datetime import date

    def run():
        while True:
            try:
                folder = DATA_DIR / "backups"
                folder.mkdir(parents=True, exist_ok=True)
                target = folder / f"gongzhang-{date.today():%Y%m%d}.db"
                if not target.exists():
                    src, dst = connect(), sqlite3.connect(target)
                    src.backup(dst)
                    dst.close()
                    src.close()
                for old in sorted(folder.glob("gongzhang-*.db"))[:-14]:
                    old.unlink()
            except Exception:
                pass
            time.sleep(3600)

    threading.Thread(target=run, daemon=True).start()
