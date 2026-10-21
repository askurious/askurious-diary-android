package de.askurious.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.askurious.app.R
import java.util.Locale

/**
 * The platform's privacy statement, its section on diary studies. It lives on the main address of Askurious, not
 * on the participants' address (BuildConfig.BASE_URL); German or, for every other language, English.
 */
private fun privacyUrl(): String =
    if (Locale.getDefault().language == "de") "https://askurious.de/privacy#tagebuch"
    else "https://askurious.de/privacy?lang=en#tagebuch"

/** The imprint of the platform (Impressum, on the same page as the privacy statement of askurious.de). */
private fun imprintUrl(): String =
    if (Locale.getDefault().language == "de") "https://askurious.de/imprint" else "https://askurious.de/imprint?lang=en"

/**
 * Who answers for the app: the provider named in the imprint of askurious.de (the server's IMPRINT_NAME and
 * IMPRINT_ADDRESS, config.py of the Askurious repository, 2026-10-12). No e-mail address: the imprint names none.
 * The name and the postal address as they are; the chair in the language of the app (R.string.about_provider_unit,
 * UX audit 2026-10-16, B31).
 */
private const val PROVIDER_NAME = "Dr. Philipp Brauner"
private val PROVIDER_ADDRESS = listOf("Campus-Boulevard 57", "52074 Aachen")

/**
 * The libraries and the font in the app, each with its licence (THIRD_PARTY_NOTICES.md in the repository). In
 * English, as the licence texts are; the page says so (about_oss_english, B31). The further AndroidX and Jetpack
 * libraries that come in transitively are named in one sentence below the list (about_oss_more, licence audit
 * 2026-10-21, C-4).
 */
private val LIBRARIES = listOf(
    "AndroidX (Core, Activity, Lifecycle, Navigation, WebKit) – The Android Open Source Project, Apache License 2.0",
    "Jetpack Compose (UI, Material 3, Material Icons) – The Android Open Source Project, Apache License 2.0",
    "Material Components for Android – The Android Open Source Project, Apache License 2.0",
    "WorkManager – The Android Open Source Project, Apache License 2.0",
    "ZXing Android Embedded – ZXing authors, Journey Mobile, Apache License 2.0",
    "ZXing – ZXing authors, Apache License 2.0",
    "Kotlin standard library – JetBrains s.r.o. and Kotlin contributors, Apache License 2.0",
    "ThreeTen-bp (in the Kotlin standard library, kotlin.time) – © 2007-present Stephen Colebourne & Michael Nascimento Santos, BSD 3-Clause License",
    "kotlinx.coroutines, kotlinx.serialization – JetBrains s.r.o. and contributors, Apache License 2.0",
    "JetBrains Java Annotations – JetBrains s.r.o., Apache License 2.0",
    "JSpecify – The JSpecify Authors, Apache License 2.0",
    "Error Prone annotations – The Error Prone Authors, Apache License 2.0",
    "Guava ListenableFuture – The Guava Authors, Apache License 2.0",
    "PT Serif (font of the logo and the app icon) – ParaType Ltd, SIL Open Font License 1.1",
)

/**
 * A licence text as the screen shows it: the hard line breaks of the text file (made for 80 columns) go, so that
 * the lines follow the width of the phone; paragraphs (a blank line) stay (audit A24). Plain Kotlin for the tests.
 */
fun unwrapped(text: String): String =
    text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        .map { p -> p.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ") }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")

/**
 * A screen of the app with the way back as an arrow at the left of the top bar (audit A21). Its heading takes the
 * focus when the screen comes, so that TalkBack names it (UX audit 2026-10-16, B32). The modifier of [content] takes
 * the insets of the bars as consumed: a field over the keyboard (imePadding) does not add them again (B21).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackScaffold(
    title: String, onBack: () -> Unit, snackbar: SnackbarHostState? = null, titleLines: Int = 2, content: @Composable (Modifier) -> Unit,
) {
    Scaffold(snackbarHost = { snackbar?.let { MessageHost(it) } }, topBar = {
        PageHeader(title, onBack = onBack, maxLines = titleLines, announce = true)
    }) { pad -> content(Modifier.padding(pad).consumeWindowInsets(pad)) }
}

/**
 * The column of a page: at most 640 dp wide, in the middle of a wide screen (landscape, tablet), so that lines stay
 * readable and buttons near the text (UX audit 2026-10-16, B34). On a phone in portrait it is the whole width.
 */
