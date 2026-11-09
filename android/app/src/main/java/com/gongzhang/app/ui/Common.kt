package com.gongzhang.app.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.gongzhang.app.Vm
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

val Brand = Color(0xFF1F5F8B)
private val BrandDark = Color(0xFF4A9BD1)

@Composable
fun GzTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = BrandDark, onPrimary = Color(0xFF0B1620), background = Color(0xFF11161B), surface = Color(0xFF1A2128), surfaceVariant = Color(0xFF1F2D3A), primaryContainer = Color(0xFF1F2D3A), onPrimaryContainer = BrandDark, secondary = BrandDark, secondaryContainer = Color(0xFF263B4D), onSecondaryContainer = Color(0xFFCFE6F7))
    else lightColorScheme(primary = Brand, onPrimary = Color.White, background = Color(0xFFF3F5F7), surface = Color.White, surfaceVariant = Color(0xFFE8F0F6), primaryContainer = Color(0xFFE8F0F6), onPrimaryContainer = Brand, secondary = Brand, secondaryContainer = Color(0xFFD6E6F2), onSecondaryContainer = Color(0xFF123F5E))
    val base = Typography()
    MaterialTheme(
        colorScheme = scheme,
        typography = base.copy(
            bodyLarge = base.bodyLarge.copy(fontSize = 18.sp), bodyMedium = base.bodyMedium.copy(fontSize = 16.sp),
            bodySmall = base.bodySmall.copy(fontSize = 14.sp), titleMedium = base.titleMedium.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}

@Composable
fun SectionCard(step: Int?, title: String, sum: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step != null) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text("$step", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (sum != null) Text(sum, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), fontSize = 14.sp, modifier = modifier)

@Composable
fun BigButton(text: String, primary: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (primary) Button(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
    else FilledTonalButton(onClick = onClick, modifier = modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun AddButton(text: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(top = 6.dp)) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

/** Tap-to-pick chips that wrap onto several lines (a vehicle list is too long for one row). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Chips(options: List<Pair<T, String>>, selected: T?, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (key, label) ->
            FilterChip(selected = key == selected, onClick = { onPick(key) }, label = { Text(label, fontSize = 16.sp) }, modifier = Modifier.heightIn(min = 44.dp))
        }
    }
}

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = true) =
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && it == '.') }) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        textStyle = MaterialTheme.typography.bodyLarge, modifier = modifier,
    )

@Composable
fun TextField2(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) =
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge, modifier = modifier)

/** A form in a dialog: scrollable body, big save button, optional delete. */
@Composable
fun FormDialog(
    title: String, onDismiss: () -> Unit, saveText: String = "保存", onSave: () -> Unit,
    extra: (@Composable () -> Unit)? = null, onDelete: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                extra?.invoke()
                Button(onClick = onSave, modifier = Modifier.heightIn(min = 46.dp)) { Text(saveText, fontSize = 17.sp) }
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 16.sp) }
                TextButton(onClick = onDismiss) { Text("取消", fontSize = 16.sp) }
            }
        },
    )
}

fun pickDate(context: Context, current: String, onPicked: (String) -> Unit) {
    val d = LocalDate.parse(current)
    DatePickerDialog(context, { _, y, m, day -> onPicked(LocalDate.of(y, m + 1, day).toString()) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
}

fun fmtNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/** Pull down anywhere on a page: sends what is queued, fetches the server's copy and looks for a newer app version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRefresh(vm: Vm, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberPullToRefreshState()
    if (state.isRefreshing) {
        LaunchedEffect(Unit) {
            vm.refreshAddresses()
            vm.sync()
            vm.checkUpdate(force = true)
            state.endRefresh()
        }
    }
    Box(modifier.clipToBounds().nestedScroll(state.nestedScrollConnection)) {
        content()
        PullToRefreshContainer(state, Modifier.align(Alignment.TopCenter))
    }
}

/** The five characters after the province letters of a plate, one box each. One real text field sits invisibly on top, so typing and deleting just work. */
@Composable
fun PlateInput(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value, onValueChange = { v -> onChange(v.uppercase().filter { it.isLetterOrDigit() }.take(5)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        textStyle = TextStyle(fontSize = 24.sp, color = Color.Transparent), cursorBrush = SolidColor(Color.Transparent), modifier = modifier,
        decorationBox = { inner ->
            Box {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(5) { i ->
                        val on = i == value.length.coerceAtMost(4)
                        Box(
                            Modifier.weight(1f).height(56.dp).border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                            contentAlignment = Alignment.Center,
                        ) { Text(value.getOrNull(i)?.toString().orEmpty(), fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                    }
                }
                Box(Modifier.matchParentSize().alpha(0f)) { inner() }
            }
        },
    )
}
