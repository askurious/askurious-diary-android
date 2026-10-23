package de.askurious.app.ui

import kotlinx.serialization.Serializable

/**
 * The screens of MainActivity as type-safe routes of Navigation Compose (UX audit 2026-10-16, structure 1, B22): the
 * list of studies, a study's page, joining, the step for the permissions, about the app and the licence texts. The
 * back stack and each screen's state are saved with the activity, so turning the phone or a change of language or
 * dark mode keeps them.
 */
@Serializable
sealed interface Route {
    @Serializable
    data object Home : Route

    /** The page of the study [code]. */
    @Serializable
    data class Study(val code: String) : Route

    /**
     * Joining; [link] is a join link (QR code, App Link) or empty for a typed code. A join link that opens the app comes
     * as a deep link of this route (JOIN_LINKS); its whole address then stands in the deep-link intent of the entry.
     */
    @Serializable
    data class Join(val link: String = "") : Route

    @Serializable
    data object Ready : Route

    @Serializable
    data object About : Route

    @Serializable
    data object Licence : Route
}

/**
 * The hosts whose join links (https://<host>/j/<code>) are App Links of the app, as in the manifest: the servers of
 * Askurious and its brands (KNOWN_SERVERS in app/build.gradle.kts). The server writes the brand's main address into the
 * QR code (askurious.de, polltergeist.de); the participants' addresses serve /j/ as well.
 */
val APP_LINK_HOSTS = listOf("askurious.de", "ask.askurious.de", "polltergeist.de", "ask.polltergeist.de")

/** The join links the app opens, as in the manifest: the App Links of [APP_LINK_HOSTS] and askurious://join. */
val JOIN_LINKS = APP_LINK_HOSTS.map { "https://$it/j/{code}" } + "askurious://join/{code}"
