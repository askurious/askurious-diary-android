package de.askurious.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The server of a study is kept on the phone (Study.server): stored, read back, and kept over a sync. */
class StudyServerTest {
    private val study = Study(
        code = "k7qm2x", secret = "s", title = "T", status = "active", firstDay = null, lastDay = null,
        modules = emptyList(), server = "https://example.org",
    )

    @Test
    fun storedAndReadBack() {
        assertEquals("https://example.org", Study.fromJson(study.toJson()).server)
    }

    @Test
    fun theServersAnswerKeepsTheStudysServer() {
        // the server sends no "server": the caller names the study's own (Sync.oneLocked)
        val answer = JSONObject().put("code", "k7qm2x").put("status", "active")
        assertEquals("https://example.org", Study.fromJson(answer, "s", study.server).server)
    }

    @Test
    fun aStudyFromBeforeHasTheAppsServer() {
        val old = study.toJson().apply { remove("server") }
        assertEquals(Api.defaultServer, Study.fromJson(old).server)
    }
}
