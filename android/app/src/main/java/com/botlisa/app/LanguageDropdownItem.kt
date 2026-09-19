package com.botlisa.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One row of a target-language dropdown -- shared by SettingsScreen's
 * LanguagePicker and IntroScreen's InlineLanguagePicker so both render the
 * exact same look (light purple fill + bold purple text for whichever
 * language is currently selected) rather than two hand-copied
 * implementations slowly drifting apart. Each caller still owns its own
 * Popup/Surface positioning -- only the row itself is shared.
 */
@Composable
fun LanguageDropdownItem(
    language: TargetLanguage,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        language.displayName,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * A thin thumb along the right edge, sized/positioned from [state] -- the
 * Compose Foundation `verticalScroll` this decorates has no scrollbar of its
 * own, so with 11+ languages in [SupportedLanguages.ALL] neither dropdown
 * (SettingsScreen's LanguagePicker, IntroScreen's InlineLanguagePicker) gave
 * any visible hint that the list kept going below the fold. Drawn on top of
 * the already-scrolled content rather than composing another element, so it
 * doesn't participate in layout/scrolling itself.
 */
@Composable
internal fun Modifier.languageListScrollbar(state: ScrollState): Modifier {
    // Bold primary-color thumb (not a subtle grey) on a faint full-height
    // track -- a first pass at onSurfaceVariant/0.4 blended into the
    // dropdown's own surfaceVariant background closely enough that it went
    // unnoticed; the track gives the thumb something to visibly sit inside
    // even when it's short.
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    return drawWithContent {
        drawContent()
        if (state.maxValue <= 0) return@drawWithContent
        val trackHeight = size.height
        val contentHeight = trackHeight + state.maxValue
        val thumbHeight = (trackHeight * trackHeight / contentHeight).coerceAtLeast(24.dp.toPx())
        val thumbTop = (trackHeight - thumbHeight) * (state.value.toFloat() / state.maxValue)
        val barWidth = 5.dp.toPx()
        val barRight = size.width - 3.dp.toPx()
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(barRight - barWidth, 0f),
            size = Size(barWidth, trackHeight),
            cornerRadius = CornerRadius(barWidth / 2),
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(barRight - barWidth, thumbTop),
            size = Size(barWidth, thumbHeight),
            cornerRadius = CornerRadius(barWidth / 2),
        )
    }
}
