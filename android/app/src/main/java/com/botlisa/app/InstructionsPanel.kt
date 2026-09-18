package com.botlisa.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
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
 * its English caption. Tapping a card speaks the phrase. The first two
 * sections also show a worked example (a chain of speaker bubbles) for
 * [spokenLanguage] -- see InstructionsExamples.kt for where that data comes
 * from. The "next suggestion" section only appears when "what else?" is
 * available in the target language ([showNextSuggestionStep]); the
 * "suggested reply" card is Russian-only ([showAnswerStep]).
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
    onSpeakBubble: (String) -> Unit,
    wordExampleEn: String,
    wordExampleTranslated: String,
    phraseExampleHeard: String,
    phraseExampleHeardGloss: String,
    phraseExampleResponse: String,
    phraseExampleResponseGloss: String,
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
                flow = listOf(
                    FlowItem.CardItem(CardSpec(Icons.Filled.Translate, false, translateTriggerPhrase, TriggerPhraseConfig.TRANSLATE_TRIGGER_EN, onSpeakTranslate)),
                    FlowItem.BubbleItem(wordExampleEn, gloss = null, speaker = Speaker.USER),
                    FlowItem.BubbleItem(wordExampleTranslated, gloss = wordExampleEn, speaker = Speaker.FOX),
                ),
            ),
        )
        if (showNextSuggestionStep) {
            add(
                Section(
                    color = purple,
                    heading = "While you're speaking",
                    body = "Get contextual suggestions on what to say next based on what you just said.",
                    flow = listOf(
                        FlowItem.BubbleItem(phraseExampleHeard, phraseExampleHeardGloss, speaker = Speaker.USER),
                        FlowItem.CardItem(CardSpec(Icons.Filled.Lightbulb, true, nextSuggestionTriggerPhrase, TriggerPhraseConfig.NEXT_SUGGESTION_TRIGGER_EN, onSpeakNext)),
                        FlowItem.BubbleItem(phraseExampleResponse, phraseExampleResponseGloss, speaker = Speaker.FOX),
                    ),
                ),
            )
        }
        add(
            Section(
                color = orange,
                heading = "After hearing $spokenLanguage",
                body = "Ask for help with what someone said or how to respond.",
                flow = buildList {
                    add(FlowItem.CardItem(CardSpec(Icons.AutoMirrored.Filled.MenuBook, false, meaningTriggerPhrase, TriggerPhraseConfig.MEANING_TRIGGER_EN, onSpeakMeaning)))
                    if (showAnswerStep) {
                        add(FlowItem.CardItem(CardSpec(Icons.Filled.QuestionAnswer, true, answerTriggerPhrase, TriggerPhraseConfig.ANSWER_TRIGGER_EN, onSpeakAnswer)))
                    }
                },
            ),
        )
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        // Same grey as the record button's idle fill.
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // A Modifier is a chained list of decorations/behaviors applied
            // in order -- here: take full width, then make the whole row
            // tappable, then add padding.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    null,
                    // Same dark grey as the header text, the mic icon, and
                    // the bubble text/icons below.
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                ) {
                    Text(
                        "How to use voice commands",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Say these phrases in hands-free mode.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(chevronRotation),
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
                        SectionBlock(number = i + 1, section = section, onSpeakBubble = onSpeakBubble)
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
    val flow: List<FlowItem>,
)

private data class CardSpec(
    val icon: ImageVector,
    val sparkle: Boolean,
    val phrase: String,
    val caption: String,
    val onClick: () -> Unit,
)

/** Who's saying a bubble's text -- the caregiver (a person icon) or Lisa (the
 * fox logo). Both render as a grey speech bubble with the icon on its left. */
private enum class Speaker { USER, FOX }

/** One row in a section's vertical flow: a tappable command card or a
 * worked-example bubble. */
private sealed class FlowItem {
    data class CardItem(val spec: CardSpec) : FlowItem()
    data class BubbleItem(val text: String, val gloss: String?, val speaker: Speaker) : FlowItem()
}

@Composable
private fun SectionBlock(number: Int, section: Section, onSpeakBubble: (String) -> Unit) {
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
        section.flow.forEachIndexed { i, item ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            when (item) {
                is FlowItem.CardItem -> CommandCard(item.spec, section.color)
                is FlowItem.BubbleItem -> if (item.speaker == Speaker.FOX) {
                    // Lisa's replies sit on the right, like the other side of
                    // a chat conversation -- the caregiver's own bubbles
                    // (cards, USER examples) stay on the left.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        SpeakerBubble(item.speaker, item.text, item.gloss) { onSpeakBubble(item.text) }
                    }
                } else {
                    SpeakerBubble(item.speaker, item.text, item.gloss) { onSpeakBubble(item.text) }
                }
            }
        }
    }
}

private val SPEAKER_BUBBLE_CORNER = 16.dp
// The corner nearest the speaker avatar -- small instead of fully rounded,
// so that corner alone reads as a "pointer" toward whoever's talking. Same
// device real chat apps (iMessage, WhatsApp, Google Messages) use instead of
// a separate triangular tail glued onto a plain rounded box.
private val SPEAKER_BUBBLE_POINT_CORNER = 4.dp
private val SPEAKER_BUBBLE_GAP = 6.dp
private val SPEAKER_ICON_SIZE = 28.dp
// The fox (Lisa) reads a little larger than the plain person glyph.
private val SPEAKER_FOX_ICON_SIZE = 34.dp

/** A rounded-rect body, fully rounded on three corners and near-square on
 * the fourth -- the bottom corner nearest the speaker avatar, which sits
 * beside it at the bottom ([SpeakerBubble] and [CommandCard] both
 * bottom-align their icon against this corner). On the left edge by
 * default, or the right edge when [tailOnRight] (Lisa's replies, which sit
 * on the right). */
