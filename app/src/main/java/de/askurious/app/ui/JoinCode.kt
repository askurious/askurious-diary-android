package de.askurious.app.ui

import de.askurious.app.data.Api
import java.net.URI
import java.net.URISyntaxException

/**
 * The join code in a join link (https://ask.askurious.de/j/<code>) or as typed.
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
    fun clean(code: String?) = code?.filterNot { it.isWhitespace() }?.lowercase()?.ifBlank { null }
    return when {
        uri == null || scheme == null -> clean(t)
        // the code of a link must be one (letters, digits, one hyphen): "https://askurious.de/j/.." named another
        // address of the server, and any text of any length was looked up at once (security audit 2026-11-26, SEC-09)
        scheme == "http" || scheme == "https" ->
            clean(segments(uri).let { s -> s.getOrNull(s.indexOf("j") + 1) })?.takeIf { Api.validCode(it) }
        // any other address (the app's former askurious://join/<code> too) holds no join code
        else -> null
    }
}

private fun segments(uri: URI): List<String> = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }

/**
 * The server a join link names: "https://ask.askurious.de" for https://ask.askurious.de/j/<code> (scheme, host and a
 * port other than the default, no path). Null for a typed code or a link without /j/<code> (a code as the server makes them):
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
    if (at < 0 || s.getOrNull(at + 1)?.lowercase()?.let { Api.validCode(it) } != true) return null
    val port = uri.port.takeIf { it != -1 && it != (if (scheme == "https") 443 else 80) }
    return "$scheme://$host" + (port?.let { ":$it" } ?: "")
}

/**
 * The join link the join screen starts with: the address of the deep link that opened it ([deepLink], the data of the
 * entry's deep-link intent) only when that is a join link itself (http(s) with /j/<code>),
 * else the route's own link ([routeLink], empty for "Join study"). Navigation puts a deep-link intent with the
 * route's internal address (android-app://androidx.navigation/...) into every entry, also one opened from the menu:
 * taken as a code, it filled the field and was looked up at once with "(404)" (audit 2026-10-18, AND-03).
 */
fun joinLinkOf(deepLink: String?, routeLink: String): String {
    val link = deepLink?.trim().orEmpty()
    return if (isJoinLink(link)) link else routeLink
}

/**
 * Whether [link] is a join link (http(s) with /j/<code>). The activity takes the data of an intent only then: any
 * other app could send it any text, which was looked up at once as a code (audit 2026-10-22, F-02).
 *
 * The app's own scheme askurious://join/<code> is gone (security audit 2026-11-26, SEC-09): unlike the App Links it is
 * not verified, so any other app could register it and take the personal invitation codes sent with it. The server
 * never wrote such links; its QR codes and links are https://<host>/j/<code>.
 */
fun isJoinLink(link: String): Boolean = when (runCatching { URI(link.trim()).scheme?.lowercase() }.getOrNull()) {
    "http", "https" -> joinServer(link, allowHttp = true) != null
    else -> false
}

/**
 * The join code of a scanned QR code, or null when it holds none: a join link ([isJoinLink]) or a code alone (no
 * address). Any other link or text (a web page, a code of another kind) is no join code; the join screen says so
 * instead of looking up a part of it or doing nothing (audit 2026-10-24, AND-06).
 */
fun scannedJoinCode(text: String): String? {
    val t = text.trim()
    val hasScheme = runCatching { URI(t).scheme != null }.getOrDefault(false)
    if (hasScheme) return if (isJoinLink(t)) joinCode(t) else null
    return joinCode(t)?.takeIf { Api.validCode(it) }
}
