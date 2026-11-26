package de.askurious.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Security audit 2026-11-26, the parts that need no Android: plain text is no longer taken once a text was sealed
 * (SEC-08), sets are not written in plain text and logs not written over while the Keystore fails (SEC-08), the ids of
 * closed refusal notices are sealed (SEC-13), a form too large for the queue is refused (SEC-03), codes and module keys
 * are checked before they become part of an address and no address with a user part counts as the server's (SEC-05,
 * SEC-09), kept files are served only under /static/ and /media/ (SEC-07), and what the participant allowed a study
 * comes only from the phone's own store (SEC-02).
 */
class SecurityAuditTest {
    /** The same stored file read by a new process: nothing of it in memory yet. */
    private fun reopened(prefs: FakePrefs) = FakePrefs().also { it.values.putAll(prefs.values) }

    /** The Keystore fails: nothing seals, nothing opens. */
    private val down = object : Sealer {
        override fun seal(plain: String, aad: String): String = throw IllegalStateException("Keystore unavailable")
        override fun open(sealed: String, aad: String): String? = null
    }

    /** Opens what is stored, but cannot seal anything new. */
    private val cannotSeal = object : Sealer {
        private val real = FakeSealer()
        override fun seal(plain: String, aad: String): String = throw IllegalStateException("Keystore unavailable")
        override fun open(sealed: String, aad: String): String? = real.open(sealed, aad)
    }

    @Test
    fun plainTextIsNotTakenOnceTheTextWasSealed() {
        val prefs = FakePrefs()
        SealedText(prefs, "studies_sealed", FakeSealer(), "studies").write("""[{"code":"abc"}]""")
        // somebody puts plain text in place of the sealed one
        val swapped = reopened(prefs)
        swapped.values["studies_sealed"] = """[{"code":"abc","server":"https://evil.example"}]"""
        val opened = SealedText(swapped, "studies_sealed", FakeSealer(), "studies").read()
        assertNull(opened.text)
        assertTrue(opened.unreadable)
        // and it was not sealed with the app's key
        assertFalse((swapped.values["studies_sealed"] as String).startsWith("k1:"))
    }

    @Test
    fun plainTextOfAnEarlierVersionIsStillTakenOnce() {
        val prefs = FakePrefs()
        prefs.values["log"] = """{"a":1}"""
        assertEquals("""{"a":1}""", SealedText(prefs, "log", FakeSealer(), "prompts").read().text)
        assertTrue((prefs.values["log"] as String).startsWith("k1:prompts:"))
        // once sealed, plain text in its place is not the app's own
        val later = reopened(prefs)
        later.values["log"] = """{"a":2}"""
        assertTrue(SealedText(later, "log", FakeSealer(), "prompts").read().unreadable)
    }

    @Test
    fun aTextTheWorkingKeystoreDoesNotOpenIsDamagedAndOneOfAFailingKeystoreIsNot() {
        val prefs = FakePrefs()
        prefs.values["log"] = "k1:garbage"
        val damaged = SealedText(prefs, "log", FakeSealer(), "prompts").read()
        assertTrue(damaged.unreadable && damaged.damaged)
        val waiting = SealedText(reopened(prefs), "log", down, "prompts").read()
        assertTrue(waiting.unreadable && !waiting.damaged)
    }

    @Test
    fun aSetIsNotWrittenInPlainTextWhenItCannotBeSealed() {
        val prefs = FakePrefs()
        val set = SealedSet(prefs, "refused", down, "refused")
        set.add("abc123")
        assertFalse(prefs.values.values.joinToString("|").contains("abc123"))
        assertThrows(IOException::class.java) { set.add("abc123", durable = true) }
        assertFalse(prefs.values.values.joinToString("|").contains("abc123"))
        // only the set that is needed exactly then is kept plain rather than lost
        val forget = SealedSet(prefs, "forget", down, "forget", fallbackPlain = true)
        forget.add("abc123", durable = true)
        assertEquals(setOf("abc123"), forget.get())
    }

