package com.jslee1972.vlinkerobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleBrandDetectorTest {

    @Test
    fun detectsHondaFromWmi() {
        assertEquals("Honda", VehicleBrandDetector.detectBrand("1HGCM82633A123456"))
    }

    @Test
    fun detectsMazdaFromWmi() {
        assertEquals("Mazda", VehicleBrandDetector.detectBrand("JM1BL1SF8D1234567"))
    }

    @Test
    fun detectsFordFromWmi() {
        assertEquals("Ford", VehicleBrandDetector.detectBrand("1FAHP3F20AG123456"))
    }

    @Test
    fun detectsCitroenFromSpainPlantWmi() {
        // VR7 = Citroën built at PSA's Vigo, Spain plant (confirmed against idlesign/vininfo and
        // way-platform/vin-go's Stellantis WMI tables) — this exact VIN came back from a real
        // Citroën Berlingo test drive where it had been going undetected as "VR7" was missing.
        assertEquals("Citroen", VehicleBrandDetector.detectBrand("VR7ECYHZRNJ613202"))
    }

    @Test
    fun returnsNullForUnknownWmi() {
        assertNull(VehicleBrandDetector.detectBrand("ZZZ00000000000000"))
    }

    @Test
    fun returnsNullForTooShortVin() {
        assertNull(VehicleBrandDetector.detectBrand("JM"))
    }
}
