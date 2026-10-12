package de.askurious.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The look of a study (server: diary.look_json): read, kept on the phone, and the palette for a light or dark phone. */
class LookTest {
    private val sent = JSONObject(
        """
        {"code": "abc", "title": "T", "status": "active",
         "look": {"colors": {"background": "#FBF8F1", "accent": "#0a7d5a", "text": "red", "glow": "#123456"},
                  "colors_dark": {"background": "#1a1d22", "accent": "#7fd1b0"}, "radius": 2}}
        """.trimIndent()
    )

    @Test
    fun readsTheColoursAndLeavesOutWhatIsNoColour() {
        val look = Study.fromJson(sent, "secret").look!!
        assertEquals(0xfbf8f1, look.colors["background"])
        assertEquals(0x0a7d5a, look.colors["accent"])
        assertNull(look.colors["text"])
        assertEquals(2, look.radius)
        assertEquals(0x0a7d5a, look.palette(phoneDark = false)["accent"])
        assertEquals(0x7fd1b0, look.palette(phoneDark = true)["accent"])
    }

    @Test
    fun aFixedLookIsTheSameOnADarkPhone() {
        val look = Look.fromJson(JSONObject("""{"colors": {"background": "#101a33"}, "colors_dark": null, "radius": 10}"""))!!
        assertEquals(look.colors, look.palette(phoneDark = true))
        assertTrue(Look.luminance(0x101a33) < 0.18 && Look.luminance(0xffffff) > 0.9)
    }

    @Test
    fun isKeptOnThePhoneAndAnOlderServerHasNone() {
        val study = Study.fromJson(sent, "secret")
        assertEquals(study.look, Study.fromJson(study.toJson()).look)
        assertNull(Study.fromJson(JSONObject("""{"code": "abc"}"""), "secret").look)
        assertNull(Look.fromJson(JSONObject("""{"colors": {"background": "white"}}""")))
    }
}
