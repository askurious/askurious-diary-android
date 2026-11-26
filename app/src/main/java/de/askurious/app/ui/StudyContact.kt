package de.askurious.app.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import de.askurious.app.R

/**
 * Only explicit contact schemes; never launch arbitrary schemes supplied by a study. A web address only with https
 * (security audit 2026-11-26, SEC-15): one with http would open unencrypted; it stays readable and can be copied.
 */
internal fun contactUri(contact: String): String? {
    val text = contact.trim()
    val email = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    return when {
        text.matches(email) -> "mailto:$text"
        text.matches(Regex("\\+?[0-9][0-9 ()/-]{4,}[0-9]")) -> "tel:" + text.filter { it.isDigit() || it == '+' }
        else -> runCatching { java.net.URI(text) }.getOrNull()?.let { uri ->
            when {
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null -> text
                uri.scheme == "mailto" && uri.schemeSpecificPart.matches(email) -> text
                uri.scheme == "tel" && uri.schemeSpecificPart.matches(Regex("\\+?[0-9 ()/-]+")) -> text
                else -> null
            }
        }
    }
}

/**
 * Copies [text] to the clipboard marked as sensitive: from Android 13 the system then shows no preview of it (the
 * contact of a study tells which study the person takes part in; SEC-15). False when the clipboard refuses.
 */
internal fun copySensitive(context: Context, text: String): Boolean = runCatching {
    val clip = ClipData.newPlainText("", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    true
}.getOrDefault(false)

@Composable
fun StudyContact(contact: String) {
    val context = LocalContext.current
    var unavailable by remember(contact) { mutableStateOf(false) }
    var copied by remember(contact) { mutableStateOf(false) }
    Column {
        SelectionContainer { Text(stringResource(R.string.study_contact, contact)) }
        contactUri(contact)?.let { address ->
            TextButton(onClick = {
                val action = when {
                    address.startsWith("mailto:") -> Intent.ACTION_SENDTO
                    address.startsWith("tel:") -> Intent.ACTION_DIAL
                    else -> Intent.ACTION_VIEW
                }
                unavailable = runCatching { context.startActivity(Intent(action, address.toUri())) }.isFailure
            }) { Text(stringResource(R.string.contact_team)) }
        }
        TextButton(onClick = { copied = copySensitive(context, contact) }) { Text(stringResource(R.string.copy_contact)) }
        if (unavailable) Message(stringResource(R.string.contact_unavailable), error = true)
        if (copied) Message(stringResource(R.string.contact_copied))
    }
}
