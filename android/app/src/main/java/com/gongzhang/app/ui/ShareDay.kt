package com.gongzhang.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

/** What can go into a shared day, in the order of the page. */
private val PARTS = listOf("att" to "出勤", "fuel" to "加油", "haul" to "吨数", "repair" to "维修费", "hired" to "外雇车", "other" to "其他费用", "overtime" to "加班费")

private fun hasPart(d: Day, key: String) = when (key) {
    "att" -> d.att.isNotEmpty()
    "haul" -> d.hauls.isNotEmpty()
    else -> d.entries.any { it.kind == key }
}

/** The day as plain text in the layout the handwritten note uses: date, then numbered sections. 带金额 off leaves the money out. */
fun dayText(d: Day, parts: Set<String>, withMoney: Boolean): String = buildString {
    append(cnDate(d.date))
    var n = 0
    fun head(name: String) { n++; append("\n$n、$name：") }
    for ((key, name) in PARTS) {
        if (key !in parts || !hasPart(d, key)) continue
        head(name)
        when (key) {
            "att" -> {
                val half = d.att.filter { it.factor > 0 && it.factor < 1 }
                val off = d.att.filter { it.factor == 0.0 }
                append("到 ${d.att.count { it.factor > 0 }} 人")
                if (half.isNotEmpty()) append("\n半天：" + half.joinToString("、") { it.name })
                if (off.isNotEmpty()) append("\n没来：" + off.joinToString("、") { it.name })
                if (withMoney) append("\n工资 ${money(d.labor)} 元")
            }
            "haul" -> {
                d.hauls.forEach { h -> append("\n${h.resourceName}：${money(h.tons)} 吨  ${h.trips} 车" + if (h.driverName.isNotBlank()) "（司机 ${h.driverName}）" else "") }
                if (d.hauls.size > 1) append("\n共 ${money(d.hauls.sumOf { it.tons })} 吨")
            }
            "fuel" -> {
                val list = d.entries.filter { it.kind == "fuel" }
                val prices = list.mapNotNull { it.price }.distinct()
                val same = prices.size == 1 && list.all { it.price != null }
                list.forEach { e ->
                    append("\n${e.resourceName.ifBlank { e.note.ifBlank { name } }}  ${money(e.qty ?: 0.0)} 升")
                    if (withMoney && !same) append("  ${money(e.amount)} 元")
                }
                val tail = mutableListOf<String>()
                if (withMoney && same) tail += "${money(prices[0])} 元/升"
                tail += "共 ${money(list.sumOf { it.qty ?: 0.0 })} 升"
                if (withMoney) tail += "合计：${money(list.sumOf { it.amount })} 元"
                append("\n" + tail.joinToString("  "))
            }
            else -> {
                val list = d.entries.filter { it.kind == key }
                list.forEach { e ->
                    val who = if (key == "other" || key == "hired") e.note.ifBlank { name } else e.resourceName.ifBlank { e.note.ifBlank { name } }
                    append("\n$who")
                    if (key != "other" && key != "hired" && e.resourceName.isNotBlank() && e.note.isNotBlank()) append("（${e.note}）")
                    if (withMoney) append("  ${money(e.amount)} 元")
                }
                if (withMoney && list.size > 1) append("\n合计：${money(list.sumOf { it.amount })} 元")
            }
        }
    }
    if (withMoney && "total" in parts) append("\n\n当天合计：${money(d.total)} 元")
}

/** Tick what to send, then hand the text to any app (WeChat shows up in the list) or copy it. The ticks are remembered. */
@Composable
fun ShareDayDialog(vm: Vm, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val d = vm.day
    val prefs = remember { context.getSharedPreferences("share", Context.MODE_PRIVATE) }
    val all = PARTS.map { it.first } + "total"
    val picked = remember {
        mutableStateMapOf<String, Boolean>().also { m -> all.forEach { m[it] = prefs.getBoolean(it, true) } }
    }
    var withMoney by remember { mutableStateOf(prefs.getBoolean("money", true)) }
    val parts = all.filter { picked[it] == true && (it == "total" || hasPart(d, it)) }.toSet()
    val text = dayText(d, parts, withMoney)

    fun remember() = prefs.edit().apply { all.forEach { putBoolean(it, picked[it] == true) }; putBoolean("money", withMoney) }.apply()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分享 ${cnDate(d.date)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Muted("勾上要发的内容；没记的项目不会发。")
                PARTS.filter { hasPart(d, it.first) }.forEach { (key, name) ->
                    TickRow(name, picked[key] == true) { picked[key] = it }
                }
                if (PARTS.none { hasPart(d, it.first) }) Muted("这一天还没有记录。")
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                TickRow("当天合计金额", picked["total"] == true && withMoney, enabled = withMoney) { picked["total"] = it }
                TickRow("带上金额", withMoney) { withMoney = it }
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Text(text, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
        },
        confirmButton = {
            Button(
                enabled = parts.any { it != "total" },
                onClick = {
                    remember()
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "发给"))
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("发给微信等", fontSize = 17.sp) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    remember()
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("工账", text))
                    vm.message = "已复制，去微信里粘贴"
                    onDismiss()
                }) { Text("复制", fontSize = 16.sp) }
                TextButton(onClick = onDismiss) { Text("取消", fontSize = 16.sp) }
            }
        },
    )
}

@Composable
private fun TickRow(label: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = { onChange(it) }, enabled = enabled)
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}
