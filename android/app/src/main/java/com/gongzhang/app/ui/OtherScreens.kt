package com.gongzhang.app.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gongzhang.app.Vm
import com.gongzhang.app.data.*
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun PageHeader(title: String, sub: String, action: (@Composable () -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                Text(sub, fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .85f))
            }
            action?.invoke()
        }
    }
}

@Composable
private fun Cell(text: String, w: Dp, bold: Boolean = false, left: Boolean = false) =
    Text(text, modifier = Modifier.width(w), textAlign = if (left) TextAlign.Start else TextAlign.End, fontSize = 15.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, maxLines = 2)

@Composable
private fun TableRow(cells: List<String>, widths: List<Dp>, bold: Boolean = false, onClick: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, c -> Cell(c, widths[i], bold, left = i == 0) }
    }

/* ------------------------------------------------------------------ 汇总 */
@Composable
fun ReportScreen(vm: Vm) {
    val context = LocalContext.current
    val r = vm.report
    Column(Modifier.fillMaxSize()) {
        PageHeader("汇总", "看一段时间一共花了多少钱") {
            if (vm.syncing) CircularProgressIndicator(Modifier.size(28.dp).padding(end = 4.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
            else FilledTonalIconButton(onClick = { vm.refresh() }, modifier = Modifier.size(48.dp)) { Text("↻", fontSize = 24.sp) }
        }
        if (vm.syncing) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        PullRefresh(vm, Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(null, "选时间") {
                Chips(listOf("month" to "本月", "last" to "上月", "7" to "近7天", "30" to "近30天", "year" to "今年"), vm.preset) { vm.pickPreset(it) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickDate(context, vm.rangeFrom) { vm.setRange(it, vm.rangeTo) } }, Modifier.weight(1f).heightIn(min = 50.dp)) { Text(cnDate(vm.rangeFrom), fontSize = 17.sp) }
                    Text("至")
                    OutlinedButton(onClick = { pickDate(context, vm.rangeTo) { vm.setRange(vm.rangeFrom, it) } }, Modifier.weight(1f).heightIn(min = 50.dp)) { Text(cnDate(vm.rangeTo), fontSize = 17.sp) }
                }
            }
            SectionCard(null, "${cnDate(r.from)} 至 ${cnDate(r.to)}") {
                Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("合计开支", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .85f))
                        Text(yuan(r.total), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val tiles = listOf(
                    Triple("人工(出勤工资)", yuan(r.labor), "${r.people.size} 个人/车"), Triple("加班费", yuan(r.overtime), if (r.autoOvertime > 0) "含车次加班 ${money(r.autoOvertime)}" else "手填+车次"),
                    Triple("油费", yuan(r.fuel), "${money(r.fuelQty)} 升"), Triple("维修费", yuan(r.repair), "${r.lines.count { it.tag == "维修" }} 笔"),
                    Triple("外雇车", yuan(r.hired), "${r.lines.count { it.tag == "外雇车" }} 笔"), Triple("其他费用", yuan(r.other), "${r.lines.count { it.tag == "其他" }} 笔"),
                )
                tiles.chunked(2).forEach { row ->
                    // IntrinsicSize.Min: both tiles in a row are as tall as the taller one, whatever the phone's font size is
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, v, sub) ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Muted(label)
                                    Text(v, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    Muted(sub)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Muted("拉运 ${money(r.tons)} 吨 · ${r.trips} 车")
                Spacer(Modifier.height(6.dp))
                BigButton("⬇ 导出 Excel（发工资用）", false) { vm.exportUrl { url -> exportExcel(context, url, "工账_${r.from}_${r.to}.xlsx") } }
            }
            SectionCard(null, "工资表", yuan(r.people.sumOf { it.pay })) {
                if (r.categories.isNotEmpty()) Muted(r.categories.joinToString("  ") { "${it.category} ${it.count}个 ${yuan(it.pay)}" })
                if (r.people.isEmpty()) Muted("这段时间没有出勤记录") else {
                    val w = listOf(130.dp, 56.dp, 74.dp, 66.dp, 84.dp)
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        TableRow(listOf("姓名/车辆", "天数", "日工资", "加班", "应发"), w, true)
                        r.people.forEach { p -> HorizontalDivider(); TableRow(listOf(p.name, money(p.days), money(p.avgRate), if (p.overtime > 0) money(p.overtime) else "-", money(p.pay)), w) }
                        HorizontalDivider(); TableRow(listOf("合计", "", "", money(r.people.sumOf { it.overtime }), money(r.people.sumOf { it.pay })), w, true)
                    }
                }
            }
            if (r.vehicles.isNotEmpty()) SectionCard(null, "每辆车") {
                val w = listOf(130.dp, 78.dp, 78.dp, 66.dp, 86.dp)
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    TableRow(listOf("车辆", "加油(升)", "油费", "维修", "吨数/车"), w, true)
                    r.vehicles.forEach { v -> HorizontalDivider(); TableRow(listOf(v.name, money(v.fuelQty), money(v.fuel), money(v.repair), if (v.tons > 0 || v.trips > 0) "${money(v.tons)}/${v.trips}" else "-"), w) }
                }
            }
            if (r.lines.isNotEmpty()) SectionCard(null, "维修和其他费用") {
                r.lines.forEach { l ->
                    Row(Modifier.fillMaxWidth().clickable { vm.date = l.date; vm.tab = 0 }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(l.text.ifBlank { l.tag }, fontSize = 17.sp); Muted("${cnDate(l.date)} · ${l.tag}") }
                        Text(yuan(l.amount), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (r.days.isNotEmpty()) SectionCard(null, "每天开支", "点一天可查看/修改") {
                val w = listOf(86.dp, 74.dp, 72.dp, 70.dp, 86.dp)
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    TableRow(listOf("日期", "人工", "油费", "其他", "合计"), w, true)
                    r.days.reversed().forEach { d -> HorizontalDivider(); TableRow(listOf(cnDate(d.date), money(d.labor + d.overtime), money(d.fuel), money(d.repair + d.hired + d.other), money(d.total)), w) { vm.date = d.date; vm.tab = 0 } }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

private fun exportExcel(context: Context, url: String, name: String) {
    try {
        val req = DownloadManager.Request(Uri.parse(url)).setTitle(name).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Toast.makeText(context, "开始下载，完成后在「下载」文件夹里", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

/* ------------------------------------------------------------------ 人员车辆 */
@Composable
fun PeopleScreen(vm: Vm) {
    var edit by remember { mutableStateOf<Res?>(null) }
    var adding by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(setOf<String>()) }
    val q = query.trim()
    val shown = vm.res.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.category.contains(q) }
    val groups = shown.groupBy { if (!it.active) "已停用" else it.category.ifBlank { if (it.isVehicle) "车辆" else "其他" } }
    Column(Modifier.fillMaxSize()) {
        PageHeader("人员和车辆", "改工资、加新人、停用离职的人（需要联网）")
        PullRefresh(vm, Modifier.weight(1f)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, label = { Text("搜索名字或工种") }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                    trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("清除") } })
                Button(onClick = { adding = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("＋ 添加", fontSize = 16.sp) }
            }
            val staff = vm.res.filter { it.active && !it.isVehicle }
            Muted("在岗 ${staff.size} 人 · ${vm.res.count { it.active && it.isVehicle }} 辆车 · 每月工资合计 ${yuan(staff.filter { it.payType == "month" }.sumOf { it.amount })}")
            groups.forEach { (cat, list) ->
                val expanded = q.isNotEmpty() || cat in open
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { open = if (cat in open) open - cat else open + cat }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cat, style = MaterialTheme.typography.titleMedium)
                                val monthly = list.filter { it.payType == "month" }.sumOf { it.amount }
                                Muted("${list.size} ${if (list.all { it.isVehicle }) "辆" else "个"}" + if (monthly > 0) " · 月薪合计 ${yuan(monthly)}" else "")
                            }
                            Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                        }
                        if (expanded) list.forEach { r ->
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().clickable { edit = r }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(r.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                    if (!r.isVehicle) Muted((if (r.payType == "month") "月薪 ${money(r.amount)}" else "日薪") + (if (r.otFree != null) " · 超过${r.otFree}车每车+${money(r.otRate ?: 0.0)}" else ""))
                                }
                                if (!r.isVehicle) Column(horizontalAlignment = Alignment.End) { Text(money(r.rate), fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Muted("元/天") }
                            }
                        }
                    }
                }
            }
            if (shown.isEmpty()) Muted(if (vm.res.isEmpty()) "还没有人员和车辆，点右上的「添加」。" else "没有找到「$q」")
            Spacer(Modifier.height(8.dp))
        }
        }
    }
    if (adding) ResourceDialog(vm, null) { adding = false }
    edit?.let { ResourceDialog(vm, it) { edit = null } }
}

