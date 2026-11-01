"""Everything the 汇总 page shows, for any date range: wages per person, fuel / repair / other per project and per vehicle."""
from collections import defaultdict


def _money(x: float) -> float:
    return round(x + 1e-9, 2)


def build(db, d1: str, d2: str) -> dict:
    res = {r["id"]: dict(r) for r in db.execute("SELECT * FROM resources")}

    people: dict[int, dict] = {}
    daily: dict[str, dict] = defaultdict(lambda: {"labor": 0.0, "overtime": 0.0, "fuel": 0.0, "repair": 0.0, "hired": 0.0, "other": 0.0, "tons": 0.0})

    def person(rid: int) -> dict:
        r = res[rid]
        return people.setdefault(rid, {"id": rid, "name": r["name"], "category": r["category"], "is_vehicle": bool(r["is_vehicle"]),
                                       "days": 0.0, "wage": 0.0, "overtime": 0.0, "fuel_qty": 0.0, "fuel": 0.0, "repair": 0.0,
                                       "tons": 0.0, "trips": 0})

    for a in db.execute("SELECT * FROM attendance WHERE date BETWEEN ? AND ?", (d1, d2)):
        p = person(a["resource_id"])
        p["days"] += a["factor"]
        pay = a["rate"] * a["factor"]
        p["wage"] += pay
        daily[a["date"]]["labor"] += pay

    other_rows, repair_rows, hired_rows = [], [], []
    for e in db.execute("SELECT * FROM entries WHERE date BETWEEN ? AND ? ORDER BY date, id", (d1, d2)):
        kind, rid, amt = e["kind"], e["resource_id"], e["amount"]
        if kind == "overtime":
            if rid:
                person(rid)["overtime"] += amt
            daily[e["date"]]["overtime"] += amt
        elif kind == "fuel":
            if rid:
                p = person(rid)
                p["fuel"] += amt
                p["fuel_qty"] += e["qty"] or 0
            daily[e["date"]]["fuel"] += amt
        elif kind == "repair":
            if rid:
                person(rid)["repair"] += amt
            daily[e["date"]]["repair"] += amt
            repair_rows.append(e)
        elif kind == "hired":
            daily[e["date"]]["hired"] += amt
            hired_rows.append(e)
        elif kind == "other":
            daily[e["date"]]["other"] += amt
            other_rows.append(e)

    auto_overtime = 0.0
    for h in db.execute("SELECT * FROM hauls WHERE date BETWEEN ? AND ?", (d1, d2)):
        p = person(h["resource_id"])
        p["tons"] += h["tons"]
        p["trips"] += h["trips"]
        daily[h["date"]]["tons"] += h["tons"]
        r = res.get(h["driver_id"]) if h["driver_id"] else None   # the rule belongs to the driver, not the vehicle
        if r and r["ot_free_trips"] is not None and r["ot_rate"]:
            extra = max(0, h["trips"] - r["ot_free_trips"]) * r["ot_rate"]
            if extra:
                person(r["id"])["overtime"] += extra
                daily[h["date"]]["overtime"] += extra
                auto_overtime += extra

    rows = []
    for p in people.values():
        for k in ("days", "wage", "overtime", "fuel_qty", "fuel", "repair", "tons"):
            p[k] = _money(p[k])
        p["avg_rate"] = _money(p["wage"] / p["days"]) if p["days"] else 0
        p["pay"] = _money(p["wage"] + p["overtime"])
        rows.append(p)
    rows.sort(key=lambda p: (res[p["id"]]["sort"], p["id"]))

    labor = _money(sum(p["wage"] for p in rows))
    # taken from the daily totals so overtime entries with no person attached still count
    overtime = _money(sum(d["overtime"] for d in daily.values()))
    fuel = _money(sum(d["fuel"] for d in daily.values()))
    repair = _money(sum(d["repair"] for d in daily.values()))
    other = _money(sum(d["other"] for d in daily.values()))
    hired = _money(sum(d["hired"] for d in daily.values()))
    days_list = []
    for date in sorted(daily):
        d = daily[date]
        total = d["labor"] + d["overtime"] + d["fuel"] + d["repair"] + d["hired"] + d["other"]
        days_list.append({"date": date, **{k: _money(v) for k, v in d.items()}, "total": _money(total)})

    by_category: dict[str, dict] = {}
    for p in rows:
        if p["days"] or p["wage"] or p["overtime"]:
            c = by_category.setdefault(p["category"] or "未分类", {"category": p["category"] or "未分类", "count": 0, "days": 0.0, "pay": 0.0})
            c["count"] += 1
            c["days"] = _money(c["days"] + p["days"])
            c["pay"] = _money(c["pay"] + p["pay"])

    return {
        "from": d1, "to": d2,
        "totals": {"labor": labor, "overtime": overtime, "fuel": fuel, "repair": repair, "hired": hired, "other": other,
                   "total": _money(labor + overtime + fuel + repair + hired + other),
                   "fuel_qty": _money(sum(p["fuel_qty"] for p in rows)), "tons": _money(sum(p["tons"] for p in rows)),
                   "trips": sum(p["trips"] for p in rows), "auto_overtime": _money(auto_overtime)},
        "people": [p for p in rows if p["days"] or p["wage"] or p["overtime"]],
        "vehicles": [p for p in rows if p["is_vehicle"] and (p["fuel"] or p["repair"] or p["tons"] or p["trips"] or p["days"])],
        "categories": list(by_category.values()),
        "days": days_list,
        "repairs": [{"date": e["date"], "name": res[e["resource_id"]]["name"] if e["resource_id"] else "", "note": e["note"], "amount": e["amount"]} for e in repair_rows],
        "hireds": [{"date": e["date"], "note": e["note"], "amount": e["amount"]} for e in hired_rows],
        "others": [{"date": e["date"], "note": e["note"], "qty": e["qty"], "amount": e["amount"]} for e in other_rows],
    }
