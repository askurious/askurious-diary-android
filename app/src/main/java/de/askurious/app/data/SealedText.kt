package de.askurious.app.data

import android.content.SharedPreferences
import java.util.WeakHashMap
import java.io.IOException

/**
 * One text in the app's SharedPreferences, stored sealed ([Sealer], AES-GCM under the Keystore key, with [aad] naming
 * what it is): the studies, the answers waiting to be sent and the log of the prompts (audit 2026-10-13, AN-3). A copy
 * of the app's files alone shows neither answers nor titles nor the times of the prompts.
 *
 * The opened text is kept in memory for the life of the process (AN-8): the Keystore is asked once per text, not at
 * every read. A text stored in plain text before (an earlier version of the app; it starts with "[" or "{", which no
 * sealed text does) is read as it is and sealed at once.
 *
 * Plain text counts only until the text was first written, or read sealed, by this version ("<key>.migrated", security audit
 * 2026-11-26, SEC-08): afterwards a plain text under the key is not the app's own (somebody put it in place of the
 * sealed one) and reads as unreadable instead of being taken over and sealed with the app's key.
 */
class SealedText(
    private val prefs: SharedPreferences, private val key: String, private val sealer: Sealer, private val aad: String,
    /** A text of an earlier version that is only moved elsewhere: read as it is, and no marker is kept for it. */
    private val legacy: Boolean = false,
) {
    /**
     * The stored text: [text] null when there is none; [unreadable] when it is there but cannot be opened. [damaged]:
     * it cannot be opened although the Keystore works (it seals and opens a probe), so waiting will not bring it back;
     * false while the Keystore itself fails (it may only be busy).
     */
    class Opened(val text: String?, val unreadable: Boolean = false, val damaged: Boolean = false)

    fun read(): Opened = synchronized(LOCK) {
        val cache = cache()
        if (cache.containsKey(key)) return Opened(cache[key])
        val raw = try { prefs.getString(key, null) } catch (e: Exception) {
            return Opened(null, unreadable = true)
        }
        when {
            raw == null -> {
                cache[key] = null
                Opened(null)
            }
            plain(raw) -> {
                // after the first read or write of this version no plain text is the app's own any more (SEC-08)
                if (isMigrated()) return Opened(null, unreadable = true, damaged = true)
                // stored before the texts were sealed: sealed now, once (if the Keystore fails, at the next write)
                runCatching { write(raw) }
                cache[key] = raw
                Opened(raw)
            }
            else -> {
                // not kept in memory: the Keystore may only be busy, the next read tries again
                val opened = sealer.open(raw, aad) ?: return Opened(null, unreadable = true, damaged = sealerWorks())
                cache[key] = opened
                migrated()
                Opened(opened)
            }
        }
    }

    fun write(plain: String, durable: Boolean = false): Unit = synchronized(LOCK) {
        val sealed = try { sealer.seal(plain, aad) } catch (e: Exception) {
            if (durable) throw IOException("Could not encrypt sealed text", e) else throw e
        }
        val editor = prefs.edit().putString(key, sealed)
        // the marker goes with the text, in the same write
        if (!legacy && !isMigrated()) editor.putBoolean(key + MIGRATED, true)
        if (durable) {
            if (!editor.commit()) throw IOException("Could not persist sealed text")
        } else editor.apply()
        cache()[key] = plain
    }

    /** Notes that the text is the sealed one from now on; written along with the next change of the file. */
    private fun migrated() {
        if (!legacy && !isMigrated()) runCatching { prefs.edit().putBoolean(key + MIGRATED, true).apply() }
    }

    private fun isMigrated(): Boolean = !legacy && runCatching { prefs.getBoolean(key + MIGRATED, false) }.getOrDefault(false)

    /** Whether the Keystore seals and opens a probe now: then a text it does not open is damaged for good. */
    private fun sealerWorks(): Boolean = runCatching { sealer.open(sealer.seal(PROBE, aad), aad) == PROBE }.getOrDefault(false)

    fun remove(durable: Boolean = false): Unit = synchronized(LOCK) {
        val editor = prefs.edit().remove(key)
        if (durable) {
            if (!editor.commit()) throw IOException("Could not remove sealed text")
        } else editor.apply()
        cache()[key] = null
    }

    /** The text was taken out of the preferences by its owner ([SealedSet]): nothing of it stays in memory. */
    internal fun forget(): Unit = synchronized(LOCK) { cache().remove(key) }

    private fun cache(): MutableMap<String, String?> = CACHE.getOrPut(prefs) { HashMap() }

    companion object {
        /**
         * One lock for all stored texts of the process: the stores are made anew for every call (StudyStore(context)),
         * so a lock of the instance would not keep two of them from writing over each other.
         */
        val LOCK = Any()

        /** The opened texts per SharedPreferences (the system keeps one instance per file and process). */
        private val CACHE = WeakHashMap<SharedPreferences, MutableMap<String, String?>>()

        /** The ending of the marker of a text read or written sealed once ([isMigrated]); never a text itself. */
        const val MIGRATED = ".migrated"
        private const val PROBE = "probe"

        /** Whether [stored] is a JSON text stored before the texts were sealed (a sealed one starts with "k1:"). */
        fun plain(stored: String): Boolean = stored.trimStart().let { it.startsWith("[") || it.startsWith("{") }
    }
}
