package com.jslee1972.vlinkerobd.ui.gauge

/**
 * Pure geometry for the analog gauges: maps a value onto a needle angle so it can be unit
 * tested without touching Compose. 0 degrees points to 3 o'clock, angles increase clockwise —
 * the convention `Canvas.drawArc`/trig functions use.
 */
object GaugeMath {

    fun valueToAngleDegrees(
        value: Float,
        minValue: Float,
        maxValue: Float,
        startAngleDegrees: Float,
        sweepAngleDegrees: Float,
    ): Float {
        val clamped = value.coerceIn(minValue, maxValue)
        val range = maxValue - minValue
        val fraction = if (range > 0f) (clamped - minValue) / range else 0f
        return startAngleDegrees + fraction * sweepAngleDegrees
    }
}