fun Modifier.pageWidth(): Modifier = wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth()

/**
 * The short message after a final action ("“X” left."): the current message of [state] as a snackbar, read out by
 * TalkBack (Snackbar is a live region).
 */
@Composable
fun MessageHost(state: SnackbarHostState) {
    state.currentSnackbarData?.let { Snackbar(it, Modifier.padding(12.dp)) }
}

/** A heading of a section, also for TalkBack (audit A22). */
@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

/**
 * About the app: what it stores, the privacy statement, the provider and the imprint, the copyright and the notices
 * of the libraries; the licence texts on a page of their own ([LicenceScreen]).
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onLicence: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) {
        // a phone without a browser: nothing to open
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
    BackScaffold(stringResource(R.string.about), onBack) { pad ->
        Column(
            pad.fillMaxSize().verticalScroll(rememberScrollState()).pageWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            // the version only here, not on the start page (B30)
            // the format from the resources of the composition: a change of language shows at once (lint
            // LocalContextGetResourceValueCall, audit 2026-10-13, AN-10)
            val format = stringResource(R.string.build_info)
            Text(buildInfo { v, t -> String.format(format, v, t) }, style = MaterialTheme.typography.bodySmall)

            SectionTitle(stringResource(R.string.about_privacy_title))
            Text(stringResource(R.string.about_stores))
            Text(stringResource(R.string.about_offline))
            // the WebView is Google's: what it may send of its own, and what the app switched off (audit 2026-10-18, AND-10)
            Text(stringResource(R.string.about_webview))
            Text(stringResource(R.string.about_quiet))
            Text(stringResource(R.string.media_privacy))
            Text(stringResource(R.string.draft_privacy))
            OutlinedButton(onClick = { open(privacyUrl()) }) { Text(stringResource(R.string.privacy_statement)) }

            SectionTitle(stringResource(R.string.about_contact_title))
            Text(stringResource(R.string.about_contact))
            Text((listOf(PROVIDER_NAME, stringResource(R.string.about_provider_unit)) + PROVIDER_ADDRESS).joinToString("\n"))
            OutlinedButton(onClick = { open(imprintUrl()) }) { Text(stringResource(R.string.imprint)) }

            SectionTitle(stringResource(R.string.about_licence_title))
            Text(stringResource(R.string.about_copyright))

            SectionTitle(stringResource(R.string.about_oss_title))
            Text(stringResource(R.string.about_oss_intro))
            Text(stringResource(R.string.about_oss_english), style = MaterialTheme.typography.bodySmall)
            for (lib in LIBRARIES) BulletText(lib, MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_oss_more), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onLicence) { Text(stringResource(R.string.show_licence)) }
        }
    }
}

/**
 * The licence texts of the libraries and the font, each under its title, readable at any width (audit A24): the
 * Apache License 2.0, the SIL Open Font License 1.1 of PT Serif, and the NOTICE files of ZXing, Kotlin,
 * kotlinx.coroutines and kotlinx.serialization with the BSD 3-Clause License of ThreeTen-bp.
 */
@Composable
fun LicenceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val texts = remember {
        listOf(
            R.string.licence_title_apache to R.raw.apache_license_2_0,
            R.string.licence_title_font to R.raw.pt_serif_licence,
            R.string.licence_title_notices to R.raw.notices,
        ).map { (title, id) -> title to unwrapped(context.resources.openRawResource(id).bufferedReader().use { it.readText() }) }
    }
    BackScaffold(stringResource(R.string.licence_texts), onBack) { pad ->
        Column(
            pad.fillMaxSize().verticalScroll(rememberScrollState()).pageWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            for ((title, t) in texts) {
                SectionTitle(stringResource(title))
                Text(t, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Touches on the window that holds this composable count only while no other app's window lies over it
 * (filterTouchesWhenObscured, audit 2026-10-13, AN-16): an overlay of another app cannot make the participant join,
 * leave or delete without seeing it. Android 12 and later block untrusted overlays themselves; this covers 8 to 11.
 * Put into a dialog it guards the dialog's window; on a screen, the screen while it is shown.
 */
@Composable
fun GuardAgainstOverlays() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = before }
    }
}
