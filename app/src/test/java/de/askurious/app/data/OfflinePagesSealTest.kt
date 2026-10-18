package de.askurious.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * A kept questionnaire page is stored sealed, as the studies and the answers are (audit 2026-10-18, AND-06: the page
 * file held the study's title, its questions and the form's CSRF token in plain text). It opens again to the same page,
 * a page an earlier version kept in plain text is still read, and the CSRF token is not kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class OfflinePagesSealTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val html = """<html><head><title>Seite 1 – Tagebuch Trinkverhalten</title></head><body>
        <form><input type="hidden" name="csrf_token" value="IjliYmM4secret"><input name="q1"></form></body></html>"""
    private val page = OfflinePages.Page(html, "v1", listOf("https://example.org/static/p.js"), 1L)
    private fun stored(): List<File> = File(context.filesDir, "offline/pages").listFiles()!!.filter { it.name.endsWith(".json") }

    @Test
    fun theKeptPageFileShowsNeitherTitleNorQuestions() {
        OfflinePages(context, FakeSealer()).save("abc", "m1", page)
        val text = stored().single().readText()
        assertFalse(text, text.contains("Trinkverhalten"))
        assertFalse(text, text.contains("<html"))
        assertTrue(text.startsWith("k1:offline:"))
        assertEquals(page, OfflinePages(context, FakeSealer()).page("abc", "m1"))
        // a sealer that cannot open it (another key) gives no page, not a broken one
        val other = object : Sealer {
            override fun seal(plain: String, aad: String) = plain
            override fun open(sealed: String, aad: String): String? = null
        }
        assertNull(OfflinePages(context, other).page("abc", "m1"))
    }

    @Test
    fun aPageKeptInPlainTextByAnEarlierVersionIsStillRead() {
        OfflinePages(context, FakeSealer()).save("abc", "m2", page)
        // the same file as an earlier version wrote it: plain JSON
        stored().single().writeText(org.json.JSONObject().put("html", "<p>old</p>").put("version", "v0")
            .put("files", org.json.JSONArray()).put("fetched", 2L).toString())
        assertEquals("<p>old</p>", OfflinePages(context, FakeSealer()).page("abc", "m2")?.html)
    }

    @Test
    fun theCsrfTokenIsNotKept() {
        val stripped = OfflinePages.withoutCsrf(html)
        assertFalse(stripped.contains("IjliYmM4secret"))
        assertTrue(stripped.contains("""name="csrf_token" value="""""))
        assertTrue(stripped.contains("""<input name="q1">"""))
        assertEquals("<input name='csrf_token' value=\"\">", OfflinePages.withoutCsrf("<input name='csrf_token' value='x'>"))
    }
}
