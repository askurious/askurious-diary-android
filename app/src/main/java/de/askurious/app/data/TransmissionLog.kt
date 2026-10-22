package de.askurious.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Last twenty confirmation receipts per study; encrypted metadata, never answer contents. All studies in one sealed
 * text under [ALL] (audit 2026-10-22, F-01): one text per study was stored under the study's code as the name of its
 * preference, so the file showed which studies the person takes part in. Those texts of earlier versions are moved in
 * at the first read and go.
 */
class TransmissionLog internal constructor(private val prefs: SharedPreferences, private val sealer: Sealer) {
    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences("transmission", Context.MODE_PRIVATE), KeystoreSealer)

    private val text = SealedText(prefs, ALL, sealer, AAD)

    fun of(code: String): List<JSONObject> = synchronized(SealedText.LOCK) {
        val a = load().optJSONArray(code) ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }
    }

    fun confirmed(code: String, module: String, review: Boolean, id: String, label: String = "", completed: Long? = null): Unit = synchronized(SealedText.LOCK) {
        if (of(code).any { it.optString("id") == id }) return@synchronized
        val receipt = JSONObject().put("id", id).put("module", module).put("at", System.currentTimeMillis()).put("review", review).put("label", label)
        completed?.let { receipt.put("completed", it) }
        val all = load()
        all.put(code, JSONArray((of(code) + receipt).takeLast(20)))
        text.write(all.toString(), durable = true)
    }

    fun remove(code: String): Unit = synchronized(SealedText.LOCK) {
        val all = load()
        if (!all.has(code)) return@synchronized
        all.remove(code)
        if (all.length() == 0) text.remove(durable = true) else text.write(all.toString(), durable = true)
    }

    /** {code: [receipts]}; the texts of earlier versions (one per study, named by its code) moved in once. */
    private fun load(): JSONObject {
        val opened = text.read()
        val all = opened.text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val legacy = runCatching { prefs.all.keys.filter { it != ALL } }.getOrDefault(emptyList())
        if (legacy.isEmpty() || opened.unreadable) return all
        for (code in legacy) {
            val old = SealedText(prefs, code, sealer, "transmission:$code").read()
            if (old.unreadable) continue // kept as it is: the Keystore may only be busy
            old.text?.let { t -> runCatching { JSONArray(t) }.getOrNull()?.let { if (!all.has(code)) all.put(code, it) } }
        }
        // the old texts go only once the new one is on disk; else they stay for the next try
        if (runCatching { text.write(all.toString(), durable = true) }.isFailure) return all
        val editor = prefs.edit()
        for (code in legacy) {
            if (SealedText(prefs, code, sealer, "transmission:$code").read().unreadable) continue
            editor.remove(code)
        }
        editor.commit()
        return all
    }

    companion object {
        const val ALL = "receipts"
        private const val AAD = "transmission"
    }
}
