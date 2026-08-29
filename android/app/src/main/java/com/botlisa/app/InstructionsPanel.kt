package com.botlisa.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Collapsible "Hands-free mode instructions" strip -- a lavender pill you tap
 * to expand the how-to copy. Replaces the always-on paragraph that used to
 * sit in the "Lisa Assistant" card.
 *
 * The two voice-command phrases are shown in bold and come straight from the
 * caregiver's Settings (translate / next-suggestion trigger phrases), so a
 * customised phrase shows here too.
 */
@Composable
fun InstructionsPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    translateTriggerPhrase: String,
    nextSuggestionTriggerPhrase: String,
    modifier: Modifier = Modifier,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "instructions-chevron",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Hands-free mode instructions",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                )
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(chevronRotation),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Text(
                text = instructionsText(translateTriggerPhrase, nextSuggestionTriggerPhrase),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

private fun instructionsText(translatePhrase: String, nextPhrase: String) = buildAnnotatedString {
    append("Hands-free mode:\n")
    append("•  Tap the mic, then speak Russian normally\n")
    append("•  To translate a word: say ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(translatePhrase) }
    append(", pause and wait for the beep, then say the English word\n")
    append("•  To hear the next suggestion: say ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(nextPhrase) }
    append("\n\nTo edit voice commands, go to settings.")
}
