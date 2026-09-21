package com.botlisa.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
 * (2 base commands, plus "what else?" when on-device suggestions are
 * available for the target language, plus "how to answer?" for Russian).
 * Persistent once there's anything to show -- these used to hide during the
 * "how to say?" word-capture phase, which read as "the buttons vanished".
 */
// FlowRow (below) is still marked experimental by Compose, so using it
// requires explicitly opting in -- this doesn't change behavior, it just
// acknowledges the API could still change in a future Compose release.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandChips(
    items: List<CommandChipSpec>,
    modifier: Modifier = Modifier,
) {
    // FlowRow lays children left-to-right and wraps to a new line once a row
    // fills up (like CSS flex-wrap) -- unlike Row, which would just overflow
    // or squeeze everything onto one line.
    AnimatedVisibility(visible = items.isNotEmpty(), modifier = modifier) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = 2,
        ) {
            items.forEach { spec ->
                CommandItem(spec, modifier = Modifier.weight(1f))
            }
            // Keep every chip half-width: with an odd count the last chip
            // would otherwise stretch across the whole row and its icon
            // would sit centre-screen instead of lining up with the column
            // above it. A spacer fills the empty half so "what else?" lands
            // in the same spot whether or not "how to answer?" follows it.
            if (items.size % 2 == 1) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Each command's own accent color -- teal for translate, purple for
 * next-suggestion, orange for meaning, rust (a darker shade of orange) for
 * answer. Shared by [CommandItem] below and (so the record button and its
 * hint text can match whichever command is currently speaking) MainActivity's
 * own UiPhase.buttonFillColor().
 */
@Composable
fun CommandKind.accentColor(): Color = when (this) {
    CommandKind.TRANSLATE -> MaterialTheme.colorScheme.tertiary // teal
    CommandKind.NEXT_SUGGESTION -> MaterialTheme.colorScheme.primary // purple
    CommandKind.MEANING -> MaterialTheme.colorScheme.secondary // orange
    CommandKind.ANSWER -> Rust
}

@Composable
private fun CommandItem(spec: CommandChipSpec, modifier: Modifier = Modifier) {
    val color = spec.kind.accentColor()
    val icon: ImageVector = when (spec.kind) {
        CommandKind.TRANSLATE -> Icons.Filled.Translate
        CommandKind.MEANING -> Icons.AutoMirrored.Filled.MenuBook
        CommandKind.NEXT_SUGGESTION -> Icons.Filled.Lightbulb
        CommandKind.ANSWER -> Icons.Filled.QuestionAnswer
    }
    // "What else?" / "How to answer?" get a sparkle badge (in the command's
    // own color -- see below) -- these are the AI-powered suggestion
    // commands, so flag them the way the tagline does.
    val sparkle = spec.kind == CommandKind.NEXT_SUGGESTION || spec.kind == CommandKind.ANSWER
    Column(
        // Tap to hear the phrase spoken.
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = spec.onSpeak)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Same solid-fill-circle-with-white-icon look as the record button
        // itself (see AssistantButton) -- a real button instead of a bare
        // tinted icon, so these read as the same kind of tappable control.
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            if (sparkle) {
                // A small white backdrop of its own -- guarantees contrast
                // for the badge regardless of what's behind it (the solid
                // accent circle in one direction, the plain page background
                // in the other), rather than risking the badge blending
                // into a same-colored circle if tinted to match it.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            formatCommand(spec.phrase),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
        )
        Text(
            // Same capitalisation + "?" treatment as the phrase above it.
            // Same light grey as the instructions panel's own translations.
            formatCommand(spec.caption),
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = bubbleContentColor(),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Presentational only: "как сказать" -> "Как сказать?". Callers keep the raw
 * string for matching. Shared with [InstructionsPanel]'s command cards.
 */
internal fun formatCommand(phrase: String): String {
    val trimmed = phrase.trim()
    if (trimmed.isEmpty()) return trimmed
    val capitalised = trimmed.replaceFirstChar { it.uppercaseChar() }
    return if (capitalised.endsWith("?")) capitalised else "$capitalised?"
}
