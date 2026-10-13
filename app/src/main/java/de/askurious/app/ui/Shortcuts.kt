package de.askurious.app.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import de.askurious.app.R
import de.askurious.app.data.Study
import de.askurious.app.data.StudyStore

/**
 * Shortcuts on the home screen to report something in one study: a module the participant starts themselves
 * (Module.selfTrigger). The home screen is seen by others, so the label is the participant's own, by default the
 * module's report label, never the title of the study. Tapping it opens MainActivity with [ACTION_REPORT], which starts
 * the entry only while the study runs and the module may be started today ([target]). A shortcut of a study that is
 * over or gone is disabled ([update]): a pinned shortcut cannot be taken off the home screen by the app.
 */
object Shortcuts {
    const val ACTION_REPORT = "de.askurious.app.action.REPORT"
    const val EXTRA_STUDY = "study"
    const val EXTRA_MODULE = "module"
    private const val PREFIX = "report|"

    /** The id of the shortcut of [module] in study [code]. */
    fun id(code: String, module: String): String = "$PREFIX$code|$module"

    /** The study code of a shortcut [id] of this app, null for any other id. */
    fun codeOf(id: String): String? = id.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.substringBefore('|')?.ifBlank { null }

    /** Whether the launcher lets the app ask to pin a shortcut. */
    fun supported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin a shortcut to report [module] of [study] under [label]; false when it cannot. */
    fun request(context: Context, study: Study, module: String, label: String): Boolean {
        val text = label.trim().ifBlank { return false }
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION_REPORT)
            .putExtra(EXTRA_STUDY, study.code).putExtra(EXTRA_MODULE, module)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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

    /** Disables the pinned shortcuts whose study is gone or over (left, ended, finished, removed, erased). */
    fun update(context: Context) {
        runCatching {
            val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { it.isEnabled }.map { it.id }
            val gone = stale(pinned, StudyStore(context).all())
            if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, context.getString(R.string.shortcut_disabled))
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
