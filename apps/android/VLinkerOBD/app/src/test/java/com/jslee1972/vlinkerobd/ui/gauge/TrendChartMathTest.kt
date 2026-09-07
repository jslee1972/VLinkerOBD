package com.jslee1972.vlinkerobd.ui.gauge

import org.junit.Assert.assertEquals
import org.junit.Test

class TrendChartMathTest {

    @Test
    fun mapsMinimumValueToBottom() {
        assertEquals(0f, TrendChartMath.yFraction(0f, 0f, 100f), 0.001f)
    }

    @Test
    fun mapsMaximumValueToTop() {
        assertEquals(1f, TrendChartMath.yFraction(100f, 0f, 100f), 0.001f)
    }

    @Test
    fun mapsMidpointToHalf() {
        assertEquals(0.5f, TrendChartMath.yFraction(50f, 0f, 100f), 0.001f)
    }

    @Test
    fun clampsValueOutsideRange() {
        assertEquals(1f, TrendChartMath.yFraction(150f, 0f, 100f), 0.001f)
        assertEquals(0f, TrendChartMath.yFraction(-50f, 0f, 100f), 0.001f)
    }

    @Test
    fun handlesZeroRangeWithoutDividingByZero() {
        assertEquals(0.5f, TrendChartMath.yFraction(5f, 5f, 5f), 0.001f)
    }
}
