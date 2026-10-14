package de.askurious.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalUiMetadataTest {
    @Test fun contactAndConfirmedPromptsSurviveLocalStorage() {
        val study = Study.fromJson(JSONObject().put("code", "example").put("contact", "team@example.org")
            .put("completed_prompts", JSONArray(listOf("m1|1234"))))
        val restored = Study.fromJson(study.toJson())
        assertEquals("team@example.org", restored.contact)
        assertEquals(setOf("m1|1234"), restored.completedPrompts)
        assertTrue(Study.fromJson(JSONObject().put("code", "old-server")).completedPrompts.isEmpty())
    }

    @Test fun transferMetadataStaysLocalAndPreservesTheModuleName() {
        val item = AnswerQueue.Item("id", "example", "m1", null, null, 10L, 20L, "version", "{}",
            attempted = 30L, error = "expired", moduleName = "Mood now")
        val restored = AnswerQueue.Item.fromJson(item.toJson())
        assertEquals("Mood now", restored.moduleName)
        assertEquals(30L, restored.attempted)
        assertEquals("expired", restored.error)
        assertFalse(item.body().has("module_name"))
        assertFalse(item.body().has("attempted"))
    }
}
