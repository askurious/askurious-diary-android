package de.askurious.app.data

/** A server-side leave closes uploads. A failed upload must keep the leave pending. */
internal suspend fun transmitLeave(upload: suspend () -> Boolean, leave: suspend () -> Boolean): Boolean =
    if (upload()) leave() else false
