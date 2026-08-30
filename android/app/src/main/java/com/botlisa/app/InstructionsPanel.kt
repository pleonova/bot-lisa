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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
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
 * expand a numbered walkthrough; each command phrase is shown in its accent
 * colour, verbatim from Settings, and tapping a step speaks the phrase. The
 * two related-phrase steps (next suggestion, suggested reply) only appear
 * for Russian ([showNextSuggestionStep]).
 */
@Composable
fun InstructionsPanel(
    expanded: Boolean,
    onToggle: () -> Unit,
    spokenLanguage: String,
    translateTriggerPhrase: String,
    meaningTriggerPhrase: String,
    nextSuggestionTriggerPhrase: String,
    answerTriggerPhrase: String,
    onSpeakTranslate: () -> Unit,
    onSpeakMeaning: () -> Unit,
    onSpeakNext: () -> Unit,
    onSpeakAnswer: () -> Unit,
    modifier: Modifier = Modifier,
    showNextSuggestionStep: Boolean = true,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "instructions-chevron",
    )
    val primary = MaterialTheme.colorScheme.primary
    val teal = MaterialTheme.colorScheme.tertiary
    val orange = MaterialTheme.colorScheme.secondary
    val divider = MaterialTheme.colorScheme.outlineVariant

    fun say(phrase: String, color: Color, tail: String = "") = buildAnnotatedString {
        append("Say ")
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(phrase) }
        if (tail.isNotEmpty()) append(tail)
    }

    val steps = buildList {
        add(
            StepSpec(
                Icons.Filled.Mic, primary, "Tap the mic",
                AnnotatedString(
                    if (showNextSuggestionStep) "Then speak $spokenLanguage normally."
                    else "Then say a command below.",
                ),
                null,
            ),
        )
        add(
            StepSpec(
                Icons.Filled.Translate, teal, "To translate a word into $spokenLanguage",
                say(translateTriggerPhrase, teal, ", then the English word after the beep and wait for the $spokenLanguage translation."),
                onSpeakTranslate,
            ),
        )
        if (showNextSuggestionStep) {
            add(
                StepSpec(
                    Icons.Filled.Lightbulb, orange, "To hear the next suggestion",
                    say(nextSuggestionTriggerPhrase, orange, " to cycle through more phrases."),
                    onSpeakNext,
                ),
            )
        }
        add(
            StepSpec(
                Icons.AutoMirrored.Filled.MenuBook, teal, "To hear what a phrase means",
                say(meaningTriggerPhrase, teal, ", for an English translation of what was just said."),
                onSpeakMeaning,
            ),
        )
        if (showNextSuggestionStep) {
            add(
                StepSpec(
                    Icons.Filled.QuestionAnswer, orange, "To hear a suggested reply",
                    say(answerTriggerPhrase, orange, " for phrases you could say back."),
                    onSpeakAnswer,
                ),
            )
        }
    }

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
                    steps.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = divider)
                        Step(
                            number = i + 1,
                            icon = s.icon,
                            iconColor = s.iconColor,
                            heading = s.heading,
                            body = s.body,
                            onClick = s.onClick,
                        )
                    }
                    HorizontalDivider(color = divider)
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

private data class StepSpec(
    val icon: ImageVector,
    val iconColor: Color,
    val heading: String,
    val body: AnnotatedString,
    val onClick: (() -> Unit)?,
)

@Composable
private fun Step(
    number: Int,
    icon: ImageVector,
    iconColor: Color,
    heading: String,
    body: AnnotatedString,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
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
