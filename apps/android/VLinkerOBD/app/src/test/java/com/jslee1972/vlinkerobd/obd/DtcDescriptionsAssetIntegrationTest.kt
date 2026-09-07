package com.jslee1972.vlinkerobd.obd

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loads the real generic/ford/mazda/honda/bmw.json assets (not inline test fixtures) to guard
 * against asset corruption and parser regressions on the actual ~770KB/9,415-entry file, and
 * checks load time stays well clear of anything that would jank MainActivity.onCreate (it loads
 * synchronously on the main thread).
 */
class DtcDescriptionsAssetIntegrationTest {

    @Test
    fun loadsRealAssetsQuicklyAndCorrectly() {
        val readAsset: (String) -> String = { path ->
            File("src/main/assets/$path").readText(StandardCharsets.UTF_8)
        }
        val start = System.nanoTime()
        val dtc = DtcDescriptions.load(readAsset)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("load took suspiciously long: ${elapsedMs}ms", elapsedMs < 2000)

        // known-good entries, asserted byte-for-byte so terminal display encoding can't hide a parser bug
        assertEquals("觸媒轉換器效率低於標準（Bank 1）", dtc.describe("P0420")) // curated zh wins over the loaded English generic entry
        assertEquals(
            "Vehicle Speed Signal Missing or Improper（英文原文，尚無中文翻譯）",
            dtc.describe("P1039", brand = "Ford"),
        )
        assertEquals(
            "OBD II Monitor Testing Not Completed（英文原文，尚無中文翻譯）",
            dtc.describe("P1000", brand = "Mazda"),
        )
        assertEquals(
            "Pedal Position Sensor 1 High Input（英文原文，尚無中文翻譯）",
            dtc.describe("P1123", brand = "BMW"),
        )
    }
}
