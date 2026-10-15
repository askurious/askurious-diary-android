package de.askurious.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import de.askurious.app.R
import de.askurious.app.data.Study
import de.askurious.app.data.StudyStore
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
 */
object Shortcuts {
    const val ACTION_REPORT = "de.askurious.app.action.REPORT"
    const val EXTRA_STUDY = "study"
    const val EXTRA_MODULE = "module"
    const val EXTRA_TOKEN = "token"
    private const val PREFIX = "report|"
    private const val PREFS = "shortcuts"
    private const val TOKEN = "token"

    /** The random token of this installation that the app's own shortcuts carry (private storage, no backup). */
    fun token(context: Context): String = synchronized(this) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(TOKEN, null) ?: Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
            .also { prefs.edit().putString(TOKEN, it).commit() }
    }

    /** Whether [given] (the extra of an intent) is the app's own [expected] token, compared in constant time. */
    fun trusted(given: String?, expected: String): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    private fun intent(context: Context, code: String, module: String): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_REPORT)
            .putExtra(EXTRA_STUDY, code).putExtra(EXTRA_MODULE, module).putExtra(EXTRA_TOKEN, token(context))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** The id of the shortcut of [module] in study [code]. */
    fun id(code: String, module: String): String = "$PREFIX$code|$module"

    /** The study code of a shortcut [id] of this app, null for any other id. */
    fun codeOf(id: String): String? = id.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.substringBefore('|')?.ifBlank { null }

    /** Whether the launcher lets the app ask to pin a shortcut. */
    fun supported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin a shortcut to report [module] of [study] under [label]; false when it cannot. */
    fun request(context: Context, study: Study, module: String, label: String): Boolean {
        val text = label.trim().ifBlank { return false }
        val intent = intent(context, study.code, module)
        val info = ShortcutInfoCompat.Builder(context, id(study.code, module))
            .setShortLabel(text.take(25))
            .setLongLabel(text)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        return runCatching {
            // an earlier shortcut of the same module that was disabled works again
            ShortcutManagerCompat.enableShortcuts(context, listOf(info))
            ShortcutManagerCompat.requestPinShortcut(context, info, null)
        }.getOrDefault(false)
    }

    /** Whether the shortcuts of [study] still lead somewhere: the study is on the phone and not over. */
    fun alive(study: Study?): Boolean =
        study != null && !study.leavePending && study.status in setOf("pending", "active", "paused")

    /**
     * The ids among [ids] whose study is gone or over ([alive]), by the studies stored now ([studies]); ids of no
     * shortcut of this app are left alone.
     */
    fun stale(ids: List<String>, studies: List<Study>): List<String> {
        val byCode = studies.associateBy { it.code }
        return ids.filter { id -> codeOf(id)?.let { !alive(byCode[it]) } == true }
    }

    /**
     * Disables the pinned shortcuts whose study is gone or over (left, ended, finished, removed, erased), and gives
     * those pinned before without the token ([trusted]) a new intent with it, keeping their label.
     */
    fun update(context: Context) {
        runCatching {
            val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { it.isEnabled }
            val gone = stale(pinned.map { it.id }, StudyStore(context).all())
            if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, context.getString(R.string.shortcut_disabled))
            val token = token(context)
            val renewed = pinned.filter { it.id !in gone && codeOf(it.id) != null && !trusted(it.intent.getStringExtra(EXTRA_TOKEN), token) }
                .mapNotNull { old ->
                    val code = codeOf(old.id) ?: return@mapNotNull null
                    val module = old.id.substringAfterLast('|').ifBlank { return@mapNotNull null }
                    ShortcutInfoCompat.Builder(context, old.id)
                        .setShortLabel(old.shortLabel)
                        .setLongLabel(old.longLabel ?: old.shortLabel)
                        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                        .setIntent(intent(context, code, module))
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
