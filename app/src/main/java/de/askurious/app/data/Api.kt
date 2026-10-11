package de.askurious.app.data

import de.askurious.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZoneId
import java.util.Locale

/** What a call can end in, besides success: the server's error key ("expired", "limit", "unknown", ...) or no network. */
class ApiError(val key: String, val status: Int = 0) : Exception(key)

/**
 * The diary API of Askurious (app/diary_api.py on the server), version 1. No account: a study is reached with the
 * secret the server returned at enrolment, sent as a bearer token. The device sends no id of its own.
 */
object Api {
    private const val BASE = "/api/diary/v1"

    val baseUrl: String get() = BuildConfig.BASE_URL

    /** The public information of a study, for the join screen. */
    suspend fun join(code: String): JSONObject =
        call("GET", "$BASE/join/${URLEncoder.encode(code, "UTF-8")}?lang=${lang()}")

    /** Enrols this device. Returns {"secret", "url"}: the url opens the first questionnaire (consent, entry part). */
    suspend fun enrol(code: String): JSONObject =
        call("POST", "$BASE/enrol", JSONObject().put("code", code).put("lang", lang()))

    /** The study as it stands now: status, study period, modules. Also tells the server the time zone. */
    suspend fun study(secret: String): JSONObject =
        call("POST", "$BASE/study", JSONObject().put("tz", ZoneId.systemDefault().id).put("lang", lang()), secret)

    /**
     * A one-time address for one entry: [module] is a module key, or "entry" for the first questionnaire.
     * [due] and [notified]: epoch milliseconds of the prompt; null for an entry the participant started.
     */
    suspend fun entry(secret: String, module: String, due: Long?, notified: Long?): String {
        val body = JSONObject().put("module", module).put("tz", ZoneId.systemDefault().id)
            .put("due", due ?: JSONObject.NULL).put("notified", notified ?: JSONObject.NULL)
        return call("POST", "$BASE/entry", body, secret).getString("url")
    }

    suspend fun leave(secret: String): JSONObject = call("POST", "$BASE/leave", JSONObject(), secret)

    /**
     * The address of an entry as the server sent it. A debug build keeps only its path and puts it under its own
     * BASE_URL: the server may name itself with another port or name than the one adb reverse leads to.
     */
    fun absolute(url: String): String {
        if (!url.startsWith("http")) return baseUrl + url
        if (!BuildConfig.DEBUG) return url
        val uri = java.net.URI(url)
        return baseUrl + uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private fun lang(): String = Locale.getDefault().language

    private suspend fun call(method: String, path: String, body: JSONObject? = null, secret: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                if (secret != null) setRequestProperty("Authorization", "Bearer $secret")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (code !in 200..299) throw ApiError(json?.optString("error")?.ifBlank { null } ?: "error", code)
                json ?: throw ApiError("error", code)
            } catch (e: IOException) {
                throw ApiError("offline")
            } finally {
                conn.disconnect()
            }
        }
}
