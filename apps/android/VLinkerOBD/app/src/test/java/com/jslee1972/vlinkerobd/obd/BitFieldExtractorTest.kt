package com.jslee1972.vlinkerobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitFieldExtractorTest {

    @Test
    fun extractsWholeFirstByte() {
        assertEquals(0xABL, BitFieldExtractor.extractRaw(listOf(0xAB), bitIndex = 0, bitLength = 8, signed = false))
    }

    @Test
    fun extractsAcrossByteBoundary() {
        // bits 4..11 span the low nibble of byte0 and the high nibble of byte1
        val bytes = listOf(0x0F, 0xF0)
        assertEquals(0xFFL, BitFieldExtractor.extractRaw(bytes, bitIndex = 4, bitLength = 8, signed = false))
    }

    @Test
    fun extractsSingleFlagBit() {
        // 0x20 = 0b00100000, MSB-first bit index 2 is the '1'
        assertEquals(1L, BitFieldExtractor.extractRaw(listOf(0x20), bitIndex = 2, bitLength = 1, signed = false))
        assertEquals(0L, BitFieldExtractor.extractRaw(listOf(0x20), bitIndex = 1, bitLength = 1, signed = false))
    }

    @Test
    fun appliesTwosComplementSignExtension() {
        assertEquals(-1L, BitFieldExtractor.extractRaw(listOf(0xFF), bitIndex = 0, bitLength = 8, signed = true))
        assertEquals(-50L, BitFieldExtractor.extractRaw(listOf(0xCE), bitIndex = 0, bitLength = 8, signed = true))
    }

    @Test
    fun returnsNullWhenRangeExceedsPayload() {
        assertNull(BitFieldExtractor.extractRaw(listOf(0x01), bitIndex = 4, bitLength = 8, signed = false))
    }

    @Test
    fun evaluatesGeneratorDutyStyleFormula() {
        // Honda "Generator": mul=100, div=255 -> percent
        val spec = BitFieldSpec(bitIndex = 0, bitLength = 8, multiplier = 100.0, divisor = 255.0, max = 100.0)
        assertEquals(100.0, BitFieldExtractor.evaluate(listOf(255), spec)!!, 0.001)
    }

    @Test
    fun evaluatesCatalystTempStyleFormula() {
        // Honda "Catalyst temperature": div=10, add=-40
        val spec = BitFieldSpec(bitIndex = 0, bitLength = 16, divisor = 10.0, offset = -40.0, min = -40.0, max = 1500.0)
        assertEquals(10.0, BitFieldExtractor.evaluate(listOf(0x01, 0xF4), spec)!!, 0.001) // raw 500 -> 500/10-40
    }

    @Test
    fun evaluatesSignedCoolantTempStyleFormula() {
        // Honda ECT sensors: div=10, sign=true
        val spec = BitFieldSpec(bitIndex = 0, bitLength = 16, divisor = 10.0, signed = true, min = -40.0, max = 215.0)
        // -100 raw as 16-bit two's complement = 0xFF9C
        assertEquals(-10.0, BitFieldExtractor.evaluate(listOf(0xFF, 0x9C), spec)!!, 0.001)
    }

    @Test
    fun clampsBelowMinimum() {
        val spec = BitFieldSpec(bitIndex = 0, bitLength = 8, signed = true, min = -40.0, max = 215.0)
        assertEquals(-40.0, BitFieldExtractor.evaluate(listOf(0xCE), spec)!!, 0.001) // raw -50, clamped to -40
    }

    @Test
    fun evaluateReturnsNullWhenExtractionFails() {
        val spec = BitFieldSpec(bitIndex = 10, bitLength = 8)
        assertNull(BitFieldExtractor.evaluate(listOf(0x01), spec))
    }
}