@Composable
private fun ResourceDialog(vm: Vm, r: Res?, onDismiss: () -> Unit) {
    var vehicle by remember { mutableStateOf(r?.isVehicle ?: false) }
    val oldPlate = r?.takeIf { it.isVehicle }?.name.orEmpty()
    var plate by remember { mutableStateOf(oldPlate.ifBlank { "辽K" }) }
    var name by remember { mutableStateOf(if (r != null && !r.isVehicle) r.name else "") }
    var category by remember { mutableStateOf(if (r != null && !r.isVehicle) r.category else "") }
    var payType by remember { mutableStateOf(r?.payType ?: "month") }
    var amount by remember { mutableStateOf(if (r != null && !r.isVehicle) r.amount.let(::fmtNum) else "") }
    var free by remember { mutableStateOf(r?.otFree?.toString() ?: "") }
    var otRate by remember { mutableStateOf(r?.otRate?.let(::fmtNum) ?: "") }
    var active by remember { mutableStateOf(r?.active ?: true) }
    val cats = vm.res.filter { !it.isVehicle }.map { it.category }.filter { it.isNotBlank() }.distinct()
    var askDelete by remember { mutableStateOf(false) }
    if (askDelete && r != null) DeleteConfirm(vm, r, onDeleted = onDismiss, onCancel = { askDelete = false }, onStopInstead = { active = false; askDelete = false })
    FormDialog(title = if (r == null) "添加" else if (vehicle) "修改车辆" else "修改人员", onDismiss = onDismiss, onDelete = if (r != null) ({ askDelete = true }) else null, onSave = {
        if (vehicle) {
            if (plate.isBlank()) vm.message = "请填车牌号"
            else vm.saveResource(r, JSONObject().put("name", plate.trim()).put("category", "车辆").put("is_vehicle", true).put("pay_type", "day").put("amount", 0)
                .put("active", active).put("note", "").put("ot_free_trips", JSONObject.NULL).put("ot_rate", JSONObject.NULL)) { onDismiss() }
        } else if (name.isBlank()) vm.message = "请填名字"
        else vm.saveResource(r, JSONObject().put("name", name.trim()).put("category", category.trim()).put("is_vehicle", false).put("pay_type", payType)
            .put("amount", amount.toDoubleOrNull() ?: 0.0).put("active", active).put("note", "")
            .put("ot_free_trips", if (free.isNotBlank()) free.toIntOrNull() ?: JSONObject.NULL else JSONObject.NULL)
            .put("ot_rate", if (otRate.toDoubleOrNull() != null) otRate.toDouble() else JSONObject.NULL)) { onDismiss() }
    }) {
        if (r == null) Chips(listOf(false to "人员", true to "车辆"), vehicle) { vehicle = it }
        if (vehicle) {
            TextField2("车牌号（想怎么写都行）", plate, { plate = it.uppercase() })
        } else {
            TextField2("名字", name, { name = it })
            TextField2("工种（如 环卫工人、司机）", category, { category = it })
            if (cats.isNotEmpty()) Chips(cats.map { it to it }, category.takeIf { it in cats }) { category = it }
            Muted("怎么算工资")
            Chips(listOf("month" to "月薪（÷30）", "day" to "日薪"), payType) { payType = it }
            NumField("金额(元)", amount, { amount = it }, Modifier.fillMaxWidth())
            amount.toDoubleOrNull()?.let { Muted(if (payType == "month") "折合每天 ${money(it / 30)} 元" else "每天 ${money(it)} 元") }
            Muted("按车次加钱（有的司机才填）：超过几车以后，每多一车加多少钱")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("超过几车", free, { free = it }, Modifier.weight(1f), decimal = false)
                NumField("每车加(元)", otRate, { otRate = it }, Modifier.weight(1f))
            }
        }
        if (r != null) {
            Muted("状态")
            Chips(listOf(true to "在岗", false to "停用"), active) { active = it }
            if (!vehicle) Muted("改工资只影响以后新记的日子，已经记过的天数不会变。")
        }
    }
}

