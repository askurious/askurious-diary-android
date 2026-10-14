package de.askurious.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * What happened to the prompts of the last days, kept only on the phone: when each was notified and whether the
 * participant opened it. The reminder (Alarms.notify) rings only for a prompt not opened yet, and it sends the
 * time of the first notification, not its own, so the server's latency stays that of the prompt.
 * Entries written more than [KEEP_MS] ago (real time: the due time of a fast-forward person is virtual and runs
 * ahead) are pruned at every write: no prompt is open that long, and a log of the days is nothing the phone needs
 * to keep. Apart from that it keeps, per study, whether the exit questionnaire was notified or opened: one
 * notification only, kept until the study goes from the phone. The log is stored sealed under the Keystore key and
 * kept open in memory ([SealedText], audit 2026-10-13, AN-3, AN-8): the times of the prompts tell the course of a day.
 */
class PromptLog internal constructor(private val prefs: SharedPreferences, sealer: Sealer) {
    constructor(context: Context, sealer: Sealer = KeystoreSealer) :
        this(context.applicationContext.getSharedPreferences("prompts", Context.MODE_PRIVATE), sealer)

    private val text = SealedText(prefs, KEY, sealer, AAD)

    fun notified(study: String, module: String, due: Long, at: Long): Unit =
        synchronized(SealedText.LOCK) { change { it.entry(key(study, module, due), due).put("notified", at) } }

    fun opened(study: String, module: String, due: Long): Unit =
        synchronized(SealedText.LOCK) { change { it.entry(key(study, module, due), due).put("opened", true) } }

    fun notifiedAt(study: String, module: String, due: Long): Long? =
        load().optJSONObject(key(study, module, due))?.let { if (it.has("notified")) it.optLong("notified") else null }

    fun isOpened(study: String, module: String, due: Long): Boolean =
        load().optJSONObject(key(study, module, due))?.optBoolean("opened", false) == true

    fun completed(study: String, module: String, due: Long): Unit =
        synchronized(SealedText.LOCK) { change { it.entry(key(study, module, due), due).put("completed", true) } }

    fun isCompleted(study: String, module: String, due: Long): Boolean =
        load().optJSONObject(key(study, module, due))?.optBoolean("completed", false) == true

    /** The exit questionnaire of [study] was notified or opened: no (further) notification for it. */
    fun exitNotified(study: String): Unit = synchronized(SealedText.LOCK) { prefs.edit { putStringSet(EXITS, exits() + study) } }

    /** The studies whose exit questionnaire was notified or opened. */
    fun exits(): Set<String> = runCatching { prefs.getStringSet(EXITS, null)?.toSet() }.getOrNull() ?: emptySet()

    /** Forgets everything of [study] (deleted or removed from the list). */
    fun removeStudy(study: String): Unit = synchronized(SealedText.LOCK) {
        change { all ->
            all.keys().asSequence().toList().filter { it.startsWith("$study|") }.forEach { all.remove(it) }
        }
        prefs.edit { putStringSet(EXITS, exits() - study) }
    }

    /** The entry of a prompt; "kept": when it was written (real time), for pruning. */
    private fun JSONObject.entry(key: String, due: Long): JSONObject =
        optJSONObject(key) ?: JSONObject().put("due", due).put("kept", System.currentTimeMillis()).also { put(key, it) }

    private fun change(block: (JSONObject) -> Unit) {
        val all = load()
        block(all)
        val oldest = System.currentTimeMillis() - KEEP_MS
        // entries written before "kept" existed are pruned by their due time
        all.keys().asSequence().toList()
            .filter { all.optJSONObject(it)?.let { e -> e.optLong("kept", e.optLong("due", 0L)) < oldest } != false }
            .forEach { all.remove(it) }
        text.write(all.toString())
    }

    /** A copy of the log (JSONObject is not thread-safe, the text in memory is). */
    private fun load(): JSONObject = text.read().text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private companion object {
        const val KEY = "log"
        const val AAD = "prompts"
        const val EXITS = "exits"
        const val KEEP_MS = 3L * 24 * 3600 * 1000

        fun key(study: String, module: String, due: Long) = "$study|$module|$due"
    }
}
