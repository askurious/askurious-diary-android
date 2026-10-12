package de.askurious.app.data

import org.json.JSONObject

/**
 * The look of a study as its participant pages show it (server: `diary.look_json`), for the app's own screens around
 * them: the study card, the bar above an entry, the join screen. [colors] always; [dark] only for a look that follows
 * the device (light or dark automatically), taken while the phone is dark. Colours are 0xRRGGBB under the keys of
 * the server (background, surface, surface_2, text, muted, accent, on_accent, accent_soft, border, error); a key the
 * server did not send (or sent in a form this app does not read) is missing, and the screen keeps its own colour.
 */
data class Look(val colors: Map<String, Int>, val dark: Map<String, Int>?, val radius: Int) {
    /** The colours to show: the dark palette on a dark phone if there is one, else the only one. */
    fun palette(phoneDark: Boolean): Map<String, Int> = if (phoneDark && dark != null) dark else colors

    fun toJson(): JSONObject = JSONObject()
        .put("colors", paletteJson(colors))
        .put("colors_dark", dark?.let(::paletteJson) ?: JSONObject.NULL)
        .put("radius", radius)

    companion object {
        private val HEX = Regex("^#[0-9a-fA-F]{6}$")

        /** Null without a look or with one that names no colour (an older server: the app's own look). */
        fun fromJson(o: JSONObject?): Look? {
            if (o == null) return null
            val colors = palette(o.optJSONObject("colors")) ?: return null
            return Look(colors, palette(o.optJSONObject("colors_dark")), o.optInt("radius", 10).coerceIn(0, 32))
        }

        private fun palette(o: JSONObject?): Map<String, Int>? {
            if (o == null) return null
            val out = o.keys().asSequence().mapNotNull { key ->
                o.optString(key).takeIf { HEX.matches(it) }?.let { key to it.substring(1).toInt(16) }
            }.toMap()
            return out.ifEmpty { null }
        }

        private fun paletteJson(p: Map<String, Int>): JSONObject =
            JSONObject().apply { for ((key, rgb) in p) put(key, "#%06x".format(rgb)) }

        /** Relative lightness 0..1 of 0xRRGGBB (WCAG luminance): below 0.18 counts as a dark background. */
        fun luminance(rgb: Int): Double {
            fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * lin(rgb shr 16 and 0xff) + 0.7152 * lin(rgb shr 8 and 0xff) + 0.0722 * lin(rgb and 0xff)
        }
    }
}
