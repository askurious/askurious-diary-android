package de.askurious.app.ui

import android.webkit.JavascriptInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

    /**
     * Audit 2026-10-13, AN-12: on the server's online pages the bridge is a message channel of the main frame; the
     * script the app puts at the start of each page offers participant.js the same names, and nothing to a frame.
     */
    @Test
    fun theBridgeScriptOffersTheSameNamesOnlyToTheMainFrame() {
        val script = EntryActivity.BRIDGE_SCRIPT
        assertTrue(script.contains("window.AskuriousApp = {"))
        assertTrue(script.contains("done: function ()"))
        assertTrue(script.contains("offlineSubmit: function (json)"))
        assertTrue(script.contains("window.top !== window"))
        assertTrue(script.contains("postMessage(\"done\")"))
        assertTrue(script.contains("postMessage(\"offline:\" + String(json))"))
        assertFalse(script.contains("$"))
    }

    /**
     * UI/UX audit 2026-10-17, A12: the back gesture presses the page's own "Back" of participant/_nav.html (a submit
     * button of the page's form, class p-back, name "nav", value "back") and learns whether there was one.
     */
    @Test
    fun theBackGesturePressesTheBackButtonOfTheParticipantNavigation() {
        val script = EntryActivity.PAGE_BACK
        assertTrue(script.contains("form button.p-back[name=\"nav\"][value=\"back\"]"))
        assertTrue(script.contains("b.click()"))
        assertTrue(script.contains("return false"))
        assertTrue(script.contains("return true"))
        assertFalse(script.contains("history"))
        assertFalse(script.contains("$"))
    }
}
