package com.botlisa.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The two voice-command reminders -- teal "Как сказать?" (translate) and
 * orange "Что ещё?" (next suggestion). Read-only in every mode: an icon disc
 * + the coloured phrase + its English caption, just a mnemonic for what to
 * say.
 *
 * MainActivity decides when they're [visible]:
 * - hands-free: shown until a command is used, back on the next new input;
 * - text mode: shown only while the field is empty -- once you type, the
 *   keyboard's Search key does the same job, so they'd be redundant.
 */
@Composable
fun CommandChips(
    visible: Boolean,
    translatePhrase: String,
    translateCaption: String,
    nextPhrase: String,
    nextCaption: String,
    modifier: Modifier = Modifier,
    // The next-suggestion command only works for Russian; hidden otherwise
    // (see relatedPhrasesSupported in MainActivity).
    showNextCommand: Boolean = true,
) {
    AnimatedVisibility(visible = visible, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (showNextCommand) {
                Arrangement.spacedBy(12.dp)
            } else {
                Arrangement.Center
            },
        ) {
            CommandItem(
                color = MaterialTheme.colorScheme.tertiary,
                icon = Icons.AutoMirrored.Filled.Chat,
                phrase = translatePhrase,
                caption = translateCaption,
                modifier = if (showNextCommand) Modifier.weight(1f) else Modifier,
            )
            if (showNextCommand) {
                CommandItem(
                    color = MaterialTheme.colorScheme.secondary,
                    icon = Icons.Filled.Lightbulb,
                    phrase = nextPhrase,
                    caption = nextCaption,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CommandItem(
    color: Color,
    icon: ImageVector,
    phrase: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
        Text(
            formatCommand(phrase),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
        )
        Text(
            // Same capitalisation + "?" treatment as the phrase above it, so
            // the English caption mirrors the trigger-phrase line above it.
            formatCommand(caption),
            style = MaterialTheme.typography.labelSmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Presentational only: "как сказать" -> "Как сказать?". Callers keep the raw string for matching. */
private fun formatCommand(phrase: String): String {
    val trimmed = phrase.trim()
    if (trimmed.isEmpty()) return trimmed
    val capitalised = trimmed.replaceFirstChar { it.uppercaseChar() }
    return if (capitalised.endsWith("?")) capitalised else "$capitalised?"
}
