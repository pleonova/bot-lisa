package com.botlisa.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Intro overlay, shown on first launch (see IntroConfig.hasSeenIntro) and
 * again any time the fox logo is tapped (LisaScreen.resetToStart()). A
 * dimmed scrim over the real home screen with a floating card on top --
 * not a Material AlertDialog (there's no existing dialog chrome in this
 * app to match), just a Box + Surface built the same way so the app
 * underneath stays visible rather than being replaced outright. Dismissed
 * by tapping the scrim OR the card's own background (hence the .clickable
 * on both), with the interactive rows inside consuming their own taps so
 * picking a language/audience doesn't also dismiss it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntroScreen(
    targetLanguage: TargetLanguage,
    onTargetLanguageChange: (TargetLanguage) -> Unit,
    audience: AudienceConfig.Audience,
    onAudienceChange: (AudienceConfig.Audience) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    "Hi, I'm Lisa",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )

                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Text("Talk freely and I'll fill in the ", style = MaterialTheme.typography.bodyLarge)
                    InlineLanguagePicker(targetLanguage, onTargetLanguageChange)
                    Text(" when you get stuck.", style = MaterialTheme.typography.bodyLarge)
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Who are you talking to?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    AudienceOption(
                        selected = audience == AudienceConfig.Audience.BABY,
                        label = "A baby",
                        caption = "soft, simple phrases a little one can absorb",
                        onClick = { onAudienceChange(AudienceConfig.Audience.BABY) },
                    )
                    AudienceOption(
                        selected = audience == AudienceConfig.Audience.ADULT,
                        label = "An adult",
                        caption = "everyday, natural phrasing",
                        onClick = { onAudienceChange(AudienceConfig.Audience.ADULT) },
                    )
                }

                Text(
                    "Tap anywhere to start · tap the fox anytime to reset page · go to settings to update language.",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AudienceOption(
    selected: Boolean,
    label: String,
    caption: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
                append(" — $caption")
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/**
 * Inline "[language] ▾" picker embedded mid-sentence -- same Popup-anchored
 * dropdown as SettingsScreen's LanguagePicker (Material3's
 * ExposedDropdownMenu flips upward when it doesn't measure room below), just
 * styled as bold underlined text instead of an outlined field so it reads
 * inline with the surrounding sentence.
 */
@Composable
private fun InlineLanguagePicker(targetLanguage: TargetLanguage, onChange: (TargetLanguage) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var anchorHeightPx by remember { mutableStateOf(0) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .onGloballyPositioned { anchorHeightPx = it.size.height }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded },
        ) {
            Text(
                targetLanguage.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.Underline,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, anchorHeightPx),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    tonalElevation = 0.dp,
                    shadowElevation = 3.dp,
                ) {
                    Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        SupportedLanguages.ALL.forEach { language ->
                            DropdownMenuItem(
                                text = { Text(language.displayName, style = MaterialTheme.typography.bodyLarge) },
                                onClick = {
                                    onChange(language)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
