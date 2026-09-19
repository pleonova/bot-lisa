package com.botlisa.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

/**
 * The big central mic / speaker button -- the primary way to toggle
 * hands-free mode (replaces the old "Start" / "Stop" text button).
 *
 * Colour and icon follow [phase]:
 *   IDLE                -> grey circle, dark grey mic icon (tap to start)
 *   LISTENING_RU        -> light purple circle, dark purple mic, pulsing ring
 *   LISTENING_EN        -> light teal circle, dark teal mic, pulsing ring
 *   SPEAKING_TRANSLATION/READING_RECOMMENDATION
 *                       -> light [speakingCommand]-accent circle, dark
 *                          accent-colored speaker icon, pulsing ring (falls
 *                          back to purple with no speakingCommand)
 *
 * Every non-idle phase fills light and tints the icon dark instead of the
 * reverse, so e.g. tapping "how to say?" turns the button a light teal
 * circle with a dark teal glyph, not a solid teal circle with a white one.
 *
 * [onClick] is wired to MainActivity's onToggleAssistant().
 */
@Composable
fun AssistantButton(
    phase: UiPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Set while a specific voice command is the one currently speaking --
    // see UiPhase.buttonFillColor()'s own doc for how this takes over the
    // fill color (teal/purple/orange) instead of the generic purple.
    speakingCommand: CommandKind? = null,
) {
    val listening = phase == UiPhase.LISTENING_RU || phase == UiPhase.LISTENING_EN
    val speaking = phase == UiPhase.SPEAKING_TRANSLATION || phase == UiPhase.READING_RECOMMENDATION
    val active = listening || speaking

    // The phase's "true" color (teal/purple/accent) -- while active, this
    // now drives the ICON and a light tint of the fill, inverted from the
    // old solid-fill/white-icon scheme: e.g. tapping "how to say?" turns the
    // button a light teal circle with a dark teal mic/speaker glyph, rather
    // than a solid teal circle with a white one.
    val accent = phase.buttonFillColor(speakingCommand)
    val fill = if (phase == UiPhase.IDLE) accent else accent.copy(alpha = 0.18f)
    val contentColor = if (phase == UiPhase.IDLE) MaterialTheme.colorScheme.onSurfaceVariant else accent

    // rememberInfiniteTransition + animateFloat drive a value that loops
    // forever (grow, shrink, repeat) without any manual timers or callbacks;
    // `remember` is what makes it survive recomposition instead of
    // restarting from scratch on every redraw.
    val transition = rememberInfiniteTransition(label = "assistant-pulse")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.35f else 1f,
        animationSpec = infiniteRepeatable(tween(950, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ring-scale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = if (active) 0.30f else 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(950, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ring-alpha",
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(120.dp)) {
        if (active) {
            // Modifier chain reads top-to-bottom: fix the size, scale it up
            // for the pulse, clip to a circle, then tint it -- each step
            // wraps the one before it.
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .scale(ringScale)
                    .clip(CircleShape)
                    // The ring pulses in the full-strength accent color, not
                    // the light fill -- a ring tinted at 18% of an already
                    // near-transparent alpha would barely register.
                    .background(accent.copy(alpha = ringAlpha)),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(fill)
                // Grey outline while idle -- same outline as the
                // voice-commands panel, so the two line up. A bolder accent-
                // colored outline once listening/speaking, now that the
                // fill itself is just a light tint -- without it the button
                // reads as a plain pale circle instead of clearly teal/
                // purple/accent-colored.
                .then(
                    if (phase == UiPhase.IDLE) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    } else {
                        Modifier.border(2.dp, accent, CircleShape)
                    },
                )
                .clickable(onClick = onClick),
        ) {
            Icon(
                imageVector = if (speaking) Icons.AutoMirrored.Filled.VolumeUp else Icons.Filled.Mic,
                contentDescription = if (phase == UiPhase.IDLE) "Start hands-free mode" else "Stop hands-free mode",
                tint = contentColor,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}
