package de.askurious.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The studies of this device, kept in the app's private storage (not in backups nor moved to a new phone:
 * android:allowBackup="false" and res/xml/data_extraction_rules.xml).
 * Each study holds its own enrolment secret, so two studies on one phone share nothing the server could link.
 * The secrets are stored sealed with a key of the Android Keystore ([KeystoreSealer], "secret_sealed"); a study stored
 * before that, with its secret in plain text, is sealed the first time it is read.
 */
class StudyStore(context: Context, private val sealer: Sealer = KeystoreSealer) {
    private val prefs = context.applicationContext.getSharedPreferences("studies", Context.MODE_PRIVATE)

    /**
     * The stored studies. A damaged entry is skipped, not thrown: one unreadable study must not crash the app at every
     * start (DiaryApp.onCreate reads them) and so cost the participant the secrets of all the others. While the stored
     * text cannot be read at all, nothing is written over it ([save] refuses), so a later fix can still read it.
     */
    @Synchronized
    fun all(): List<Study> {
        val stored = read() ?: return emptyList()
        if (stored.plain) save(stored.studies)  // seal the secrets stored before they were sealed
        return stored.studies
    }

    private class Stored(val studies: List<Study>, val kept: List<JSONObject>, val plain: Boolean)

    /** The stored studies, null when the stored text is there but no JSON list. */
    private fun read(): Stored? {
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return Stored(emptyList(), emptyList(), false)
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        val studies = mutableListOf<Study>()
        val kept = mutableListOf<JSONObject>()
        var plain = false
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            when (val opened = opened(o, sealer)) {
                null -> kept += o
                else -> runCatching { Study.fromJson(opened) }.getOrNull()?.let {
                    studies += it
                    if (!o.has(SEALED)) plain = true
                }
            }
        }
        return Stored(studies, kept, plain)
    }

    fun get(code: String): Study? = all().firstOrNull { it.code == code }

    /** Adds the study or replaces the one with the same code. */
    @Synchronized
    fun put(study: Study) {
        val rest = all().filterNot { it.code == study.code }
        save(rest + study)
    }

    /**
     * Replaces the stored study with the same code, but only if it is still there: a sync or a leave that ends
     * after the participant deleted the study must not bring it back.
     */
    @Synchronized
    fun update(study: Study) {
        val all = all()
        if (all.any { it.code == study.code }) save(all.map { if (it.code == study.code) study else it })
    }

    @Synchronized
    fun remove(code: String) = save(all().filterNot { it.code == code })

    private fun save(studies: List<Study>) {
        val stored = read()
        // a stored text that is no JSON list at all stays as it is, unless a new study is joined (put) on purpose
        if (stored == null && studies.isEmpty()) return
        // an entry whose secret cannot be opened (the Keystore lost its key) stays as it was, for a later fix
        val kept = stored?.kept.orEmpty()
        prefs.edit { putString(KEY, JSONArray(studies.map { sealed(it.toJson(), sealer) } + kept).toString()) }
    }

    companion object {
        private const val KEY = "studies"
        private const val SEALED = "secret_sealed"

        /** A study's JSON for storage: its secret sealed, never in plain text. */
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
