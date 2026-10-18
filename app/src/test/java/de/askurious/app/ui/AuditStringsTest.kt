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

    @Test
    fun theNameOfARepeatedButtonHoldsItsVisibleLabel() {
        for (dir in languages) {
            val s = strings(dir)
            assertTrue(dir.name, s.getValue("answer_prompt_for").contains(s.getValue("answer_prompt")))
            assertTrue(dir.name, s.getValue("transmissions_for").contains(s.getValue("transmissions")))
        }
    }
}
