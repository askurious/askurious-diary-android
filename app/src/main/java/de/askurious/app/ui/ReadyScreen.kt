package de.askurious.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import de.askurious.app.R

/**
 * After the entry questionnaire: one step that says why the app needs notifications and "Alarms & reminders",
 * and asks for one after the other (audit A27); the battery optimisation as an optional last point (UX audit
 * 2026-10-16, B11). "Alarms & reminders" exists from Android 12 on: before, its point is left out and the others
 * are numbered on (B28). Whatever is missing later shows on the start page (Readiness).
 */
@Composable
internal fun MainActivity.ReadyScreen(onDone: () -> Unit, allowNotifications: () -> Unit) {
    val notifyOk = notifyState == NotifyNotice.NONE
    val exactOk = exactOn
    val batteryOk = batteryOn
    val exactStep = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    BackScaffold(stringResource(R.string.ready_title), onDone) { pad ->
        Column(
            pad.fillMaxSize().verticalScroll(rememberScrollState()).pageWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(if (exactStep) R.string.ready_intro else R.string.ready_intro_one))
            ReadyStep(
                1, stringResource(R.string.ready_notify_title), stringResource(R.string.ready_notify_text), notifyOk, primary = true,
                button = stringResource(if (notifyState == NotifyNotice.SETTINGS) R.string.allow_in_settings else R.string.allow),
                onClick = allowNotifications,
            )
            if (exactStep) ReadyStep(
                2, stringResource(R.string.ready_exact_title), stringResource(R.string.ready_exact_text), exactOk, primary = notifyOk,
                button = stringResource(R.string.allow), onClick = { openExactSettings() },
            )
            // optional: the button stays outlined, and "Done" does not wait for it
            ReadyStep(
                if (exactStep) 3 else 2, stringResource(R.string.ready_battery_title), stringResource(R.string.ready_battery_text), batteryOk,
                primary = false, button = stringResource(R.string.battery_open), onClick = { openBatterySettings() },
            )
            // "Done" once both permissions are there; "Later" quietly beside the permission buttons until then
            if (notifyOk && exactOk) Button(onClick = onDone) { Text(stringResource(R.string.done)) }
            else TextButton(onClick = onDone) { Text(stringResource(R.string.later)) }
        }
    }
}

@Composable
private fun ReadyStep(number: Int, title: String, text: String, ok: Boolean, primary: Boolean, button: String, onClick: () -> Unit) {
    val granted = stringResource(R.string.allowed)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            // "1. Notifications" from the resources, so that a language may write its numbers its own way (B31)
            stringResource(R.string.ready_step, number, title), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading(); if (ok) stateDescription = granted },
        )
        Text(text)
        when {
            ok -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(granted)
            }
            // the next missing permission is the filled button; one after the other
            primary -> Button(onClick = onClick) { Text(button) }
            else -> OutlinedButton(onClick = onClick) { Text(button) }
        }
    }
}
