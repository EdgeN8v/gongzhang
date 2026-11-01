"""汇总 as an Excel file, because 开工资 ends up on paper / WeChat as a sheet anyway."""
from io import BytesIO

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

KIND_NAMES = {"labor": "人工(出勤工资)", "overtime": "加班费", "fuel": "油费", "repair": "维修费", "hired": "外雇车", "other": "其他费用"}


def _sheet(ws, header, rows, widths=None):
    ws.append(header)
    for c in ws[1]:
        c.font = Font(bold=True)
        c.fill = PatternFill("solid", fgColor="E8EEF4")
        c.alignment = Alignment(horizontal="center")
    for r in rows:
        ws.append(r)
    for i, h in enumerate(header, 1):
        ws.column_dimensions[get_column_letter(i)].width = (widths or {}).get(i, max(10, len(str(h)) * 2 + 2))
    ws.freeze_panes = "A2"


def to_xlsx(rep: dict) -> bytes:
    t = rep["totals"]
    wb = Workbook()
    ws = wb.active
    ws.title = "汇总"
    _sheet(ws, ["项目", "金额(元)"], [[KIND_NAMES[k], t[k]] for k in KIND_NAMES] + [["合计", t["total"]], [], ["加油合计(升)", t["fuel_qty"]], ["拉运吨数", t["tons"]], ["车次", t["trips"]]], {1: 20, 2: 14})
    ws.insert_rows(1)
    ws["A1"] = f"{rep['from']} 至 {rep['to']}"
    ws["A1"].font = Font(bold=True, size=13)

    ws = wb.create_sheet("工资表")
    rows = [[p["name"], p["category"], p["days"], p["avg_rate"], p["wage"], p["overtime"], p["pay"]] for p in rep["people"]]
    rows.append(["合计", "", "", "", round(sum(p["wage"] for p in rep["people"]), 2), round(sum(p["overtime"] for p in rep["people"]), 2), round(sum(p["pay"] for p in rep["people"]), 2)])
    _sheet(ws, ["姓名/车辆", "工种", "出勤天数", "日工资", "工资", "加班费", "应发合计"], rows, {1: 22})
    ws.cell(ws.max_row, 1).font = Font(bold=True)

    ws = wb.create_sheet("车辆")
    rows = [[v["name"], v["fuel_qty"], v["fuel"], v["repair"], v["tons"], v["trips"]] for v in rep["vehicles"]]
    _sheet(ws, ["车辆", "加油(升)", "油费", "维修费", "吨数", "车次"], rows, {1: 22})

    ws = wb.create_sheet("每日")
    rows = [[d["date"], d["labor"], d["overtime"], d["fuel"], d["repair"], d["hired"], d["other"], d["total"], d["tons"]] for d in rep["days"]]
    _sheet(ws, ["日期", "人工", "加班费", "油费", "维修费", "外雇车", "其他费用", "当日合计", "吨数"], rows, {1: 14})

    ws = wb.create_sheet("维修和其他")
    rows = [[r["date"], "维修", r["name"], r["note"], r["amount"]] for r in rep["repairs"]] + [[r["date"], "外雇车", "", r["note"], r["amount"]] for r in rep["hireds"]] + [[r["date"], "其他", "", r["note"], r["amount"]] for r in rep["others"]]
    rows.sort(key=lambda r: r[0])
    _sheet(ws, ["日期", "类型", "车辆", "内容", "金额"], rows, {1: 14, 4: 30})

    out = BytesIO()
    wb.save(out)
    return out.getvalue()
