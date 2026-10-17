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
 * The join links the app opens, as in the manifest: the App Link of the participants' address and askurious://join.
 * Only the participants' address serves /j/, so askurious.de is not listed (see AndroidManifest.xml).
 */
val JOIN_LINKS = listOf("https://ask.askurious.de/j/{code}", "askurious://join/{code}")
