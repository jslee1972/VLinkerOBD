package com.jslee1972.vlinkerobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcDescriptionsTest {

    @Test
    fun describesKnownGenericCodeInChinese() {
        val dtc = DtcDescriptions.withoutEnglishData()
        assertEquals("與 ABS（防鎖死煞車系統）控制模組失去通訊", dtc.describe("U0121"))
    }

    @Test
    fun describeIsCaseInsensitive() {
        val dtc = DtcDescriptions.withoutEnglishData()
        assertEquals(dtc.describe("P0133"), dtc.describe("p0133"))
    }

    @Test
    fun fallsBackHonestlyWhenNoChineseOrEnglishDataMatches() {
        val dtc = DtcDescriptions.withoutEnglishData()
        assertTrue(dtc.describe("P1ZZZ").contains("尚無內建說明"))
    }

    @Test
    fun mapsCategoryPrefixesToChineseLabels() {
        assertEquals("動力系統", DtcDescriptions.categoryName("P0133"))
        assertEquals("底盤", DtcDescriptions.categoryName("C0035"))
        assertEquals("車身", DtcDescriptions.categoryName("B0001"))
        assertEquals("網路通訊", DtcDescriptions.categoryName("U0121"))
        assertEquals("未知類別", DtcDescriptions.categoryName(""))
    }

    @Test
    fun fallsBackToEnglishGenericDatabaseWhenNoChineseTranslation() {
        val generic = """[{"code": "P0AA6", "description": "Contactor Control Circuit/Open"}]"""
        val dtc = DtcDescriptions.load({ path -> if (path == "dtc-codes/generic.json") generic else "[]" })

        val description = dtc.describe("P0AA6")

        assertTrue(description.contains("Contactor Control Circuit/Open"))
        assertTrue(description.contains("英文原文"))
    }

    @Test
    fun prefersBrandSpecificEnglishOverGenericWhenBrandGiven() {
        val readAsset: (String) -> String = { path ->
            when (path) {
                "dtc-codes/generic.json" -> """[{"code": "P1039", "description": "Generic placeholder"}]"""
                "dtc-codes/ford.json" -> """[{"code": "P1039", "description": "Vehicle Speed Signal Missing or Improper"}]"""
                else -> "[]"
            }
        }
        val dtc = DtcDescriptions.load(readAsset, brands = listOf("ford"))

        val description = dtc.describe("P1039", brand = "Ford")

        assertTrue(description.contains("Vehicle Speed Signal Missing or Improper"))
    }

    @Test
    fun chineseCuratedDescriptionStillWinsOverLoadedEnglishData() {
        val generic = """[{"code": "P0133", "description": "O2 Sensor Circuit Slow Response Bank 1 Sensor 1"}]"""
        val dtc = DtcDescriptions.load({ path -> if (path == "dtc-codes/generic.json") generic else "[]" })

        assertEquals("氧感應器反應遲緩（Bank 1 Sensor 1）", dtc.describe("P0133"))
    }
}
