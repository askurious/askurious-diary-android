package de.askurious.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * The pages of modules that can be answered without a connection ([Module.offline]), with the files they need
 * (/static/ and /media/ paths: style sheet, participant.js, images). Kept in the app's files directory, which goes
 * into no backup and to no new phone (res/xml/data_extraction_rules.xml excludes the domain "file").
 *
 * pages/<study>_<module>.json holds {html, version, files, fetched}, sealed under the Keystore key ([Sealer], AAD
 * "offline:<file name>"): a copy of the app's files shows neither the title of a study nor its questions, as for the
 * studies and the answers (audit 2026-10-18, AND-06); the form's CSRF token is not kept at all (the kept page sends
 * nothing to the server). files/<hash> holds one file of a server and files/<hash>.type its content type. A file is known by its full address (server and path: two servers may have
 * different files under the same path); files are shared by all pages of a server (they are the same for everybody)
 * and deleted when no page names them any more.
 */
class OfflinePages(context: Context, private val sealer: Sealer = KeystoreSealer) {
    private val app = context.applicationContext
    // found when first needed: filesDir checks the disk, and the WebView's client is made on the main thread (AND-09)
    private val root by lazy { File(app.filesDir, "offline") }
    private val pages by lazy { File(root, "pages") }
    private val files by lazy { File(root, "files") }

    /**
     * A kept page: [files] are the full addresses (server and path) of the files it needs, [fetched] the time it was
     * last checked with the server. A page kept before the addresses were full names paths only: it counts as missing.
     */
    data class Page(val html: String, val version: String, val files: List<String>, val fetched: Long)

    fun page(study: String, module: String): Page? {
        val f = pageFile(study, module) ?: return null
        if (!f.exists()) moveLegacy(study, module, f)
        if (!f.exists()) return null
        return runCatching {
            val o = opened(f) ?: return null
            val list = o.optJSONArray("files") ?: JSONArray()
            Page(o.getString("html"), o.optString("version"), (0 until list.length()).map { list.getString(it) }, o.optLong("fetched"))
        }.getOrNull()
    }

