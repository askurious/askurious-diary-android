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

/**
 * What a call can end in, besides success: the server's error key ("expired", "limit", "unknown", "unauthorised",
 * ...) or no network ("offline", [status] 0). A 401 means the server does not know the secret (study deleted);
 * a 429 is the rate limit and says nothing about the study.
 */
class ApiError(val key: String, val status: Int = 0) : Exception(key) {
    /** Worth trying again later: no network, the rate limit, or a fault of the server. */
    val transient: Boolean get() = key == "offline" || status == 429 || status >= 500
}

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

    /**
     * The study as it stands now: status, study period, modules, the seed and the history of the quiet hours. Also
     * tells the server the time zone, and [quiet] when the participant changed the quiet hours (the server keeps
     * the change from the person's next day on).
     */
    suspend fun study(secret: String, quiet: QuietFrom? = null): JSONObject {
        val body = JSONObject().put("tz", ZoneId.systemDefault().id).put("lang", lang())
        if (quiet != null) body.put("quiet", quiet.quiet?.toJson() ?: JSONObject.NULL)
        return call("POST", "$BASE/study", body, secret)
    }

    /**
     * A one-time address for one entry: [module] is a module key, or "entry" for the first questionnaire.
     * [due] and [notified]: epoch milliseconds of the prompt; null for an entry the participant started.
     * [reminded]: the participant opened it from the reminder notification.
     */
    suspend fun entry(secret: String, module: String, due: Long?, notified: Long?, reminded: Boolean = false): String {
        val body = JSONObject().put("module", module).put("tz", ZoneId.systemDefault().id)
            .put("due", due ?: JSONObject.NULL).put("notified", notified ?: JSONObject.NULL)
        if (reminded) body.put("reminded", true)
        return call("POST", "$BASE/entry", body, secret).getString("url")
    }

    /**
     * The page of [module] for answering without a connection: {"offline": false}, or {"offline": true, "html",
     * "version"}. The html is the participant page with absolute paths (/static/..., /media/...).
     */
    suspend fun offlinePage(secret: String, module: String): JSONObject =
        call("POST", "$BASE/offline/${URLEncoder.encode(module, "UTF-8")}", JSONObject(), secret)

    /** Sends one answer given without a connection (AnswerQueue.Item); see [AnswerQueue.fate] for the errors. */
    suspend fun offlineAnswer(secret: String, item: AnswerQueue.Item): JSONObject =
        call("POST", "$BASE/offline", item.body(), secret)

    /**
     * A file of the server (a /static/ or /media/ path) with its content type, for pages kept for answering
     * without a connection. No secret: these files are the same for everybody. At most [MAX_FILE] bytes.
     */
    suspend fun download(path: String): Pair<ByteArray, String?> = withContext(Dispatchers.IO) {
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw ApiError("error", code)
            if (conn.contentLengthLong > MAX_FILE) throw ApiError("error", code)
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_FILE) throw ApiError("error", code)
                }
                out.toByteArray()
            }
            bytes to conn.contentType
        } catch (e: IOException) {
            throw ApiError("offline")
        } finally {
            conn.disconnect()
        }
    }

    /** The largest file kept for an offline page: a video does not belong on the phone without being asked. */
    private const val MAX_FILE = 10L * 1024 * 1024

    suspend fun leave(secret: String): JSONObject = call("POST", "$BASE/leave", JSONObject(), secret)

    /**
     * Deletes all answers of this person in this study and the person itself, at the researchers too; afterwards
     * the server no longer knows the secret (401).
     */
    suspend fun erase(secret: String): JSONObject = call("POST", "$BASE/erase", JSONObject(), secret)

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
