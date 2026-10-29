package de.askurious.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The studies of this device, kept in the app's private storage (not in backups nor moved to a new phone:
 * android:allowBackup="false" and res/xml/data_extraction_rules.xml).
 * Each study holds its own enrolment secret, so two studies on one phone share nothing the server could link.
 *
 * The whole list (titles, servers, schedules, seeds and secrets) is stored as one text sealed with a key of the
 * Android Keystore ([SealedText] under "studies_sealed", audit 2026-10-13, AN-3) and kept open in memory, so the
 * Keystore is asked once per process, not for every study at every read (AN-8). The lists of earlier versions under
 * "studies" (each secret sealed on its own, "secret_sealed", or in plain text before that) are moved over the first
 * time they are read; an entry whose secret cannot be opened (the Keystore lost its key) stays there as it is, not
 * overwritten, for a later fix.
 */
class StudyStore internal constructor(private val prefs: SharedPreferences, private val sealer: Sealer) {
    constructor(context: Context, sealer: Sealer = KeystoreSealer) :
        this(context.applicationContext.getSharedPreferences("studies", Context.MODE_PRIVATE), sealer)

    private val text = SealedText(prefs, KEY_SEALED, sealer, AAD)

    /**
     * The studies joined while [text] could not be read (audit 2026-10-18, AND-05): kept apart under their own key, so
     * that the stored list is never written over, and moved into it at the first read that succeeds.
     */
    private val pending = SealedText(prefs, KEY_PENDING, sealer, AAD_PENDING)

    /**
     * The stored studies. A damaged entry is skipped, not thrown: one unreadable study must not crash the app at every
     * start (DiaryApp.onCreate reads them) and so cost the participant the secrets of all the others. While the stored
     * text cannot be read at all, nothing is written over it: the studies joined meanwhile are kept apart ([pending])
     * and are all this gives until the stored text opens again ([unreadable] tells the start page so).
     */
    fun all(): List<Study> = synchronized(SealedText.LOCK) {
        val stored = read() ?: return readPending()
        val waiting = readPending()
        if (waiting.isNotEmpty()) {
            // the stored text opens again: the studies joined meanwhile join it (the newer copy of a code wins)
            val merged = stored.studies.filterNot { s -> waiting.any { it.code == s.code } } + waiting
            save(merged)
            pending.remove()
            return merged
        }
        if (stored.moved) save(stored.studies)  // the list of an earlier version: sealed as a whole now
        return stored.studies
    }

    /**
     * Whether the stored list is there but cannot be opened now (the Keystore failed): the start page says so and
     * offers "Try again" instead of "not taking part in a study yet" (AND-05).
     */
    fun unreadable(): Boolean = synchronized(SealedText.LOCK) { read() == null }

