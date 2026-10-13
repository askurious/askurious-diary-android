package de.askurious.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The secrets are stored sealed (StudyStore.sealed/opened, architecture concept 2026-10-12, K4). */
class StudyStoreSealTest {
    /** A stand-in for the Keystore: reversible, and it refuses what it did not seal. */
    private val fake = object : Sealer {
        override fun seal(plain: String, aad: String) = "x:" + plain.reversed()
        override fun open(sealed: String, aad: String) = if (sealed.startsWith("x:")) sealed.removePrefix("x:").reversed() else null
    }

    private val study = Study(code = "abc", secret = "s3cr3t", title = "T", status = "active", firstDay = null, lastDay = null,
        modules = emptyList(), server = "https://example.org")

    @Test
    fun theStoredJsonHasNoSecretInPlainText() {
        val stored = StudyStore.sealed(study.toJson(), fake)
        assertFalse(stored.has("secret"))
        assertFalse(stored.toString().contains("s3cr3t"))
        assertEquals("x:t3rc3s", stored.getString("secret_sealed"))
    }

    @Test
    fun aSealedStudyOpensToTheSameStudy() {
        val opened = StudyStore.opened(JSONObject(StudyStore.sealed(study.toJson(), fake).toString()), fake)!!
        assertEquals(study, Study.fromJson(opened))
    }

    @Test
    fun aStudyStoredBeforeTheSealIsReadAsItWas() {
        val old = study.toJson()
        assertTrue(old.has("secret"))
        assertEquals(study, Study.fromJson(StudyStore.opened(old, fake)!!))
    }

    @Test
    fun aSecretThatCannotBeOpenedGivesNoStudy() {
        val other = JSONObject(study.toJson().toString()).apply { remove("secret") }.put("secret_sealed", "k1:lost")
        assertNull(StudyStore.opened(other, fake))
    }
}
