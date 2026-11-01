package com.gongzhang.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gongzhang.app.Vm
import com.gongzhang.app.data.*
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.round

private val KIND_NAME = mapOf("fuel" to "加油", "repair" to "维修费", "hired" to "外雇车", "other" to "其他费用", "overtime" to "加班费")
private val KIND_STEP = mapOf("fuel" to 2, "repair" to 4, "hired" to 5, "other" to 6, "overtime" to 7)

private sealed interface Dlg {
    data class EntryD(val kind: String, val existing: Entry?, val key: Int = 0) : Dlg
    data class HaulD(val existing: Haul?) : Dlg
    data object AttD : Dlg
}

@Composable
fun TodayScreen(vm: Vm) {
    val context = LocalContext.current
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    val d = vm.day
    val today = LocalDate.now().toString()

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.primary) {
            Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { vm.date = LocalDate.parse(vm.date).minusDays(1).toString() }, modifier = Modifier.size(48.dp)) { Text("‹", fontSize = 26.sp) }
                    Column(Modifier.weight(1f).clickable { pickDate(context, vm.date) { vm.date = it } }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(cnDate(vm.date), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                        val rel = when (vm.date) { today -> " · 今天"; LocalDate.parse(today).minusDays(1).toString() -> " · 昨天"; else -> "" }
                        Text("${vm.date.take(4)}年 ${weekOf(vm.date)}$rel  ▾", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .85f))
                    }
                    if (vm.date != today) TextButton(onClick = { vm.date = today }) { Text("今天", color = MaterialTheme.colorScheme.onPrimary, fontSize = 16.sp) }
                    FilledTonalIconButton(onClick = { vm.date = LocalDate.parse(vm.date).plusDays(1).toString() }, modifier = Modifier.size(48.dp)) { Text("›", fontSize = 26.sp) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("当天合计", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .9f), fontSize = 15.sp)
                        if (vm.syncText.isNotBlank()) Text(vm.syncText, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .75f), fontSize = 12.sp)
                    }
                    Text(yuan(d.total), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        if (vm.syncing) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        PullRefresh(vm, Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!vm.hasData) {
                SectionCard(null, "还没有数据") { Muted("第一次需要联网。连上网络后，在这个页面往下拉刷新。"); Spacer(Modifier.height(8.dp)); BigButton("刷新", false) { vm.refresh() } }
            } else {
                AttendanceCard(vm, d) { dlg = Dlg.AttD }
                EntryCard(d, "fuel") { dlg = Dlg.EntryD("fuel", it) }
                HaulCard(d) { dlg = Dlg.HaulD(it) }
                EntryCard(d, "repair") { dlg = Dlg.EntryD("repair", it) }
                EntryCard(d, "hired") { dlg = Dlg.EntryD("hired", it) }
                EntryCard(d, "other") { dlg = Dlg.EntryD("other", it) }
                EntryCard(d, "overtime") { dlg = Dlg.EntryD("overtime", it) }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }

    when (val x = dlg) {
        is Dlg.EntryD -> key(x.key) { EntryDialog(vm, x.kind, x.existing, { dlg = null }) { again -> dlg = if (again) Dlg.EntryD(x.kind, null, x.key + 1) else null } }
        is Dlg.HaulD -> HaulDialog(vm, x.existing) { dlg = null }
        Dlg.AttD -> AttendanceDialog(vm) { dlg = null }
        null -> {}
    }
}

@Composable
private fun AttendanceCard(vm: Vm, d: Day, openList: () -> Unit) {
    val att = d.att
    SectionCard(1, "出勤", if (att.isNotEmpty()) yuan(d.labor) else null) {
        if (att.isEmpty()) {
            Muted("还没记这一天的出勤。点开名单，没来的人取消勾选，再点确定。")
            Spacer(Modifier.height(10.dp))
            BigButton("✅ 记今天出勤（默认全部到）") { openList() }
            Spacer(Modifier.height(8.dp))
            BigButton("和上一次记的一样", false) { vm.confirmDay(true) }
        } else {
            val full = att.count { it.factor >= 1 }; val half = att.count { it.factor > 0 && it.factor < 1 }; val off = att.count { it.factor == 0.0 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("到 ${full + half} 个人", fontSize = 18.sp)
                    Muted("全天 $full · 半天 $half · 缺勤 $off")
                }
                FilledTonalButton(onClick = openList, modifier = Modifier.heightIn(min = 48.dp)) { Text("修改名单", fontSize = 16.sp) }
            }
            val absent = att.filter { it.factor == 0.0 }
            if (absent.isNotEmpty()) Muted("没来：" + absent.joinToString("、") { it.name }, Modifier.padding(top = 6.dp))
        }
    }
}

