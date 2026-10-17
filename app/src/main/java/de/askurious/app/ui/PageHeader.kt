package de.askurious.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import de.askurious.app.R
import androidx.compose.ui.unit.dp

/**
 * The top bar of a screen in one row (UX audit 2026-10-16, B7): the arrow back or the X of an entry at the left of
 * the title, as Material 3 has it, the "about" button at the right. Sized by its content, not a fixed height, so that
 * system text enlargement does not clip it; a long title takes [maxLines] lines at most. [compact]: the smaller title
 * of the entry, whose page needs the room. [announce]: the title takes the focus when the screen comes, so that
 * TalkBack names the new screen (UX audit 2026-10-16, B32); a field that wants the keyboard may take it afterwards.
 */
@Composable
fun PageHeader(
    title: String, onBack: (() -> Unit)? = null, onClose: (() -> Unit)? = null, onAbout: (() -> Unit)? = null, compact: Boolean = false,
    maxLines: Int = 2, announce: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    if (announce) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
            }
            if (onClose != null) IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, stringResource(R.string.close_entry))
            }
            val icon = onBack != null || onClose != null
            Text(
                title,
                Modifier.weight(1f).padding(start = if (icon) 4.dp else 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).semantics { heading() }
                    .then(if (announce) Modifier.focusRequester(focus).focusable() else Modifier),
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            )
            if (onAbout != null) IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, stringResource(R.string.about)) }
        }
    }
}
