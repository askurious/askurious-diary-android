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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import de.askurious.app.data.Look

/**
 * The look of a study (its participant pages) on the app's screens around them: the study card, the bar above an
 * entry, the join screen. Without a look (an older server) the app's own [DiaryTheme]. A look that follows the device
 * shows its dark palette while the phone is dark; a fixed one shows its colours whatever the phone says, as the pages
 * do. [bars]: the status and navigation bars take light or dark icons for the look's background (a whole screen in
 * the look); back to the phone's own when the screen goes.
 */
@Composable
fun StudyTheme(look: Look?, bars: Boolean = false, content: @Composable () -> Unit) {
    val phoneDark = isSystemInDarkTheme()
    if (look == null) return DiaryTheme(content)
    val p = look.palette(phoneDark)
    fun c(key: String, or: Color) = p[key]?.let { Color(0xFF000000 or it.toLong()) } ?: or
    val dark = p["background"]?.let { Look.luminance(it) < 0.18 } ?: phoneDark
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val background = c("background", base.background)
    val text = c("text", base.onBackground)
    val raised = c("surface_2", base.surfaceVariant)
    val accentSoft = c("accent_soft", base.primaryContainer)
    val scheme = base.copy(
        primary = c("accent", base.primary), onPrimary = c("on_accent", base.onPrimary),
        primaryContainer = accentSoft, onPrimaryContainer = text,
        tertiaryContainer = accentSoft, onTertiaryContainer = text,
        background = background, onBackground = text,
        surface = background, onSurface = text,
        surfaceVariant = raised, onSurfaceVariant = c("muted", base.onSurfaceVariant),
        surfaceContainerLowest = background, surfaceContainerLow = raised, surfaceContainer = raised,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        outline = c("border", base.outline), outlineVariant = c("border", base.outlineVariant),
        error = c("error", base.error),
    )
    val r = look.radius.dp
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
