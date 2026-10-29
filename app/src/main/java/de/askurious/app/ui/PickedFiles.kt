package de.askurious.app.ui

import android.content.Context
import android.net.Uri

/**
 * What a file picker hands back before it goes to the page. The picker is another app, so its result is not trusted
 * (Android's FileChooserParams says the app checks it): only content addresses of another app's provider that this
 * app may read now, never a file:// path, nothing of this app's own providers, at most [MAX] files.
 */
object PickedFiles {
    const val MAX = 5

    /** Whether one address may go to the page: content://, an authority, not one of this app ([ownPackage]). */
    fun allowed(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (!scheme.equals("content", ignoreCase = true)) return false
        val host = authority?.substringAfterLast('@')?.lowercase()?.ifBlank { null } ?: return false
        val own = ownPackage.lowercase()
        return host != own && !host.startsWith("$own.")
    }

    /** The picked addresses that pass [allowed] and open for reading; null when none does (as for a cancel). */
    fun checked(context: Context, picked: Array<Uri>?): Array<Uri>? {
        val ok = (picked ?: return null).take(MAX).filter { uri ->
            allowed(uri.scheme, uri.authority, context.packageName) && runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
        }
        return ok.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
