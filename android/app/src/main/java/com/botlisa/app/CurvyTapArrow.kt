package com.botlisa.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small hand-drawn-style curvy arrow, tail near the bottom (beside the
 * record-button subtitle) sweeping up to a point near the top (beside the
 * button itself) -- drawn to sit just to the right of that button+text
 * block (see its caller in MainActivity's LisaScreen) as a "tap here" cue
 * whenever the text below actually says "Tap" (see showTapArrow). [alpha]
 * is driven by the same idleHintPulse the text itself pulses with, so the
 * two stay in sync instead of the arrow just sitting there static.
 */
@Composable
fun CurvyTapArrow(color: Color, alpha: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 44.dp, height = 150.dp)) {
        val w = size.width
        val h = size.height
        // Tail sits low and slightly right (roughly level with the text),
        // the curve bows WAY out to the right through the middle (control1
        // past the canvas's own right edge) for a real hook rather than a
        // gentle bend, and the tip ends close to the canvas's left edge, near
        // the button's own vertical center (not its top edge -- the button
        // occupies roughly the top 70% of this whole block, so its center
        // lands around 0.35 of the way down).
        val tail = Offset(w * 0.68f, h * 0.92f)
        val control1 = Offset(w * 1.35f, h * 0.60f)
        val control2 = Offset(w * 0.55f, h * 0.42f)
        val tip = Offset(w * 0.02f, h * 0.34f)
        val path = Path().apply {
            moveTo(tail.x, tail.y)
            cubicTo(control1.x, control1.y, control2.x, control2.y, tip.x, tip.y)
        }
        val strokeWidth = 3.dp.toPx()
        drawPath(path, color = color, alpha = alpha, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))

        // Arrowhead: two short strokes back from the tip, angled off the
        // curve's own approach direction (control2 -> tip) so it reads as
        // pointing the way the line is actually travelling.
        val approachAngle = atan2(tip.y - control2.y, tip.x - control2.x)
        val headLength = 11.dp.toPx()
        val headSpread = Math.toRadians(30.0).toFloat()
        for (sign in floatArrayOf(-1f, 1f)) {
            val angle = approachAngle + sign * headSpread
            val end = Offset(tip.x - headLength * cos(angle), tip.y - headLength * sin(angle))
            drawLine(color = color, start = tip, end = end, strokeWidth = strokeWidth, alpha = alpha, cap = StrokeCap.Round)
        }
    }
}
