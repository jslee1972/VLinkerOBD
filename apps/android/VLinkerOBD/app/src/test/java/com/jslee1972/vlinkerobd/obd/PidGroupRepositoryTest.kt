package com.jslee1972.vlinkerobd.obd

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidGroupRepositoryTest {

    private fun repository() = PidGroupRepository { assetPath ->
        File("src/main/assets/$assetPath").readText()
    }

    @Test
    fun loadsUniversalProfileWithSpeedAndRpm() {
        val profile = repository().loadUniversal()

        assertEquals("universal-obd2", profile.profileId)
        assertNull(profile.brand)

        val speed = profile.pids.single { it.field == "speedKPH" }
        assertEquals("010D", speed.request)
        assertEquals("A", speed.formula)

        val rpm = profile.pids.single { it.field == "rpm" }
        assertEquals("010C", rpm.request)
    }

    @Test
    fun loadsUniversalProfileWithExtendedPids() {
        val profile = repository().loadUniversal()

        val stft1 = profile.pids.single { it.field == "shortTermFuelTrimBank1Percent" }
        assertEquals("0106", stft1.request)
        assertEquals("(A-128)*100/128", stft1.formula)

        assertEquals("0107", profile.pids.single { it.field == "longTermFuelTrimBank1Percent" }.request)
        assertEquals("0108", profile.pids.single { it.field == "shortTermFuelTrimBank2Percent" }.request)
        assertEquals("0109", profile.pids.single { it.field == "longTermFuelTrimBank2Percent" }.request)
        assertEquals("010E", profile.pids.single { it.field == "timingAdvanceDegrees" }.request)
        assertEquals("0121", profile.pids.single { it.field == "distanceWithMilOnKM" }.request)
    }

    @Test
    fun loadsMazdaBrandProfileWithPerModelHeaders() {
        val profile = repository().loadBrand("mazda.json")

        assertEquals("Mazda", profile.brand)
        assertTrue(profile.pids.isEmpty())
        assertEquals(2, profile.models.size)

        val miata = profile.models.single { it.modelId == "miata-nc" }
        assertEquals("720", miata.ecuHeader)
        val tire1 = miata.pids.single { it.field == "tire1PressurePSI" }
        assertEquals("22C901", tire1.request)
        assertEquals("forum-partial", tire1.verified)

        val rx8 = profile.models.single { it.modelId == "rx8-ms6" }
        assertEquals("751", rx8.ecuHeader)
    }

    @Test
    fun loadsFordBrandProfileWithBitFieldPids() {
        val profile = repository().loadBrand("ford.json")

        assertEquals("Ford", profile.brand)
        assertTrue(profile.models.isEmpty())

        val odometer = profile.pids.single { it.field == "odometerKM" }
        assertEquals("22404C", odometer.request)
        assertEquals("720", odometer.ecuHeader)
        assertEquals("728", odometer.ecuReceiveFilter)
        assertNull(odometer.formula)
        assertEquals(24, odometer.bitField!!.bitLength)
        assertEquals(10.0, odometer.bitField!!.divisor, 0.0)

        val tireWarning = profile.pids.single { it.field == "tirePressureWarning" }
        assertEquals(2, tireWarning.bitField!!.bitIndex)
        assertEquals(1, tireWarning.bitField!!.bitLength)
    }

    @Test
    fun loadsHondaBrandProfileWithSignedBitFieldPid() {
        val profile = repository().loadBrand("honda.json")

        assertEquals("Honda", profile.brand)
        val current = profile.pids.single { it.field == "batteryCurrent" }
        assertEquals("DA01", current.ecuHeader)
        assertEquals("01", current.ecuReceiveFilter)
        assertTrue(current.bitField!!.signed)
        assertEquals(50.0, current.bitField!!.divisor, 0.0)
        assertEquals(-100.0, current.bitField!!.min)
    }

    @Test
    fun parsesMinimalInlineProfile() {
        val profile = PidGroupRepository.parseProfile(
            """
            {
              "schemaVersion": 2,
              "profileId": "test-profile",
              "pids": [
                {"request": "0105", "field": "coolantTempC", "unit": "degC", "formula": "A-40"}
              ]
            }
            """.trimIndent()
        )

        assertEquals("test-profile", profile.profileId)
        assertEquals(1, profile.pids.size)
        assertEquals("A-40", profile.pids.first().formula)
    }
}
