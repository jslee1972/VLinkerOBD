package com.jslee1972.vlinkerobd.ui.gauge

/**
 * Pure geometry for the trend-line sparkline: maps a value onto a vertical fraction so it can be
 * unit tested without touching Compose. 0 = bottom of the chart, 1 = top.
 */
object TrendChartMath {

    fun yFraction(value: Float, min: Float, max: Float): Float {
        val range = max - min
        if (range <= 0f) return 0.5f
        return ((value - min) / range).coerceIn(0f, 1f)
    }
}
