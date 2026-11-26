package de.askurious.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Security audit 2026-11-26, the parts kept in files and in the database: the queue has no write-ahead log, takes no
 * row filed under another study and writes no row it could not read again (SEC-12, SEC-03); the files of the kept
 * pages are encrypted, the page's name shows neither the study nor the module, and nothing in plain text is read once
 * everything was encrypted (SEC-07, SEC-08); the list of failed updates shows no study code (SEC-04).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SecurityAuditStorageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sealer = FakeSealer()
    private val queuePrefs get() = context.getSharedPreferences("queue", Context.MODE_PRIVATE)
    private fun queue() = AnswerQueue(context, sealer)
    private fun add(id: String, study: String, form: String = "{\"answer\":[\"original\"]}") =
        queue().add(study, "m1", null, null, 1L, 2L, "v", form, id)
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private val filesDir get() = File(context.filesDir, "offline/files")
    private val pagesDir get() = File(context.filesDir, "offline/pages")

    @Before fun reset() {
        QueueDatabase.closeForTest(); context.deleteDatabase("answers.db"); queuePrefs.edit().clear().commit()
        SealedText(queuePrefs, "items", sealer, "queue").remove(durable = true)
        File(context.filesDir, "offline").deleteRecursively()
        context.getSharedPreferences("offline", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("sync_status", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun close() { QueueDatabase.closeForTest() }

    @Test fun theQueueKeepsNoWriteAheadLog() {
        add("one", "abc")
        queue().remove("one")
        val path = context.getDatabasePath("answers.db")
        assertTrue(path.exists())
        assertFalse(File(path.path + "-wal").exists())
        QueueDatabase.closeForTest()
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA journal_mode", null).use { c ->
                assertTrue(c.moveToFirst())
                assertFalse(c.getString(0), c.getString(0).equals("wal", ignoreCase = true))
            }
        }
    }

    @Test fun aRowFiledUnderAnotherStudyIsNotTaken() {
        add("one", "abc")
        add("two", "xyz")
        QueueDatabase.closeForTest()
        // somebody with the file moves the answer of "abc" to the study "xyz" (the index columns are not sealed)
        SQLiteDatabase.openDatabase(context.getDatabasePath("answers.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE answers SET study=(SELECT study FROM answers WHERE id='two') WHERE id='one'")
        }
        assertThrows(IOException::class.java) { queue().of("xyz") }
        assertThrows(IOException::class.java) { queue().all() }
    }

    @Test fun aRowTooLargeToBeReadAgainIsNeverWritten() {
        add("small", "abc")
        val large = JSONObject().put("text", "x".repeat(AnswerQueue.MAX_FORM_BYTES)).toString()
        assertThrows(AnswerTooLargeException::class.java) { add("large", "abc", large) }
        // the queue of every study still reads, and the answer kept before is as it was
        assertEquals(listOf("small"), queue().all().map { it.id })
        // nor by a later change of a row
        val item = queue().get("small")!!
        assertThrows(IOException::class.java) { queue().replace(item.copy(form = large)) }
        assertEquals("{\"answer\":[\"original\"]}", queue().get("small")!!.form)
        // the largest form that is taken fits a row a cursor reads
        val fits = JSONObject().put("t", "x".repeat(AnswerQueue.MAX_FORM_BYTES - 16)).toString()
        add("fits", "abc", fits)
        assertEquals(fits, queue().get("fits")!!.form)
    }

    @Test fun theFilesOfAKeptPageAreEncrypted() {
        val pages = OfflinePages(context, sealer)
        val svg = "<svg><title>Trinktagebuch</title></svg>".toByteArray()
        pages.keepFile("https://example.org/media/1/logo.svg", svg, "image/svg+xml")
        val stored = filesDir.listFiles()!!.single { !it.name.contains('.') }
        val onDisk = stored.readBytes()
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("Trinktagebuch"))
        assertEquals("AKF1", String(onDisk, 0, 4, Charsets.US_ASCII))
        // it opens again, also for a new instance (the key is kept sealed in the preferences)
        val (bytes, type) = OfflinePages(context, sealer).file("https://example.org", "/media/1/logo.svg")!!
        assertArrayEquals(svg, bytes)
        assertEquals("image/svg+xml", type)
        // a file changed on disk, or put under another file's name, does not open
        val other = File(filesDir, sha("https://example.org/media/1/other.svg"))
        other.writeBytes(onDisk)
        assertNull(pages.file("https://example.org", "/media/1/other.svg"))
        stored.writeBytes(onDisk.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertNull(pages.file("https://example.org", "/media/1/logo.svg"))
        // the key itself is not in the preferences in plain text
        val key = context.getSharedPreferences("offline", Context.MODE_PRIVATE).getString("file_key", null)!!
        assertTrue(key.startsWith("k1:offline-file-key:"))
    }

    @Test fun aFileKeptInPlainTextByAnEarlierVersionIsEncryptedOnceAndPlainTextIsNotReadAfterwards() {
        val pages = OfflinePages(context, sealer)
        filesDir.mkdirs()
        val address = "https://example.org/static/participant.js"
        val old = File(filesDir, sha(address))
        old.writeText("console.log('alt')")
        File(filesDir, sha(address) + ".type").writeText("text/javascript")
        // still served as it is until everything was encrypted
        assertEquals("console.log('alt')", String(pages.file("https://example.org", "/static/participant.js")!!.first))
        pages.sealOld()
        assertEquals("AKF1", String(old.readBytes(), 0, 4, Charsets.US_ASCII))
        assertEquals("console.log('alt')", String(pages.file("https://example.org", "/static/participant.js")!!.first))
        // from now on a file in plain text is not the app's own
        old.writeText("console.log('untergeschoben')")
        assertNull(OfflinePages(context, sealer).file("https://example.org", "/static/participant.js"))
        // nor a page in plain text
        pages.save("abc", "m1", OfflinePages.Page("<p>echt</p>", "v1", emptyList(), 1L))
        pagesDir.listFiles()!!.single { it.name.endsWith(".json") }.writeText(
            JSONObject().put("html", "<script>alert(1)</script>").put("version", "v1").put("files", org.json.JSONArray()).put("fetched", 2L).toString())
        assertNull(OfflinePages(context, sealer).page("abc", "m1"))
    }

    @Test fun theNameOfAKeptPageShowsNeitherTheStudyNorTheModule() {
        OfflinePages(context, sealer).save("k7qm2x", "mood", OfflinePages.Page("<p>x</p>", "v1", emptyList(), 1L))
        val name = pagesDir.listFiles()!!.single().name
        assertFalse(name, name.contains(sha("k7qm2x")) || name.contains(sha("mood")) || name.contains("mood"))
        assertEquals("<p>x</p>", OfflinePages(context, sealer).page("k7qm2x", "mood")?.html)
    }

    /** A page kept under the name of the last version (keyed study part, plain hash of the module) is moved over. */
    @Test fun aPageNamedWithThePlainHashOfItsModuleIsRenamed() {
        val pages = OfflinePages(context, sealer)
        pages.save("k7qm2x", "other", OfflinePages.Page("<p>y</p>", "v1", emptyList(), 1L))
        val studyPart = pagesDir.listFiles()!!.single().name.substringBefore('_')
        val old = File(pagesDir, "${studyPart}_${sha("mood")}.json")
        old.writeText(sealer.seal("""{"html":"<p>alt</p>","version":"v0","files":[],"fetched":1}""", "offline:" + old.name))
        assertEquals("<p>alt</p>", OfflinePages(context, sealer).page("k7qm2x", "mood")?.html)
        assertFalse(old.exists())
        assertTrue(pagesDir.listFiles()!!.none { it.name.contains(sha("mood")) })
        pages.drop("k7qm2x", "mood")
        assertNull(OfflinePages(context, sealer).page("k7qm2x", "mood"))
    }

    @Test fun theListOfFailedUpdatesShowsNoStudyCode() {
        val code = "k7qm2x"
        val prefs = context.getSharedPreferences("sync_status", Context.MODE_PRIVATE)
        // what the last version left there in plain text
        prefs.edit().putStringSet("failed_codes", setOf(code)).commit()
        val failed = Sync.failedCodes(context, sealer)
        assertEquals(setOf(code), failed.get())
        failed.set(setOf(code, "abc123"))
        assertFalse(prefs.all.any { (key, value) -> key.contains(code) || value.toString().contains(code) })
        assertEquals(setOf(code, "abc123"), Sync.failedCodes(context, sealer).get())
        // a study taken off the phone goes from it
        Sync.failedCodes(context, sealer).remove(code)
        assertEquals(setOf("abc123"), Sync.failedCodes(context, sealer).get())
    }
}
