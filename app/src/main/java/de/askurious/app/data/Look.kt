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

    /**
     * The look for a card in the list of studies. A study with one fixed, light palette would show a bright white card
     * on a dark phone; there the card wears the app's own dark palette instead (keeping the study's corner radius). A
     * look that has a dark partner, or is dark itself, stays as it is. The study's own screens keep its look.
     */
    fun forList(phoneDark: Boolean): Look =
        if (phoneDark && dark == null && colors["background"]?.let { luminance(it) >= 0.18 } == true) {
            Look(DEFAULT.dark ?: colors, null, radius)
        } else this

    fun toJson(): JSONObject = JSONObject()
        .put("colors", paletteJson(colors))
        .put("colors_dark", dark?.let(::paletteJson) ?: JSONObject.NULL)
        .put("radius", radius)

    companion object {
        private val HEX = Regex("^#[0-9a-fA-F]{6}$")

        /**
         * The app's own look: the standard look of the participant pages of Askurious ("askurious", light) with its
         * dark partner ("askurious-dark"), the values the server's look_json gives for them (tokens of
         * app/static/participant.css and app/theme.py, 2026-10-12). One source for the start page, the notices and every
         * study without a look of its own (audit A19, SA5).
         */
        val DEFAULT = Look(
            colors = mapOf(
                "background" to 0xffffff, "surface" to 0xffffff, "surface_2" to 0xf7f7f4, "text" to 0x1d1d1b,
                "muted" to 0x66665f, "accent" to 0x2a5db0, "on_accent" to 0xffffff, "accent_soft" to 0xe9f0fb,
                "border" to 0xd6d4cc, "error" to 0xb3261e,
            ),
            dark = mapOf(
                "background" to 0x141517, "surface" to 0x1c1e21, "surface_2" to 0x212224, "text" to 0xe7e5e0,
                "muted" to 0xa4a29c, "accent" to 0x7ea6e6, "on_accent" to 0x0e1a2e, "accent_soft" to 0x272f3c,
                "border" to 0x555555, "error" to 0xf08d86,
            ),
            radius = 10,
        )

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
