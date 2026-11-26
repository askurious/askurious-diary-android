package de.askurious.app.ui

import android.app.Application
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import de.askurious.app.data.KeystoreSealer
import de.askurious.app.data.OfflinePages
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference

/**
 * Application-context WebView survives Activity recreation; bounded form drafts use Android SavedState.
 *
 * What goes into the saved state leaves the app: the system keeps it over the end of the process, outside the app's
 * storage. The entry's one-time address and the draft of the open page (the answers typed so far) are therefore kept
 * there only sealed ([keep], AES-GCM under the Keystore key, bound to this entry), and in plain text only in memory
 * ([url], [draft]); of the page only a hash (security audit 2026-11-26, SEC-01).
 */
class EntrySession(app: Application, val state: SavedStateHandle) : AndroidViewModel(app) {
    var activity: WeakReference<EntryActivity>? = null
    var dirty by mutableStateOf(state.get<Boolean>("dirty") ?: false)
    /** The entry's address, in memory; sealed in the saved state under [URL] ([keep], [opened]). */
    var url by mutableStateOf<String?>(null)
    /** The draft of the open page as strict JSON text, in memory; sealed in the saved state under [DRAFT]. */
    var draft: String? = null
    var offline by mutableStateOf<OfflinePages.Page?>(null)
    var error by mutableStateOf<String?>(null)
    var saved by mutableStateOf<Boolean>(false)
    var done by mutableStateOf<Boolean>(false)
    var saving by mutableStateOf<Boolean>(false)
    val entryId: String = state.get<String>("entryId") ?: java.util.UUID.randomUUID().toString().also { state["entryId"] = it }
    var offlineAnswerId by mutableStateOf<String>(entryId)
    var retryable by mutableStateOf<Boolean>(false)
    var loadFailed by mutableStateOf<Boolean>(false)
    var pageProgress by mutableStateOf<Int>(100)
    var askAbort by mutableStateOf<Boolean>(false)
    /** The question comes from the back gesture on a page without its own "Back": "Leave the questionnaire?" (A12). */
    var askLeave by mutableStateOf<Boolean>(false)
    /** [error] asks to contact the researchers: the study's contact goes with it (UI/UX audit 2026-10-17, A10). */
    var askTeam by mutableStateOf<Boolean>(false)
    var cleared by mutableStateOf<Boolean>(false)
    var offlineStarted by mutableStateOf<Long>(0L)
    /** The form of a kept page whose saving failed: the banner's "Save again" sends it once more (UX audit 2026-10-16, B8). */
    var unsaved by mutableStateOf<String?>(null)
    /**
     * The page waits for the files of an `<input type=file>` (the file alternative of a voice question): kept here, so
     * that the result of the phone's file picker reaches it also after the activity was made anew (turning the phone).
     */
    var fileCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    @android.annotation.SuppressLint("StaticFieldLeak") // constructed with applicationContext
    var web: WebView? = null
    private val changes = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val sealing = kotlinx.coroutines.sync.Mutex()

    /**
     * Keeps [plain] sealed in the saved state under [key] ([URL], [DRAFT]); null takes it out. Called on the main
     * thread; the Keystore is asked off it, and only the newest value of a key is written (a draft changes with every
     * letter).
     */
    fun keep(key: String, plain: String?) {
        val mine = (changes[key] ?: 0) + 1
        changes[key] = mine
        if (plain == null) { state.remove<String>(key); return }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val sealed = sealing.withLock {
                if (changes[key] != mine) return@launch
                runCatching { KeystoreSealer.seal(plain, aad(key)) }.getOrNull()
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (changes[key] != mine) return@withContext
                // what cannot be sealed is not kept at all, never in plain text
                if (sealed != null) state[key] = sealed else state.remove<String>(key)
            }
        }
    }

    /** The text [sealed] under [key] in the saved state opened again; null when it does not open. Off the main thread. */
    fun opened(key: String, sealed: String): String? = KeystoreSealer.open(sealed, aad(key))

    private fun aad(key: String) = "entry:$key:$entryId"

    companion object {
        const val URL = "url_sealed"
        const val DRAFT = "draft_sealed"
        /** SHA-256 of the open page's address and field names: enough to tell a new page, and says nothing itself. */
        const val PAGE = "page_hash"

        fun hash(text: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    override fun onCleared() { fileCallback?.onReceiveValue(null); fileCallback = null; web?.apply { (parent as? android.view.ViewGroup)?.removeView(this); stopLoading(); destroy() }; web = null }
}
