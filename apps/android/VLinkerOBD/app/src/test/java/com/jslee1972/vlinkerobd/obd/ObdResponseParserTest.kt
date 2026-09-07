package com.jslee1972.vlinkerobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdResponseParserTest {

    @Test
    fun parsesSpacedSpeed() {
        assertEquals(40.0, ObdResponseParser.parsePid("41 0D 28\r>", "010D", "A"))
    }

    @Test
    fun parsesEchoAndCompactLowercase() {
        assertEquals(100.0, ObdResponseParser.parsePid("010D\r410d64\r>", "010D", "A"))
    }

    @Test
    fun parsesSplitLines() {
        assertEquals(10.0, ObdResponseParser.parsePid("41 0D\r0A\r>", "010D", "A"))
    }

    @Test
    fun rejectsMissingByte() {
        assertNull(ObdResponseParser.parsePid("41 0D\r>", "010D", "A"))
    }

    @Test
    fun rejectsInvalidHex() {
        assertNull(ObdResponseParser.parsePid("41 0D GG\r>", "010D", "A"))
    }

    @Test
    fun rejectsDifferentPid() {
        assertNull(ObdResponseParser.parsePid("41 0C 12 34\r>", "010D", "A"))
    }

    @Test
    fun parsesRpmAcrossTwoBytes() {
        assertEquals(872.0, ObdResponseParser.parsePid("41 0C 0D A0\r>", "010C", "((A*256)+B)/4"))
    }

    @Test
    fun classifiesNoDataCaseInsensitively() {
        assertEquals(ObdResponseStatus.NoData, ObdResponseParser.classify("NO DATA\r>"))
        assertEquals(ObdResponseStatus.NoData, ObdResponseParser.classify("no data\r>"))
    }

    @Test
    fun noDataMeansParsePidReturnsNull() {
        assertNull(ObdResponseParser.parsePid("NO DATA\r>", "010D", "A"))
    }

    @Test
    fun classifiesNegativeResponseServiceNotSupported() {
        val status = ObdResponseParser.classify("7F 01 12\r>")
        assertTrue(status is ObdResponseStatus.NegativeResponse)
        val negative = status as ObdResponseStatus.NegativeResponse
        assertEquals("01", negative.service)
        assertEquals("12", negative.nrc)
        assertEquals("不支援此 PID", negative.messageZh)
    }

    @Test
    fun negativeResponseMeansParsePidReturnsNull() {
        assertNull(ObdResponseParser.parsePid("7F 01 12\r>", "010D", "A"))
    }

    @Test
    fun classifiesUnknownNegativeResponseCodeWithFallbackMessage() {
        val status = ObdResponseParser.classify("7F22FF\r>") as ObdResponseStatus.NegativeResponse
        assertEquals("FF", status.nrc)
        assertTrue(status.messageZh.contains("FF"))
    }

    @Test
    fun classifiesUnrecognizedGarbage() {
        assertEquals(ObdResponseStatus.Unrecognized, ObdResponseParser.classify("ELM327 v1.5\r>"))
    }

    @Test
    fun parsesBitFieldEncodedPid() {
        // mirrors Honda "Catalyst temperature": div=10, add=-40, payload 0x01F4=500 -> 10.0
        val spec = BitFieldSpec(bitIndex = 0, bitLength = 16, divisor = 10.0, offset = -40.0)
        val result = ObdResponseParser.parsePidBitField("62 26 62 01 F4\r>", "222662", spec)
        assertEquals(10.0, result!!, 0.001)
    }

    @Test
    fun bitFieldPidReturnsNullOnNoData() {
        val spec = BitFieldSpec(bitIndex = 0, bitLength = 16)
        assertNull(ObdResponseParser.parsePidBitField("NO DATA\r>", "222662", spec))
    }

    @Test
    fun parsesMazdaTirePressurePrivatePid() {
        val result = ObdResponseParser.parsePid(
            "62 C9 01 B7\r>",
            "22C901",
            "((A*1373)/1000)*0.145037738",
        )
        assertEquals(36.44, result!!, 0.01)
    }

    @Test
    fun parsesVinFromMode09Response() {
        // "1HGCM82633A123456" as ASCII hex, with the leading 0x01 data-item count byte
        val raw = "49 02 01 31 48 47 43 4D 38 32 36 33 33 41 31 32 33 34 35 36\r>"
        assertEquals("1HGCM82633A123456", ObdResponseParser.parseVin(raw))
    }

    @Test
    fun parseVinReturnsNullOnNoData() {
        assertNull(ObdResponseParser.parseVin("NO DATA\r>"))
    }

    @Test
    fun parseVinReturnsNullOnNegativeResponse() {
        assertNull(ObdResponseParser.parseVin("7F 09 11\r>"))
    }
}
