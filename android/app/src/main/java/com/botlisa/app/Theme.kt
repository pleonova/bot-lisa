package com.botlisa.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * App-wide Compose theme -- the "Assistant Lisa" palette from the wireframe
 * legend (see android/UI_REDESIGN_PLAN.md). Replaces the bare
 * `MaterialTheme {}` the app used before, which fell back to Material 3's
 * default purple.
 *
 * Per UI_REDESIGN_PLAN.md section 8, only the light scheme is tuned for now;
 * the dark scheme is defined (so dark-mode devices don't get the M3 baseline
 * purple) but not yet designed.
 */

private val Purple = Color(0xFF8F7FEE)   // primary  -- title, active mic, translation speaker
private val Orange = Color(0xFFFE9F4D)   // secondary -- "Что ещё?" / phrase being read
private val Teal = Color(0xFF2CB3AE)     // tertiary  -- "Как сказать?" / listening-for-word
private val OffWhite = Color(0xFFF7F7FA) // window / surface background

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    secondary = Orange,
    onSecondary = Color.White,
    tertiary = Teal,
    onTertiary = Color.White,
    background = OffWhite,
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Purple,
    secondary = Orange,
    tertiary = Teal,
)

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
