"""Load the roster (人员 + 车辆 and their wages) from the family's 工资明细表.xlsx into a running server.

    python tools/import_excel.py 工资明细表.xlsx http://localhost:8100 妈妈

It reads the newest 人工费用 sheet (the first one), and asks for the password on the command line, so nothing is stored.
Run it once; running it twice skips names that already exist."""
import getpass
import json
import sys
import urllib.request

import openpyxl

RENAME = {"压缩车": "压缩车（辽K0B777）"}  # the 其他车费用 sheet shows 压缩车 is 辽KOB777; the handwritten fuel list uses the plate
OT_RULE = {"K8B330": (2, 70), "K6B238": (2, 70)}  # from the 货车费用 sheet: MAX(0, 车次 - 2) * 70


def call(base, path, token=None, body=None):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})})
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read())


def roster(path):
    ws = next(w for w in openpyxl.load_workbook(path, data_only=False) if w.title.startswith("人工费用"))
    out = []
    for row in ws.iter_rows(min_row=2, values_only=True):
        name, kind, monthly, daily = row[1], row[3], row[4], row[5]
        if not name:
            break
        if isinstance(monthly, (int, float)):
            pay_type, amount = "month", float(monthly)
        elif isinstance(daily, (int, float)):
            pay_type, amount = "day", float(daily)
        else:
            continue
        name = str(name).strip()
        name = RENAME.get(name, name)
        is_vehicle = any(w in name for w in ("车", "辽"))
        item = {"name": name, "category": kind or ("货车" if "辽" in name else "车辆"), "is_vehicle": is_vehicle, "pay_type": pay_type, "amount": amount}
        for plate, (free, rate) in OT_RULE.items():
            if plate in name:
                item.update(ot_free_trips=free, ot_rate=rate)
        out.append(item)
    return out


if __name__ == "__main__":
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    path, base, user = sys.argv[1], sys.argv[2].rstrip("/"), sys.argv[3]
    token = call(base, "/api/login", body={"username": user, "password": getpass.getpass("密码: ")})["token"]
    have = {r["name"] for r in call(base, "/api/resources", token)}
    new = [r for r in roster(path) if r["name"] not in have]
    for r in new:
        call(base, "/api/resources", token, r)
        print("+", r["name"], r["category"], r["pay_type"], r["amount"])
    print(f"导入 {len(new)} 个，跳过 {len(roster(path)) - len(new)} 个已有的")
