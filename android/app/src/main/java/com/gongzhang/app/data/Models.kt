package com.gongzhang.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

// ---- what the screens show (all of it is computed on the phone from the local copy, see Local.kt)

data class Res(
    val id: Int, val name: String, val category: String, val isVehicle: Boolean, val payType: String, val amount: Double,
    val otFree: Int?, val otRate: Double?, val active: Boolean, val rate: Double,
)

data class Att(val resourceId: Int, val name: String, val category: String, val factor: Double, val rate: Double, val note: String)

data class Entry(val uid: String, val date: String, val kind: String, val resourceId: Int?, val resourceName: String, val qty: Double?, val price: Double?, val amount: Double, val note: String)

data class Haul(val uid: String, val resourceId: Int, val resourceName: String, val driverId: Int?, val driverName: String, val tons: Double, val trips: Int)

data class Day(val date: String, val att: List<Att>, val entries: List<Entry>, val hauls: List<Haul>, val labor: Double, val total: Double)

data class Hints(val fuelPrice: Double?, val notes: Map<String, List<String>>)

data class PersonRow(val name: String, val category: String, val days: Double, val avgRate: Double, val overtime: Double, val pay: Double)
data class VehicleRow(val name: String, val fuelQty: Double, val fuel: Double, val repair: Double, val tons: Double, val trips: Int)
data class DayRow(val date: String, val labor: Double, val overtime: Double, val fuel: Double, val repair: Double, val hired: Double, val other: Double, val total: Double)
data class Line(val date: String, val tag: String, val text: String, val amount: Double)
data class CatRow(val category: String, val count: Int, val pay: Double)

data class Report(
    val from: String, val to: String,
    val total: Double, val labor: Double, val overtime: Double, val fuel: Double, val repair: Double, val hired: Double, val other: Double,
    val fuelQty: Double, val tons: Double, val trips: Int, val autoOvertime: Double,
    val people: List<PersonRow>, val vehicles: List<VehicleRow>, val days: List<DayRow>, val lines: List<Line>, val categories: List<CatRow>,
)

// ---- JSON helpers

fun JSONObject.dbl(k: String) = optDouble(k, 0.0)
fun JSONObject.dblOrNull(k: String) = if (isNull(k) || !has(k)) null else getDouble(k)
fun JSONObject.intOrNull(k: String) = if (isNull(k) || !has(k)) null else getInt(k)
fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k, "")
fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

fun parseRes(o: JSONObject) = Res(o.getInt("id"), o.str("name"), o.str("category"), o.getBoolean("is_vehicle"), o.str("pay_type"), o.dbl("amount"),
    o.intOrNull("ot_free_trips"), o.dblOrNull("ot_rate"), o.getBoolean("active"), o.dbl("rate"))

fun Res.toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("category", category).put("is_vehicle", isVehicle).put("pay_type", payType)
    .put("amount", amount).put("ot_free_trips", otFree ?: JSONObject.NULL).put("ot_rate", otRate ?: JSONObject.NULL).put("active", active).put("rate", rate)

// ---- formatting

/** 1234.5 -> "1,234.5", 7830.9 -> "7,830.9", 60.0 -> "60" */
fun money(x: Double): String {
    val s = String.format(Locale.US, "%,.2f", x)
    return s.trimEnd('0').trimEnd('.')
}
fun yuan(x: Double) = "¥" + money(x)

fun cnDate(iso: String): String = LocalDate.parse(iso).let { "${it.monthValue}月${it.dayOfMonth}日" }
val WEEK = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
fun weekOf(iso: String): String = WEEK[LocalDate.parse(iso).dayOfWeek.value - 1]
