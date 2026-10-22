package de.askurious.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import java.io.IOException

/**
 * A small set of study codes in the app's SharedPreferences, stored sealed like [SealedText] under "<legacy>.sealed"
 * (audit 2026-10-22, F-01): stored as a plain StringSet, the file showed which studies the person takes part in. A set
 * stored plain under [legacy] (an earlier version of the app) is read along and goes at the next write. Should the
 * Keystore fail to seal, the set is kept plain under [legacy] as before rather than lost: [AnswerQueue.forgetStudy]
 * needs it exactly when the queue's key cannot be used.
 */
class SealedSet(private val prefs: SharedPreferences, private val legacy: String, sealer: Sealer, aad: String) {
    private val text = SealedText(prefs, "$legacy.sealed", sealer, aad)

    fun get(): Set<String> = synchronized(SealedText.LOCK) {
        val sealed = text.read().text?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val plain = runCatching { prefs.getStringSet(legacy, null)?.toSet() }.getOrNull().orEmpty()
        sealed.toSet() + plain
    }

    operator fun contains(value: String): Boolean = value in get()

    /** Stores [values]; [durable]: written to disk before it returns (else an IOException). */
    fun set(values: Set<String>, durable: Boolean = false): Unit = synchronized(SealedText.LOCK) {
        try {
            if (values.isEmpty()) text.remove(durable) else text.write(JSONArray(values.sorted()).toString(), durable)
            val editor = prefs.edit().remove(legacy)
            if (durable) {
                if (!editor.commit()) throw IOException("Could not persist set")
            } else editor.apply()
        } catch (e: Exception) {
            val editor = prefs.edit().putStringSet(legacy, values)
            if (durable) {
                if (!editor.commit()) throw IOException("Could not persist set", e)
            } else editor.apply()
        }
    }

    fun add(value: String, durable: Boolean = false) = set(get() + value, durable)

    fun remove(value: String) = set(get() - value)
}
