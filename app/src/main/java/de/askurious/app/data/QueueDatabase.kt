package de.askurious.app.data

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.SharedPreferences
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** One sealed answer per transaction. Indexed identifiers are keyed, opaque tags; times and forms stay sealed.
 * Migration commits the entire old queue before removing it. A committed marker prevents resurrection after a
 * crash between the two stores. Corruption fails closed: SQLite's default delete-and-recreate is forbidden.
 */
internal class QueueDatabase(context: Context, private val prefs: SharedPreferences, private val sealer: Sealer) {
    private val app = context.applicationContext
    private val helper = helper(app)
    private var indexKey: ByteArray? = null

    private fun tag(kind: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(indexKey ?: throw IOException("Queue index unavailable"), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal("$kind:$value".toByteArray(Charsets.UTF_8)))
    }

    private fun initialise(db: SQLiteDatabase) {
        if (indexKey != null) return
        val raw = db.rawQuery("SELECT value FROM metadata WHERE name='index'", null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (raw != null) {
            val plain = sealer.open(raw, "queue-index") ?: throw IOException("Queue index cannot be decrypted")
            indexKey = Base64.getDecoder().decode(plain)
        } else {
            val old = SealedText(prefs, "items", sealer, "queue").read()
            if (old.unreadable) throw IOException("Legacy answers cannot be decrypted")
            // Parse every item before changing either store. A malformed tail must not silently disappear.
            val items = old.text?.let { text -> JSONArray(text).let { array ->
                (0 until array.length()).map { AnswerQueue.Item.fromJson(array.getJSONObject(it)) }
            } }.orEmpty()
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val sealedKey = sealer.seal(Base64.getEncoder().encodeToString(key), "queue-index")
            db.beginTransaction()
            try {
                indexKey = key
                items.forEach { insert(db, it) }
                db.execSQL("INSERT INTO metadata(name,value) VALUES('index',?)", arrayOf(sealedKey))
                db.setTransactionSuccessful()
            } catch (e: Exception) {
                indexKey = null
                throw e
            } finally {
                try { db.endTransaction() } catch (e: Exception) { indexKey = null; throw e }
            }
        }
        // Retry cleanup after a previous process committed migration but could not remove preferences.
        try {
            SealedText(prefs, "items", sealer, "queue").remove(durable = true)
        } catch (e: Exception) { indexKey = null; throw e }
    }

    private fun <T> access(action: (SQLiteDatabase) -> T): T = synchronized(SealedText.LOCK) {
        try {
            val db = helper.writableDatabase
            initialise(db)
            action(db)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Queued answers cannot be accessed", e)
        }
    }

    private fun insert(db: SQLiteDatabase, item: AnswerQueue.Item) {
        val sealed = sealer.seal(item.toJson().toString(), "queue-row:${item.id}")
        val values = ContentValues().apply {
            put("id", item.id)
            put("study", tag("study", item.study))
            put("module", tag("module", item.module))
            put("prompt", tag("prompt", item.due?.toString() ?: "self"))
            put("day", tag("day", Instant.ofEpochMilli(item.completed).atZone(ZoneOffset.UTC).toLocalDate().toString()))
            put("body", sealed)
        }
        db.insertOrThrow("answers", null, values)
    }

    private fun read(db: SQLiteDatabase, where: String? = null, args: Array<String>? = null): List<AnswerQueue.Item> =
        db.query("answers", arrayOf("id", "body"), where, args, null, null, "rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val plain = sealer.open(cursor.getString(1), "queue-row:$id")
                        ?: throw IOException("Queued answer cannot be decrypted")
                    val item = AnswerQueue.Item.fromJson(JSONObject(plain))
                    if (item.id != id) throw IOException("Queued answer identity mismatch")
                    add(item)
                }
            }
        }

    fun all(): List<AnswerQueue.Item> = access { read(it) }
    fun of(study: String): List<AnswerQueue.Item> = access { read(it, "study=?", arrayOf(tag("study", study))) }
    fun get(id: String): AnswerQueue.Item? = access { read(it, "id=?", arrayOf(id)).firstOrNull() }

    fun add(item: AnswerQueue.Item): AnswerQueue.Item = access { db ->
        db.beginTransaction()
        try {
            val result = read(db, "id=?", arrayOf(item.id)).firstOrNull() ?: item.also { insert(db, it) }
            db.setTransactionSuccessful()
            result
        } finally { db.endTransaction() }
    }

    fun remove(id: String): Unit = access { it.delete("answers", "id=?", arrayOf(id)); Unit }

    /**
     * Deletes the whole queue (the database file with its index key, and the legacy store): only on the participant's
     * explicit "discard" of a queue that cannot be read any more (Abgleich 2026-10-15, T07-N-2). The next access
     * creates an empty queue, so new answers can be kept again.
     */
    fun discard(): Unit = synchronized(SealedText.LOCK) {
        indexKey = null
        runCatching { helper.close() }
        if (!app.deleteDatabase("answers.db") && app.getDatabasePath("answers.db").exists()) {
            throw IOException("Queue database could not be deleted")
        }
        SealedText(prefs, "items", sealer, "queue").remove(durable = true)
    }
    fun removeStudy(study: String): Unit = access { it.delete("answers", "study=?", arrayOf(tag("study", study))); Unit }

    fun update(id: String, transform: (AnswerQueue.Item) -> AnswerQueue.Item): Unit = access { db ->
        db.beginTransaction()
        try {
            read(db, "id=?", arrayOf(id)).firstOrNull()?.let { previous ->
                val item = transform(previous)
                require(item.id == id && item.study == previous.study && item.module == previous.module && item.due == previous.due)
                if (item != previous) {
                    val values = ContentValues().apply {
                        put("body", sealer.seal(item.toJson().toString(), "queue-row:$id"))
                        put("day", tag("day", Instant.ofEpochMilli(item.completed).atZone(ZoneOffset.UTC).toLocalDate().toString()))
                    }
                    db.update("answers", values, "id=?", arrayOf(id))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun has(study: String, module: String, due: Long): Boolean = access { db ->
        db.rawQuery("SELECT 1 FROM answers WHERE study=? AND module=? AND prompt=? LIMIT 1",
            arrayOf(tag("study", study), tag("module", module), tag("prompt", due.toString()))).use { it.moveToFirst() }
    }

    fun today(study: String, module: String, now: Long, zone: ZoneId): Int = access { db ->
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var utc = date.atStartOfDay(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDate()
        val last = date.plusDays(1).atStartOfDay(zone).minusNanos(1).withZoneSameInstant(ZoneOffset.UTC).toLocalDate()
        val days = mutableListOf<String>()
        while (!utc.isAfter(last)) { days.add(tag("day", utc.toString())); utc = utc.plusDays(1) }
        read(db, "study=? AND module=? AND prompt=? AND day IN (${days.joinToString(",") { "?" }})",
            (listOf(tag("study", study), tag("module", module), tag("prompt", "self")) + days).toTypedArray())
            .count { Instant.ofEpochMilli(it.completed).atZone(zone).toLocalDate() == date }
    }

    companion object {
        private var shared: SQLiteOpenHelper? = null
        private fun helper(context: Context): SQLiteOpenHelper = synchronized(SealedText.LOCK) {
            shared ?: object : SQLiteOpenHelper(context, "answers.db", null, 1,
                DatabaseErrorHandler { throw IOException("Queue database corrupt; original retained") }) {
                override fun onConfigure(db: SQLiteDatabase) {
                    db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() }
                    db.rawQuery("PRAGMA synchronous=FULL", null).use { it.moveToFirst() }
                }
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
                    db.execSQL("CREATE TABLE answers(id TEXT PRIMARY KEY, study TEXT NOT NULL, module TEXT NOT NULL, prompt TEXT NOT NULL, day TEXT NOT NULL, body TEXT NOT NULL)")
                    db.execSQL("CREATE INDEX answers_prompt ON answers(study,module,prompt)")
                    db.execSQL("CREATE INDEX answers_day ON answers(study,module,prompt,day)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    throw IOException("Unsupported queue schema; original retained")
                }
            }.also { shared = it }
        }

        internal fun closeForTest() = synchronized(SealedText.LOCK) { shared?.close(); shared = null }
    }
}
