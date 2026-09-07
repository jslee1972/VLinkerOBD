package com.jslee1972.vlinkerobd.obd

/**
 * Maps a VIN's first 3 characters (the WMI — World Manufacturer Identifier, ISO 3780) to a
 * brand name matching the "brand" field used by the vehicle profile JSON files under
 * shared/vehicle-profiles. This is a best-effort table of the most common real-world WMIs per
 * brand (not exhaustive — large manufacturers hold dozens of WMIs split by plant, region, and
 * vehicle type), good enough to auto-select a brand PID profile or just label the detected
 * brand in the UI.
 */
object VehicleBrandDetector {

    private val wmiToBrand: Map<String, String> = buildMap {
        putAllFor("Mazda", "JM1", "JM3", "JM6", "JM7", "JMZ", "1YV", "4F2", "4F4")
        putAllFor("Ford", "1FA", "1FB", "1FC", "1FD", "1FM", "1FT", "2FA", "3FA", "WF0")
        putAllFor("Honda", "JHM", "JH1", "JH2", "JH4", "1HG", "2HG", "5FN", "5J6", "19X")
        putAllFor("Toyota", "JT1", "JT2", "JT3", "JT4", "JTD", "JTE", "4T1", "4T3", "5TD", "5TF")
        putAllFor("BMW", "WBA", "WBS", "WBX", "WBY", "4US", "5UX", "5YM")
        putAllFor("Mercedes-Benz", "WDB", "WDC", "WDD", "WDF", "4JG", "55S")
        putAllFor("Volkswagen", "WVW", "WV1", "WV2", "WV3", "1VW", "3VW", "9BW")
        putAllFor("Audi", "WAU", "WA1", "WUA")
        putAllFor("Hyundai", "KMH", "KM8", "5NP", "5NM")
        putAllFor("Kia", "KNA", "KND", "KNM", "5XY", "5XX")
        putAllFor("Mitsubishi", "JA3", "JA4", "4A3", "4A4", "6MM")
        putAllFor("Peugeot", "VF3", "VR3")
        putAllFor("Citroen", "VF7", "VS7")
    }

    /** Returns the detected brand name, or null if [vin] is too short or its WMI is unrecognized. */
    fun detectBrand(vin: String): String? {
        if (vin.length < 3) return null
        return wmiToBrand[vin.take(3).uppercase()]
    }

    private fun MutableMap<String, String>.putAllFor(brand: String, vararg wmis: String) {
        wmis.forEach { put(it, brand) }
    }
}
