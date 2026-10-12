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
    val p = shown.palette(phoneDark)
    fun c(key: String, or: Color) = p[key]?.let { Color(0xFF000000 or it.toLong()) } ?: or
    val dark = p["background"]?.let { Look.luminance(it) < 0.18 } ?: phoneDark
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val background = c("background", base.background)
    val text = c("text", base.onBackground)
    val surface = c("surface", background)
    val raised = c("surface_2", base.surfaceVariant)
    val accent = c("accent", base.primary)
    val onAccent = c("on_accent", base.onPrimary)
    val accentSoft = c("accent_soft", base.primaryContainer)
    val muted = c("muted", base.onSurfaceVariant)
    val border = c("border", base.outline)
    val error = c("error", base.error)
    // the tinted area of a notice: the error colour faintly on the page, its text in the page's text colour (the
    // participant pages' --error-soft is the same mix)
    val errorSoft = lerp(background, error, if (dark) 0.22f else 0.10f)
    val scheme = base.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = text,
        inversePrimary = accent, surfaceTint = accent,
        // secondary and tertiary roles (chips, badges, switches) stay in the accent and the neutral tones of the page
        secondary = accent, onSecondary = onAccent, secondaryContainer = accentSoft, onSecondaryContainer = text,
        tertiary = accent, onTertiary = onAccent, tertiaryContainer = raised, onTertiaryContainer = text,
        background = background, onBackground = text,
        surface = background, onSurface = text,
        surfaceVariant = raised, onSurfaceVariant = muted,
        surfaceBright = surface, surfaceDim = raised,
        surfaceContainerLowest = background, surfaceContainerLow = surface, surfaceContainer = raised,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        inverseSurface = text, inverseOnSurface = background,
        outline = border, outlineVariant = border,
        error = error, onError = background, errorContainer = errorSoft, onErrorContainer = text,
        scrim = Color.Black,
    )
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
