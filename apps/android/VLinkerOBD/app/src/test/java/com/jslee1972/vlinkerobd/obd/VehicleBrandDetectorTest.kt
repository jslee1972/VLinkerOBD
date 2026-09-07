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
    fun returnsNullForUnknownWmi() {
        assertNull(VehicleBrandDetector.detectBrand("ZZZ00000000000000"))
    }

    @Test
    fun returnsNullForTooShortVin() {
        assertNull(VehicleBrandDetector.detectBrand("JM"))
    }
}
