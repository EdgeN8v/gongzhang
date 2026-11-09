package com.gongzhang.app.data

import android.content.Context
import com.gongzhang.app.BuildConfig
import kotlinx.coroutines.Dispatchers
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

    /** Every address to try, in order, with how long to wait for a connection (short for home addresses, longer for the internet). */
    fun bases(c: Context): List<Pair<String, Int>> {
        EndpointTable.init(c)
        EndpointTable.refreshInBackground()
        val table = EndpointTable.candidates().map { Prefs.normalize(it.first) to it.second }
        fun ms(b: String) = table.firstOrNull { it.first == b }?.second ?: if (isHome(b)) EndpointTable.LOCAL_MS else EndpointTable.EXTERNAL_MS
        val first = listOfNotNull(working ?: Prefs.lastOk(c), Prefs.customServer(c)).map { it to ms(it) }
        return (first + table).filter { it.first.isNotEmpty() }.distinctBy { it.first }
    }

    fun clientFor(connectMs: Int): OkHttpClient = http.newBuilder().connectTimeout(connectMs.toLong(), TimeUnit.MILLISECONDS).build()

    fun noteWorking(c: Context, base: String) {
        if (base != working) Prefs.setLastOk(c, base)
        working = base
    }

    /** Drops the remembered address: the next call starts from the top of the list. */
    fun forget(c: Context) { working = null; Prefs.setLastOk(c, null) }

    /** The address in use (the one that answered last), for links like the Excel download and the 我的 page. */
    fun base(c: Context): String = working ?: bases(c).first().first

    /** Walks the addresses in order and keeps the first one whose /health answers. Null = none does. */
    suspend fun probe(c: Context): String? = withContext(Dispatchers.IO) {
        for ((base, ms) in bases(c)) {
            try {
                val ok = clientFor(ms).newCall(Request.Builder().url(base.trimEnd('/') + "/health").build()).execute().use { it.isSuccessful }
                if (ok) { noteWorking(c, base); return@withContext base }
            } catch (_: IOException) {}
        }
        null
    }

    suspend fun call(c: Context, method: String, path: String, body: JSONObject? = null): Any = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for ((base, ms) in bases(c)) {
            val req = Request.Builder().url(base.trimEnd('/') + "/api" + path).apply {
                val t = Prefs.token(c)
                if (t.isNotEmpty()) header("Authorization", "Bearer $t")
                val rb = (body?.toString() ?: if (method == "GET") null else "{}")?.toRequestBody(JSON)
                method(method, rb)
            }.build()
            try {
                clientFor(ms).newCall(req).execute().use { resp ->
                    noteWorking(c, base)
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val detail = try { JSONObject(text).optString("detail") } catch (_: Exception) { "" }
                        if (resp.code == 401 && path != "/login") Prefs.logout(c)
                        throw ApiError(detail.ifBlank { "服务器出错了 (${resp.code})" }, resp.code)
                    }
                    return@withContext if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
                }
            } catch (e: IOException) {
                lastError = e
            }
        }
        working = null
        EndpointTable.refreshInBackground(force = true)
        throw ApiError("连不上服务器，请检查网络" + (lastError?.message?.let { " ($it)" } ?: ""))
    }

    suspend fun obj(c: Context, method: String, path: String, body: JSONObject? = null) = call(c, method, path, body) as JSONObject
    suspend fun arr(c: Context, path: String) = call(c, "GET", path) as JSONArray
}
