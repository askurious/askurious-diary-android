package de.askurious.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * The studies of this device, kept in the app's private storage (not in backups nor moved to a new phone:
 * android:allowBackup="false" and res/xml/data_extraction_rules.xml).
 * Each study holds its own enrolment secret, so two studies on one phone share nothing the server could link.
 */
class StudyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("studies", Context.MODE_PRIVATE)

    /**
     * The stored studies. A damaged entry is skipped, not thrown: one unreadable study must not crash the app at every
     * start (DiaryApp.onCreate reads them) and so cost the participant the secrets of all the others. While the stored
     * text cannot be read at all, nothing is written over it ([save] refuses), so a later fix can still read it.
     */
    @Synchronized
    fun all(): List<Study> = read() ?: emptyList()

    /** The stored studies, null when the stored text is there but no JSON list. */
    private fun read(): List<Study>? {
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        return (0 until arr.length()).mapNotNull { k -> runCatching { Study.fromJson(arr.getJSONObject(k)) }.getOrNull() }
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
        // a stored text that is no JSON list at all stays as it is, unless a new study is joined (put) on purpose
        if (read() == null && studies.isEmpty()) return
        prefs.edit { putString(KEY, JSONArray(studies.map { it.toJson() }).toString()) }
    }

    private companion object {
        const val KEY = "studies"
    }
}
