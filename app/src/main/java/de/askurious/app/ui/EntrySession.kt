package de.askurious.app.ui

import android.app.Application
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import de.askurious.app.data.OfflinePages
import java.lang.ref.WeakReference

/** Application-context WebView survives Activity recreation; bounded form drafts use Android SavedState. */
class EntrySession(app: Application, val state: SavedStateHandle) : AndroidViewModel(app) {
    var activity: WeakReference<EntryActivity>? = null
    var dirty by mutableStateOf(state.get<Boolean>("dirty") ?: false)
    var url by mutableStateOf<String?>(state.get<String>("url"))
    var offline by mutableStateOf<OfflinePages.Page?>(null)
    var error by mutableStateOf<String?>(null)
    var saved by mutableStateOf<Boolean>(false)
    var done by mutableStateOf<Boolean>(false)
    var saving by mutableStateOf<Boolean>(false)
    var offlineAnswerId by mutableStateOf<String>(java.util.UUID.randomUUID().toString())
    var retryable by mutableStateOf<Boolean>(false)
    var loadFailed by mutableStateOf<Boolean>(false)
    var pageProgress by mutableStateOf<Int>(100)
    var askAbort by mutableStateOf<Boolean>(false)
    var cleared by mutableStateOf<Boolean>(false)
    var offlineStarted by mutableStateOf<Long>(0L)
    /** The form of a kept page whose saving failed: the banner's "Save again" sends it once more (UX audit 2026-10-16, B8). */
    var unsaved by mutableStateOf<String?>(null)
    @android.annotation.SuppressLint("StaticFieldLeak") // constructed with applicationContext
    var web: WebView? = null
    override fun onCleared() { web?.apply { (parent as? android.view.ViewGroup)?.removeView(this); stopLoading(); destroy() }; web = null }
}
