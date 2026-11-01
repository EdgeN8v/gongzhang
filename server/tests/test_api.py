import importlib
import io

import pytest
from fastapi.testclient import TestClient
from openpyxl import load_workbook


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("GZ_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("GZ_SECRET", "test-secret-test-secret")
    monkeypatch.setenv("GZ_USERS", "妈妈:abc12345")
    from app import db, main
    importlib.reload(db)
    importlib.reload(main)
    with TestClient(main.app) as c:
        token = c.post("/api/login", json={"username": "妈妈", "password": "abc12345"}).json()["token"]
        c.headers["Authorization"] = f"Bearer {token}"
        yield c


def add(c, **kw):
    r = c.post("/api/resources", json=kw)
    assert r.status_code == 200, r.text
    return r.json()


def test_login_required_and_wrong_password(client):
    anon = TestClient(client.app)
    assert anon.get("/api/resources").status_code == 401
    assert anon.post("/api/login", json={"username": "妈妈", "password": "nope"}).status_code == 401


def test_october_7th_note(client):
    """The handwritten page: wages unchanged, 1017 L of diesel at 7.7 = 7831, two trucks' tonnage, 150 repair, 159 + 78 other."""
    worker = add(client, name="张三", category="环卫工人", pay_type="month", amount=1800)
    truck = add(client, name="辽K8B330", category="车辆", is_vehicle=True, pay_type="day", amount=0)
    driver = add(client, name="司机甲", category="司机", pay_type="month", amount=4150, ot_free_trips=2, ot_rate=70)
    shovel = add(client, name="50铲车", category="铲车", is_vehicle=True, pay_type="day", amount=600)
    day = "2026-10-07"

    d = client.post(f"/api/day/{day}/confirm", json={}).json()
    assert len(d["attendance"]) == 4
    assert d["totals"]["labor"] == pytest.approx(60 + 138.33 + 600, abs=0.02)

    client.post("/api/entries", json={"date": day, "kind": "fuel", "resource_id": shovel["id"], "qty": 157, "price": 7.7})
    client.post("/api/entries", json={"date": day, "kind": "fuel", "resource_id": truck["id"], "qty": 268, "price": 7.7})
    client.post("/api/hauls", json={"date": day, "resource_id": truck["id"], "driver_id": driver["id"], "tons": 43.54, "trips": 4})
    client.post("/api/entries", json={"date": day, "kind": "repair", "resource_id": shovel["id"], "note": "补胎", "amount": 150})
    d = client.post("/api/entries", json={"date": day, "kind": "other", "note": "盒饭 6人", "amount": 78}).json()
    assert d["totals"]["fuel"] == pytest.approx(157 * 7.7 + 268 * 7.7)
    assert d["totals"]["repair"] == 150 and d["totals"]["other"] == 78

    rep = client.get("/api/report", params={"from": "2026-10-01", "to": "2026-10-31"}).json()
    t = rep["totals"]
    assert t["fuel_qty"] == 425 and t["tons"] == 43.54
    assert t["overtime"] == 140  # 4 trips, 2 free, 2 x 70
    assert t["total"] == pytest.approx(t["labor"] + 140 + t["fuel"] + 150 + 78)
    v = {x["name"]: x for x in rep["vehicles"]}
    assert v["辽K8B330"]["fuel"] == pytest.approx(268 * 7.7)

    # other ranges do not leak in
    assert client.get("/api/report", params={"from": "2026-10-08", "to": "2026-10-09"}).json()["totals"]["total"] == 0

    xlsx = client.get("/api/report.xlsx", params={"from": "2026-10-01", "to": "2026-10-31"})
    assert xlsx.status_code == 200
    assert "工资表" in load_workbook(io.BytesIO(xlsx.content)).sheetnames


def test_wage_change_does_not_rewrite_old_days(client):
    r = add(client, name="李四", category="环卫工人", pay_type="month", amount=3000)
    client.post("/api/day/2026-10-01/confirm", json={})
    client.put(f"/api/resources/{r['id']}", json={**r, "amount": 6000})
    client.post("/api/day/2026-10-02/confirm", json={})
    rep = client.get("/api/report", params={"from": "2026-10-01", "to": "2026-10-02"}).json()
    assert rep["people"][0]["wage"] == pytest.approx(100 + 200)


def test_half_day_absent_and_copy_previous(client):
    a = add(client, name="甲", pay_type="day", amount=100)
    b = add(client, name="乙", pay_type="day", amount=100)
    client.post("/api/day/2026-10-01/confirm", json={})
    client.put("/api/day/2026-10-01/attendance", json={"resource_id": a["id"], "factor": 0.5})
    client.put("/api/day/2026-10-01/attendance", json={"resource_id": b["id"], "factor": 0})
    d = client.post("/api/day/2026-10-02/confirm", json={"from_prev": True}).json()
    assert {x["name"]: x["factor"] for x in d["attendance"]} == {"甲": 0.5, "乙": 0}
    assert client.get("/api/day/2026-10-01").json()["totals"]["labor"] == 50


def test_update_endpoint(client, tmp_path):
    assert client.get("/app-update/latest").status_code == 404
    (tmp_path / "updates").mkdir()
    (tmp_path / "updates" / "app.apk").write_bytes(b"apk")
    (tmp_path / "updates" / "version.json").write_text('{"versionCode": 2, "versionName": "0.2", "apk": "app.apk"}')
    assert client.get("/app-update/latest").json()["versionCode"] == 2
    assert client.get("/app-update/apk").content == b"apk"


def test_dump_and_push_are_idempotent(client):
    r = add(client, name="货车", is_vehicle=True, pay_type="day", amount=700)
    ops = [
        {"op": "att", "date": "2026-10-08", "resource_id": r["id"], "factor": 1, "rate": 700, "note": ""},
        {"op": "entry", "uid": "u1", "date": "2026-10-08", "kind": "fuel", "resource_id": r["id"], "qty": 100, "price": 7.7, "amount": 770, "note": ""},
        {"op": "entry", "uid": "u2", "date": "2026-10-08", "kind": "hired", "resource_id": None, "qty": None, "price": None, "amount": 800, "note": "小钩机"},
        {"op": "haul", "uid": "h1", "date": "2026-10-08", "resource_id": r["id"], "tons": 40.5, "trips": 2, "note": ""},
        {"op": "entry", "uid": "bad", "date": "2026-10-08", "kind": "nonsense", "amount": 1},
        {"op": "att", "date": "2026-10-08", "resource_id": 9999, "factor": 1, "rate": 1},
    ]
    for _ in range(2):  # a retried batch must not double anything
        res = client.post("/api/push", json={"ops": ops}).json()
        assert res == {"applied": 4, "skipped": 2}
    d = client.get("/api/dump").json()
    assert len(d["entries"]) == 2 and len(d["hauls"]) == 1 and len(d["attendance"]) == 1
    # an edit and a delete by uid
    client.post("/api/push", json={"ops": [
        {"op": "entry", "uid": "u1", "date": "2026-10-08", "kind": "fuel", "resource_id": r["id"], "qty": 110, "price": 7.7, "amount": 847, "note": ""},
        {"op": "entry_del", "uid": "u2"}, {"op": "haul_del", "uid": "h1"}]})
    d = client.get("/api/dump").json()
    assert [e["amount"] for e in d["entries"]] == [847] and d["hauls"] == []
    rep = client.get("/api/report", params={"from": "2026-10-08", "to": "2026-10-08"}).json()
    assert rep["totals"]["hired"] == 0 and rep["totals"]["fuel"] == 847


def test_delete_resource(client):
    spare = add(client, name="多余的人", pay_type="day", amount=100)
    assert client.delete(f"/api/resources/{spare['id']}").json()["deleted"] == spare["id"]
    assert all(r["id"] != spare["id"] for r in client.get("/api/resources").json())
    assert client.delete(f"/api/resources/{spare['id']}").status_code == 404

    truck = add(client, name="辽K12345", is_vehicle=True, pay_type="day", amount=0)
    driver = add(client, name="司机乙", pay_type="month", amount=3000, ot_free_trips=2, ot_rate=50)
    client.post("/api/push", json={"ops": [
        {"op": "att", "date": "2026-10-09", "resource_id": driver["id"], "factor": 1, "rate": 100},
        {"op": "entry", "uid": "f1", "date": "2026-10-09", "kind": "fuel", "resource_id": truck["id"], "qty": 10, "price": 7, "amount": 70, "note": ""},
        {"op": "haul", "uid": "h9", "date": "2026-10-09", "resource_id": truck["id"], "driver_id": driver["id"], "tons": 30, "trips": 3, "note": ""}]})
    r = client.delete(f"/api/resources/{truck['id']}")
    assert r.status_code == 409 and "条记录" in r.json()["detail"]          # has records: not without force
    assert client.delete(f"/api/resources/{truck['id']}", params={"force": "true"}).json()["records"] == 2
    d = client.get("/api/dump").json()
    assert d["hauls"] == [] and [e["amount"] for e in d["entries"]] == [70] and d["entries"][0]["resource_id"] is None
    assert client.delete(f"/api/resources/{driver['id']}", params={"force": "true"}).status_code == 200
    assert client.get("/api/dump").json()["attendance"] == []