    /**
     * The JSON of the page file [f]: opened with the Keystore; a page kept in plain text by an earlier version is read
     * as it is (and sealed at its next check with the server). Null when it cannot be opened.
     */
    private fun opened(f: File): JSONObject? {
        val raw = runCatching { f.readText() }.getOrNull() ?: return null
        val text = if (SealedText.plain(raw)) raw else sealer.open(raw, aad(f)) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun aad(f: File) = "offline:" + f.name

    /** A page counts only with all its files: without participant.js the answers would go nowhere. */
    fun usable(study: String, module: String): Page? = page(study, module)?.takeIf { complete(it) }

    /** The kept file of [path] on [server] and its content type, null when it is not kept. */
    fun file(server: String, path: String): Pair<File, String>? = file(server + path)

    private fun file(address: String): Pair<File, String>? {
        val f = File(files, hash(address))
        if (!f.exists()) return null
        val type = File(files, hash(address) + ".type").takeIf { it.exists() }?.readText()?.ifBlank { null } ?: guessType(address)
        return f to type
    }

    /** All files of [page] are kept, under their full addresses. */
    private fun complete(page: Page) = page.files.all { it.contains("://") && has(it) }

    fun drop(study: String, module: String) {
        pageFile(study, module)?.delete()
        legacyFile(study, module).delete()
    }

    /**
     * Deletes all pages of [study] and the files no other page needs. Under the same lock as [refresh] (a fetch running
     * at the same time must not lose the files it just kept) and off the main thread.
     */
    suspend fun dropStudy(study: String) {
        // a refresh of this study that runs now stops after the file it is fetching (audit 2026-10-13, AN-9)
        dropping.add(study)
        try {
            lock.withLock {
                withContext(Dispatchers.IO) {
                    val mine = setOfNotNull(name(study)?.let { it + "_" }, hash(study) + "_")
                    pages.listFiles()?.filter { f -> mine.any { f.name.startsWith(it) } }?.forEach { it.delete() }
                    prune()
                }
            }
        } finally {
            dropping.remove(study)
        }
    }

    private fun has(path: String) = File(files, hash(path)).exists()

    /**
     * The file of a kept page, named by a keyed hash of the study's code (null while the key cannot be read): the plain
     * SHA-256 of a code of six letters was undone in minutes, and the names showed which studies the person takes
     * part in (audit 2026-10-22, F-01).
     */
    private fun pageFile(study: String, module: String) = name(study)?.let { File(pages, "${it}_${hash(module)}.json") }

    /** The name of earlier versions; moved to [pageFile] at the first read ([moveLegacy]). */
    private fun legacyFile(study: String, module: String) = File(pages, "${hash(study)}_${hash(module)}.json")

    private fun moveLegacy(study: String, module: String, f: File) {
        val old = legacyFile(study, module)
        if (!old.exists()) return
        runCatching {
            val raw = old.readText()
            val plain = if (SealedText.plain(raw)) raw else sealer.open(raw, aad(old)) ?: return
            write(f, sealer.seal(plain, aad(f)).toByteArray())
            old.delete()
        }
    }

    /** HMAC-SHA256 of [study] with the key kept sealed in the app's preferences (made once), first 24 hex digits. */
    private fun name(study: String): String? {
        val key = pageKey() ?: return null
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(study.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun pageKey(): ByteArray? = synchronized(SealedText.LOCK) {
        val text = SealedText(app.getSharedPreferences("offline", Context.MODE_PRIVATE), "page_key", sealer, "offline-key")
        val opened = text.read()
        if (opened.unreadable) return null
        opened.text?.let { return runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return runCatching { text.write(java.util.Base64.getEncoder().encodeToString(key), durable = true); key }.getOrNull()
    }

    internal fun save(study: String, module: String, page: Page) {
        pages.mkdirs()
        val o = JSONObject().put("html", page.html).put("version", page.version)
            .put("files", JSONArray(page.files)).put("fetched", page.fetched)
        val f = pageFile(study, module) ?: return // the key cannot be read now: the page is fetched again later
        write(f, sealer.seal(o.toString(), aad(f)).toByteArray())
        legacyFile(study, module).delete()
    }

    private fun keepFile(path: String, bytes: ByteArray, type: String?) {
        files.mkdirs()
        write(File(files, hash(path)), bytes)
        write(File(files, hash(path) + ".type"), (type ?: guessType(path)).toByteArray())
    }

    /** Written beside and renamed, so a page or file is never read half written. */
    private fun write(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    /** Deletes the files no kept page names; nothing while a kept page cannot be opened (its files may be needed). */
    private fun prune() {
        val needed = pages.listFiles()?.filter { it.name.endsWith(".json") }?.flatMap { f ->
            val o = opened(f) ?: return
            val list = o.optJSONArray("files") ?: JSONArray()
            runCatching { (0 until list.length()).map { hash(list.getString(it)) } }.getOrDefault(emptyList())
        }?.toSet() ?: emptySet()
        files.listFiles()?.forEach { f ->
            if (f.name.substringBefore('.') !in needed) f.delete()
        }
    }

    /**
     * Brings the kept pages of [study] up to date: fetches the page of every offline module of a running study
     * (at most every [REFRESH_MS], at once when it or one of its files is missing), downloads the files it names
     * that are not kept yet, and drops the pages of modules no longer offline. Errors are swallowed: the pages are
     * a fallback, and the next sync tries again.
     *
     * Runs outside the lock of the sync (Sync.refreshPages, AN-9): it works with the study as it is stored now, does
     * nothing when it is gone from the phone (deleted, joined again with another secret), and stops between two files
     * when the study is being dropped ([dropStudy]).
     */
    suspend fun refresh(given: Study) = lock.withLock {
        withContext(Dispatchers.IO) {
            val study = StudyStore(app).get(given.code)?.takeIf { it.secret == given.secret } ?: return@withContext
            val wanted = if (study.running) study.modules.filter { it.offline }.map { it.key }.toSet() else emptySet()
            // pages of modules no longer wanted go, under the name of now and of earlier versions (F-01)
            val keep = wanted.flatMap { listOfNotNull(pageFile(study.code, it)?.name, legacyFile(study.code, it).name) }.toSet()
            val mine = setOfNotNull(name(study.code)?.let { it + "_" }, hash(study.code) + "_")
            pages.listFiles()?.filter { f -> mine.any { f.name.startsWith(it) } && f.name !in keep }?.forEach { it.delete() }
            val now = System.currentTimeMillis()
            for (key in wanted) {
                if (study.code in dropping) return@withContext
                val kept = page(study.code, key)
                if (kept != null && complete(kept) && now - kept.fetched in 0 until REFRESH_MS) continue
                try {
                    fetch(study, key, kept, now)
                } catch (e: ApiError) {
                    if (e.status == 400 || e.status == 404) drop(study.code, key)
                } catch (e: java.io.IOException) {
                    // storage full or similar: the old page (if any) stays
                } catch (e: Dropped) {
                    // the study is being dropped: dropStudy deletes what was fetched
                    return@withContext
                }
            }
            prune()
        }
    }

    private suspend fun fetch(study: Study, module: String, kept: Page?, now: Long) {
        val r = Api.offlinePage(study.server, study.secret, module)
        if (!r.optBoolean("offline", false)) {
            drop(study.code, module)
            return
        }
        val html = withoutCsrf(r.optString("html"))
        val version = r.optString("version")
        if (html.isBlank()) return
        val server = study.server
        val needed = LinkedHashSet<String>()  // full addresses: server and path
        var total = 0L
        // all files of one page together at most MAX_PAGE: the phone keeps no page that would fill it
        fun count(address: String) {
            total += File(files, hash(address)).length()
            if (total > MAX_PAGE) throw ApiError("error", 413)
        }
        for (path in resourcePaths(html)) {
            val address = server + path
            needed += address
            if (!has(address)) {
                if (study.code in dropping) throw Dropped()
                val (bytes, type) = Api.download(server, path)
                keepFile(address, bytes, type)
            }
            count(address)
            // a style sheet names its fonts and images itself
            if (path.substringBefore('?').endsWith(".css")) {
                val css = file(address)?.first?.readText().orEmpty()
                for (inner in cssPaths(css, path)) {
                    val innerAddress = server + inner
                    val seen = innerAddress in needed
                    needed += innerAddress
                    if (!has(innerAddress)) {
                        if (study.code in dropping) throw Dropped()
                        val (bytes, type) = Api.download(server, inner)
                        keepFile(innerAddress, bytes, type)
                    }
                    if (!seen) count(innerAddress)
                }
            }
        }
        // the same page as before: only the time of the check changes
        if (kept != null && kept.version == version && kept.html == html) {
            save(study.code, module, kept.copy(files = needed.toList(), fetched = now))
        } else {
            save(study.code, module, Page(html, version, needed.toList(), now))
        }
    }

    companion object {
        /** How often a kept page is checked with the server: often enough that a changed questionnaire is seen soon. */
        private const val REFRESH_MS = 15L * 60 * 1000

        /** The most all files of one kept page may take together. */
        private const val MAX_PAGE = 30L * 1024 * 1024

        private val lock = Mutex()

        /** The studies whose pages [dropStudy] is about to delete: a refresh of one of them stops (AN-9). */
        private val dropping: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** A refresh stopped because its study is being dropped. */
        private class Dropped : Exception()

        private val ATTR = Regex("""\b(?:src|href)\s*=\s*(["'])(/[^"']*)\1""", RegexOption.IGNORE_CASE)
        private val CSS_URL = Regex("""url\(\s*(["']?)([^"')]+)\1\s*\)""", RegexOption.IGNORE_CASE)

        private val CSRF_INPUT = Regex("""<input\b[^>]*\bname\s*=\s*(["'])csrf_token\1[^>]*>""", RegexOption.IGNORE_CASE)
        private val VALUE = Regex("""\bvalue\s*=\s*("[^"]*"|'[^']*')""", RegexOption.IGNORE_CASE)

        /**
         * [html] with the value of its CSRF field emptied: the kept page never sends its form to the server (the answer
         * goes through the app's queue), so the token has no use on the phone (AND-06).
         */
        fun withoutCsrf(html: String): String = CSRF_INPUT.replace(html) { m -> VALUE.replace(m.value, "value=\"\"") }

        /** Whether [path] is a server file the offline page may load: /static/ and /media/, nothing else. */
        fun servable(path: String): Boolean =
            (path.startsWith("/static/") || path.startsWith("/media/")) && !path.contains("..")

        /**
         * The server files an HTML page names in src and href attributes with an absolute path (/static/...,
         * /media/...), in order and without duplicates; &amp; in a query is read as &.
         */
        fun resourcePaths(html: String): List<String> =
            ATTR.findAll(html).map { it.groupValues[2].replace("&amp;", "&") }
                .filter { !it.startsWith("//") && servable(it) }.distinct().toList()

        /** The server files a style sheet at [cssPath] names with url(...), relative paths resolved against it. */
        fun cssPaths(css: String, cssPath: String): List<String> =
            CSS_URL.findAll(css).mapNotNull { m ->
                val ref = m.groupValues[2].trim()
                when {
                    ref.startsWith("data:") || ref.startsWith("#") || ref.startsWith("//") || ref.contains("://") -> null
                    ref.startsWith("/") -> ref
                    else -> runCatching { URI("http://h" + cssPath.substringBefore('?')).resolve(ref).let { u ->
                        u.rawPath + (u.rawQuery?.let { "?$it" } ?: "")
                    } }.getOrNull()
                }
            }.filter { servable(it) }.distinct().toList()

        fun guessType(path: String): String = when (path.substringBefore('?').substringAfterLast('.').lowercase()) {
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            "ttf" -> "font/ttf"
            "json" -> "application/json"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }
}
