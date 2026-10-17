package de.askurious.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Last twenty confirmation receipts per study; encrypted metadata, never answer contents. */
class TransmissionLog(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("transmission", Context.MODE_PRIVATE)
    private fun text(code: String) = SealedText(prefs, code, KeystoreSealer, "transmission:$code")
    fun of(code: String): List<JSONObject> = synchronized(SealedText.LOCK) {
        val a = runCatching { JSONArray(text(code).read().text ?: "[]") }.getOrDefault(JSONArray())
        (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun confirmed(code: String, module: String, review: Boolean, id: String, label: String = "", completed: Long? = null): Unit = synchronized(SealedText.LOCK) {
        if (of(code).any { it.optString("id") == id }) return@synchronized
        val receipt = JSONObject().put("id", id).put("module", module).put("at", System.currentTimeMillis()).put("review", review).put("label", label)
        completed?.let { receipt.put("completed", it) }
        text(code).write(JSONArray((of(code) + receipt).takeLast(20)).toString(), durable = true)
    }
    fun remove(code: String): Unit = synchronized(SealedText.LOCK) { text(code).remove(durable = true) }
}
