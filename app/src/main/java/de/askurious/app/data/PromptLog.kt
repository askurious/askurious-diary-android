package de.askurious.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * What happened to the prompts of the last days, kept only on the phone: when each was notified and whether the
 * participant opened it. The reminder (Alarms.notify) rings only for a prompt not opened yet, and it sends the
 * time of the first notification, not its own, so the server's latency stays that of the prompt.
 * Entries older than [KEEP_MS] are pruned at every write: no prompt is open that long, and a log of the days is
 * nothing the phone needs to keep.
 */
class PromptLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("prompts", Context.MODE_PRIVATE)

    @Synchronized
    fun notified(study: String, module: String, due: Long, at: Long) = change { it.entry(key(study, module, due), due).put("notified", at) }

    @Synchronized
    fun opened(study: String, module: String, due: Long) = change { it.entry(key(study, module, due), due).put("opened", true) }

    @Synchronized
    fun notifiedAt(study: String, module: String, due: Long): Long? =
        load().optJSONObject(key(study, module, due))?.let { if (it.has("notified")) it.optLong("notified") else null }

    @Synchronized
    fun isOpened(study: String, module: String, due: Long): Boolean =
        load().optJSONObject(key(study, module, due))?.optBoolean("opened", false) == true

    /** Forgets everything of [study] (deleted or removed from the list). */
    @Synchronized
    fun removeStudy(study: String) = change { all ->
        all.keys().asSequence().toList().filter { it.startsWith("$study|") }.forEach { all.remove(it) }
    }

    private fun JSONObject.entry(key: String, due: Long): JSONObject =
        optJSONObject(key) ?: JSONObject().put("due", due).also { put(key, it) }

    private fun change(block: (JSONObject) -> Unit) {
        val all = load()
        block(all)
        val oldest = System.currentTimeMillis() - KEEP_MS
        all.keys().asSequence().toList().filter { all.optJSONObject(it)?.optLong("due", 0L)?.let { d -> d < oldest } != false }
            .forEach { all.remove(it) }
        prefs.edit { putString(KEY, all.toString()) }
    }

    private fun load(): JSONObject = prefs.getString(KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    private companion object {
        const val KEY = "log"
        const val KEEP_MS = 3L * 24 * 3600 * 1000

        fun key(study: String, module: String, due: Long) = "$study|$module|$due"
    }
}
