package com.botlisa.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The two voice-command items (teal "translate" trigger, orange
 * "next-suggestion" trigger).
 *
 * - [CommandChipsMode.BUTTONS]   -- text mode: tappable buttons.
 * - [CommandChipsMode.REMINDERS] -- hands-free mode: a read-only
 *   "Quick reminders:" card, just a mnemonic for what to say.
 *
 * Visibility (the [visible] flag) is driven by MainActivity: shown by
 * default, hidden once a command is used, back on the next new input.
 *
 * Captions ([translateCaption] / [nextCaption]) are passed in -- step 6b
 * will feed them an on-device targetLanguage -> English translation of the
 * phrase; for now they're static fallbacks.
 */
enum class CommandChipsMode { BUTTONS, REMINDERS }

@Composable
fun CommandChips(
    mode: CommandChipsMode,
    visible: Boolean,
    translatePhrase: String,
    translateCaption: String,
    nextPhrase: String,
    nextCaption: String,
    onTranslate: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    // BUTTONS mode only. The buttons act on the text field, so each is
    // enabled only when the field holds text of the matching kind -- Latin
    // for translate, Cyrillic for suggestions (mirrors the backend's
    // script-based mode detection). [hint] shows under the row when neither
    // applies (empty field).
    translateEnabled: Boolean = true,
    nextEnabled: Boolean = true,
    hint: String? = null,
) {
    val teal = MaterialTheme.colorScheme.tertiary
    val orange = MaterialTheme.colorScheme.secondary

    AnimatedVisibility(visible = visible, modifier = modifier) {
        when (mode) {
            CommandChipsMode.BUTTONS -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CommandButton(teal, translatePhrase, translateCaption, onTranslate, translateEnabled, Modifier.weight(1f))
                    CommandButton(orange, nextPhrase, nextCaption, onNext, nextEnabled, Modifier.weight(1f))
                }
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            CommandChipsMode.REMINDERS -> Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "Quick reminders:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    ) {
                        ReminderItem(teal, translatePhrase, translateCaption, Modifier.weight(1f))
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        ReminderItem(orange, nextPhrase, nextCaption, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandButton(
    color: Color,
    phrase: String,
    caption: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp),
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatCommand(phrase), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic)
        }
    }
}

@Composable
private fun ReminderItem(
    color: Color,
    phrase: String,
    caption: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = color)
        Text(
            formatCommand(phrase),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
        )
        Text(
            caption,
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
