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
 */
class SealedText(private val prefs: SharedPreferences, private val key: String, private val sealer: Sealer, private val aad: String) {
    /** The stored text: [text] null when there is none; [unreadable] when it is there but cannot be opened. */
    class Opened(val text: String?, val unreadable: Boolean = false)

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
                // stored before the texts were sealed: sealed now, once (if the Keystore fails, at the next write)
                runCatching { write(raw) }
                cache[key] = raw
                Opened(raw)
            }
            else -> {
                // not kept in memory: the Keystore may only be busy, the next read tries again
                val opened = sealer.open(raw, aad) ?: return Opened(null, unreadable = true)
                cache[key] = opened
                Opened(opened)
            }
        }
    }

    fun write(plain: String, durable: Boolean = false): Unit = synchronized(LOCK) {
        val sealed = try { sealer.seal(plain, aad) } catch (e: Exception) {
            if (durable) throw IOException("Could not encrypt sealed text", e) else throw e
        }
        val editor = prefs.edit().putString(key, sealed)
        if (durable) {
            if (!editor.commit()) throw IOException("Could not persist sealed text")
        } else editor.apply()
        cache()[key] = plain
    }

    fun remove(durable: Boolean = false): Unit = synchronized(LOCK) {
        val editor = prefs.edit().remove(key)
        if (durable) {
            if (!editor.commit()) throw IOException("Could not remove sealed text")
        } else editor.apply()
        cache()[key] = null
    }

    private fun cache(): MutableMap<String, String?> = CACHE.getOrPut(prefs) { HashMap() }

    companion object {
        /**
         * One lock for all stored texts of the process: the stores are made anew for every call (StudyStore(context)),
         * so a lock of the instance would not keep two of them from writing over each other.
         */
        val LOCK = Any()

        /** The opened texts per SharedPreferences (the system keeps one instance per file and process). */
        private val CACHE = WeakHashMap<SharedPreferences, MutableMap<String, String?>>()

        /** Whether [stored] is a JSON text stored before the texts were sealed (a sealed one starts with "k1:"). */
        fun plain(stored: String): Boolean = stored.trimStart().let { it.startsWith("[") || it.startsWith("{") }
    }
}
