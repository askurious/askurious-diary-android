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
 * pages/<study>_<module>.json holds {html, version, files, fetched}; files/<hash> holds one file of a server and
 * files/<hash>.type its content type. A file is known by its full address (server and path: two servers may have
 * different files under the same path); files are shared by all pages of a server (they are the same for everybody)
 * and deleted when no page names them any more.
 */
class OfflinePages(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "offline")
    private val pages = File(root, "pages")
    private val files = File(root, "files")

    /**
     * A kept page: [files] are the full addresses (server and path) of the files it needs, [fetched] the time it was
     * last checked with the server. A page kept before the addresses were full names paths only: it counts as missing.
     */
    data class Page(val html: String, val version: String, val files: List<String>, val fetched: Long)

    fun page(study: String, module: String): Page? {
        val f = pageFile(study, module)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val list = o.optJSONArray("files") ?: JSONArray()
            Page(o.getString("html"), o.optString("version"), (0 until list.length()).map { list.getString(it) }, o.optLong("fetched"))
        }.getOrNull()
    }

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
        pageFile(study, module).delete()
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
                    pages.listFiles()?.filter { it.name.startsWith(hash(study) + "_") }?.forEach { it.delete() }
                    prune()
                }
            }
        } finally {
            dropping.remove(study)
        }
    }

    private fun has(path: String) = File(files, hash(path)).exists()

    private fun pageFile(study: String, module: String) = File(pages, "${hash(study)}_${hash(module)}.json")

    private fun save(study: String, module: String, page: Page) {
        pages.mkdirs()
        val o = JSONObject().put("html", page.html).put("version", page.version)
            .put("files", JSONArray(page.files)).put("fetched", page.fetched)
        write(pageFile(study, module), o.toString().toByteArray())
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

    /** Deletes the files no kept page names. */
    private fun prune() {
        val needed = pages.listFiles()?.filter { it.name.endsWith(".json") }?.flatMap { f ->
            runCatching {
                val list = JSONObject(f.readText()).optJSONArray("files") ?: JSONArray()
                (0 until list.length()).map { hash(list.getString(it)) }
            }.getOrDefault(emptyList())
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
            pages.listFiles()?.filter { f ->
                f.name.startsWith(hash(study.code) + "_") && wanted.none { f.name == pageFile(study.code, it).name }
            }?.forEach { it.delete() }
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
        val html = r.optString("html")
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
