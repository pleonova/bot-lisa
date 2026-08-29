package com.botlisa.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Collapsible "How hands-free mode works" card. Tap the lavender header to
 * expand a three-step walkthrough; the two command phrases are shown in
 * their accent colour and come straight from Settings, so a customised
 * phrase (and its punctuation) shows here verbatim.
 */
@Composable
fun InstructionsPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    spokenLanguage: String,
    translateTriggerPhrase: String,
    nextSuggestionTriggerPhrase: String,
    modifier: Modifier = Modifier,
    // Step 3 (next-suggestion) only applies where suggestions exist -- Russian.
    showNextSuggestionStep: Boolean = true,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "instructions-chevron",
    )
    val teal = MaterialTheme.colorScheme.tertiary
    val orange = MaterialTheme.colorScheme.secondary

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "How hands-free mode works",
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

            AnimatedVisibility(visible = expanded) {
                Column {
                    Step(
                        number = 1,
                        icon = Icons.Filled.Mic,
                        iconColor = MaterialTheme.colorScheme.primary,
                        heading = "Tap the mic",
                        body = AnnotatedString("Then speak $spokenLanguage normally."),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Step(
                        number = 2,
                        icon = Icons.AutoMirrored.Filled.Chat,
                        iconColor = teal,
                        heading = "To translate a word",
                        body = buildAnnotatedString {
                            append("Say ")
                            withStyle(SpanStyle(color = teal, fontWeight = FontWeight.Bold)) {
                                append(translateTriggerPhrase)
                            }
                            append(", pause and wait for the beep, then say the English word.")
                        },
                    )
                    if (showNextSuggestionStep) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Step(
                            number = 3,
                            icon = Icons.Filled.Lightbulb,
                            iconColor = orange,
                            heading = "To hear the next suggestion",
                            body = buildAnnotatedString {
                                append("Say ")
                                withStyle(SpanStyle(color = orange, fontWeight = FontWeight.Bold)) {
                                    append(nextSuggestionTriggerPhrase)
                                }
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            Icons.Filled.Settings,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            buildAnnotatedString {
                                append("To edit voice commands, go to ")
                                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("settings") }
                                append(".")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Step(
    number: Int,
    icon: ImageVector,
    iconColor: Color,
    heading: String,
    body: AnnotatedString,
) {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Icon(icon, null, tint = iconColor, modifier = Modifier.padding(top = 2.dp))
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                "$number. $heading",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
