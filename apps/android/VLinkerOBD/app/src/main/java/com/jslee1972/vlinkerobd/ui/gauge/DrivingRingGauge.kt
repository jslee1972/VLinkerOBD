package com.jslee1972.vlinkerobd.ui.gauge

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START_ANGLE_DEGREES = 150f
private const val SWEEP_ANGLE_DEGREES = 240f
private const val REDLINE_RPM = 6500f
private const val MAX_SPEED_KPH = 220f
private const val MAX_RPM = 8000f
private const val PEAK_RESET_INTERVAL_MS = 5000L

/**
 * The concentric center dial for the landscape driving-dynamics dashboard: an outer speed ring
 * (orange) and an inner RPM ring (purple, turning amber past 5500 and red past the redline), each
 * with its own 5-second peak-hold marker — an arrow + number riding the ring at the highest value
 * seen in roughly the last 5 seconds, so a glance shows not just "how fast now" but "how fast a
 * moment ago." Ported from iOS's DrivingRingGauge (DrivingDynamicsDashboardView.swift) — same
 * angle convention ([GaugeMath], already shared with the portrait needle [Gauge]), same colors,
 * same peak-hold idea.
 *
 * [obdSpeedKph] is OBD speed, falling back to [gpsSpeedKph] when OBD has gone stale (BLE dropout,
 * weak signal) rather than dropping to 0 and reading as "stopped" during a signal gap that has
 * nothing to do with the car's actual speed. [legendFields] are the (label, value) pairs the user
 * picked for the center legend under the speed readout — resolved by the caller since this
 * composable has no reason to know about [com.jslee1972.vlinkerobd.ui.ParameterMetadata].
 */
