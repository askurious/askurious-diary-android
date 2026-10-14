package de.askurious.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import de.askurious.app.R
import androidx.compose.ui.unit.dp

/** Content-sized header: system text enlargement must not clip a fixed-height app bar. */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)? = null, onClose: (() -> Unit)? = null, onAbout: (() -> Unit)? = null) {
    Surface {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (onBack != null || onClose != null) Row {
                if (onBack != null) TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                if (onClose != null) TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
            Row {
                Text(title, Modifier.weight(1f).padding(vertical = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                if (onAbout != null) IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, stringResource(R.string.about)) }
            }
        }
    }
}
