package de.askurious.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import de.askurious.app.BuildConfig
import de.askurious.app.R
import java.util.Locale

/**
 * The platform's privacy statement, its section on diary studies. It lives on the main address of Askurious, not
 * on the participants' address (BuildConfig.BASE_URL); German or, for every other language, English.
 */
private fun privacyUrl(): String =
    if (Locale.getDefault().language == "de") "https://askurious.de/privacy#tagebuch"
    else "https://askurious.de/privacy?lang=en#tagebuch"

/** The libraries in the app, all under the Apache License 2.0 (THIRD_PARTY_NOTICES.md in the repository). */
private val LIBRARIES = listOf(
    "AndroidX (Core, Activity, Lifecycle) – The Android Open Source Project",
    "Jetpack Compose (UI, Material 3, Material Icons) – The Android Open Source Project",
    "Material Components for Android – The Android Open Source Project",
    "WorkManager – The Android Open Source Project",
    "ZXing Android Embedded – JourneyApps",
    "ZXing – ZXing authors",
    "Kotlin standard library, kotlinx.coroutines – JetBrains s.r.o. and Kotlin contributors",
)

/** About the app: what it stores, the privacy statement, the copyright and the notices of the libraries. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showLicence by remember { mutableStateOf(false) }
    val licence = remember(showLicence) {
        if (!showLicence) "" else context.resources.openRawResource(R.raw.apache_license_2_0).bufferedReader().use { it.readText() }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.about)) }, navigationIcon = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        })
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.about_privacy_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_stores))
            OutlinedButton(onClick = {
                // a phone without a browser: nothing to open
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, privacyUrl().toUri())) }
            }) {
                Text(stringResource(R.string.privacy_statement))
            }

            Text(stringResource(R.string.about_licence_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_copyright))

            Text(stringResource(R.string.about_oss_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_oss_intro))
            for (lib in LIBRARIES) Text("• $lib", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showLicence = !showLicence }) {
                Text(stringResource(if (showLicence) R.string.hide_licence else R.string.show_licence))
            }
            if (showLicence) Text(licence, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}
