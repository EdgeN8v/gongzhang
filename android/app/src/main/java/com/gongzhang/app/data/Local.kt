package com.gongzhang.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.round

/**
 * The phone keeps a full copy of the books (a few thousand small rows a year) and every screen is computed from it, so the app
 * works without a connection. What the user does is applied to the copy at once and queued as an "op"; ops are sent to the server
 * when it can be reached, then the copy is replaced by the server's.
 */
data class AttRow(val date: String, val resourceId: Int, val factor: Double, val rate: Double, val note: String)
data class EntryRow(val uid: String, val date: String, val kind: String, val resourceId: Int?, val qty: Double?, val price: Double?, val amount: Double, val note: String)
data class HaulRow(val uid: String, val date: String, val resourceId: Int, val driverId: Int?, val tons: Double, val trips: Int, val note: String)

data class Replica(
    val res: List<Res> = emptyList(), val att: List<AttRow> = emptyList(),
    val entries: List<EntryRow> = emptyList(), val hauls: List<HaulRow> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("resources", JSONArray(res.map { it.toJson() }))
        .put("attendance", JSONArray(att.map { JSONObject().put("date", it.date).put("resource_id", it.resourceId).put("factor", it.factor).put("rate", it.rate).put("note", it.note) }))
        .put("entries", JSONArray(entries.map {
            JSONObject().put("uid", it.uid).put("date", it.date).put("kind", it.kind).put("resource_id", it.resourceId ?: JSONObject.NULL)
                .put("qty", it.qty ?: JSONObject.NULL).put("price", it.price ?: JSONObject.NULL).put("amount", it.amount).put("note", it.note)
        }))
        .put("hauls", JSONArray(hauls.map { JSONObject().put("uid", it.uid).put("date", it.date).put("resource_id", it.resourceId).put("driver_id", it.driverId ?: JSONObject.NULL).put("tons", it.tons).put("trips", it.trips).put("note", it.note) }))

    companion object {
        fun fromJson(o: JSONObject) = Replica(
            o.getJSONArray("resources").map(::parseRes),
            o.getJSONArray("attendance").map { AttRow(it.str("date"), it.getInt("resource_id"), it.dbl("factor"), it.dbl("rate"), it.str("note")) },
            o.getJSONArray("entries").map { EntryRow(it.str("uid"), it.str("date"), it.str("kind"), it.intOrNull("resource_id"), it.dblOrNull("qty"), it.dblOrNull("price"), it.dbl("amount"), it.str("note")) },
            o.getJSONArray("hauls").map { HaulRow(it.str("uid"), it.str("date"), it.getInt("resource_id"), it.intOrNull("driver_id"), it.dbl("tons"), it.optInt("trips"), it.str("note")) },
        )
    }
}

/** Files in the app's private folder: the copy of the books and the queue of changes not yet sent. */
object Store {
    private fun file(c: Context, name: String) = File(c.filesDir, name)

    fun loadReplica(c: Context): Replica = try { Replica.fromJson(JSONObject(file(c, "replica.json").readText())) } catch (_: Exception) { Replica() }

    fun loadOutbox(c: Context): List<JSONObject> = try { JSONArray(file(c, "outbox.json").readText()).map { it } } catch (_: Exception) { emptyList() }

    @Synchronized fun save(c: Context, replica: Replica, outbox: List<JSONObject>) {
        atomic(file(c, "replica.json"), replica.toJson().toString())
        atomic(file(c, "outbox.json"), JSONArray(outbox).toString())
    }

    private fun atomic(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}

// ---- ops (what is queued for the server; same shapes as POST /api/push)

object Ops {
    fun att(date: String, rid: Int, factor: Double, rate: Double, note: String = "") =
        JSONObject().put("op", "att").put("date", date).put("resource_id", rid).put("factor", factor).put("rate", rate).put("note", note)

    fun entry(uid: String, date: String, kind: String, rid: Int?, qty: Double?, price: Double?, amount: Double, note: String) =
        JSONObject().put("op", "entry").put("uid", uid).put("date", date).put("kind", kind).put("resource_id", rid ?: JSONObject.NULL)
            .put("qty", qty ?: JSONObject.NULL).put("price", price ?: JSONObject.NULL).put("amount", amount).put("note", note)

    fun entryDel(uid: String) = JSONObject().put("op", "entry_del").put("uid", uid)

    fun haul(uid: String, date: String, rid: Int, driverId: Int?, tons: Double, trips: Int, note: String = "") =
        JSONObject().put("op", "haul").put("uid", uid).put("date", date).put("resource_id", rid).put("driver_id", driverId ?: JSONObject.NULL).put("tons", tons).put("trips", trips).put("note", note)