    @Test
    fun aCodeTakenOutOfTheFallbackSetDoesNotComeBack() {
        val prefs = FakePrefs()
        SealedSet(prefs, "forget", FakeSealer(), "forget", fallbackPlain = true).set(setOf("one", "two"))
        val later = reopened(prefs)
        SealedSet(later, "forget", cannotSeal, "forget", fallbackPlain = true).remove("one")
        assertEquals(setOf("two"), SealedSet(later, "forget", cannotSeal, "forget", fallbackPlain = true).get())
        // also once the Keystore works again, in a new process
        assertEquals(setOf("two"), SealedSet(reopened(later), "forget", FakeSealer(), "forget", fallbackPlain = true).get())
    }

    @Test
    fun thePromptLogIsNotWrittenOverWhileTheKeystoreFails() {
        val prefs = FakePrefs()
        PromptLog(prefs, FakeSealer()).notified("abc", "m1", 1000L, 1234L)
        val failing = reopened(prefs)
        PromptLog(failing, down).opened("xyz", "m1", 2000L)
        PromptLog(failing, down).removeStudy("abc")
        assertEquals(prefs.values["log"], failing.values["log"])
        assertEquals(1234L, PromptLog(reopened(failing), FakeSealer()).notifiedAt("abc", "m1", 1000L))
    }

    @Test
    fun aDamagedPromptLogStartsAnew() {
        val prefs = FakePrefs()
        prefs.values["log"] = "k1:garbage"
        PromptLog(prefs, FakeSealer()).opened("abc", "m1", System.currentTimeMillis())
        assertTrue((prefs.values["log"] as String).startsWith("k1:prompts:"))
    }

    @Test
    fun theReceiptsAreNotWrittenOverWhileTheKeystoreFails() {
        val prefs = FakePrefs()
        TransmissionLog(prefs, FakeSealer()).confirmed("abc", "m1", false, "id-1")
        val failing = reopened(prefs)
        assertThrows(IOException::class.java) { TransmissionLog(failing, down).confirmed("xyz", "m1", false, "id-2") }
        TransmissionLog(failing, down).remove("abc")
        assertEquals(prefs.values[TransmissionLog.ALL], failing.values[TransmissionLog.ALL])
        assertEquals("id-1", TransmissionLog(reopened(failing), FakeSealer()).of("abc").single().optString("id"))
        // the marker of the sealed text is no study of an earlier version
        assertTrue(TransmissionLog(reopened(failing), FakeSealer()).of("receipts.migrated").isEmpty())
    }

    @Test
    fun theIdsOfClosedRefusalNoticesAreSealedAndGoWithTheirAnswers() {
        val prefs = FakePrefs()
        val queue = AnswerQueue(prefs, FakeSealer())
        val item = queue.add("abc", "m1", null, null, 1L, 2L, "v", "{}", id = "11111111-2222-3333-4444-555555555555")
        queue.replace(item.copy(refusal = "done"))
        queue.dismissRefused("abc")
        assertEquals(setOf(item.id), queue.seenRefusals())
        assertNull(prefs.values["refusals_seen"])
        assertFalse(prefs.values.values.joinToString("|").contains(item.id))
        queue.removeStudy("abc")
        assertTrue(queue.seenRefusals().isEmpty())
    }

    @Test
    fun aPlainSetOfClosedNoticesOfAnEarlierVersionIsReadAndGoes() {
        val prefs = FakePrefs()
        prefs.values["refusals_seen"] = setOf("old-id")
        val queue = AnswerQueue(prefs, FakeSealer())
        assertEquals(setOf("old-id"), queue.seenRefusals())
        val item = queue.add("abc", "m1", null, null, 1L, 2L, "v", "{}", id = "new-id")
        queue.replace(item.copy(refusal = "done"))
        queue.dismissRefused("abc")
        assertNull(prefs.values["refusals_seen"])
        assertEquals(setOf("new-id"), queue.seenRefusals())
    }

