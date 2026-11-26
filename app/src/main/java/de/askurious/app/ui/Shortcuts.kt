package de.askurious.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import de.askurious.app.R
import de.askurious.app.data.KeystoreSealer
import de.askurious.app.data.SealedText
import de.askurious.app.data.Sealer
import de.askurious.app.data.Study
import de.askurious.app.data.StudyStore
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Shortcuts on the home screen to report something in one study: a module the participant starts themselves
 * (Module.selfTrigger). The home screen is seen by others, so the label is the participant's own, by default the
 * module's report label, never the title of the study. Tapping it opens MainActivity with [ACTION_REPORT], which starts
 * the entry only while the study runs and the module may be started today ([target]). A shortcut of a study that is
 * over or gone is disabled ([update]): a pinned shortcut cannot be taken off the home screen by the app.
 *
 * MainActivity is exported (the launcher starts it), so any app could send it [ACTION_REPORT]. The shortcut's intent
 * therefore carries a random token kept only in the app's private storage ([token]); MainActivity starts an entry only
 * when it matches ([trusted]; Abgleich 2026-10-15, T07-N-1). Shortcuts pinned before carry none: [update] gives them
 * the token.
 *
 * The id of a shortcut and its intent say nothing about the study (security audit 2026-11-26, SEC-11): the system
 * keeps both outside the app (its shortcut service, the launcher, the launcher's backup), and the id was
 * "report|<study code>|<module key>" with both again as extras. A new shortcut gets a random id; which study and
 * module it stands for is kept sealed in the app's preferences ([targets]). Shortcuts pinned by earlier versions keep
 * their id (the id of a pinned shortcut cannot change) and go on working.
 */
object Shortcuts {
    const val ACTION_REPORT = "de.askurious.app.action.REPORT"
    /** Only in the intents of shortcuts pinned by earlier versions ([legacy]). */
    const val EXTRA_STUDY = "study"
    const val EXTRA_MODULE = "module"
    const val EXTRA_TOKEN = "token"
    /** The random id of the shortcut, the only thing its intent names ([resolve]). */
    const val EXTRA_ID = "id"
    private const val PREFIX = "report|"
    private const val OPAQUE = "s_"
    private const val PREFS = "shortcuts"
    private const val TOKEN = "token"
    private const val MAP = "map"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The random token of this installation that the app's own shortcuts carry (private storage, no backup). */
    fun token(context: Context): String = synchronized(this) {
        val prefs = prefs(context)
        prefs.getString(TOKEN, null) ?: Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            .also { prefs.edit().putString(TOKEN, it).commit() }
    }

    /** Whether [given] (the extra of an intent) is the app's own [expected] token, compared in constant time. */
    fun trusted(given: String?, expected: String): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    private fun intent(context: Context, id: String): Intent {
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_REPORT)
            .putExtra(EXTRA_TOKEN, token(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // a shortcut of an earlier version names its study itself; a new one only its id
        val old = legacy(id)
        return if (old != null) intent.putExtra(EXTRA_STUDY, old.first).putExtra(EXTRA_MODULE, old.second) else intent.putExtra(EXTRA_ID, id)
    }

    /** The id earlier versions gave the shortcut of [module] in study [code]; still read ([legacy]), no longer made. */
    fun id(code: String, module: String): String = "$PREFIX$code|$module"

    /** The study code in the id of a shortcut of an earlier version, null for any other id. */
    fun codeOf(id: String): String? = id.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.substringBefore('|')?.ifBlank { null }

    /** Study code and module key in the id of a shortcut of an earlier version ("report|<code>|<module>"). */
    fun legacy(id: String): Pair<String, String>? {
        val code = codeOf(id) ?: return null
        val module = id.removePrefix(PREFIX).substringAfter('|', "").ifBlank { return null }
        return code to module
    }

    /** A new random id: 16 random bytes, nothing of the study. */
    fun newId(): String = OPAQUE + ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * Which study and module each shortcut with a random id stands for: {id: (code, module)}, kept sealed. Empty while
     * it cannot be opened (the shortcuts then only open the app).
     */
    internal fun targets(context: Context, sealer: Sealer = KeystoreSealer): Map<String, Pair<String, String>> =
        decode(SealedText(prefs(context), MAP, sealer, "shortcuts-map").read().text)

    internal fun decode(text: String?): Map<String, Pair<String, String>> = runCatching {
        val o = JSONObject(text ?: return emptyMap())
        o.keys().asSequence().mapNotNull { id ->
            o.optJSONObject(id)?.let { t -> id to (t.getString("study") to t.getString("module")) }
        }.toMap()
    }.getOrDefault(emptyMap())

    internal fun encode(targets: Map<String, Pair<String, String>>): String = JSONObject().apply {
        targets.forEach { (id, t) -> put(id, JSONObject().put("study", t.first).put("module", t.second)) }
    }.toString()

    private fun save(context: Context, targets: Map<String, Pair<String, String>>, sealer: Sealer = KeystoreSealer) {
        val text = SealedText(prefs(context), MAP, sealer, "shortcuts-map")
        if (text.read().let { it.unreadable && !it.damaged }) throw java.io.IOException("Shortcuts cannot be opened")
        if (targets.isEmpty()) text.remove(durable = true) else text.write(encode(targets), durable = true)
    }

    /** Study code and module key of the shortcut [id]: of a random id from [targets], of an earlier one from the id. */
    fun resolve(context: Context, id: String?): Pair<String, String>? {
        if (id == null) return null
        return legacy(id) ?: if (id.startsWith(OPAQUE)) targets(context)[id] else null
    }

    /** Whether the launcher lets the app ask to pin a shortcut. */
    fun supported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Asks the launcher to pin a shortcut to report [module] of [study] under [label]; false when it cannot. Opens
     * sealed storage: not on the main thread.
     */
    fun request(context: Context, study: Study, module: String, label: String): Boolean {
        val text = label.trim().ifBlank { return false }
        return runCatching {
            val id = synchronized(this) {
                // a shortcut of an earlier version that is still pinned is used again, not doubled
                val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).map { it.id }
                val old = id(study.code, module)
                if (old in pinned) old else {
                    val targets = targets(context)
                    targets.entries.firstOrNull { it.value == study.code to module }?.key
                        ?: newId().also { save(context, targets + (it to (study.code to module))) }
                }
            }
            val info = ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(text.take(25))
                .setLongLabel(text)
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent(context, id))
                .build()
            // an earlier shortcut of the same module that was disabled works again
            ShortcutManagerCompat.enableShortcuts(context, listOf(info))
            ShortcutManagerCompat.requestPinShortcut(context, info, null)
        }.getOrDefault(false)
    }

    /** Whether the shortcuts of [study] still lead somewhere: the study is on the phone and not over. */
    fun alive(study: Study?): Boolean =
        study != null && !study.leavePending && study.status in setOf("pending", "active", "paused")

    /**
     * The ids among [ids] whose study is gone or over ([alive]), by the studies stored now ([studies]); the study of a
     * random id is looked up in [targets]. Ids of no shortcut of this app are left alone, and so is a random id that
     * [targets] does not name (it may only be unreadable now).
     */
    fun stale(ids: List<String>, studies: List<Study>, targets: Map<String, Pair<String, String>> = emptyMap()): List<String> {
        val byCode = studies.associateBy { it.code }
        return ids.filter { id -> (codeOf(id) ?: targets[id]?.first)?.let { !alive(byCode[it]) } == true }
    }

    /**
     * Disables the pinned shortcuts whose study is gone or over (left, ended, finished, removed, erased) and forgets
     * what their random ids stood for, and gives those pinned before without the token ([trusted]) a new intent with
     * it, keeping their label.
     */
    fun update(context: Context) {
        runCatching {
            val studies = StudyStore(context).all()
            val targets = synchronized(this) {
                val all = targets(context)
                // a study that is gone or over: nothing of it stays in the list (SEC-11)
                val kept = all.filterValues { t -> alive(studies.firstOrNull { it.code == t.first }) }
                if (kept.size != all.size) runCatching { save(context, kept) }
                all
            }
            val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { it.isEnabled }
            val gone = stale(pinned.map { it.id }, studies, targets)
            if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, context.getString(R.string.shortcut_disabled))
            val token = token(context)
            val renewed = pinned.filter { it.id !in gone && legacy(it.id) != null && !trusted(it.intent.getStringExtra(EXTRA_TOKEN), token) }
                .map { old ->
                    ShortcutInfoCompat.Builder(context, old.id)
                        .setShortLabel(old.shortLabel)
                        .setLongLabel(old.longLabel ?: old.shortLabel)
                        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                        .setIntent(intent(context, old.id))
                        .build()
                }
            if (renewed.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, renewed)
        }
    }

    /**
     * The module to start for a shortcut's [code] and [module], read from the stored studies: only while the study runs
     * and the module may be started by the participant today; null otherwise (the app then just opens).
     */
    fun target(context: Context, code: String?, module: String?): Pair<String, String>? {
        if (code == null || module == null) return null
        val study = StudyStore(context).get(code) ?: return null
        if (!study.running) return null
        val day = study.dayIndex(study.today()) ?: return null
        return if (study.selfModules(day).any { it.key == module }) code to module else null
    }
}
