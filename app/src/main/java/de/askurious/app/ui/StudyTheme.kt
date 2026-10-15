package de.askurious.app.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import de.askurious.app.data.Look

/**
 * The app's own look: the standard look of the participant pages ([Look.DEFAULT]), light or dark with the phone. The
 * start page, the notices and "About the app" wear it; a study without a look of its own too (audit A19, SA5).
 */
@Composable
fun DiaryTheme(content: @Composable () -> Unit) = StudyTheme(null, content = content)

/** The look of a study for its card in the list of studies: a bright fixed look is dimmed on a dark phone (Look.forList). */
@Composable
fun ListTheme(look: Look?, content: @Composable () -> Unit) = StudyTheme(look?.forList(isSystemInDarkTheme()), content = content)

/**
 * The look of a study (its participant pages) on the app's screens around them: the study card and its detail page,
 * the bar above an entry, the join screen. Without a look (an older server) the app's own ([Look.DEFAULT]). A look that
 * follows the device shows its dark palette while the phone is dark; a fixed one shows its colours whatever the phone
 * says, as the pages do. Every colour role of Material 3 is set from the look's colours, so no purple default of
 * Material shows anywhere (audit A19). [bars]: the status and navigation bars take light or dark icons for the look's
 * background (a whole screen in the look); back to the phone's own when the screen goes.
 */
@Composable
fun StudyTheme(look: Look?, bars: Boolean = false, content: @Composable () -> Unit) {
    val phoneDark = isSystemInDarkTheme()
    val shown = look ?: Look.DEFAULT
    val scheme = studyColors(look, phoneDark)
    val dark = colorLuminance(scheme.background) < .18
    val r = shown.radius.dp
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(r / 2), small = RoundedCornerShape(r), medium = RoundedCornerShape(r),
        large = RoundedCornerShape(r), extraLarge = RoundedCornerShape(r * 2),
    )
    if (bars) {
        val view = LocalView.current
        if (!view.isInEditMode) DisposableEffect(dark, phoneDark) {
            val window = (view.context as Activity).window
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !dark
            bars.isAppearanceLightNavigationBars = !dark
            onDispose {
                bars.isAppearanceLightStatusBars = !phoneDark
                bars.isAppearanceLightNavigationBars = !phoneDark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
}

/** Study palettes retain their design, with readable native text and controls even for custom colours. */
fun studyColors(look: Look?, phoneDark: Boolean): androidx.compose.material3.ColorScheme {
    val p = (look ?: Look.DEFAULT).palette(phoneDark)
    fun c(key: String, or: Color) = p[key]?.let { Color(0xFF000000 or it.toLong()) } ?: or
    val dark = p["background"]?.let { Look.luminance(it) < 0.18 } ?: phoneDark
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val background = c("background", base.background)
    val requestedText = c("text", base.onBackground)
    val surface = compatibleSurface(c("surface", background), background)
    val raised = compatibleSurface(c("surface_2", base.surfaceVariant), background)
    val surfaces = listOf(background, surface, raised)
    val text = readable(requestedText, surfaces)
    val accent = readable(c("accent", base.primary), surfaces)
    val onAccent = readable(c("on_accent", base.onPrimary), listOf(accent))
    val accentSoft = c("accent_soft", base.primaryContainer)
    val muted = readable(c("muted", base.onSurfaceVariant), surfaces)
    val border = readable(c("border", base.outline), surfaces, 3.0)
    val error = readable(c("error", base.error), surfaces)
    // the tinted area of a notice: the error colour faintly on the page, its text in the page's text colour (the
    // participant pages' --error-soft is the same mix)
    val errorSoft = lerp(background, error, if (dark) 0.22f else 0.10f)
    return base.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = readable(text, listOf(accentSoft)),
        inversePrimary = accent, surfaceTint = accent,
        // secondary and tertiary roles (chips, badges, switches) stay in the accent and the neutral tones of the page
        secondary = accent, onSecondary = onAccent, secondaryContainer = accentSoft, onSecondaryContainer = readable(text, listOf(accentSoft)),
        tertiary = accent, onTertiary = onAccent, tertiaryContainer = raised, onTertiaryContainer = text,
        background = background, onBackground = text,
        surface = background, onSurface = text,
        surfaceVariant = raised, onSurfaceVariant = muted,
        surfaceBright = surface, surfaceDim = raised,
        surfaceContainerLowest = background, surfaceContainerLow = surface, surfaceContainer = raised,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        inverseSurface = text, inverseOnSurface = background,
        outline = border, outlineVariant = border,
        error = error, onError = readable(background, listOf(error)), errorContainer = errorSoft, onErrorContainer = readable(text, listOf(errorSoft)),
        scrim = Color.Black,
    )
}
private fun colorLuminance(c: Color): Double {
    fun lin(v: Float) = if (v <= .04045) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4)
    return .2126 * lin(c.red) + .7152 * lin(c.green) + .0722 * lin(c.blue)
}
private fun contrast(a: Color, b: Color) = (maxOf(colorLuminance(a), colorLuminance(b)) + .05) / (minOf(colorLuminance(a), colorLuminance(b)) + .05)
private fun compatibleSurface(c: Color, background: Color) = if ((colorLuminance(c) < .18) == (colorLuminance(background) < .18)) c else background
private fun readable(c: Color, backgrounds: List<Color>, minimum: Double = 4.5): Color {
    fun score(ink: Color) = backgrounds.minOf { contrast(ink, it) }
    if (score(c) >= minimum) return c
    val target = if (score(Color.White) > score(Color.Black)) Color.White else Color.Black
    for (step in 1..100) { val adjusted = lerp(c, target, step / 100f); if (score(adjusted) >= minimum) return adjusted }
    return target
}