@Composable
fun DrivingRingGauge(
    obdSpeedKph: Float?,
    gpsSpeedKph: Float?,
    rpm: Float,
    legendFields: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val speedKph = obdSpeedKph ?: gpsSpeedKph ?: 0f
    val isGpsFallback = obdSpeedKph == null && gpsSpeedKph != null

    val animatedSpeed by animateFloatAsState(targetValue = speedKph, label = "ring-speed")
    val animatedRpm by animateFloatAsState(targetValue = rpm, label = "ring-rpm")

    var speedPeak by remember { mutableFloatStateOf(speedKph) }
    var rpmPeak by remember { mutableFloatStateOf(rpm) }

    LaunchedEffect(speedKph) { if (speedKph > speedPeak) speedPeak = speedKph }
    LaunchedEffect(rpm) { if (rpm > rpmPeak) rpmPeak = rpm }

    // A long-running effect (key = Unit, never relaunched) needs rememberUpdatedState to see the
    // *latest* speedKph/rpm each time it wakes — referencing the raw parameter directly here would
    // capture whatever value was current when this LaunchedEffect was first composed.
    val currentSpeedKph = rememberUpdatedState(speedKph)
    val currentRpm = rememberUpdatedState(rpm)
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(PEAK_RESET_INTERVAL_MS)
            speedPeak = currentSpeedKph.value
            rpmPeak = currentRpm.value
        }
    }

    val rpmColor = when {
        rpm >= REDLINE_RPM -> DesignPalette.danger
        rpm >= REDLINE_RPM * 0.82f -> DesignPalette.warn
        else -> DesignPalette.rpmNormal
    }

    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ringSize = min(size.width, size.height)
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = ringSize / 2f - 10.dp.toPx()
            val rpmRadius = outerRadius - 22.dp.toPx()

            drawRing(
                center = center,
                radius = outerRadius,
                trackWidth = 7.dp.toPx(),
                trackColor = Color.White.copy(alpha = 0.06f),
                value = animatedSpeed,
                maxValue = MAX_SPEED_KPH,
                fillColor = DesignPalette.speedOrange,
                tickCount = 11,
                peak = speedPeak,
            )
            drawRing(
                center = center,
                radius = rpmRadius,
                trackWidth = 11.dp.toPx(),
                trackColor = Color.White.copy(alpha = 0.08f),
                value = animatedRpm,
                maxValue = MAX_RPM,
                fillColor = rpmColor,
                tickCount = 8,
                peak = rpmPeak,
            )
        }

        // Nudged down from dead-center, away from the crowded upper arc where the rings and their
        // peak markers already sit.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.offset(y = 28.dp),
        ) {
            Text(
                text = animatedSpeed.roundToInt().toString(),
                fontSize = 64.sp,
                fontWeight = FontWeight.Black,
                color = DesignPalette.speedOrange,
            )
            when {
                isGpsFallback -> Text(
                    "GPS 訊號（OBD 中斷）",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = DesignPalette.warn,
                )
                gpsSpeedKph != null -> Text(
                    "GPS ${gpsSpeedKph.roundToInt()} km/h",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.55f),
                )
                else -> Text("km/h", fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
            }
            if (legendFields.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    legendFields.forEach { (label, value) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawRing(
    center: Offset,
    radius: Float,
    trackWidth: Float,
    trackColor: Color,
    value: Float,
    maxValue: Float,
    fillColor: Color,
    tickCount: Int,
    peak: Float,
) {
    drawArcSpan(center, radius, trackWidth, trackColor, START_ANGLE_DEGREES, START_ANGLE_DEGREES + SWEEP_ANGLE_DEGREES)

    val clamped = value.coerceIn(0f, maxValue)
    val endAngle = GaugeMath.valueToAngleDegrees(clamped, 0f, maxValue, START_ANGLE_DEGREES, SWEEP_ANGLE_DEGREES)
    drawArcSpan(center, radius, trackWidth, fillColor, START_ANGLE_DEGREES, endAngle)

    for (i in 0..tickCount) {
        val t = i / tickCount.toFloat()
        val deg = START_ANGLE_DEGREES + t * SWEEP_ANGLE_DEGREES
        val rad = Math.toRadians(deg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        drawLine(
            color = Color.White.copy(alpha = 0.4f),
            start = Offset(center.x + (radius - 4.dp.toPx()) * cosA, center.y + (radius - 4.dp.toPx()) * sinA),
            end = Offset(center.x + (radius + 4.dp.toPx()) * cosA, center.y + (radius + 4.dp.toPx()) * sinA),
            strokeWidth = 1.5.dp.toPx(),
        )
    }

    if (peak > 0f) drawPeakMarker(center, radius, peak, maxValue, fillColor)
}

private fun DrawScope.drawArcSpan(center: Offset, radius: Float, width: Float, color: Color, fromDeg: Float, toDeg: Float) {
    if (toDeg <= fromDeg) return
    drawArc(
        color = color,
        startAngle = fromDeg,
        sweepAngle = toDeg - fromDeg,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

/**
 * A small filled triangle riding just outside the ring at [peak]'s angle, tip pointing back along
 * the ring (opposite the direction the fill sweeps as the value grows — pointing "forward" read as
 * pointing where the value is heading, not where the peak sits), plus the value as text right at
 * the triangle's base. Drawn with the native canvas for the text since Compose's DrawScope has no
 * built-in text primitive — this is the one place in the ring gauge that reaches past Compose
 * drawing APIs.
 */
private fun DrawScope.drawPeakMarker(center: Offset, radius: Float, peak: Float, maxValue: Float, color: Color) {
    val deg = GaugeMath.valueToAngleDegrees(peak.coerceIn(0f, maxValue), 0f, maxValue, START_ANGLE_DEGREES, SWEEP_ANGLE_DEGREES)
    val rad = Math.toRadians(deg.toDouble())
    // radial points away from center; back points the way the fill *came from* (opposite the
    // direction the arc sweeps as the value increases).
    val radial = Offset(cos(rad).toFloat(), sin(rad).toFloat())
    val back = Offset(sin(rad).toFloat(), -cos(rad).toFloat())
    val markerRadius = radius + 6.dp.toPx()
    val base = Offset(center.x + markerRadius * radial.x, center.y + markerRadius * radial.y)

    fun along(p: Offset, v: Offset, distance: Float) = Offset(p.x + v.x * distance, p.y + v.y * distance)

    val tip = along(base, back, 9.dp.toPx())
    val backCenter = along(base, back, -3.dp.toPx())
    val backLeft = along(backCenter, radial, 3.5.dp.toPx())
    val backRight = along(backCenter, radial, -3.5.dp.toPx())
    val arrow = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(backLeft.x, backLeft.y)
        lineTo(backRight.x, backRight.y)
        close()
    }
    drawPath(arrow, color = color)

    // Right at the triangle's base (a small radial nudge so it doesn't sit on top of the ring
    // stroke) rather than trailing further back along the ring.
    val labelPoint = along(backCenter, radial, 11.dp.toPx())
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            this.color = color.toArgb()
            textSize = 11.sp.toPx()
            isFakeBoldText = true
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val text = peak.roundToInt().toString()
        val metrics = paint.fontMetrics
        val textY = labelPoint.y - (metrics.ascent + metrics.descent) / 2f
        drawText(text, labelPoint.x, textY, paint)
    }
}
