package de.askurious.app.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-10-13, AN-3: the answers waiting to be sent, the log of the prompts and the list of studies are stored
 * sealed, and what earlier versions stored in plain text is sealed the first time it is read. AN-8: the Keystore is
 * asked once per stored text and process, not at every read.
 */
class SealedStorageTest {
    private val study = Study(code = "abc", secret = "s3cr3t", title = "Tagebuch Alkoholkonsum", status = "active",
        firstDay = null, lastDay = null, modules = emptyList(), server = "https://ask.askurious.de")

    private fun everything(prefs: FakePrefs) = prefs.values.values.joinToString("|")

    @Test
    fun queuedAnswersAreStoredSealed() {
        val prefs = FakePrefs()
        val queue = AnswerQueue(prefs, FakeSealer())
        queue.add("abc", "m1", null, null, 1L, 2L, "v", """{"drinks": ["Bier"]}""")
        assertFalse(everything(prefs).contains("Bier"))
        assertTrue((prefs.values["items"] as String).startsWith("k1:queue:"))
        // a new store of the same file reads it (the process keeps one SharedPreferences per file)
        assertEquals("""{"drinks":["Bier"]}""", AnswerQueue(prefs, FakeSealer()).all().single().form)
    }

    @Test
    fun aQueueStoredInPlainTextIsSealedWhenRead() {
        val prefs = FakePrefs()
        val item = AnswerQueue.Item("id-1", "abc", "m1", null, null, 1L, 2L, "v", """{"drinks":["Bier"]}""")
        prefs.values["items"] = JSONArray(listOf(item.toJson())).toString()
        assertEquals(listOf(item), AnswerQueue(prefs, FakeSealer()).all())
        assertFalse(everything(prefs).contains("Bier"))
    }

    @Test
    fun thePromptLogIsStoredSealedAndAnOldOneIsSealedWhenRead() {
        val prefs = FakePrefs()
        prefs.values["log"] = """{"abc|m1|1000": {"due": 1000, "notified": 1234, "kept": ${System.currentTimeMillis()}}}"""
        val log = PromptLog(prefs, FakeSealer())
        assertEquals(1234L, log.notifiedAt("abc", "m1", 1000L))
        assertFalse(everything(prefs).contains("1234"))
        log.opened("abc", "m1", 1000L)
        assertTrue(PromptLog(prefs, FakeSealer()).isOpened("abc", "m1", 1000L))
        assertTrue((prefs.values["log"] as String).startsWith("k1:prompts:"))
    }

    @Test
    fun theStudiesAreStoredSealedAsAWhole() {
        val prefs = FakePrefs()
        StudyStore(prefs, FakeSealer()).put(study)
        assertFalse(everything(prefs).contains("Alkohol"))
        assertFalse(everything(prefs).contains("s3cr3t"))
        assertFalse(everything(prefs).contains("askurious.de"))
        assertEquals(listOf(study), StudyStore(prefs, FakeSealer()).all())
    }

    @Test
    fun theListOfAnEarlierVersionIsMovedAndAnEntryThatCannotBeOpenedStays() {
        val prefs = FakePrefs()
        val sealer = FakeSealer()
        // one study with its secret sealed on its own (as before), one in plain text (older still), one whose sealed
        // secret no longer opens (the Keystore lost its key)
        val other = study.copy(code = "def", secret = "zweites")
        val lost = StudyStore.sealed(study.copy(code = "ghi").toJson(), sealer).put("secret_sealed", "k1:lost")
        prefs.values["studies"] = JSONArray(listOf(StudyStore.sealed(study.toJson(), sealer), other.toJson(), lost)).toString()

        val store = StudyStore(prefs, sealer)
        assertEquals(setOf("abc", "def"), store.all().map { it.code }.toSet())
        // moved: sealed as a whole; the earlier list keeps only the entry that cannot be opened, for a later fix
        assertFalse(everything(prefs).contains("zweites"))
        assertTrue((prefs.values["studies_sealed"] as String).startsWith("k1:studies:"))
        val left = JSONArray(prefs.values["studies"] as String)
        assertEquals(1, left.length())
        assertEquals("ghi", left.getJSONObject(0).getString("code"))
        // and nothing is lost when the store writes again
        store.remove("def")
        assertEquals(listOf("abc"), StudyStore(prefs, sealer).all().map { it.code })
        assertEquals(1, JSONArray(prefs.values["studies"] as String).length())
    }

    @Test
    fun aSealedListThatCannotBeOpenedIsNotWrittenOver() {
        val prefs = FakePrefs()
        prefs.values["studies_sealed"] = "k1:other:xyz"
        val store = StudyStore(prefs, FakeSealer())
        assertEquals(emptyList<Study>(), store.all())
        store.remove("abc")
        assertEquals("k1:other:xyz", prefs.values["studies_sealed"])
    }

    @Test
    fun aTextSealedForOnePurposeDoesNotOpenForAnother() {
        val sealer = FakeSealer()
        assertNull(sealer.open(sealer.seal("[]", "queue"), "studies"))
        val prefs = FakePrefs()
        prefs.values["studies_sealed"] = sealer.seal(JSONArray(listOf(study.toJson())).toString(), "queue")
        assertEquals(emptyList<Study>(), StudyStore(prefs, sealer).all())
    }

    @Test
    fun theKeystoreIsAskedOncePerStoredText() {
        val prefs = FakePrefs()
        StudyStore(prefs, FakeSealer()).put(study)
        val sealer = FakeSealer()
        // a fresh process: the text in memory is gone
        val fresh = FakePrefs().apply { values.putAll(prefs.values) }
        repeat(20) { StudyStore(fresh, sealer).all() }
        StudyStore(fresh, sealer).get("abc")
        assertEquals(1, sealer.opened)
    }
}
