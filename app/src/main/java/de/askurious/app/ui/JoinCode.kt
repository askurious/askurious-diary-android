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

/**
 * The server a join link names: "https://ask.askurious.de" for https://ask.askurious.de/j/<code> (scheme, host and a
 * port other than the default, no path). Null for a typed code, an askurious:// link, or a link without /j/<code>:
 * then the app's own server ([de.askurious.app.data.Api.defaultServer]) is meant. Only https, unless [allowHttp] (a
 * debug build, for a server on the computer).
 */
fun joinServer(text: String, allowHttp: Boolean): String? {
    val uri = try {
        URI(text.trim())
    } catch (e: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "https" && !(allowHttp && scheme == "http")) return null
    val host = uri.host?.lowercase()?.ifBlank { null } ?: return null
    val s = segments(uri)
    val at = s.indexOf("j")
    if (at < 0 || s.getOrNull(at + 1).isNullOrBlank()) return null
    val port = uri.port.takeIf { it != -1 && it != (if (scheme == "https") 443 else 80) }
    return "$scheme://$host" + (port?.let { ":$it" } ?: "")
}

/**
 * The join link the join screen starts with: the address of the deep link that opened it ([deepLink], the data of the
 * entry's deep-link intent) only when that is a join link itself (askurious://join/<code>, or http(s) with /j/<code>),
 * else the route's own link ([routeLink], empty for "Join study"). Navigation puts a deep-link intent with the
 * route's internal address (android-app://androidx.navigation/...) into every entry, also one opened from the menu:
 * taken as a code, it filled the field and was looked up at once with "(404)" (audit 2026-10-18, AND-03).
 */
fun joinLinkOf(deepLink: String?, routeLink: String): String {
    val link = deepLink?.trim().orEmpty()
    val scheme = runCatching { URI(link).scheme?.lowercase() }.getOrNull()
    val isJoinLink = when (scheme) {
        "askurious" -> joinCode(link) != null
        "http", "https" -> joinServer(link, allowHttp = true) != null
        else -> false
    }
    return if (isJoinLink) link else routeLink
}