    /** The studies joined while the stored list could not be read; empty when there are none (or they cannot be read). */
    private fun readPending(): List<Study> {
        val t = pending.read().text ?: return emptyList()
        val arr = runCatching { JSONArray(t) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { k -> arr.optJSONObject(k)?.let { o -> runCatching { Study.fromJson(o) }.getOrNull() } }
    }

    /**
     * [kept]: entries of the earlier list whose secret cannot be opened, null when that list is not there or no JSON
     * list (then it is left alone). [moved]: studies of the earlier list were read and are to be stored sealed.
     */
    private class Stored(val studies: List<Study>, val kept: List<JSONObject>?, val moved: Boolean)

    /** The stored studies, null when the sealed text is there but cannot be opened or is no JSON list. */
    private fun read(): Stored? {
        val opened = text.read()
        if (opened.unreadable) return null
        val studies = mutableListOf<Study>()
        opened.text?.let { t ->
            val arr = runCatching { JSONArray(t) }.getOrNull() ?: return null
            for (k in 0 until arr.length()) {
                arr.optJSONObject(k)?.let { o -> runCatching { Study.fromJson(o) }.getOrNull()?.let { studies += it } }
            }
        }
        val legacy = runCatching { prefs.getString(KEY, null) }.getOrNull()?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return Stored(studies, null, false)
        val kept = mutableListOf<JSONObject>()
        var moved = false
        for (k in 0 until legacy.length()) {
            val o = legacy.optJSONObject(k) ?: continue
            when (val plain = opened(o, sealer)) {
                null -> kept += o
                else -> runCatching { Study.fromJson(plain) }.getOrNull()?.let { s ->
                    moved = true
                    // the sealed list is the newer one
                    if (studies.none { it.code == s.code }) studies += s
                }
            }
        }
        return Stored(studies, kept, moved)
    }

    fun get(code: String): Study? = all().firstOrNull { it.code == code }

    /** Adds the study or replaces the one with the same code. */
    fun put(study: Study): Unit = synchronized(SealedText.LOCK) {
        val rest = all().filterNot { it.code == study.code }
        save(rest + study)
    }

    /**
     * Replaces the stored study with the same code, but only if it is still there: a sync or a leave that ends
     * after the participant deleted the study must not bring it back.
     */
    fun update(study: Study): Unit = synchronized(SealedText.LOCK) {
        val all = all()
        if (all.any { it.code == study.code }) save(all.map { if (it.code == study.code) study else it })
    }

    /** Merge while holding the same lock as local changes. */
    fun synced(fetched: Study, sentQuiet: QuietFrom?): Study? = synchronized(SealedText.LOCK) {
        val fresh = Sync.merged(fetched, get(fetched.code), sentQuiet) ?: return@synchronized null
        update(fresh)
        fresh
    }

    fun remove(code: String): Unit = synchronized(SealedText.LOCK) { save(all().filterNot { it.code == code }) }

    private fun save(studies: List<Study>) {
        val stored = read()
        if (stored == null) {
            // a stored text that cannot be read stays as it is (the secrets of the other studies are in it): what changes
            // meanwhile (a study joined, synced, removed) is kept apart until it opens again (AND-05)
            // the studies kept apart cannot be opened either: nothing is written over them (a join fails instead)
            if (pending.read().unreadable) {
                if (studies.isEmpty()) return
                throw java.io.IOException("The stored studies cannot be read")
            }
            if (studies.isEmpty()) pending.remove() else pending.write(JSONArray(studies.map { it.toJson() }).toString())
            return
        }
        text.write(JSONArray(studies.map { it.toJson() }).toString())
        // what is left of the earlier list: only the entries whose secret cannot be opened
        val kept = stored?.kept ?: return
        prefs.edit { if (kept.isEmpty()) remove(KEY) else putString(KEY, JSONArray(kept).toString()) }
    }

    companion object {
        /** Whether two server addresses name the same server (scheme, host and port, as [Api.origin] writes them). */
        fun sameServer(a: String, b: String): Boolean = Api.origin(a)?.let { it == Api.origin(b) } == true

        /** The list of earlier versions (secrets sealed one by one); only read, and emptied as it is moved. */
        private const val KEY = "studies"
        private const val KEY_SEALED = "studies_sealed"
        private const val AAD = "studies"
        private const val KEY_PENDING = "studies_sealed_pending"
        private const val AAD_PENDING = "studies_pending"
        private const val SEALED = "secret_sealed"

        /** A study's JSON as earlier versions stored it: its secret sealed on its own (still read, see [opened]). */
        fun sealed(o: JSONObject, sealer: Sealer): JSONObject {
            val secret = o.optString("secret")
            o.remove("secret")
            return o.put(SEALED, sealer.seal(secret))
        }

        /**
         * A stored study's JSON with its secret in plain text again, for Study.fromJson; an entry stored before the
         * secrets were sealed has it already. Null when the sealed secret cannot be opened.
         */
        fun opened(o: JSONObject, sealer: Sealer): JSONObject? {
            if (!o.has(SEALED)) return o
            val secret = sealer.open(o.optString(SEALED)) ?: return null
            return JSONObject(o.toString()).apply { remove(SEALED) }.put("secret", secret)
        }
    }
}