    @Test
    fun aFormTooLargeForTheQueueIsRefused() {
        val queue = AnswerQueue(FakePrefs(), FakeSealer())
        val large = JSONObject().put("text", "x".repeat(AnswerQueue.MAX_FORM_BYTES)).toString()
        assertTrue(AnswerQueue.tooLarge(large))
        assertFalse(AnswerQueue.tooLarge("""{"text":["kurz"]}"""))
        assertThrows(AnswerTooLargeException::class.java) { queue.add("abc", "m1", null, null, 1L, 2L, "v", large) }
        assertTrue(queue.all().isEmpty())
        // umlauts count as the bytes they take
        assertTrue(AnswerQueue.tooLarge("ä".repeat(AnswerQueue.MAX_FORM_BYTES / 2 + 1)))
    }

    @Test
    fun onlyCodesAsTheServerMakesThemAreSent() {
        for (code in listOf("k7qm2x", "k7qm2x-t3f9a", "a", "0")) assertTrue(code, Api.validCode(code))
        for (code in listOf("", "..", ".", "a/b", "a-b-c", "ABC", "k7 qm2x", "a%2e", "-a", "a-", "x".repeat(33), "ä")) {
            assertFalse(code, Api.validCode(code))
        }
    }

    @Test
    fun aModuleKeyIsNoPathOfItsOwn() {
        for (key in listOf("mood", "m1", "abend-fragen", "Schlaf 2")) assertTrue(key, Api.validModule(key))
        for (key in listOf("", ".", "..", "../study", "a/b", "a\\b", "a\nb", "x".repeat(129))) assertFalse(key, Api.validModule(key))
    }

    @Test
    fun anAddressWithAUserPartIsNotTheServers() {
        val base = java.net.URI("https://ask.askurious.de")
        assertTrue(Api.sameOrigin(java.net.URI("https://ask.askurious.de/p/x"), base))
        assertFalse(Api.sameOrigin(java.net.URI("https://user@ask.askurious.de/p/x"), base))
        assertFalse(Api.sameOrigin(java.net.URI("https://ask.askurious.de@evil.example/p/x"), base))
        assertThrows(ApiError::class.java) { Api.absolute("https://x@ask.askurious.de/p/x", "https://ask.askurious.de", debug = false) }
    }

    @Test
    fun keptFilesAreServedOnlyFromTheirTwoFolders() {
        for (path in listOf("/static/participant.js", "/static/css/a.css?v=3", "/media/1/bild.png", "/media/a%20b.png")) {
            assertTrue(path, OfflinePages.servable(path))
        }
        for (path in listOf(
            "/api/diary/v1/study", "/static/../api/x", "/media/..", "/static/%2e%2e/api", "/static/%2E%2E/api", "/static/a%2fb",
            "/static/a%5cb", "/static\\..\\api", "/static/a#b", "/static/a\u0000b", "/static/a\nb", "/static//etc", "/staticx/a",
            "/static/./a", "static/a",
        )) assertFalse(path, OfflinePages.servable(path))
    }

    @Test
    fun whatTheParticipantAllowedAStudyComesOnlyFromThePhonesOwnStore() {
        val study = Study(code = "abc", secret = "s", title = "T", status = "active", firstDay = null, lastDay = null,
            modules = emptyList(), server = "https://ask.askurious.de", allowMicrophone = true, allowLocation = true)
        val json = study.toJson()
        // as the phone stored it
        assertTrue(Study.fromJson(json, stored = true).let { it.allowMicrophone && it.allowLocation })
        // an answer of a server that names them sets nothing
        assertFalse(Study.fromJson(json, "s", "https://ask.askurious.de").let { it.allowMicrophone || it.allowLocation })
        assertFalse(Study.fromJson(json).let { it.allowMicrophone || it.allowLocation })
        // a sync keeps the participant's choice
        val fetched = Study.fromJson(json, "s", "https://ask.askurious.de")
        assertTrue(Sync.merged(fetched, study, null)!!.let { it.allowMicrophone && it.allowLocation })
        // and the store gives it back
        val prefs = FakePrefs()
        StudyStore(prefs, FakeSealer()).put(study)
        assertTrue(StudyStore(reopened(prefs), FakeSealer()).get("abc")!!.allowMicrophone)
    }
}
