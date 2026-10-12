package de.askurious.app.ui

import android.webkit.JavascriptInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The bridge participant.js talks to (server: app/static/participant.js): window.AskuriousApp.done() when an entry
 * ended and window.AskuriousApp.offlineSubmit(json) for a kept page. The server's tests/test_diary_app_bridge.py runs
 * the real pages against these names in a browser; this test checks that the app offers them, so that a rename on
 * either side fails a test instead of losing answers without an error.
 */
class BridgeContractTest {
    @Test
    fun theBridgeHasTheNameAndTheFunctionsParticipantJsCalls() {
        assertEquals("AskuriousApp", EntryActivity.BRIDGE)
        val bridge = EntryActivity.Bridge::class.java
        for ((name, params) in listOf("done" to arrayOf<Class<*>>(), "offlineSubmit" to arrayOf<Class<*>>(String::class.java))) {
            val method = bridge.getDeclaredMethod(name, *params)
            // without the annotation the WebView does not offer the function to the page
            assertNotNull(name, method.getAnnotation(JavascriptInterface::class.java))
        }
    }
}
