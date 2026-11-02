package com.gongzhang.app

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gongzhang.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * All screen state. The books live on the phone (`replica`); every change is applied there first and queued in `pending`,
 * then sent to the server whenever it can be reached. Nothing on screen waits for the network.
 */
class Vm(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    var loggedIn by mutableStateOf(Prefs.token(app).isNotEmpty())
    var user by mutableStateOf(Prefs.user(app))
    var tab by mutableStateOf(0)
    var message by mutableStateOf<String?>(null)

    var replica by mutableStateOf(Store.loadReplica(app))
        private set
    var pending by mutableStateOf(Store.loadOutbox(app))
        private set
    var syncing by mutableStateOf(false)
        private set
    var offline by mutableStateOf(false)
        private set
    var lastSync by mutableStateOf<String?>(null)
        private set

    var date by mutableStateOf(LocalDate.now().toString())
    var rangeFrom by mutableStateOf(LocalDate.now().withDayOfMonth(1).toString())
    var rangeTo by mutableStateOf(LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()).toString())
    var preset by mutableStateOf("month")

    val res: List<Res> get() = replica.res
    val hasData by derivedStateOf { replica.res.isNotEmpty() }
    val day by derivedStateOf { dayOf(replica, date) }
    val hints by derivedStateOf { hintsOf(replica) }
    val report by derivedStateOf { reportOf(replica, rangeFrom, rangeTo) }

    val syncText by derivedStateOf {
        when {
            pending.isNotEmpty() && (offline || !syncing) -> (if (offline) "离线 · " else "") + "${pending.size} 条待上传"
            syncing -> "同步中…"
            offline -> "离线，显示的是手机里的数据"
            lastSync != null -> "已同步 $lastSync"
            else -> ""
        }
    }

    // ---- sync

    private val mutex = Mutex()
    private var debounce: Job? = null

    init {
        viewModelScope.launch {
            while (true) {                       // keeps trying while the app is open and something is waiting or the server was unreachable
                delay(45_000)
                if (loggedIn && (pending.isNotEmpty() || offline)) sync()
            }
        }
    }

    fun refresh() { viewModelScope.launch { sync(); checkUpdate(force = true) } }

    /** Sends the queue, then replaces the local copy with the server's (plus anything queued meanwhile). True = in step with the server. */
    suspend fun sync(): Boolean {
        if (!loggedIn) return false
        return mutex.withLock {
            syncing = true
            try {
                val batch = pending
                if (batch.isNotEmpty()) {
                    Api.obj(ctx, "POST", "/push", JSONObject().put("ops", JSONArray(batch)))
                    pending = pending.drop(batch.size)
                    persist()
                }
                var fresh = Replica.fromJson(Api.obj(ctx, "GET", "/dump"))
                for (op in pending) fresh = applyOp(fresh, op)
                replica = fresh
                persist()
                offline = false
                lastSync = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
                true
            } catch (e: ApiError) {
                if (e.code == 401) loggedIn = false else if (e.code == 0) offline = true else message = e.message
                false
            } catch (e: Exception) {
                offline = true
                false
            } finally {
                syncing = false
            }
        }
    }

    private fun persist() {
        val r = replica; val p = pending
        viewModelScope.launch(Dispatchers.IO) { Store.save(ctx, r, p) }
    }

    private fun change(vararg ops: JSONObject) {
        for (op in ops) { replica = applyOp(replica, op); pending = pending + op }
        persist()
        debounce?.cancel()
        debounce = viewModelScope.launch { delay(700); withContext(NonCancellable) { sync() } }
    }

    // ---- login / account (these need the server)

    fun login(server: String, username: String, password: String) {
        viewModelScope.launch {
            syncing = true
            try {
                if (server.isNotBlank()) Prefs.setServer(ctx, server)
                val o = Api.obj(ctx, "POST", "/login", JSONObject().put("username", username.trim()).put("password", password))
                Prefs.setLogin(ctx, o.getString("token"), o.getString("username"))
                user = o.getString("username"); loggedIn = true; tab = 0
            } catch (e: ApiError) { message = e.message } catch (e: Exception) { message = e.message ?: "出错了" } finally { syncing = false }
            if (loggedIn) sync()
        }
    }

    fun logout() { Prefs.logout(ctx); loggedIn = false }

    fun changePassword(old: String, new: String, done: () -> Unit) = online("修改密码") {
        Api.obj(ctx, "POST", "/password", JSONObject().put("old", old).put("new", new)); message = "密码已修改"; done()
    }

    fun saveResource(existing: Res?, body: JSONObject, done: () -> Unit) = online("添加或修改人员车辆") {
        if (existing == null) Api.obj(ctx, "POST", "/resources", body) else Api.obj(ctx, "PUT", "/resources/${existing.id}", body)
        done()
        sync()
    }

    fun deleteResource(r: Res, done: () -> Unit) = online("删除") {
        Api.obj(ctx, "DELETE", "/resources/${r.id}?force=true")
        done()
        sync()
    }

    private fun online(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: ApiError) {
                message = if (e.code == 0) "没有网络，${what}需要联网" else e.message
                if (e.code == 401) loggedIn = false
            } catch (e: Exception) { message = e.message ?: "出错了" }
        }
    }

    // ---- 出勤

    fun confirmDay(fromPrev: Boolean) {
        val have = replica.att.filter { it.date == date }.map { it.resourceId }.toSet()
        val prevDate = if (fromPrev) replica.att.filter { it.date < date }.maxOfOrNull { it.date } else null
        val template = prevDate?.let { d -> replica.att.filter { it.date == d }.associateBy { it.resourceId } }.orEmpty()
        val ops = replica.res.filter { it.active && !it.isVehicle && it.id !in have && (template.isEmpty() || it.id in template) }.map {
            val t = template[it.id]
            Ops.att(date, it.id, t?.factor ?: 1.0, t?.rate ?: it.rate)
        }
        if (ops.isNotEmpty()) change(*ops.toTypedArray())
    }

    /** The roll-call list: factor per person (1 = whole day, 0.5 = half, 0 = absent). Only people whose state changed are queued. */
    fun saveAttendance(states: Map<Int, Double>) {
        val have = replica.att.filter { it.date == date }.associateBy { it.resourceId }
        val ops = states.mapNotNull { (id, factor) ->
            val r = replica.res.firstOrNull { it.id == id } ?: return@mapNotNull null
            val old = have[id]
            if (old != null && old.factor == factor) null else Ops.att(date, id, factor, old?.rate ?: r.rate, old?.note.orEmpty())
        }
        if (ops.isNotEmpty()) change(*ops.toTypedArray())
    }

    fun setAttendance(a: Att, factor: Double, rate: Double? = null) = change(Ops.att(date, a.resourceId, factor, rate ?: a.rate, a.note))

    // ---- 费用 / 吨数

    fun saveEntry(existing: Entry?, kind: String, resourceId: Int?, qty: Double?, price: Double?, amount: Double, note: String, done: () -> Unit) {
        change(Ops.entry(existing?.uid ?: UUID.randomUUID().toString(), date, kind, resourceId, qty, price, amount, note))
        done()
    }

    fun deleteEntry(e: Entry, done: () -> Unit) { change(Ops.entryDel(e.uid)); done() }

    fun saveHaul(existing: Haul?, resourceId: Int, driverId: Int?, tons: Double, trips: Int, note: String, done: () -> Unit) {
        change(Ops.haul(existing?.uid ?: UUID.randomUUID().toString(), date, resourceId, driverId, tons, trips, note)); done()
    }

    fun deleteHaul(h: Haul, done: () -> Unit) { change(Ops.haulDel(h.uid)); done() }

    // ---- 汇总

    fun pickPreset(p: String) {
        val t = LocalDate.now()
        when (p) {
            "month" -> { rangeFrom = t.withDayOfMonth(1).toString(); rangeTo = t.withDayOfMonth(t.lengthOfMonth()).toString() }
            "last" -> { val l = t.minusMonths(1); rangeFrom = l.withDayOfMonth(1).toString(); rangeTo = l.withDayOfMonth(l.lengthOfMonth()).toString() }
            "7" -> { rangeFrom = t.minusDays(6).toString(); rangeTo = t.toString() }
            "30" -> { rangeFrom = t.minusDays(29).toString(); rangeTo = t.toString() }
            "year" -> { rangeFrom = t.withDayOfYear(1).toString(); rangeTo = t.withDayOfYear(t.lengthOfYear()).toString() }
        }
        preset = p
    }

    fun setRange(from: String, to: String) { rangeFrom = minOf(from, to); rangeTo = maxOf(from, to); preset = "custom" }

    /** The Excel file is made by the server, so what is still queued on the phone is sent first. */
    fun exportUrl(onReady: (String) -> Unit) {
        viewModelScope.launch {
            if (!sync()) { message = "没有网络，导出 Excel 需要联网"; return@launch }
            onReady(Api.base(ctx).trimEnd('/') + "/api/report.xlsx?from=$rangeFrom&to=$rangeTo&access_token=" + Prefs.token(ctx))
        }
    }

    // ---- app updates: checked at launch, every time the app comes back, and on pull-to-refresh

    var update by mutableStateOf<UpdateInfo?>(null)
    private var lastCheck = 0L
    private var dismissed = 0

    fun checkUpdate(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60_000) return
        lastCheck = now
        if (force) dismissed = 0
        viewModelScope.launch {
            try { AppUpdater.check(ctx)?.let { if (it.versionCode != dismissed) update = it } } catch (_: Exception) {}
        }
    }

    fun dismissUpdate() { dismissed = update?.versionCode ?: 0; update = null }
}