    fun haulDel(uid: String) = JSONObject().put("op", "haul_del").put("uid", uid)
}

/** The same change the server will make, applied to the phone's copy. */
fun applyOp(r: Replica, op: JSONObject): Replica = when (op.getString("op")) {
    "att" -> {
        val row = AttRow(op.getString("date"), op.getInt("resource_id"), op.getDouble("factor"), op.getDouble("rate"), op.str("note"))
        val i = r.att.indexOfFirst { it.date == row.date && it.resourceId == row.resourceId }
        r.copy(att = if (i >= 0) r.att.toMutableList().also { it[i] = row } else r.att + row)
    }
    "att_del" -> r.copy(att = r.att.filterNot { it.date == op.getString("date") && it.resourceId == op.getInt("resource_id") })
    "entry" -> {
        val row = EntryRow(op.getString("uid"), op.getString("date"), op.getString("kind"), op.intOrNull("resource_id"), op.dblOrNull("qty"), op.dblOrNull("price"), op.getDouble("amount"), op.str("note"))
        val i = r.entries.indexOfFirst { it.uid == row.uid }
        r.copy(entries = if (i >= 0) r.entries.toMutableList().also { it[i] = row } else r.entries + row)
    }
    "entry_del" -> r.copy(entries = r.entries.filterNot { it.uid == op.getString("uid") })
    "haul" -> {
        val row = HaulRow(op.getString("uid"), op.getString("date"), op.getInt("resource_id"), op.intOrNull("driver_id"), op.getDouble("tons"), op.getInt("trips"), op.str("note"))
        val i = r.hauls.indexOfFirst { it.uid == row.uid }
        r.copy(hauls = if (i >= 0) r.hauls.toMutableList().also { it[i] = row } else r.hauls + row)
    }
    "haul_del" -> r.copy(hauls = r.hauls.filterNot { it.uid == op.getString("uid") })
    else -> r
}

// ---- the arithmetic (same rules as server/app/report.py, so offline and online show the same numbers)

private fun r2(x: Double) = round((x + 1e-9) * 100) / 100

/** Extra pay for a driver whose pay depends on trips (e.g. beyond 2 trips a day, 70 yuan more for each). */
private fun autoOvertime(res: Res?, trips: Int): Double =
    if (res?.otFree != null && (res.otRate ?: 0.0) > 0) max(0, trips - res.otFree) * res.otRate!! else 0.0

fun dayOf(r: Replica, date: String): Day {
    val byId = r.res.associateBy { it.id }
    val order = r.res.withIndex().associate { it.value.id to it.index }
    val att = r.att.filter { it.date == date && byId.containsKey(it.resourceId) }.sortedBy { order[it.resourceId] }
        .map { Att(it.resourceId, byId[it.resourceId]!!.name, byId[it.resourceId]!!.category, it.factor, it.rate, it.note) }
    val entries = r.entries.filter { it.date == date }.map { Entry(it.uid, it.date, it.kind, it.resourceId, it.resourceId?.let { id -> byId[id]?.name }.orEmpty(), it.qty, it.price, it.amount, it.note) }
    val hauls = r.hauls.filter { it.date == date }.map { Haul(it.uid, it.resourceId, byId[it.resourceId]?.name.orEmpty(), it.driverId, it.driverId?.let { id -> byId[id]?.name }.orEmpty(), it.tons, it.trips, it.note) }
    val labor = r2(att.sumOf { it.rate * it.factor })
    val ot = hauls.sumOf { autoOvertime(it.driverId?.let { id -> byId[id] }, it.trips) }
    return Day(date, att, entries, hauls, labor, r2(labor + entries.sumOf { it.amount } + ot))
}

fun hintsOf(r: Replica): Hints {
    val fuelPrice = r.entries.filter { it.kind == "fuel" && it.price != null }.withIndex().maxWithOrNull(compareBy({ it.value.date }, { it.index }))?.value?.price
    fun notes(kind: String) = r.entries.filter { it.kind == kind && it.note.isNotBlank() }.groupBy { it.note }
        .entries.sortedWith(compareByDescending<Map.Entry<String, List<EntryRow>>> { it.value.size }.thenByDescending { e -> e.value.maxOf { it.date } })
        .take(8).map { it.key }
    return Hints(fuelPrice, listOf("other", "repair", "overtime", "hired").associateWith(::notes))
}

private class Acc { var days = 0.0; var wage = 0.0; var overtime = 0.0; var fuelQty = 0.0; var fuel = 0.0; var repair = 0.0; var tons = 0.0; var trips = 0 }
private class DayAcc { var labor = 0.0; var overtime = 0.0; var fuel = 0.0; var repair = 0.0; var hired = 0.0; var other = 0.0 }

fun reportOf(r: Replica, from: String, to: String): Report {
    val byId = r.res.associateBy { it.id }
    val order = r.res.withIndex().associate { it.value.id to it.index }
    val people = HashMap<Int, Acc>()
    val daily = java.util.TreeMap<String, DayAcc>()
    fun acc(id: Int) = people.getOrPut(id) { Acc() }
    fun day(d: String) = daily.getOrPut(d) { DayAcc() }
    fun inRange(d: String) = d >= from && d <= to

    for (a in r.att) if (inRange(a.date) && byId.containsKey(a.resourceId)) {
        val p = acc(a.resourceId); p.days += a.factor; p.wage += a.rate * a.factor; day(a.date).labor += a.rate * a.factor
    }
    val lines = ArrayList<Line>()
    for (e in r.entries) if (inRange(e.date)) {
        val d = day(e.date); val p = e.resourceId?.takeIf { byId.containsKey(it) }?.let(::acc); val name = e.resourceId?.let { byId[it]?.name }.orEmpty()
        when (e.kind) {
            "overtime" -> { p?.let { it.overtime += e.amount }; d.overtime += e.amount }
            "fuel" -> { p?.let { it.fuel += e.amount; it.fuelQty += e.qty ?: 0.0 }; d.fuel += e.amount }
            "repair" -> { p?.let { it.repair += e.amount }; d.repair += e.amount; lines += Line(e.date, "维修", listOf(name, e.note).filter { s -> s.isNotBlank() }.joinToString(" "), e.amount) }
            "hired" -> { d.hired += e.amount; lines += Line(e.date, "外雇车", e.note, e.amount) }
            "other" -> { d.other += e.amount; lines += Line(e.date, "其他", e.note, e.amount) }
        }
    }
    var auto = 0.0
    for (h in r.hauls) if (inRange(h.date) && byId.containsKey(h.resourceId)) {
        val p = acc(h.resourceId); p.tons += h.tons; p.trips += h.trips
        val driver = h.driverId?.let { byId[it] }
        val extra = autoOvertime(driver, h.trips)
        if (driver != null && extra > 0) { acc(driver.id).overtime += extra; day(h.date).overtime += extra; auto += extra }
    }

    val rows = people.entries.sortedBy { order[it.key] ?: Int.MAX_VALUE }
    val labor = r2(daily.values.sumOf { it.labor }); val overtime = r2(daily.values.sumOf { it.overtime }); val fuel = r2(daily.values.sumOf { it.fuel })
    val repair = r2(daily.values.sumOf { it.repair }); val hired = r2(daily.values.sumOf { it.hired }); val other = r2(daily.values.sumOf { it.other })

    val payroll = rows.filter { (_, p) -> p.days > 0 || p.wage > 0 || p.overtime > 0 }.map { (id, p) ->
        val res = byId[id]!!
        PersonRow(res.name, res.category, r2(p.days), if (p.days > 0) r2(p.wage / p.days) else 0.0, r2(p.overtime), r2(p.wage + p.overtime))
    }
    val vehicles = rows.filter { (id, p) -> byId[id]!!.isVehicle && (p.fuel > 0 || p.repair > 0 || p.tons > 0 || p.trips > 0 || p.days > 0) }
        .map { (id, p) -> VehicleRow(byId[id]!!.name, r2(p.fuelQty), r2(p.fuel), r2(p.repair), r2(p.tons), p.trips) }
    val cats = LinkedHashMap<String, CatRow>()
    for (pr in payroll) {
        val k = pr.category.ifBlank { "未分类" }
        val c = cats[k]; cats[k] = CatRow(k, (c?.count ?: 0) + 1, r2((c?.pay ?: 0.0) + pr.pay))
    }
    val days = daily.entries.map { (d, a) -> DayRow(d, r2(a.labor), r2(a.overtime), r2(a.fuel), r2(a.repair), r2(a.hired), r2(a.other), r2(a.labor + a.overtime + a.fuel + a.repair + a.hired + a.other)) }

    return Report(
        from, to, r2(labor + overtime + fuel + repair + hired + other), labor, overtime, fuel, repair, hired, other,
        r2(rows.sumOf { it.value.fuelQty }), r2(rows.sumOf { it.value.tons }), rows.sumOf { it.value.trips }, r2(auto),
        payroll, vehicles, days, lines.sortedByDescending { it.date }, cats.values.toList(),
    )
}
