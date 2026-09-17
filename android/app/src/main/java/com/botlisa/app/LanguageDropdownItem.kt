package com.botlisa.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
