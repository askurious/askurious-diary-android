package de.askurious.app.data

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Audit 2026-10-22, F-01: the studies' codes stood readable in the app's files although their values were sealed: as
 * the name of a preference (receipts), in plain sets (exits, refused, forget) and as an unsalted SHA-256 in the names
 * of the kept pages (a code of six letters is undone in minutes). Whoever had a copy of the app's files saw which
 * studies the person takes part in. Now none of them shows a code, and what earlier versions stored is moved over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class StudyCodesNotStoredTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val code = "k7qm2x"
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun shows(name: String): Boolean = prefs(name).all.any { (key, value) ->
        key.contains(code) || value.toString().contains(code)
    }

    @Test
    fun noPreferenceAndNoFileNameShowsTheCode() {
        val sealer = FakeSealer()
        TransmissionLog(prefs("transmission"), sealer).confirmed(code, "m1", false, "id-1")
        PromptLog(prefs("prompts"), sealer).exitNotified(code)
        val queue = AnswerQueue(prefs("queue"), sealer)
        queue.noteRefused(code)
        OfflinePages(context, sealer).save(code, "m1", OfflinePages.Page("<p>x</p>", "v1", emptyList(), 1L))
        for (name in listOf("transmission", "prompts", "queue", "offline")) assertFalse(name, shows(name))
        val names = File(context.filesDir, "offline/pages").listFiles()!!.map { it.name }
        assertTrue(names.isNotEmpty())
        assertTrue(names.none { it.contains(code) || it.startsWith(sha(code)) })
        // and everything reads back
        assertEquals("id-1", TransmissionLog(prefs("transmission"), sealer).of(code).single().getString("id"))
        assertEquals(setOf(code), PromptLog(prefs("prompts"), sealer).exits())
        assertEquals(setOf(code), queue.refusedStudies())
        assertEquals("<p>x</p>", OfflinePages(context, sealer).page(code, "m1")?.html)
    }

    @Test
    fun whatEarlierVersionsStoredIsMovedOver() {
        val sealer = FakeSealer()
        val transmission = prefs("transmission")
        SealedText(transmission, code, sealer, "transmission:$code").write(JSONArray().put(org.json.JSONObject().put("id", "old")).toString(), durable = true)
        prefs("prompts").edit().putStringSet("exits", setOf(code)).commit()
        prefs("queue").edit().putStringSet("refused", setOf(code)).commit()
        assertEquals("old", TransmissionLog(transmission, sealer).of(code).single().getString("id"))
        assertFalse(transmission.all.keys.contains(code))
        assertEquals(setOf(code), PromptLog(prefs("prompts"), sealer).exits())
        PromptLog(prefs("prompts"), sealer).exitNotified("other1")       // the next write seals and drops the plain set
        assertFalse(prefs("prompts").all.containsKey("exits"))
        assertEquals(setOf(code, "other1"), PromptLog(prefs("prompts"), sealer).exits())
        val queue = AnswerQueue(prefs("queue"), sealer)
        assertEquals(setOf(code), queue.refusedStudies())
        queue.clearRefused(code)
        assertEquals(emptySet<String>(), queue.refusedStudies())
        assertFalse(prefs("queue").all.containsKey("refused"))
    }

    @Test
    fun aPageKeptByAnEarlierVersionIsRenamedAtItsFirstRead() {
        val sealer = FakeSealer()
        val pages = File(context.filesDir, "offline/pages").apply { mkdirs() }
        val old = File(pages, "${sha(code)}_${sha("m1")}.json")
        old.writeText(sealer.seal("""{"html":"<p>alt</p>","version":"v0","files":[],"fetched":1}""", "offline:" + old.name))
        assertEquals("<p>alt</p>", OfflinePages(context, sealer).page(code, "m1")?.html)
        assertFalse(old.exists())
        assertTrue(pages.listFiles()!!.none { it.name.startsWith(sha(code)) })
    }
}
