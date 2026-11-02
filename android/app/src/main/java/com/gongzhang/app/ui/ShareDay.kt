package com.gongzhang.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
import java.io.File
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

/**
 * The day drawn like the handwritten sheet: date on top, numbered sections, names on the left and amounts on the right,
 * plain black on white. WeChat shows it as a picture, so the columns stay straight (text in a chat bubble cannot do that).
 */
private fun renderDay(d: Day, parts: Set<String>, withMoney: Boolean): Bitmap {
    val w = 1080
    val pad = 64f
    val ink = 0xFF1A1A1A.toInt()
    fun paint(size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = ink; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
    val body = paint(44f); val strong = paint(44f, true); val head = paint(48f, true); val title = paint(68f, true)
    val right = w - pad
    val indent = pad + 36f

    fun layoutOf(s: String, p: TextPaint, width: Float) = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt()).setLineSpacing(0f, 1.1f).build()

    /** One pass over the sheet; with no canvas it only measures. Returns the height used. */
    fun pass(c: Canvas?): Float {
        var y = 56f
        fun gap(h: Float) { y += h }
        fun centered(s: String, p: TextPaint) {
            val l = layoutOf(s, p, w - 2 * pad)
            c?.let { it.save(); it.translate(pad + (w - 2 * pad - p.measureText(s)) / 2, y); l.draw(it); it.restore() }
            y += l.height
        }
        fun left(s: String, p: TextPaint, x: Float = pad) {
            val l = layoutOf(s, p, right - x)
            c?.let { it.save(); it.translate(x, y); l.draw(it); it.restore() }
            y += l.height
        }
        fun rightLine(s: String, p: TextPaint = body) {
            c?.drawText(s, right - p.measureText(s), y + p.textSize, p)
            y += p.textSize * 1.35f
        }
        /** name on the left (wraps if long), value on the right on the first line */
        fun row(name: String, value: String) {
            val maxLeft = (right - indent) - (if (value.isBlank()) 0f else body.measureText(value) + 40f)
            val l = layoutOf(name, body, maxLeft.coerceAtLeast(300f))
            c?.let {
                it.save(); it.translate(indent, y); l.draw(it); it.restore()
                if (value.isNotBlank()) it.drawText(value, right - body.measureText(value), y + l.getLineBaseline(0), body)
            }
            y += l.height + 8f
        }

        centered(cnDate(d.date), title)
        gap(36f)
        var n = 0
        for ((key, name) in PARTS) {
            if (key !in parts || !hasPart(d, key)) continue
            n++
            left("$n、$name：", head)
            gap(10f)
            when (key) {
                "att" -> {
                    row("到 ${d.att.count { it.factor > 0 }} 人", if (withMoney) "工资 ${money(d.labor)} 元" else "")
                    val half = d.att.filter { it.factor > 0 && it.factor < 1 }
                    val off = d.att.filter { it.factor == 0.0 }
                    if (half.isNotEmpty()) row("半天：" + half.joinToString("、") { it.name }, "")
                    if (off.isNotEmpty()) row("没来：" + off.joinToString("、") { it.name }, "")
                }
                "haul" -> {
                    d.hauls.forEach { h -> row(h.resourceName + if (h.driverName.isNotBlank()) "（${h.driverName}）" else "", "${money(h.tons)} 吨   ${h.trips} 车") }
                    if (d.hauls.size > 1) rightLine("共 ${money(d.hauls.sumOf { it.tons })} 吨")
                }
                "fuel" -> {
                    val list = d.entries.filter { it.kind == "fuel" }
                    val prices = list.mapNotNull { it.price }.distinct()
                    val same = prices.size == 1 && list.all { it.price != null }
                    list.forEach { e ->
                        row(e.resourceName.ifBlank { e.note.ifBlank { name } }, "${money(e.qty ?: 0.0)} 升" + if (withMoney && !same) "   ${money(e.amount)} 元" else "")
                    }
                    gap(4f)
                    rightLine((if (withMoney && same) "${money(prices[0])} 元/升    " else "") + "共 ${money(list.sumOf { it.qty ?: 0.0 })} 升")
                    if (withMoney) rightLine("合计：${money(list.sumOf { it.amount })} 元", strong)
                }
                else -> {
                    val list = d.entries.filter { it.kind == key }
                    list.forEach { e ->
                        val who = if (key == "other" || key == "hired") e.note.ifBlank { name } else e.resourceName.ifBlank { e.note.ifBlank { name } } +
                            if (e.resourceName.isNotBlank() && e.note.isNotBlank()) "（${e.note}）" else ""
                        row(who, if (withMoney) "${money(e.amount)} 元" else "")
                    }
                    if (withMoney && list.size > 1) { gap(4f); rightLine("合计：${money(list.sumOf { it.amount })} 元", strong) }
                }
            }
            gap(30f)
        }
        if (withMoney && "total" in parts) {
            c?.drawLine(pad, y, right, y, paint(2f).apply { strokeWidth = 3f })
            gap(22f)
            rightLine("当天合计：${money(d.total)} 元", paint(52f, true))
        }
        return y + 48f
    }

    val bmp = Bitmap.createBitmap(w, pass(null).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(0xFFFFFFFF.toInt())
    pass(canvas)
    return bmp
}

/** Tick what to send, then send the picture to WeChat (or any app), or copy the text. The ticks are remembered. */
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
    val image = remember(d, parts, withMoney) { renderDay(d, parts, withMoney) }

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
                TickRow("带上金额", withMoney) { withMoney = it }
                TickRow("当天合计金额", picked["total"] == true && withMoney, enabled = withMoney) { picked["total"] = it }
                Spacer(Modifier.height(8.dp))
                Muted("发出去的图片就是这样：")
                Surface(shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.padding(top = 4.dp)) {
                    Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                enabled = parts.any { it != "total" },
                onClick = {
                    remember()
                    val dir = File(context.cacheDir, "share").apply { mkdirs() }
                    val file = File(dir, "gongzhang-${d.date}.png")
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "发给").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    onDismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("发图片给微信", fontSize = 17.sp) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    remember()
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("工账", dayText(d, parts, withMoney)))
                    vm.message = "文字已复制，去微信里粘贴"
                    onDismiss()
                }) { Text("复制文字", fontSize = 16.sp) }
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
