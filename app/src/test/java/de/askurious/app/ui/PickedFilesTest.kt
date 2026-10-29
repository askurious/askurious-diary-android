package de.askurious.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check of a file picker's answer (audit 2026-10-28, S07). */
class PickedFilesTest {
    private val own = "de.askurious.app"

    @Test
    fun onlyContentOfOtherApps() {
        assertTrue(PickedFiles.allowed("content", "com.android.providers.media.documents", own))
        assertTrue(PickedFiles.allowed("CONTENT", "com.example.files", own))
        assertFalse(PickedFiles.allowed("file", "", own))
        assertFalse(PickedFiles.allowed(null, "com.example.files", own))
        assertFalse(PickedFiles.allowed("content", null, own))
        assertFalse(PickedFiles.allowed("content", "", own))
        assertFalse(PickedFiles.allowed("content", own, own))
        assertFalse(PickedFiles.allowed("content", "$own.fileprovider", own))
        assertFalse(PickedFiles.allowed("content", "user@$own.provider", own))
        assertFalse(PickedFiles.allowed("https", "example.org", own))
    }
}
