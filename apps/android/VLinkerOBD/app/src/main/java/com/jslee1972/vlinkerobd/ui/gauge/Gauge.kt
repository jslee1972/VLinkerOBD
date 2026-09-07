package com.jslee1972.vlinkerobd.ui.gauge

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jslee1972.vlinkerobd.ui.theme.GaugeNumeralStyle
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START_ANGLE_DEGREES = 150f
private const val SWEEP_ANGLE_DEGREES = 240f
private const val TICK_COUNT = 9

/**
 * Analog instrument-cluster style gauge drawn with Compose Canvas (no external gauge library):
 * a tick-marked arc, an optional redline segment, a needle, and the live value as a big digital
 * readout inside the gauge face — matching the dark-cluster look used across the dashboard.
 */
@Composable
fun Gauge(
    value: Float,
    minValue: Float,
    maxValue: Float,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    redlineStart: Float? = null,
    trendContent: (@Composable () -> Unit)? = null,
) {
    val animatedValue by animateFloatAsState(targetValue = value, label = "gauge-$label")
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val needleColor = MaterialTheme.colorScheme.primary
    val needleGlowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val redlineColor = MaterialTheme.colorScheme.error

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                val strokeWidth = size.minDimension * 0.07f
                val radius = (size.minDimension - strokeWidth) / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)

                drawArc(
                    color = trackColor,
                    startAngle = START_ANGLE_DEGREES,
                    sweepAngle = SWEEP_ANGLE_DEGREES,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )

                if (redlineStart != null && redlineStart < maxValue) {
                    val redStart = GaugeMath.valueToAngleDegrees(redlineStart, minValue, maxValue, START_ANGLE_DEGREES, SWEEP_ANGLE_DEGREES)
                    drawArc(
                        color = redlineColor,
                        startAngle = redStart,
                        sweepAngle = (START_ANGLE_DEGREES + SWEEP_ANGLE_DEGREES) - redStart,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }

                // Tick marks radiating just inside the arc track.
                val tickOuter = radius - strokeWidth * 0.75f
                val tickInner = radius - strokeWidth * 1.6f
                for (i in 0..TICK_COUNT) {
                    val fraction = i / TICK_COUNT.toFloat()
                    val angle = Math.toRadians((START_ANGLE_DEGREES + fraction * SWEEP_ANGLE_DEGREES).toDouble())
                    val cosA = cos(angle).toFloat()
                    val sinA = sin(angle).toFloat()
                    drawLine(
                        color = tickColor,
                        start = Offset(center.x + tickInner * cosA, center.y + tickInner * sinA),
                        end = Offset(center.x + tickOuter * cosA, center.y + tickOuter * sinA),
                        strokeWidth = strokeWidth * 0.18f,
                        cap = StrokeCap.Round,
                    )
                }

                val angleDegrees = GaugeMath.valueToAngleDegrees(animatedValue, minValue, maxValue, START_ANGLE_DEGREES, SWEEP_ANGLE_DEGREES)
                val angleRadians = Math.toRadians(angleDegrees.toDouble())
                val needleLength = radius * 0.82f
                val needleEnd = Offset(
                    x = center.x + (needleLength * cos(angleRadians)).toFloat(),
                    y = center.y + (needleLength * sin(angleRadians)).toFloat(),
                )
                // Soft glow pass (a wider, translucent line) behind the crisp needle line.
                drawLine(
                    color = needleGlowColor,
                    start = center,
                    end = needleEnd,
                    strokeWidth = strokeWidth * 0.9f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = needleColor,
                    start = center,
                    end = needleEnd,
                    strokeWidth = strokeWidth * 0.3f,
                    cap = StrokeCap.Round,
                )
                drawCircle(color = needleColor, radius = strokeWidth * 0.45f, center = center)
            }

            val displayValue = if (animatedValue.isNaN()) "--" else animatedValue.roundToInt().toString()
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(text = displayValue, style = GaugeNumeralStyle, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        trendContent?.invoke()

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
