import Foundation

/// Maps a VIN's WMI (World Manufacturer Identifier, ISO 3780) to a brand name matching the
/// `brand` field used by the vehicle profile JSON files. Looks up the full 3-character WMI
/// first, falling back to the first 2 characters for manufacturers whose database entry is only
/// a 2-character family code. Faithful port of Android's `VehicleBrandDetector.kt`.
final class VehicleBrandDetector {
    private let wmiToBrand: [String: String]

    init(wmiToBrand: [String: String]) {
        self.wmiToBrand = wmiToBrand
    }

    /// Returns the detected brand name, or nil if `vin` is too short or its WMI is unrecognized.
    func detectBrand(vin: String) -> String? {
        guard vin.count >= 3 else { return nil }
        let wmi3 = String(vin.prefix(3)).uppercased()
        if let brand = wmiToBrand[wmi3] { return brand }
        return wmiToBrand[String(wmi3.prefix(2))]
    }

    /// Loads shared/wmi-database/wmi-to-brand.json (~700 entries) bundled into the app.
    static func loadFromBundle() -> VehicleBrandDetector {
        guard let url = Bundle.main.url(forResource: "wmi-to-brand", withExtension: "json", subdirectory: "wmi-database"),
              let data = try? Data(contentsOf: url),
              let map = try? JSONDecoder().decode([String: String].self, from: data) else {
            return FALLBACK
        }
        return VehicleBrandDetector(wmiToBrand: map)
    }

    /// Small built-in subset covering just the brands this project currently ships PID profiles
    /// for — used as a default when the comprehensive database can't be loaded. Not meant to be
    /// a complete WMI list; production always uses `loadFromBundle()`.
    static let FALLBACK: VehicleBrandDetector = {
        var map: [String: String] = [:]
        func putAll(_ brand: String, _ wmis: String...) {
            for wmi in wmis { map[wmi] = brand }
        }
        putAll("Mazda", "JM1", "JM3", "JM6", "JM7", "JMZ", "1YV", "4F2", "4F4")
        putAll("Ford", "1FA", "1FB", "1FC", "1FD", "1FM", "1FT", "2FA", "3FA", "WF0")
        putAll("Honda", "JHM", "JH1", "JH2", "JH4", "1HG", "2HG", "5FN", "5J6", "19X")
        putAll("Toyota", "JT1", "JT2", "JT3", "JT4", "JTD", "JTE", "4T1", "4T3", "5TD", "5TF")
        putAll("BMW", "WBA", "WBS", "WBX", "WBY", "4US", "5UX", "5YM")
        putAll("Mercedes-Benz", "WDB", "WDC", "WDD", "WDF", "4JG", "55S")
        putAll("Volkswagen", "WVW", "WV1", "WV2", "WV3", "1VW", "3VW", "9BW")
        putAll("Audi", "WAU", "WA1", "WUA")
        putAll("Hyundai", "KMH", "KM8", "5NP", "5NM")
        putAll("Kia", "KNA", "KND", "KNM", "5XY", "5XX")
        putAll("Mitsubishi", "JA3", "JA4", "4A3", "4A4", "6MM")
        putAll("Peugeot", "VF3", "VR3")
        putAll("Citroen", "VF7", "VS7", "VR7")
        return VehicleBrandDetector(wmiToBrand: map)
    }()
}
