package com.jslee1972.vlinkerobd.ui.gauge

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START_ANGLE_DEGREES = 150f
private const val SWEEP_ANGLE_DEGREES = 240f

/** Analog needle gauge (speed/RPM style) drawn with Compose Canvas — no external gauge library. */
@Composable
fun Gauge(
    value: Float,
    minValue: Float,
    maxValue: Float,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    redlineStart: Float? = null,
) {
    val animatedValue by animateFloatAsState(targetValue = value, label = "gauge-$label")
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val needleColor = MaterialTheme.colorScheme.primary
    val redlineColor = MaterialTheme.colorScheme.error

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            val strokeWidth = size.minDimension * 0.08f
            val radius = (size.minDimension - strokeWidth) / 2f
            val arcTopLeft = Offset(size.width / 2f - radius, size.height / 2f - radius)
            val arcSize = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f)

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

            val angleDegrees = GaugeMath.valueToAngleDegrees(animatedValue, minValue, maxValue, START_ANGLE_DEGREES, SWEEP_ANGLE_DEGREES)
            val angleRadians = Math.toRadians(angleDegrees.toDouble())
            val center = Offset(size.width / 2f, size.height / 2f)
            val needleLength = radius * 0.85f
            val needleEnd = Offset(
                x = center.x + (needleLength * cos(angleRadians)).toFloat(),
                y = center.y + (needleLength * sin(angleRadians)).toFloat(),
            )
            drawLine(
                color = needleColor,
                start = center,
                end = needleEnd,
                strokeWidth = strokeWidth * 0.35f,
                cap = StrokeCap.Round,
            )
            drawCircle(color = needleColor, radius = strokeWidth * 0.5f, center = center)
        }

        val displayValue = if (animatedValue.isNaN()) "--" else animatedValue.roundToInt().toString()
        Text(
            text = "$displayValue $unit",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
