package de.askurious.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app shows its own licence (EUPL-1.2 with the copyright and the parts not under it) and links its source code
 * (EUPL-1.2 Art. 5); res/raw/eupl_licence.txt is the root LICENSE, unchanged.
 */
class OwnLicenceTest {
    @Test
    fun theShippedLicenceIsTheRootLicense() {
        val shipped = File("src/main/res/raw/eupl_licence.txt").readText().replace("\r\n", "\n")
        assertEquals(File("../LICENSE").readText().replace("\r\n", "\n"), shipped)
        assertTrue(shipped.contains("EUROPEAN UNION PUBLIC LICENCE v. 1.2"))
    }

    @Test
    fun everyLanguageNamesTheLicenceAndTheSourceCode() {
        val res = File("src/main/res")
        val dirs = res.listFiles { f -> f.isDirectory && File(f, "strings.xml").isFile }!!
        assertEquals(12, dirs.size)
        for (dir in dirs) {
            val xml = File(dir, "strings.xml").readText()
            for (key in listOf("licence_title_eupl", "source_code")) {
                assertTrue("${dir.name}: $key", Regex("""<string name="$key">[^<]+</string>""").containsMatchIn(xml))
            }
        }
        assertTrue(SOURCE_CODE_URL.startsWith("https://github.com/askurious/"))
    }
}
