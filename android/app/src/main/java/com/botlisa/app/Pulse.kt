package com.botlisa.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * A gentle repeating scale pulse while [active], used on the result card's
 * speaker icons to show which line is being read aloud. When [active] is
 * false the transition settles at scale 1 and does nothing.
 */
fun Modifier.pulse(active: Boolean): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.18f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )
    Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
}
