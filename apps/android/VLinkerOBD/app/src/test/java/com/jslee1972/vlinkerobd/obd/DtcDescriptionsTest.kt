package com.jslee1972.vlinkerobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDescriptionsTest {

    @Test
    fun describesKnownGenericCode() {
        assertEquals("與 ABS（防鎖死煞車系統）控制模組失去通訊", DtcDescriptions.describe("U0121"))
    }

    @Test
    fun describeIsCaseInsensitive() {
        assertEquals(DtcDescriptions.describe("P0133"), DtcDescriptions.describe("p0133"))
    }

    @Test
    fun fallsBackHonestlyForUnknownCode() {
        val description = DtcDescriptions.describe("P1ZZZ")
        assertTrue(description.contains("尚無內建說明"))
    }

    @Test
    fun mapsCategoryPrefixesToChineseLabels() {
        assertEquals("動力系統", DtcDescriptions.categoryName("P0133"))
        assertEquals("底盤", DtcDescriptions.categoryName("C0035"))
        assertEquals("車身", DtcDescriptions.categoryName("B0001"))
        assertEquals("網路通訊", DtcDescriptions.categoryName("U0121"))
        assertEquals("未知類別", DtcDescriptions.categoryName(""))
    }
}
