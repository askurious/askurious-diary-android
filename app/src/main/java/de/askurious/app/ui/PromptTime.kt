package de.askurious.app.ui

import de.askurious.app.R

/**
 * The words of the card for the next question: its time, or "about" its time while the app may not set exact alarms
 * ("Alarms & reminders" off): Android then rings a few minutes late, for a fast-forward person hours of study time
 * (audit A11). Plain Kotlin, so the unit tests can run it.
 */
fun nextPromptText(exact: Boolean): Int = if (exact) R.string.next_prompt else R.string.next_prompt_about
