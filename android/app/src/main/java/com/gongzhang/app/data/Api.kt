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
    fun server(c: Context): String = sp(c).getString("server", null) ?: BuildConfig.DEFAULT_SERVER
    fun setServer(c: Context, v: String) = sp(c).edit().putString("server", normalize(v)).apply()
    fun lan(c: Context): String = sp(c).getString("lan", null) ?: BuildConfig.LAN_SERVER
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
 * Talks to the server on the NAS. Tries the public address first and the home-network address second, so the app works both
 * at the workplace and at home on the same Wi-Fi as the NAS.
 */
object Api {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build()
    @Volatile private var working: String? = null

    fun bases(c: Context): List<String> {
        val list = listOfNotNull(working, Prefs.server(c), Prefs.lan(c)).map { Prefs.normalize(it) }.filter { it.isNotEmpty() }
        return list.distinct()
    }

    /** The address that answered last, for links like the Excel download. */
    fun base(c: Context): String = working ?: bases(c).first()

    suspend fun call(c: Context, method: String, path: String, body: JSONObject? = null): Any = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (base in bases(c)) {
            val req = Request.Builder().url(base.trimEnd('/') + "/api" + path).apply {
                val t = Prefs.token(c)
                if (t.isNotEmpty()) header("Authorization", "Bearer $t")
                val rb = (body?.toString() ?: if (method == "GET") null else "{}")?.toRequestBody(JSON)
                method(method, rb)
            }.build()
            try {
                http.newCall(req).execute().use { resp ->
                    working = base
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
        throw ApiError("连不上服务器，请检查网络" + (lastError?.message?.let { " ($it)" } ?: ""))
    }

    suspend fun obj(c: Context, method: String, path: String, body: JSONObject? = null) = call(c, method, path, body) as JSONObject
    suspend fun arr(c: Context, path: String) = call(c, "GET", path) as JSONArray
}
