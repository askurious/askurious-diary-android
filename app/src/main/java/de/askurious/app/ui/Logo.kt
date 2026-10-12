package de.askurious.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.askurious.app.BuildConfig
import de.askurious.app.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** PT Serif Bold (ParaType Ltd, ParaType Free Font License; res/raw/pt_serif_licence.txt), bundled: not every phone has a serif. */
val LogoFont = FontFamily(Font(R.font.pt_serif_bold, FontWeight.Bold))

/**
 * The mark of Askurious: a serif "A" in a square, as the favicon of the website (app/static/favicon.ico) and the app
 * icon (the same letter, drawable/ic_logo_a). Dark on a light screen, light on a dark one.
 */
@Composable
fun LogoMark(size: Dp = 30.dp) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.onBackground, RoundedCornerShape(size / 6)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_logo_a), contentDescription = null,
            tint = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The mark and the name in a serif font, as the word mark of the website: the head of the start page. */
@Composable
fun Logo() {
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "Askurious"; heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LogoMark()
        Text("Askurious", fontFamily = LogoFont, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, softWrap = false)
    }
}

/**
 * Version and build time of this APK, and for a debug build the server it talks to by default: so that one sees
 * which version runs on a phone.
 */
fun buildInfo(format: (String, String) -> String): String {
    val built = Instant.ofEpochMilli(BuildConfig.BUILD_TIME).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    val line = format(BuildConfig.VERSION_NAME, built)
    return if (BuildConfig.DEBUG) "$line · debug · ${BuildConfig.BASE_URL.substringAfter("://")}" else line
}
