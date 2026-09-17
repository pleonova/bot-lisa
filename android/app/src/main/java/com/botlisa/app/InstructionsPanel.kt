package com.botlisa.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Collapsible "Use voice commands" card. Tap the header to expand three
 * numbered, colour-coded sections -- stuck speaking (teal), while speaking
 * (purple), after hearing [spokenLanguage] (orange) -- each holding one or
 * two tappable command cards showing the phrase (verbatim from Settings) and
 * its English caption. Tapping a card speaks the phrase. The "next
 * suggestion" section only appears when "what else?" is available in the
 * target language ([showNextSuggestionStep]); the "suggested reply" card is
 * Russian-only ([showAnswerStep]).
 */
@Composable
fun InstructionsPanel(
    // expanded/onToggle follow Compose's "state hoisting" pattern: this
    // composable owns no state of its own, just reads expanded and reports
    // taps via onToggle -- the caller (MainActivity) decides what expanded
    // actually means and remembers it, so this stays a dumb, reusable view.
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
    showAnswerStep: Boolean = true,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "instructions-chevron",
    )
    val teal = MaterialTheme.colorScheme.tertiary
    val purple = MaterialTheme.colorScheme.primary
    val orange = MaterialTheme.colorScheme.secondary

    val sections = buildList {
        add(
            Section(
                color = teal,
                heading = "When you're stuck speaking",
                body = "Say the command, then an English word. I'll translate it into $spokenLanguage.",
                cards = listOf(
                    CardSpec(Icons.Filled.Translate, false, translateTriggerPhrase, TriggerPhraseConfig.TRANSLATE_TRIGGER_EN, onSpeakTranslate),
                ),
            ),
        )
        if (showNextSuggestionStep) {
            add(
                Section(
                    color = purple,
                    heading = "While you're speaking",
                    body = "Get contextual suggestions on what to say next based on what you just said.",
                    cards = listOf(
                        CardSpec(Icons.Filled.Lightbulb, true, nextSuggestionTriggerPhrase, TriggerPhraseConfig.NEXT_SUGGESTION_TRIGGER_EN, onSpeakNext),
                    ),
                ),
            )
        }
        add(
            Section(
                color = orange,
                heading = "After hearing $spokenLanguage",
                body = "Ask for help with what someone said or how to respond.",
                cards = buildList {
                    add(CardSpec(Icons.AutoMirrored.Filled.MenuBook, false, meaningTriggerPhrase, TriggerPhraseConfig.MEANING_TRIGGER_EN, onSpeakMeaning))
                    if (showAnswerStep) {
                        add(CardSpec(Icons.Filled.QuestionAnswer, true, answerTriggerPhrase, TriggerPhraseConfig.ANSWER_TRIGGER_EN, onSpeakAnswer))
                    }
                },
            ),
        )
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // A Modifier is a chained list of decorations/behaviors applied
            // in order -- here: take full width, then make the whole row
            // tappable, then add padding.
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp),
                ) {
                    Text(
                        "Use voice commands",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Say these phrases in hands-free mode.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(chevronRotation),
                )
            }

            // AnimatedVisibility fades/expands its content in and out as
            // `visible` flips, instead of the content just appearing or
            // disappearing instantly.
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    sections.forEachIndexed { i, section ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SectionBlock(number = i + 1, section = section)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
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

private data class Section(
    val color: Color,
    val heading: String,
    val body: String,
    val cards: List<CardSpec>,
)

private data class CardSpec(
    val icon: ImageVector,
    val sparkle: Boolean,
    val phrase: String,
    val caption: String,
    val onClick: () -> Unit,
)

@Composable
private fun SectionBlock(number: Int, section: Section) {
    Column(modifier = Modifier.padding(20.dp)) {
        Row {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(section.color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    number.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    section.heading.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
                Text(
                    section.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        section.cards.forEachIndexed { i, card ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            CommandCard(card, section.color)
        }
    }
}

@Composable
private fun CommandCard(card: CardSpec, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(onClick = card.onClick)
            .padding(14.dp),
    ) {
        Box {
            Icon(card.icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
            if (card.sparkle) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-2).dp)
                        .size(14.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                formatCommand(card.phrase),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(
                formatCommand(card.caption),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
