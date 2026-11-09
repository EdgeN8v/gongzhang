package com.gongzhang.app.data

import android.content.Context
import com.gongzhang.app.BuildConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiError(message: String, val code: Int = 0) : Exception(message)

/** Server address, login token and the tiny bit of state the app keeps on the phone. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("gongzhang", Context.MODE_PRIVATE)
    /** A hand-typed server address, or null: the app then follows the home address table. The old built-in domain no longer exists, so an address saved from it is ignored. */
    fun customServer(c: Context): String? =
        sp(c).getString("server", null)?.takeIf { it.isNotBlank() && "npqq12.senkin.dpdns.org" !in it }?.let { normalize(it) }
    /** The address that answered last time: tried first on the next start, so a server that does not move is found at once. */
    fun lastOk(c: Context): String? = sp(c).getString("last_ok", null)?.takeIf { it.isNotBlank() }
    fun setLastOk(c: Context, v: String?) = sp(c).edit().apply { if (v == null) remove("last_ok") else putString("last_ok", v) }.apply()
    fun setServer(c: Context, v: String) = sp(c).edit().apply { if (v.isBlank()) remove("server") else putString("server", normalize(v)) }.apply()
    fun token(c: Context): String = sp(c).getString("token", "") ?: ""
    fun user(c: Context): String = sp(c).getString("user", "") ?: ""
    fun setLogin(c: Context, token: String, user: String) = sp(c).edit().putString("token", token).putString("user", user).apply()
    fun logout(c: Context) = sp(c).edit().remove("token").apply()
    fun normalize(v: String): String {
        var s = v.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s.trimEnd('/') + "/"
    }
}

/**
 * Talks to the server. Where it is comes from the home's address table (EndpointTable): two home-network addresses
 * (Orange Pi, NAS) and two internet ones, home first, so the app works at home and away and follows the server from one
 * machine to the other. The address that answered last is tried first; a failed round re-reads the table.
 */
object Api {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var working: String? = null

    private fun isHome(base: String): Boolean = Regex("""^https?://(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)""").containsMatchIn(base)
    private fun waitMs(base: String) = if (isHome(base)) EndpointTable.LOCAL_MS else EndpointTable.EXTERNAL_MS

    /** The address in use, as state: the 我的 page shows it and follows when the app switches to the other machine. */
    var current by mutableStateOf<String?>(null)
        private set

    /** The table's addresses in order (home first), with a hand-typed one in front. */
    private fun ordered(c: Context): List<Pair<String, Int>> {
        EndpointTable.init(c)
        EndpointTable.refreshInBackground()
        val table = EndpointTable.candidates().map { Prefs.normalize(it.first) to it.second }
        return (listOfNotNull(Prefs.customServer(c)).map { it to waitMs(it) } + table).filter { it.first.isNotEmpty() }.distinctBy { it.first }
    }

    fun clientFor(connectMs: Int): OkHttpClient = http.newBuilder().connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS).build()

    fun noteWorking(c: Context, base: String) {
        if (base != working) Prefs.setLastOk(c, base)
        working = base
        current = base
    }

    /** Drops the remembered address. */
    fun forget(c: Context) { working = null; current = null; Prefs.setLastOk(c, null) }

    /** The address in use (the one that answered last), for links like the Excel download. */
    fun base(c: Context): String = working ?: Prefs.lastOk(c) ?: ordered(c).first().first

    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun healthy(base: String, ms: Int): Boolean = try {
        clientFor(ms).newCall(Request.Builder().url(base.trimEnd('/') + "/health").build()).execute().use { it.isSuccessful && it.body?.string().orEmpty().contains("\"ok\"") }
    } catch (_: IOException) { false }

    /**
     * Asks every address at the same time whether the server answers there, and takes the first one in table order that does
     * (home before internet, the machine the service runs on first). So a machine that is switched off or no longer has the
     * service costs one short wait, not one wait per address. Null = none answers.
     */
    suspend fun pick(c: Context, skip: String? = null): String? {
        val list = ordered(c).filter { it.first != skip }
        val probes = list.map { (base, ms) -> probeScope.async { healthy(base, minOf(ms, 3000)) } }
        for ((i, probe) in probes.withIndex()) {
            if (probe.await()) {
                probes.forEach { it.cancel() }
                noteWorking(c, list[i].first)
                return list[i].first
            }
        }
        return null
    }

    /**
     * Runs [run] on the address in use. If it cannot be reached - or answers like a leftover proxy (502/503/504) because the
     * service has moved - looks for another address once and runs it there.
     */
    private suspend fun <T> withServer(c: Context, run: (String, OkHttpClient) -> T): T = withContext(Dispatchers.IO) {
        var base = working ?: Prefs.customServer(c) ?: Prefs.lastOk(c) ?: pick(c)
        var switched = false
        var lastError: IOException? = null
        while (base != null) {
            try {
                return@withContext run(base, clientFor(waitMs(base)))
            } catch (e: IOException) {
                lastError = e
                if (switched) break
                switched = true
                base = pick(c, skip = base)
            }
        }
        working = null
        EndpointTable.refreshInBackground(force = true)
        throw ApiError("连不上服务器，请检查网络" + (lastError?.message?.let { " ($it)" } ?: ""))
    }

    /** A plain GET (no login), for the app updater. The caller closes the response. */
    suspend fun rawGet(c: Context, path: String): okhttp3.Response = withServer(c) { base, client ->
        val resp = client.newCall(Request.Builder().url(base.trimEnd('/') + path).build()).execute()
        if (resp.code in 502..504) { resp.close(); throw IOException("HTTP ${resp.code}") }
        noteWorking(c, base)
        resp
    }

    suspend fun call(c: Context, method: String, path: String, body: JSONObject? = null): Any = withServer(c) { base, client ->
        val req = Request.Builder().url(base.trimEnd('/') + "/api" + path).apply {
            val t = Prefs.token(c)
            if (t.isNotEmpty()) header("Authorization", "Bearer $t")
            val rb = (body?.toString() ?: if (method == "GET") null else "{}")?.toRequestBody(JSON)
            method(method, rb)
        }.build()
        client.newCall(req).execute().use { resp ->
            if (resp.code in 502..504) throw IOException("HTTP ${resp.code}")
            noteWorking(c, base)
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = try { JSONObject(text).optString("detail") } catch (_: Exception) { "" }
                if (resp.code == 401 && path != "/login") Prefs.logout(c)
                throw ApiError(detail.ifBlank { "服务器出错了 (${resp.code})" }, resp.code)
            }
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
        }
    }

    suspend fun obj(c: Context, method: String, path: String, body: JSONObject? = null) = call(c, method, path, body) as JSONObject
    suspend fun arr(c: Context, path: String) = call(c, "GET", path) as JSONArray
}
