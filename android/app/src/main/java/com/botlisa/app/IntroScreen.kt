package com.botlisa.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
 * only via the scrim (tapping outside the card) or the explicit "Let's go"
 * button -- unlike the first version of this screen, the card body itself
 * is no longer a dismiss target, so a mistap near the audience rows can't
 * close it before the caregiver's made a choice.
 */
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
        var cardCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .onGloballyPositioned { cardCoordinates = it },
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Hi, I'm Lisa",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Column {
                    // Two fixed lines, not one wrapping paragraph -- so the
                    // language name (varying from "Hindi" to "Marathi") never
                    // changes how many words fit on the first line and shifts
                    // the whole card's layout around depending which is
                    // selected. The language always opens line two instead.
                    Text("Talk naturally and I'll help with your", style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InlineLanguagePicker(targetLanguage, onTargetLanguageChange, cardCoordinates)
                        Text(" when you get stuck.", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Who are you talking to?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    AudienceOption(
                        selected = audience == AudienceConfig.Audience.BABY,
                        icon = Icons.Filled.Face,
                        label = "A baby",
                        caption = "Soft, simple phrases",
                        onClick = { onAudienceChange(AudienceConfig.Audience.BABY) },
                    )
                    AudienceOption(
                        selected = audience == AudienceConfig.Audience.ADULT,
                        icon = Icons.Filled.People,
                        label = "An adult",
                        caption = "Everyday, natural phrasing",
                        onClick = { onAudienceChange(AudienceConfig.Audience.ADULT) },
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Filled.Headset,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "Works best with one headphone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("Let's go", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun AudienceOption(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    caption: String,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val avatarColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val iconTint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        RoundedCornerShape(16.dp),
                    )
                },
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(avatarColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Inline "[language] ▾" picker embedded mid-sentence -- same Popup-anchored
 * dropdown as SettingsScreen's LanguagePicker (Material3's
 * ExposedDropdownMenu flips upward when it doesn't measure room below), just
 * styled as bold underlined text instead of an outlined field so it reads
 * inline with the surrounding sentence.
 *
 * The dropdown itself is sized and positioned to match the intro card, not
 * the (much narrower) inline text it hangs off of: cardCoordinates -- the
 * card Surface's own onGloballyPositioned result, from the caller -- gives
 * both its width and its window position, so the popup can be widened to
 * match and shifted left by however far this text sits from the card's left
 * edge. That gap is fixed today (the caller always opens its own line with
 * this picker -- see the two-line Column in IntroScreen), but computing it
 * from actual layout coordinates rather than hardcoding it means this still
 * holds if that sentence is ever restructured again.
 */
@Composable
private fun InlineLanguagePicker(
    targetLanguage: TargetLanguage,
    onChange: (TargetLanguage) -> Unit,
    cardCoordinates: LayoutCoordinates?,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .onGloballyPositioned { anchorCoordinates = it }
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
        val card = cardCoordinates
        val anchor = anchorCoordinates
        if (expanded && card != null && anchor != null) {
            val xOffsetPx = (card.positionInWindow().x - anchor.positionInWindow().x).toInt()
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(xOffsetPx, anchor.size.height),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    modifier = Modifier.width(with(density) { card.size.width.toDp() }),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.extraSmall,
                    tonalElevation = 0.dp,
                    shadowElevation = 3.dp,
                ) {
                    Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        SupportedLanguages.ALL.forEach { language ->
                            LanguageDropdownItem(
                                language = language,
                                selected = language.code == targetLanguage.code,
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
