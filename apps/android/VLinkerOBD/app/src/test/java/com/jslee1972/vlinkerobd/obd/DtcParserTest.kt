package com.jslee1972.vlinkerobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtcParserTest {

    @Test
    fun parsesNoStoredCodesAsEmptyList() {
        assertEquals(emptyList<String>(), DtcParser.parse("43 00\r>", "03"))
    }

    @Test
    fun parsesSingleCurrentCode() {
        assertEquals(listOf("P0133"), DtcParser.parse("43 01 33\r>", "03"))
    }

    @Test
    fun parsesMultipleCodesAndSkipsZeroPadding() {
        assertEquals(
            listOf("P0133", "P0471"),
            DtcParser.parse("43 01 33 04 71 00 00\r>", "03"),
        )
    }

    @Test
    fun decodesAllFourCategoryPrefixes() {
        // top 2 bits of the high byte select P/C/B/U
        assertEquals(listOf("P0100"), DtcParser.parse("43 01 00\r>", "03"))
        assertEquals(listOf("C0100"), DtcParser.parse("43 41 00\r>", "03"))
        assertEquals(listOf("B0100"), DtcParser.parse("43 81 00\r>", "03"))
        assertEquals(listOf("U0100"), DtcParser.parse("43 C1 00\r>", "03"))
    }

    @Test
    fun parsesPendingCodesFromMode07() {
        assertEquals(listOf("P0133"), DtcParser.parse("47 01 33\r>", "07"))
    }

    @Test
    fun parsesPermanentCodesFromMode0A() {
        assertEquals(listOf("P0133"), DtcParser.parse("4A 01 33\r>", "0A"))
    }

    @Test
    fun returnsNullOnNoData() {
        assertNull(DtcParser.parse("NO DATA\r>", "03"))
    }

    @Test
    fun returnsNullOnNegativeResponse() {
        assertNull(DtcParser.parse("7F 03 12\r>", "03"))
    }

    @Test
    fun returnsNullForUnsupportedMode() {
        assertNull(DtcParser.parse("43 01 33\r>", "99"))
    }
}