/** Roll call: everybody who is on the payroll, ticked. Untick who did not come, tap 确定. */
@Composable
private fun AttendanceDialog(vm: Vm, onDismiss: () -> Unit) {
    val have = remember { vm.day.att.associateBy { it.resourceId } }
    val people = remember { vm.res.filter { !it.isVehicle && (it.active || have.containsKey(it.id)) } }
    val states = remember { mutableStateMapOf<Int, Double>().also { m -> people.forEach { m[it.id] = have[it.id]?.factor ?: 1.0 } } }
    val groups = remember { people.groupBy { it.category.ifBlank { if (it.isVehicle) "车辆" else "其他" } } }
    val here = states.values.count { it > 0 }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f)) {
            Column(Modifier.padding(top = 16.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${cnDate(vm.date)} 出勤", style = MaterialTheme.typography.titleMedium)
                        Muted("到 $here / 共 ${people.size}，没来的取消勾选")
                    }
                    TextButton(onClick = { people.forEach { states[it.id] = 1.0 } }) { Text("全部勾选", fontSize = 15.sp) }
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).padding(top = 6.dp)) {
                    groups.forEach { (cat, list) ->
                        item(key = "h$cat") {
                            val allOn = list.all { (states[it.id] ?: 0.0) > 0 }
                            Row(Modifier.fillMaxWidth().clickable { list.forEach { states[it.id] = if (allOn) 0.0 else 1.0 } }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("$cat（${list.count { (states[it.id] ?: 0.0) > 0 }}/${list.size}）", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                                Text(if (allOn) "整组取消" else "整组勾选", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        items(list.size, key = { list[it].id }) { i ->
                            val r = list[i]
                            val f = states[r.id] ?: 0.0
                            Row(Modifier.fillMaxWidth().clickable { states[r.id] = if (f > 0) 0.0 else 1.0 }.padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = f > 0, onCheckedChange = { states[r.id] = if (it) 1.0 else 0.0 })
                                Text(r.name, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1, color = if (f > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
                                if (f > 0) FilterChip(selected = f < 1, onClick = { states[r.id] = if (f < 1) 1.0 else 0.5 }, label = { Text("半天") })
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 52.dp)) { Text("取消", fontSize = 17.sp) }
                    Button(onClick = { vm.saveAttendance(states.toMap()); onDismiss() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("确定（到 $here 个）", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(d: Day, kind: String, open: (Entry?) -> Unit) {
    val list = d.entries.filter { it.kind == kind }
    SectionCard(KIND_STEP[kind], KIND_NAME[kind]!!, if (list.isNotEmpty()) yuan(list.sumOf { it.amount }) else null) {
        if (list.isEmpty()) Muted("今天没有")
        list.forEach { e ->
            Row(Modifier.fillMaxWidth().clickable { open(e) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (kind == "other" || kind == "hired") e.note.ifBlank { KIND_NAME[kind]!! } else e.resourceName.ifBlank { e.note.ifBlank { KIND_NAME[kind]!! } }, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    val sub = if (kind == "fuel") "${money(e.qty ?: 0.0)} 升 × ${money(e.price ?: 0.0)} 元" else if (e.resourceName.isNotBlank()) e.note else ""
                    if (sub.isNotBlank()) Muted(sub)
                }
                Text(yuan(e.amount), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        AddButton("＋ 记一笔${KIND_NAME[kind]}") { open(null) }
    }
}

@Composable
private fun HaulCard(d: Day, open: (Haul?) -> Unit) {
    SectionCard(3, "吨数", if (d.hauls.isNotEmpty()) "${money(d.hauls.sumOf { it.tons })} 吨" else null) {
        if (d.hauls.isEmpty()) Muted("今天没有")
        d.hauls.forEach { h ->
            Row(Modifier.fillMaxWidth().clickable { open(h) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(h.resourceName, fontSize = 18.sp, fontWeight = FontWeight.Medium); Muted("${h.trips} 车" + if (h.driverName.isNotBlank()) " · 司机 ${h.driverName}" else "") }
                Text("${money(h.tons)} 吨", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        AddButton("＋ 记吨数") { open(null) }
    }
}

@Composable
private fun EntryDialog(vm: Vm, kind: String, existing: Entry?, onDismiss: () -> Unit, onSaved: (again: Boolean) -> Unit) {
    val pool = if (kind == "overtime") vm.res.filter { it.active } else vm.res.filter { it.active && it.isVehicle }.ifEmpty { vm.res.filter { it.active } }
    var rid by remember { mutableStateOf(existing?.resourceId) }
    var qty by remember { mutableStateOf(existing?.qty?.let(::fmtNum) ?: "") }
    var price by remember { mutableStateOf((existing?.price ?: if (kind == "fuel") vm.hints.fuelPrice else null)?.let(::fmtNum) ?: "") }
    var amount by remember { mutableStateOf(existing?.amount?.let(::fmtNum) ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: if (kind == "hired") "小钩机" else "") }
    var manual by remember { mutableStateOf(existing != null && kind == "fuel" && round((existing.qty ?: 0.0) * (existing.price ?: 0.0) * 100) / 100 != existing.amount) }
    fun recalc() {
        if (kind != "fuel" || manual) return
        val q = qty.toDoubleOrNull(); val p = price.toDoubleOrNull()
        amount = if (q != null && p != null) fmtNum(round(q * p * 100) / 100) else ""
    }
    fun save(again: Boolean) {
        val a = amount.toDoubleOrNull()
        when {
            (kind == "fuel" || kind == "overtime") && rid == null -> vm.message = if (kind == "fuel") "请选哪辆车" else "请选给谁"
            kind == "other" && note.isBlank() -> vm.message = "请写是什么费用"
            kind == "hired" && note.isBlank() -> vm.message = "请写是什么车"
            a == null -> vm.message = "请填金额"
            else -> vm.saveEntry(existing, kind, rid, if (kind == "fuel") qty.toDoubleOrNull() else null, if (kind == "fuel") price.toDoubleOrNull() else null, a, note.trim()) { onSaved(again) }
        }
    }
    FormDialog(
        title = (if (existing == null) "记" else "修改") + KIND_NAME[kind], onDismiss = onDismiss, onSave = { save(false) },
        extra = if (existing == null && kind == "fuel") ({ FilledTonalButton(onClick = { save(true) }, modifier = Modifier.heightIn(min = 46.dp)) { Text("再记一辆", fontSize = 16.sp) } }) else null,
        onDelete = existing?.let { e -> { vm.deleteEntry(e) { onSaved(false) } } },
    ) {
        if (kind != "other" && kind != "hired") {
            Muted(when (kind) { "overtime" -> "给谁"; "repair" -> "哪辆车（可不选）"; else -> "哪辆车" })
            Chips(pool.map { it.id as Int? to it.name }, rid) { rid = if (kind == "repair" && rid == it) null else it }
        }
        if (kind == "fuel") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("加了多少升", qty, { qty = it; recalc() }, Modifier.weight(1f))
            NumField("单价(元/升)", price, { price = it; recalc() }, Modifier.weight(1f))
        } else {
            TextField2(if (kind == "repair") "维修内容" else if (kind == "other") "什么费用" else if (kind == "hired") "什么车" else "备注", note, { note = it })
            val tips = (if (kind == "hired") (listOf("小钩机") + vm.hints.notes[kind].orEmpty()).distinct() else vm.hints.notes[kind].orEmpty()).take(6)
            if (tips.isNotEmpty()) Chips(tips.map { it to it }, null) { note = it }
        }
        NumField(if (kind == "fuel") "金额(自动算，可改)" else "金额(元)", amount, { amount = it; manual = true }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun HaulDialog(vm: Vm, existing: Haul?, onDismiss: () -> Unit) {
    val vehicles = vm.res.filter { it.active && it.isVehicle }
    val drivers = vm.res.filter { it.active && !it.isVehicle && it.otFree != null }   // drivers paid extra per trip over a limit
    var rid by remember { mutableStateOf(existing?.resourceId) }
    var driver by remember { mutableStateOf(existing?.driverId) }
    var tons by remember { mutableStateOf(existing?.tons?.let(::fmtNum) ?: "") }
    var trips by remember { mutableStateOf(existing?.trips?.toString() ?: "") }
    FormDialog(
        title = if (existing == null) "记吨数" else "修改吨数", onDismiss = onDismiss,
        onSave = {
            val r = rid
            if (r == null) vm.message = "请选哪辆车" else vm.saveHaul(existing, r, driver, tons.toDoubleOrNull() ?: 0.0, trips.toIntOrNull() ?: 0) { onDismiss() }
        },
        onDelete = existing?.let { h -> { vm.deleteHaul(h) { onDismiss() } } },
    ) {
        Muted("哪辆车")
        if (vehicles.isEmpty()) Muted("还没有车辆，请先在「人员车辆」里添加车牌") else Chips(vehicles.map { it.id as Int? to it.name }, rid) { rid = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumField("吨数", tons, { tons = it }, Modifier.weight(1f))
            NumField("几车", trips, { trips = it }, Modifier.weight(1f), decimal = false)
        }
        if (drivers.isNotEmpty()) {
            Muted("谁开的（按车次算加班费的司机，可不选）")
            Chips(drivers.map { it.id as Int? to it.name }, driver) { driver = if (driver == it) null else it }
            val d = vm.res.firstOrNull { it.id == driver }
            val n = trips.toIntOrNull() ?: 0
            if (d?.otFree != null && (d.otRate ?: 0.0) > 0 && n > d.otFree) Muted("${d.name} 超过 ${d.otFree} 车，加班费 ${yuan((n - d.otFree) * d.otRate!!)}")
        }
    }
}
