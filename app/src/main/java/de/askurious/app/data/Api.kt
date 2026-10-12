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
 * What a call can end in, besides success: the server's error key ("expired", "daily_limit", "limit", "unknown",
 * "unauthorised", ...) or no network ("offline", [status] 0). A 401 means the server does not know the secret (study
 * deleted); a 429 ("limit") is the rate limit and says nothing about the study; a 409 "daily_limit" is today's limit
 * of a self-started report, final for that answer.
 */
class ApiError(val key: String, val status: Int = 0) : Exception(key) {
    /** Worth trying again later: no network, the rate limit, or a fault of the server. */
    val transient: Boolean get() = key == "offline" || status == 429 || status >= 500
}

/**
 * The diary API of Askurious (app/diary_api.py on the server), version 1. No account: a study is reached with the
 * secret the server returned at enrolment, sent as a bearer token. The device sends no id of its own.
 *
 * Every call names its [server] ("https://ask.askurious.de", no slash at the end): the one of the study
 * ([Study.server]), which came from its join link, or [defaultServer] for a typed join code.
 */
object Api {
    private const val BASE = "/api/diary/v1"

    /** The server of a join code typed by hand, and of the studies joined before the server was kept per study. */
    val defaultServer: String get() = BuildConfig.BASE_URL

    /**
     * The phone's clock minus the server's, in milliseconds, from the Date header of the last answer of the server;
     * null until one came. Only to tell why answers given without a connection wait (AnswerQueue.Fate.CLOCK).
     */
    @Volatile
    var clockSkew: Long? = null
        private set

    /** The public information of a study, for the join screen. */
    suspend fun join(server: String, code: String): JSONObject =
        call(server, "GET", "$BASE/join/${URLEncoder.encode(code, "UTF-8")}?lang=${lang()}")

    /** Enrols this device. Returns {"secret", "url"}: the url opens the first questionnaire (consent, entry part). */
    suspend fun enrol(server: String, code: String): JSONObject =
        call(server, "POST", "$BASE/enrol", JSONObject().put("code", code).put("lang", lang()))

    /**
     * The study as it stands now: status, study period, modules, the seed and the history of the quiet hours. Also
     * tells the server the time zone, and [quiet] when the participant changed the quiet hours (the server keeps
     * the change from the person's next day on).
     */
    suspend fun study(server: String, secret: String, quiet: QuietFrom? = null): JSONObject {
        val body = JSONObject().put("tz", ZoneId.systemDefault().id).put("lang", lang())
        if (quiet != null) body.put("quiet", quiet.quiet?.toJson() ?: JSONObject.NULL)
        return call(server, "POST", "$BASE/study", body, secret)
    }

    /**
     * A one-time address for one entry: [module] is a module key, or "entry" for the first questionnaire.
     * [due] and [notified]: epoch milliseconds of the prompt; null for an entry the participant started.
     * [reminded]: the participant opened it from the reminder notification.
     */
    suspend fun entry(server: String, secret: String, module: String, due: Long?, notified: Long?, reminded: Boolean = false): String {
        val body = JSONObject().put("module", module).put("tz", ZoneId.systemDefault().id)
            .put("due", due ?: JSONObject.NULL).put("notified", notified ?: JSONObject.NULL)
        if (reminded) body.put("reminded", true)
        return call(server, "POST", "$BASE/entry", body, secret).optStringOrNull("url") ?: throw ApiError("error")
    }

    /**
     * The page of [module] for answering without a connection: {"offline": false}, or {"offline": true, "html",
     * "version"}. The html is the participant page with absolute paths (/static/..., /media/...).
     */
    suspend fun offlinePage(server: String, secret: String, module: String): JSONObject =
        call(server, "POST", "$BASE/offline/${URLEncoder.encode(module, "UTF-8")}", JSONObject(), secret)

    /** Sends one answer given without a connection (AnswerQueue.Item); see [AnswerQueue.fate] for the errors. */
    suspend fun offlineAnswer(server: String, secret: String, item: AnswerQueue.Item): JSONObject =
        call(server, "POST", "$BASE/offline", item.body(), secret)

    /**
     * A file of the server (a /static/ or /media/ path) with its content type, for pages kept for answering
     * without a connection. No secret: these files are the same for everybody. At most [MAX_FILE] bytes.
     */
    suspend fun download(server: String, path: String): Pair<ByteArray, String?> = withContext(Dispatchers.IO) {
        val conn = (URL(server + path).openConnection() as HttpURLConnection).apply {
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

    suspend fun leave(server: String, secret: String): JSONObject = call(server, "POST", "$BASE/leave", JSONObject(), secret)

    /**
     * Deletes all answers of this person in this study and the person itself, at the researchers too; afterwards
     * the server no longer knows the secret (401).
     */
    suspend fun erase(server: String, secret: String): JSONObject = call(server, "POST", "$BASE/erase", JSONObject(), secret)

    /**
     * The address of an entry as [server] sent it. A debug build keeps only its path and puts it under [server]: a
     * server on the computer may name itself with another port or name than the one adb reverse leads to. A release
     * build takes only an address of [server] itself (scheme, host and port): the WebView and its bridge must never
     * open another site, even if a server sent one. Anything else is an [ApiError] "error".
     */
    fun absolute(url: String, server: String): String = absolute(url, server, BuildConfig.DEBUG)

    /** [absolute] for a given [base] and build type; plain Kotlin for the unit tests. */
    fun absolute(url: String, base: String, debug: Boolean): String {
        if (url.startsWith("/") && !url.startsWith("//")) return base + url
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: throw ApiError("error")
        if (debug) return base + uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: "")
        if (!sameOrigin(uri, java.net.URI(base))) throw ApiError("error")
        return url
    }

    /** Whether [uri] has the scheme, host and port of [base] (the default port counted as given). */
    fun sameOrigin(uri: java.net.URI, base: java.net.URI): Boolean {
        fun port(u: java.net.URI) = if (u.port != -1) u.port else when (u.scheme?.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }
        return uri.scheme.equals(base.scheme, ignoreCase = true) && uri.host != null &&
            uri.host.equals(base.host, ignoreCase = true) && port(uri) == port(base)
    }

    private fun lang(): String = Locale.getDefault().language

    private suspend fun call(server: String, method: String, path: String, body: JSONObject? = null, secret: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = (URL(server + path).openConnection() as HttpURLConnection).apply {
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
                // the Date header has whole seconds: a skew below a few seconds is noise and not looked at
                conn.date.takeIf { it > 0 }?.let { clockSkew = System.currentTimeMillis() - it }
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
