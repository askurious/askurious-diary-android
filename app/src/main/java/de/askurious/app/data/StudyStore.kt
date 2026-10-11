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

    @Synchronized
    fun all(): List<Study> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Study.fromJson(arr.getJSONObject(it)) }
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
        prefs.edit { putString(KEY, JSONArray(studies.map { it.toJson() }).toString()) }
    }

    private companion object {
        const val KEY = "studies"
    }
}