/** Delete asks first. With no records it just goes; with records it says what is lost and offers 停用 (history stays) instead. */
@Composable
private fun DeleteConfirm(vm: Vm, r: Res, onDeleted: () -> Unit, onCancel: () -> Unit, onStopInstead: () -> Unit) {
    val rep = vm.replica
    val att = rep.att.count { it.resourceId == r.id }
    val hauls = rep.hauls.count { it.resourceId == r.id || it.driverId == r.id }
    val money = rep.entries.count { it.resourceId == r.id }
    val used = att + hauls + money
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("删除「${r.name}」？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (used == 0) Text("还没有任何记录，删除后不影响账目。", fontSize = 16.sp)
                else {
                    Text("已有记录：出勤 $att 条、吨数 $hauls 条、加油/维修等费用 $money 条。", fontSize = 16.sp)
                    Text("删除后，出勤和吨数记录会一起删掉，过去的工资、吨数汇总会变少；费用的金额还在，只是不再显示是谁。", fontSize = 16.sp)
                    Text("如果只是不干了，建议改为「停用」：以后不再出现在名单里，过去的账都保留。", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (used > 0) FilledTonalButton(onClick = onStopInstead, modifier = Modifier.heightIn(min = 46.dp)) { Text("改为停用") }
                Button(onClick = { vm.deleteResource(r, onDeleted) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.heightIn(min = 46.dp)) { Text(if (used == 0) "删除" else "仍然删除") }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

/* ------------------------------------------------------------------ 我的 */
@Composable
fun MeScreen(vm: Vm) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var status by remember { mutableStateOf("") }
    var checkNote by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("我的", "账号和更新")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(null, "当前账号") {
                Text(vm.user, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("服务器 ${(Api.current ?: Api.base(context)).trimEnd('/')}" + checkNote, Modifier.weight(1f))
                    IconButton(onClick = {
                        if (!checking) { checking = true; checkNote = ""; scope.launch { if (vm.recheckAddress() == null) checkNote = "  连不上，换个网络再试"; checking = false } }
                    }, modifier = Modifier.size(40.dp)) {
                        if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("↻", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Muted(vm.syncText.ifBlank { "还没同步" })
            }
            SectionCard(null, "更新") {
                Text("当前版本 ${AppUpdater.installedVersionName(context)}", fontSize = 17.sp)
                if (status.isNotBlank()) Muted(status)
                Spacer(Modifier.height(8.dp))
                BigButton("检查更新") {
                    status = "检查中…"
                    scope.launch {
                        try { val u = AppUpdater.check(context); if (u == null) status = "已经是最新版" else { status = ""; update = u } }
                        catch (e: Exception) { status = "检查失败: ${e.message ?: "网络不通"}" }
                    }
                }
            }
            BigButton("修改密码", false) { pw = true }
            BigButton("修改服务器地址", false) { server = true }
            OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("退出登录", color = MaterialTheme.colorScheme.error, fontSize = 18.sp) }
        }
    }
    update?.let { UpdateDialog(it) { update = null } }
    if (pw) {
        var old by remember { mutableStateOf("") }; var new by remember { mutableStateOf("") }
        FormDialog("修改密码", { pw = false }, onSave = { if (new.length < 6) vm.message = "新密码至少 6 位" else vm.changePassword(old, new) { pw = false } }) {
            TextField2("原密码", old, { old = it }); TextField2("新密码（至少 6 位）", new, { new = it })
        }
    }
    if (server) {
        val current = Api.base(context)
        var addr by remember { mutableStateOf(current) }
        FormDialog("服务器地址", { server = false }, onSave = {
            val typed = addr.trim().let { if (it.isEmpty()) it else Prefs.normalize(it) }
            when {
                typed == current -> vm.message = "没有改动"
                else -> { Prefs.setServer(context, typed); Api.forget(context); vm.message = if (typed.isEmpty()) "已改回自动识别" else "已保存" }
            }
            server = false
        }) {
            TextField2("地址", addr, { addr = it }); Muted("这是现在用的地址。一般不用改：不改就是自动识别，连不通时按家里的地址表（两台机器、两个域名）挨个换。清空后保存 = 改回自动识别。")
        }
    }
}

@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val busy = progress != null
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("发现新版本 ${info.versionName}") },
        text = {
            Column {
                if (info.notes.isNotBlank()) Text(info.notes, fontSize = 15.sp)
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                message = null; progress = 0f
                scope.launch {
                    try {
                        val f = AppUpdater.download(context) { progress = it }
                        progress = null
                        if (AppUpdater.install(context, f)) onDismiss() else message = "请先在刚打开的页面里允许「工账」安装应用，然后回来再点一次更新"
                    } catch (e: Exception) { progress = null; message = "下载失败: ${e.message ?: "网络不通"}" }
                }
            }) { Text(if (busy) "下载中…" else "一键更新", fontSize = 17.sp) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("以后再说") } },
    )
}
