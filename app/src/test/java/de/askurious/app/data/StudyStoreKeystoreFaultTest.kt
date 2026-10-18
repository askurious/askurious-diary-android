package de.askurious.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A join while the stored list of studies cannot be opened (the Keystore failed for a while) must not write over the
 * secrets of the other studies (audit 2026-10-18, AND-05: StudyStore.save wrote the new study alone over the sealed
 * text; the audit's test showed only "c" left of a, b and c). The new study is kept apart and joins the list as soon
 * as it opens again; meanwhile the store says it is unreadable, so the start page can say so.
 */
class StudyStoreKeystoreFaultTest {
    private fun study(code: String) = Study(code = code, secret = "secret-$code", title = code, status = "active",
        firstDay = null, lastDay = null, modules = emptyList(), server = "https://example.org")

    /** Reversible like the Keystore, and refuses to open anything while [broken]. */
    private class Flaky : Sealer {
        @Volatile var broken = false
        override fun seal(plain: String, aad: String) = "x:$aad:" + plain.reversed()
        override fun open(sealed: String, aad: String) =
            if (broken || !sealed.startsWith("x:$aad:")) null else sealed.removePrefix("x:$aad:").reversed()
    }

    /** A new process: nothing opened is kept in memory. */
    private fun clearCache() {
        val f = SealedText::class.java.getDeclaredField("CACHE").apply { isAccessible = true }
        (f.get(null) as MutableMap<*, *>).clear()
    }

    @Test
    fun aJoinDuringAKeystoreFaultKeepsTheOtherStudies() {
        val prefs = FakePrefs()
        val sealer = Flaky()
        StudyStore(prefs, sealer).put(study("a"))
        StudyStore(prefs, sealer).put(study("b"))
        clearCache()
        sealer.broken = true
        assertTrue(StudyStore(prefs, sealer).unreadable())
        // opening the store fails, sealing a new text still works (the fault of the audit's scenario)
        sealer.broken = false
        val joining = object : Sealer {
            override fun seal(plain: String, aad: String) = sealer.seal(plain, aad)
            override fun open(sealed: String, aad: String) = if (aad == "studies") null else sealer.open(sealed, aad)
        }
        StudyStore(prefs, joining).put(study("c"))
        // meanwhile the new study is there, and the list of the others is not written over
        assertEquals(listOf("c"), StudyStore(prefs, joining).all().map { it.code })
        clearCache()
        assertEquals(setOf("a", "b", "c"), StudyStore(prefs, sealer).all().map { it.code }.toSet())
        assertFalse(StudyStore(prefs, sealer).unreadable())
        // moved into the list: read again from the stored text alone
        clearCache()
        assertEquals(setOf("a", "b", "c"), StudyStore(prefs, sealer).all().map { it.code }.toSet())
        assertEquals(null, prefs.getString("studies_sealed_pending", null))
    }

    @Test
    fun nothingIsWrittenWhileNeitherTextOpens() {
        val prefs = FakePrefs()
        val sealer = Flaky()
        StudyStore(prefs, sealer).put(study("a"))
        val before = prefs.getString("studies_sealed", null)
        clearCache()
        sealer.broken = true
        // sealing works, opening does not: a second join must not replace the first one kept apart either
        val noOpen = object : Sealer {
            override fun seal(plain: String, aad: String) = sealer.seal(plain, aad)
            override fun open(sealed: String, aad: String): String? = null
        }
        StudyStore(prefs, noOpen).put(study("b"))
        clearCache()
        val failed = runCatching { StudyStore(prefs, noOpen).put(study("c")) }
        assertTrue(failed.exceptionOrNull() is java.io.IOException)
        assertEquals(before, prefs.getString("studies_sealed", null))
        clearCache()
        sealer.broken = false
        assertEquals(setOf("a", "b"), StudyStore(prefs, sealer).all().map { it.code }.toSet())
    }
}
