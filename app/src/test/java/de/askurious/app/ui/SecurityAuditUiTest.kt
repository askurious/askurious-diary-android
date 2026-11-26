package de.askurious.app.ui

import de.askurious.app.data.Study
import de.askurious.app.schedule.BootReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Security audit 2026-11-26, the parts of the screens and receivers that need no Android: the id of a home-screen
 * shortcut names no study (SEC-11), the receiver takes the "Alarms & reminders" broadcast only where the system sends
 * it (SEC-14), the saved state of an entry holds only a hash of its page (SEC-01), and the new texts are there in every
 * language (SEC-02, SEC-03).
 */
class SecurityAuditUiTest {
    private fun study(code: String, status: String) = Study(
        code = code, secret = "s", title = "T", status = status, firstDay = null, lastDay = null, modules = emptyList(),
        server = "https://ask.askurious.de",
    )

    @Test
    fun theIdOfANewShortcutNamesNoStudy() {
        val id = Shortcuts.newId()
        assertTrue(id, Regex("^s_[0-9a-f]{32}$").matches(id))
        assertNotEquals(id, Shortcuts.newId())
        assertNull(Shortcuts.codeOf(id))
        assertNull(Shortcuts.legacy(id))
        // a shortcut of an earlier version still names its study and module
        assertEquals("k7qm2x" to "mood", Shortcuts.legacy(Shortcuts.id("k7qm2x", "mood")))
        assertNull(Shortcuts.legacy("report|k7qm2x|"))
        assertNull(Shortcuts.legacy("report||mood"))
    }

    @Test
    fun whatAShortcutStandsForIsKeptAsAListOfItsOwn() {
        val targets = mapOf("s_1" to ("k7qm2x" to "mood"), "s_2" to ("abc" to "sleep|2"))
        assertEquals(targets, Shortcuts.decode(Shortcuts.encode(targets)))
        assertTrue(Shortcuts.decode(null).isEmpty())
        assertTrue(Shortcuts.decode("no json").isEmpty())
    }

    @Test
    fun shortcutsWithARandomIdGoStaleWithTheirStudy() {
        val studies = listOf(study("run", "active"), study("over", "finished"))
        val targets = mapOf("s_run" to ("run" to "m1"), "s_over" to ("over" to "m1"), "s_gone" to ("gone" to "m1"))
        val ids = listOf("s_run", "s_over", "s_gone", "s_unknown", Shortcuts.id("over", "m1"), "other")
        assertEquals(listOf("s_over", "s_gone", Shortcuts.id("over", "m1")), Shortcuts.stale(ids, studies, targets))
        // without the list (it cannot be opened now) no shortcut with a random id is disabled
        assertEquals(listOf(Shortcuts.id("over", "m1")), Shortcuts.stale(ids, studies))
    }

    @Test
    fun theExactAlarmBroadcastCountsOnlyFromAndroid12() {
        assertTrue(BootReceiver.accepted(BootReceiver.EXACT_CHANGED, 31))
        assertTrue(BootReceiver.accepted(BootReceiver.EXACT_CHANGED, 36))
        for (sdk in 26..30) assertFalse("API $sdk", BootReceiver.accepted(BootReceiver.EXACT_CHANGED, sdk))
        assertTrue(BootReceiver.accepted("android.intent.action.BOOT_COMPLETED", 26))
        assertTrue(BootReceiver.accepted("android.intent.action.TIMEZONE_CHANGED", 26))
        assertFalse(BootReceiver.accepted("de.example.ANYTHING", 36))
        assertFalse(BootReceiver.accepted(null, 36))
    }

    @Test
    fun onlyAHashOfThePageIsKeptWithTheSavedState() {
        val page = "https://ask.askurious.de/p/abc/3?t=secret|3|alkohol_heute|stimmung"
        val hash = EntrySession.hash(page)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hash))
        assertFalse(hash.contains("alkohol"))
        assertEquals(hash, EntrySession.hash(page))
        assertNotEquals(hash, EntrySession.hash(page + "|schlaf"))
    }

    /** The entry keeps the address, the draft and the page only under these names: sealed, or as a hash. */
    @Test
    fun theEntryPutsNoPlainDraftOrAddressIntoTheSavedState() {
        val source = File("src/main/java/de/askurious/app/ui/EntryActivity.kt").readText() +
            File("src/main/java/de/askurious/app/ui/EntrySession.kt").readText()
        for (key in listOf("state[\"draft\"]", "state[\"url\"]", "state[\"page\"]", "get<String>(\"draft\")", "get<String>(\"url\")")) {
            assertFalse(key, source.contains(key))
        }
    }

    @Test
    fun everyLanguageHasTheStringsOfTheSecurityAudit20261126() {
        val res = File("src/main/res")
        val languages = res.listFiles { f -> f.isDirectory && File(f, "strings.xml").isFile }!!.sortedBy { it.name }
        assertEquals(12, languages.size)
        for (dir in languages) {
            val s = Regex("""<string name="([^"]+)">(.*?)</string>""").findAll(File(dir, "strings.xml").readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
            for (key in listOf(
                "consent_microphone_title", "consent_microphone_text", "consent_location_title", "consent_location_text", "consent_deny",
                "access_title", "access_microphone", "access_microphone_revoke", "access_location", "access_location_revoke", "answer_too_large",
            )) assertTrue("${dir.name}: $key", !s[key].isNullOrBlank())
            // the question names the study
            for (key in listOf("consent_microphone_text", "consent_location_text")) assertTrue("${dir.name}: $key", s.getValue(key).contains("%1\$s"))
            // an apostrophe not escaped breaks the build of the resources
            for ((key, text) in s) if (key.startsWith("consent_") || key.startsWith("access_") || key == "answer_too_large") {
                assertFalse("${dir.name}: $key", Regex("(?<!\\\\)'").containsMatchIn(text))
            }
        }
    }
}
