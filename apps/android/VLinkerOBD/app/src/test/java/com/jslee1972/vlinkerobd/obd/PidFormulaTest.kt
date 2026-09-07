package com.jslee1972.vlinkerobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PidFormulaTest {

    @Test
    fun evaluatesSingleByteIdentity() {
        assertEquals(40.0, PidFormula.evaluate("A", listOf(40)))
    }

    @Test
    fun evaluatesRpmFormula() {
        assertEquals(872.0, PidFormula.evaluate("((A*256)+B)/4", listOf(13, 160)))
    }

    @Test
    fun evaluatesCoolantTempFormula() {
        assertEquals(71.0, PidFormula.evaluate("A-40", listOf(111)))
    }

    @Test
    fun evaluatesPercentFormula() {
        assertEquals(50.0, PidFormula.evaluate("A*100/255", listOf(127))!!, 0.5)
    }

    @Test
    fun evaluatesControlModuleVoltageFormula() {
        assertEquals(14.2, PidFormula.evaluate("((A*256)+B)/1000", listOf(0x37, 0x70))!!, 0.01)
    }

    @Test
    fun evaluatesMafFormula() {
        assertEquals(2.5, PidFormula.evaluate("((A*256)+B)/100", listOf(0, 250)))
    }

    @Test
    fun evaluatesFuelPressureFormula() {
        assertEquals(300.0, PidFormula.evaluate("A*3", listOf(100)))
    }

    @Test
    fun evaluatesRuntimeFormula() {
        assertEquals(512.0, PidFormula.evaluate("(A*256)+B", listOf(2, 0)))
    }

    @Test
    fun evaluatesMazdaTirePressureFormula() {
        // 183 raw -> 251.259 kPa*10 -> ~36.44 psi (Mazda tire pressure conversion constant)
        assertEquals(36.44, PidFormula.evaluate("((A*1373)/1000)*0.145037738", listOf(183))!!, 0.01)
    }

    @Test
    fun evaluatesFuelTrimFormula() {
        // (A-128)*100/128; A=138 -> +7.8125%
        assertEquals(7.8125, PidFormula.evaluate("(A-128)*100/128", listOf(138))!!, 0.0001)
    }

    @Test
    fun evaluatesTimingAdvanceFormula() {
        assertEquals(10.0, PidFormula.evaluate("A/2-64", listOf(148)))
    }

    @Test
    fun evaluatesDistanceWithMilOnFormula() {
        assertEquals(300.0, PidFormula.evaluate("(A*256)+B", listOf(1, 44)))
    }

    @Test
    fun evaluatesPsaTurboTempFormula() {
        // (((A*256)+B)*0.0234375)-273.15; raw=25600 -> 600.0K -> 326.85C
        assertEquals(326.85, PidFormula.evaluate("(((A*256)+B)*0.0234375)-273.15", listOf(100, 0))!!, 0.001)
    }

    @Test
    fun evaluatesPsaMapPressureFormula() {
        // ((A*256)+B)*0.078125/100; raw=25600 -> 20.0 bar
        assertEquals(20.0, PidFormula.evaluate("((A*256)+B)*0.078125/100", listOf(100, 0))!!, 0.001)
    }

    @Test
    fun evaluatesPsaOilPressureFormula() {
        // (((A*256)+B)*0.00076294)-0.25; raw=25600 -> ~19.28 bar
        assertEquals(19.281, PidFormula.evaluate("(((A*256)+B)*0.00076294)-0.25", listOf(100, 0))!!, 0.001)
    }

    @Test
    fun returnsNullWhenNotEnoughBytes() {
        assertNull(PidFormula.evaluate("((A*256)+B)/4", listOf(13)))
    }

    @Test
    fun returnsNullForUnsupportedVariable() {
        assertNull(PidFormula.evaluate("E", listOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun returnsNullForMalformedFormula() {
        assertNull(PidFormula.evaluate("A+*B", listOf(1, 2)))
    }

    @Test
    fun returnsNullForDivisionByZero() {
        assertNull(PidFormula.evaluate("A/B", listOf(10, 0)))
    }
}
