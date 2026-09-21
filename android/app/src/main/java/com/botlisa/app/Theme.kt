package com.botlisa.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * App-wide Compose theme -- the "Assistant Lisa" palette from the wireframe
 * legend (see android/UI_REDESIGN_PLAN.md). Replaces the bare
 * `MaterialTheme {}` the app used before, which fell back to Material 3's
 * default purple.
 *
 * Both light and dark schemes are tuned (Step 8). The three brand hues
 * (purple / orange / teal) are shared; only the neutrals differ.
 */

private val Purple = Color(0xFF8F7FEE)   // primary  -- title, active mic, "Что ещё?" / suggested phrases
private val Orange = Color(0xFFFE9F4D)   // secondary -- "Что это значит?"
private val Teal = Color(0xFF2CB3AE)     // tertiary  -- "Как сказать?" / listening-for-word
private val OffWhite = Color(0xFFF7F7FA) // light window background

// Rust -- a darker shade of Orange, not a new brand hue, so "Как ответить?"
// reads as distinct from "Что это значит?" (both used to share Orange) while
// staying in the same warm family. Not part of the Material ColorScheme
// (no free role left) so CommandChips.kt reads it directly.
val Rust = Color(0xFFD8722E)

// Charcoal -- the default hands-free "listening for [language]" state
// (UiPhase.LISTENING_RU), which used to borrow Purple even though it isn't
// the "what else?" command. A neutral so the record button/subtitle reads as
// "ambiently listening" rather than any one brand command. Fixed hex, same
// in both themes, like the other accents above -- see MainActivity's
// UiPhase.buttonFillColor().
val Charcoal = Color(0xFF6E6E78)

// Baloo 2 (SIL OFL, see app/licenses/baloo2-OFL.txt) -- the rounded, playful
// weight used for the "Assistant Lisa" header title, in place of the
// default Material sans-serif which read as too plain/corporate for a
// mascot-led app.
val TitleFontFamily = FontFamily(Font(R.font.baloo2_bold, FontWeight.Bold))

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E5FB),
    onPrimaryContainer = Color(0xFF241B4D),
    secondary = Orange,
    onSecondary = Color.White,
    tertiary = Teal,
    onTertiary = Color.White,
    background = OffWhite,
    onBackground = Color(0xFF1B1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFECECF1),
    onSurfaceVariant = Color(0xFF5A5A66),
    outline = Color(0xFF8C8C99),
    outlineVariant = Color(0xFFD8D8E0),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Purple,
    onPrimary = Color(0xFF1E1633),
    primaryContainer = Color(0xFF3B3168),
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = Orange,
    onSecondary = Color(0xFF3A2400),
    tertiary = Teal,
    onTertiary = Color(0xFF00201E),
    background = Color(0xFF141317),
    onBackground = Color(0xFFE5E1E6),
    surface = Color(0xFF1C1B20),
    onSurface = Color(0xFFE5E1E6),
    surfaceVariant = Color(0xFF35343B),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF908F9A),
    outlineVariant = Color(0xFF45444C),
    error = Color(0xFFFFB4AB),
)

// Wraps the whole app's UI once, near the root. Everything nested inside
// `content` can then read MaterialTheme.colorScheme.xxx and get these colors
// without it being passed down explicitly -- Compose makes it available
// implicitly to every descendant composable.
@Composable
fun BotLisaTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content,
    )
}