private fun speechBubbleShape(tailOnRight: Boolean = false): Shape =
    if (tailOnRight) {
        RoundedCornerShape(
            topStart = SPEAKER_BUBBLE_CORNER,
            topEnd = SPEAKER_BUBBLE_CORNER,
            bottomEnd = SPEAKER_BUBBLE_POINT_CORNER,
            bottomStart = SPEAKER_BUBBLE_CORNER,
        )
    } else {
        RoundedCornerShape(
            topStart = SPEAKER_BUBBLE_CORNER,
            topEnd = SPEAKER_BUBBLE_CORNER,
            bottomEnd = SPEAKER_BUBBLE_CORNER,
            bottomStart = SPEAKER_BUBBLE_POINT_CORNER,
        )
    }

/** Who said [text] (and its English [gloss]): the caregiver or Lisa. Renders
 * as a real chat-style speech bubble -- rounded on three corners, pointed on
 * the fourth toward a same-toned speaker avatar sitting at its bottom edge --
 * the fox logo (Lisa's own icon, see MainActivity's "Start over" button) or a
 * person glyph. Lisa's bubbles mirror to the right (icon on the right,
 * pointed corner on the right), like the other side of a chat conversation;
 * the caregiver's stay on the left. Tapping the bubble speaks [text] aloud
 * ([onClick]) -- no extra speaker icon here, that's reserved for the
 * trigger-phrase command cards (see CommandCard). */
@Composable
private fun SpeakerBubble(speaker: Speaker, text: String, gloss: String?, onClick: () -> Unit) {
    val isFox = speaker == Speaker.FOX
    val shape = speechBubbleShape(tailOnRight = isFox)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(SPEAKER_BUBBLE_GAP),
    ) {
        if (!isFox) SpeakerIcon(speaker)
        Column(
            modifier = Modifier
                .clip(shape)
                // Halfway between `surface` and the panel's own
                // `surfaceVariant` -- still a tone apart so the bubble reads
                // as its own card, but nowhere near as stark as a flat
                // white/near-black fill sitting on the grey panel.
                .background(bubbleSurfaceColor())
                // Same border as the panel that holds it.
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = bubbleContentColor(),
            )
            if (gloss != null) {
                Text(
                    "($gloss)",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = bubbleContentColor(),
                )
            }
        }
        if (isFox) SpeakerIcon(speaker)
    }
}

/** Mostly the panel's own `surfaceVariant`, leaning just slightly toward
 * `surface` -- the shared fill for [SpeakerBubble] and [SpeakerIcon], soft
 * enough that they read as a gentle step up from the panel rather than a
 * stark white-on-grey (or near-black-on-grey in dark mode) card. */
@Composable
private fun bubbleSurfaceColor(): Color =
    lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceVariant, 0.75f)

/** A lighter grey than `onSurfaceVariant` on its own -- shared by
 * [SpeakerIcon] and [SpeakerBubble]'s text so neither the icons nor their
 * bubble text pull the eye the way a full-strength `onSurfaceVariant` did. */
@Composable
private fun bubbleContentColor(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

/** The fox logo (Lisa) or a person glyph (the caregiver) -- a bare glyph, no
 * background or border, tinted with [bubbleContentColor] (the same lighter
 * grey as the bubble text) so it reads correctly in both light and dark
 * mode without needing its own coloured disc. The fox is sized a little
 * larger than the person glyph. */
@Composable
private fun SpeakerIcon(speaker: Speaker, modifier: Modifier = Modifier) {
    val tint = bubbleContentColor()
    when (speaker) {
        Speaker.USER -> Icon(
            Icons.Filled.Person,
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(SPEAKER_ICON_SIZE),
        )
        Speaker.FOX -> Image(
            painter = painterResource(R.drawable.lisa_fox),
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn),
            modifier = modifier.size(SPEAKER_FOX_ICON_SIZE),
        )
    }
}

// Bolder than SpeakerBubble's own outlineVariant hairline -- a command card
// keeps its section colour, so its outline needs to read clearly against
// that tint.
private val COMMAND_CARD_BORDER = 2.5.dp

/** A trigger phrase the caregiver says -- rendered as a speech bubble
 * pointing at the person avatar at its bottom-left, like the example
 * bubbles, but keeping the section's accent colour as a bold outline + tint
 * so it's still identifiable as a command. Tapping it speaks the phrase in
 * Lisa's voice, as a demo -- a separate affordance from who says it during
 * actual hands-free use. Stops short of the right edge by a speaker icon's
 * width plus the icon-bubble gap -- lining its right edge up with the
 * rounded body of Lisa's reply bubbles on the right (whose icon occupies
 * that same margin), so neither side's bubbles ever reach edge to edge. */
@Composable
private fun CommandCard(card: CardSpec, color: Color) {
    val shape = speechBubbleShape()
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(SPEAKER_BUBBLE_GAP),
        modifier = Modifier
            .fillMaxWidth()
            // Matches the fox icon's (larger) size, not the person glyph's --
            // it's Lisa's reply bubbles on the right this lines up with.
            .padding(end = SPEAKER_FOX_ICON_SIZE + SPEAKER_BUBBLE_GAP),
    ) {
        SpeakerIcon(Speaker.USER)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(color.copy(alpha = 0.08f))
                .border(COMMAND_CARD_BORDER, color.copy(alpha = 0.7f), shape)
                .clickable(onClick = card.onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
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
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
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
            // Marks this bubble specifically as "tap to hear" -- the worked-
            // example bubbles above don't get this icon, only trigger-phrase
            // commands do.
            Icon(
                Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                tint = color,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(18.dp),
            )
        }
    }
}
