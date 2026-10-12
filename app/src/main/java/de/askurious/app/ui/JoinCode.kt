package de.askurious.app.ui

import java.net.URI
import java.net.URISyntaxException

/**
 * The join code in a join link (https://ask.askurious.de/j/<code>, askurious://join/<code>) or as typed.
 * Codes are lowercase letters and digits; a test code has one hyphen (k7qm2x-t3f9a), which stays. Spaces go, as
 * people type codes in groups. Plain Kotlin (java.net.URI, not android.net.Uri), so the unit tests can run it.
 */
fun joinCode(text: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val uri = try {
        URI(t)
    } catch (e: URISyntaxException) {
        null // typed with spaces in between: not a link
    }
    val scheme = uri?.scheme?.lowercase()
    val code = when {
        uri == null || scheme == null -> t
        // askurious://join/<code>; askurious://join alone names no study (the host is no code)
        scheme == "askurious" -> segments(uri).lastOrNull()
        scheme.startsWith("http") -> segments(uri).let { s -> s.getOrNull(s.indexOf("j") + 1) }
        else -> t
    }
    return code?.filterNot { it.isWhitespace() }?.lowercase()?.ifBlank { null }
}

private fun segments(uri: URI): List<String> = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
