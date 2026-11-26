package de.askurious.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import java.io.IOException

/**
 * A small set of study codes in the app's SharedPreferences, stored sealed like [SealedText] under "<legacy>.sealed"
 * (audit 2026-10-22, F-01): stored as a plain StringSet, the file showed which studies the person takes part in. A set
 * stored plain under [legacy] (an earlier version of the app) is read along and goes at the next write.
 *
 * Should the Keystore fail to seal, the set is not written at all (security audit 2026-11-26, SEC-08: it went into the
 * file in plain text, codes and all): a write asked to be durable throws, any other is dropped. Only with
 * [fallbackPlain] is it kept plain under [legacy] rather than lost: [AnswerQueue.forgetStudy] needs its set exactly
 * when the queue's key cannot be used. The sealed set then goes if it could be read (its members are in the plain
 * one), so that a code taken out does not come back with it.
 */
class SealedSet(
    private val prefs: SharedPreferences, private val legacy: String, sealer: Sealer, aad: String,
    private val fallbackPlain: Boolean = false,
) {
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
            if (!fallbackPlain) {
                if (durable) throw (e as? IOException ?: IOException("Could not persist set", e))
                return@synchronized
            }
            // the sealed set was read into [values] (get): it goes, or a member taken out came back with it. One that
            // cannot be read stays: its members are not in [values]
            val readable = !text.read().unreadable
            val editor = prefs.edit().putStringSet(legacy, values)
            if (readable) editor.remove("$legacy.sealed")
            if (durable) {
                if (!editor.commit()) throw IOException("Could not persist set", e)
            } else editor.apply()
            if (readable) text.forget()
        }
    }

    fun add(value: String, durable: Boolean = false) = set(get() + value, durable)

    fun remove(value: String) = set(get() - value)
}
