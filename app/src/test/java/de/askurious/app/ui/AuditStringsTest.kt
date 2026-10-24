package de.askurious.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The strings of the UI/UX audit 2026-10-17 in all languages of the app: the name of a repeated button holds its
 * visible label (label in name, A08) and names its module and study; the question of the back gesture (A12).
 */
class AuditStringsTest {
    private val res = File("src/main/res")
    private val languages = res.listFiles { f -> f.isDirectory && File(f, "strings.xml").isFile }!!.sortedBy { it.name }

    private fun strings(dir: File): Map<String, String> =
        Regex("""<string name="([^"]+)">(.*?)</string>""").findAll(File(dir, "strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    @Test
    fun everyLanguageHasTheNewStrings() {
        assertEquals(12, languages.size)
        for (dir in languages) {
            val s = strings(dir)
            for (key in listOf("answer_prompt_for", "transmissions_for", "entry_leave_title", "entry_leave")) {
                assertTrue("${dir.name}: $key", !s[key].isNullOrBlank())
            }
            assertTrue(dir.name, s.getValue("answer_prompt_for").contains("%1\$s") && s.getValue("answer_prompt_for").contains("%2\$s"))
            assertTrue(dir.name, s.getValue("transmissions_for").contains("%1\$s"))
        }
    }

    /** The message of an entry on a WebView too old for the main-frame bridge names what to update, in every language. */
    @Test
    fun everyLanguageAsksToUpdateTheWebView() {
        for (dir in languages) assertTrue(dir.name, strings(dir)["webview_outdated"].orEmpty().contains("Android System WebView"))
    }

    @Test
    fun theNameOfARepeatedButtonHoldsItsVisibleLabel() {
        for (dir in languages) {
            val s = strings(dir)
            assertTrue(dir.name, s.getValue("answer_prompt_for").contains(s.getValue("answer_prompt")))
            assertTrue(dir.name, s.getValue("transmissions_for").contains(s.getValue("transmissions")))
        }
    }

    /**
     * The strings of the UX audit 2026-10-24 in every language: the notice of a category switched off per study, the
     * quiet hours with the same time twice, a QR code without a join code, and the denied permission named on its own
     * (AND-02, AND-04, AND-05, AND-06).
     */
    @Test
    fun everyLanguageHasTheStringsOfTheUxAudit20261024() {
        for (dir in languages) {
            val s = strings(dir)
            for (key in listOf(
                "channel_off_sound", "channel_off_silent", "channel_open", "channel_open_for", "quiet_same", "scan_no_code",
                "scan_again", "permission_microphone", "permission_location", "ready_notify_text",
            )) assertTrue("${dir.name}: $key", !s[key].isNullOrBlank())
            assertTrue(dir.name, s.getValue("channel_open_for").contains(s.getValue("channel_open")) && s.getValue("channel_open_for").contains("%1\$s"))
            // the switch is named as the dialog names it
            assertTrue(dir.name, s.getValue("quiet_same").contains(s.getValue("quiet_use")))
            assertTrue(dir.name, "permission_needed" !in s)
            assertTrue(dir.name, s.getValue("permission_microphone") != s.getValue("permission_location"))
        }
    }
}
