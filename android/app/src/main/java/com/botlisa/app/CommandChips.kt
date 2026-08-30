package com.botlisa.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Which voice command an on-screen reminder chip stands for. */
enum class CommandKind { TRANSLATE, MEANING, NEXT_SUGGESTION, ANSWER }

/** One reminder chip: [kind] fixes its icon + colour, [phrase]/[caption] the labels. */
data class CommandChipSpec(
    val kind: CommandKind,
    val phrase: String,
    val caption: String,
    val onSpeak: () -> Unit,
)

/**
 * The voice-command reminders on the home screen -- an icon + the coloured
 * trigger phrase + its English caption. Read-only mnemonics; tapping one
 * speaks its phrase aloud (`onSpeak`). MainActivity builds the [items] list
 * (2 for non-Russian targets, 4 for Russian) and controls [visible].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandChips(
    visible: Boolean,
    items: List<CommandChipSpec>,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible && items.isNotEmpty(), modifier = modifier) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 2,
        ) {
            items.forEach { spec ->
                CommandItem(spec, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CommandItem(spec: CommandChipSpec, modifier: Modifier = Modifier) {
    val color = when (spec.kind) {
        CommandKind.TRANSLATE, CommandKind.MEANING -> MaterialTheme.colorScheme.tertiary // teal
        CommandKind.NEXT_SUGGESTION, CommandKind.ANSWER -> MaterialTheme.colorScheme.secondary // orange
    }
    val icon: ImageVector = when (spec.kind) {
        CommandKind.TRANSLATE -> Icons.Filled.Translate
        CommandKind.MEANING -> Icons.AutoMirrored.Filled.MenuBook
        CommandKind.NEXT_SUGGESTION -> Icons.Filled.Lightbulb
        CommandKind.ANSWER -> Icons.Filled.QuestionAnswer
    }
    Column(
        // Tap to hear the phrase spoken.
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = spec.onSpeak)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
        Text(
            formatCommand(spec.phrase),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
        )
        Text(
            // Same capitalisation + "?" treatment as the phrase above it.
            formatCommand(spec.caption),
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
