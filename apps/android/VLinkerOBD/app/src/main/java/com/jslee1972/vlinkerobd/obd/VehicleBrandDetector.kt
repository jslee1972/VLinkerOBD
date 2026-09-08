package com.jslee1972.vlinkerobd.obd

/**
 * Maps a VIN's WMI (World Manufacturer Identifier, ISO 3780) to a brand name matching the
 * "brand" field used by the vehicle profile JSON files under shared/vehicle-profiles. Looks up
 * the full 3-character WMI first, falling back to the first 2 characters for manufacturers whose
 * database entry is only a 2-character family code (matching how the source WMI tables
 * themselves mix 2- and 3-character keys — see shared/wmi-database/README.md).
 */
class VehicleBrandDetector(private val wmiToBrand: Map<String, String>) {

    /** Returns the detected brand name, or null if [vin] is too short or its WMI is unrecognized. */
    fun detectBrand(vin: String): String? {
        if (vin.length < 3) return null
        val wmi3 = vin.take(3).uppercase()
        wmiToBrand[wmi3]?.let { return it }
        return wmiToBrand[wmi3.take(2)]
    }

    companion object {
        /** Parses a flat `{"WMI": "Brand", ...}` JSON object, e.g. shared/wmi-database/wmi-to-brand.json. */
        fun loadFromJson(json: String): VehicleBrandDetector {
            val fields = MiniJson.parse(json).asObject()
            return VehicleBrandDetector(fields.mapValues { (_, value) -> value.asString() })
        }

        // Small built-in subset covering just the brands this project currently ships PID
        // profiles for — used by default (e.g. in unit tests) when no comprehensive database has
        // been loaded. Production wires the full shared/wmi-database/wmi-to-brand.json instead
        // (see VLinkerObdApplication); this is not meant to be a complete WMI list.
        val FALLBACK: VehicleBrandDetector = VehicleBrandDetector(
            buildMap {
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
                putAllFor("Citroen", "VF7", "VS7", "VR7")
            },
        )

        private fun MutableMap<String, String>.putAllFor(brand: String, vararg wmis: String) {
            wmis.forEach { put(it, brand) }
        }
    }
}
