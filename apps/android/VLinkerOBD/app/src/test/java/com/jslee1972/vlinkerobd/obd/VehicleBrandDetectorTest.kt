package com.jslee1972.vlinkerobd.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleBrandDetectorTest {

    private val detector = VehicleBrandDetector.FALLBACK

    @Test
    fun detectsHondaFromWmi() {
        assertEquals("Honda", detector.detectBrand("1HGCM82633A123456"))
    }

    @Test
    fun detectsMazdaFromWmi() {
        assertEquals("Mazda", detector.detectBrand("JM1BL1SF8D1234567"))
    }

    @Test
    fun detectsFordFromWmi() {
        assertEquals("Ford", detector.detectBrand("1FAHP3F20AG123456"))
    }

    @Test
    fun detectsCitroenFromSpainPlantWmi() {
        // VR7 = Citroën built at PSA's Vigo, Spain plant (confirmed against idlesign/vininfo and
        // way-platform/vin-go's Stellantis WMI tables) — this exact VIN came back from a real
        // Citroën Berlingo test drive where it had been going undetected as "VR7" was missing.
        assertEquals("Citroen", detector.detectBrand("VR7ECYHZRNJ613202"))
    }

    @Test
    fun returnsNullForUnknownWmi() {
        assertNull(detector.detectBrand("ZZZ00000000000000"))
    }

    @Test
    fun returnsNullForTooShortVin() {
        assertNull(detector.detectBrand("JM"))
    }

    @Test
    fun loadFromJsonParsesFlatWmiToBrandObject() {
        val loaded = VehicleBrandDetector.loadFromJson("""{"1HG": "Honda", "VF7": "Citroen"}""")
        assertEquals("Honda", loaded.detectBrand("1HGCM82633A123456"))
        assertEquals("Citroen", loaded.detectBrand("VF7ABCDEFGH123456"))
        assertNull(loaded.detectBrand("ZZZ00000000000000"))
    }

    @Test
    fun loadFromJsonFallsBackToTwoCharacterWmiWhenNoExactThreeCharacterEntry() {
        // Mirrors real entries in shared/wmi-database/wmi-to-brand.json (e.g. "JT" -> Toyota),
        // used when a manufacturer's family code isn't split by a distinct 3rd WMI character.
        val loaded = VehicleBrandDetector.loadFromJson("""{"JT": "Toyota", "1HG": "Honda"}""")
        assertEquals("Toyota", loaded.detectBrand("JT2BF22K1X0123456"))
        // An exact 3-character match always wins over a 2-character fallback.
        assertEquals("Honda", loaded.detectBrand("1HGCM82633A123456"))
    }
}
