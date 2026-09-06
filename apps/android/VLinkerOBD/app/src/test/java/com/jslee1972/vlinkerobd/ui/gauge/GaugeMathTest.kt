package com.jslee1972.vlinkerobd.ui.gauge

import org.junit.Assert.assertEquals
import org.junit.Test

class GaugeMathTest {

    @Test
    fun mapsMinimumValueToStartAngle() {
        val angle = GaugeMath.valueToAngleDegrees(0f, 0f, 220f, startAngleDegrees = 150f, sweepAngleDegrees = 240f)
        assertEquals(150f, angle, 0.01f)
    }

    @Test
    fun mapsMaximumValueToStartPlusSweep() {
        val angle = GaugeMath.valueToAngleDegrees(220f, 0f, 220f, startAngleDegrees = 150f, sweepAngleDegrees = 240f)
        assertEquals(390f, angle, 0.01f)
    }

    @Test
    fun mapsMidpointToHalfSweep() {
        val angle = GaugeMath.valueToAngleDegrees(110f, 0f, 220f, startAngleDegrees = 150f, sweepAngleDegrees = 240f)
        assertEquals(270f, angle, 0.01f)
    }

    @Test
    fun clampsValueAboveMaximum() {
        val angle = GaugeMath.valueToAngleDegrees(9000f, 0f, 8000f, startAngleDegrees = 150f, sweepAngleDegrees = 240f)
        assertEquals(390f, angle, 0.01f)
    }

    @Test
    fun clampsValueBelowMinimum() {
        val angle = GaugeMath.valueToAngleDegrees(-50f, 0f, 8000f, startAngleDegrees = 150f, sweepAngleDegrees = 240f)
        assertEquals(150f, angle, 0.01f)
    }

    @Test
    fun handlesZeroRangeWithoutDividingByZero() {
        val angle = GaugeMath.valueToAngleDegrees(5f, 5f, 5f, startAngleDegrees = 150f, sweepAngleDegrees = 240f)
        assertEquals(150f, angle, 0.01f)
    }
}
