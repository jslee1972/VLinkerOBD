import Foundation

/// Section labels and display order for the live-readings grid. Which field belongs to which
/// group is data (each PID's own `group` in shared/vehicle-profiles JSON, schema v5 — see
/// ``ParameterMetadata``); this only holds the fixed set of section names and render order, a
/// layout concern shared with the Android port (`ParameterGroups.kt`).
enum ParameterGroups {
    static let tripComputer = "行車電腦"
    static let engine = "引擎與動力"
    static let temperature = "溫度"
    static let pressure = "壓力"
    static let fuelEmissions = "燃油與排放"
    static let electrical = "電力與診斷"
    static let brandSpecific = "廠牌專屬"

    /// Render order for the sections — trip computer and engine first as the most driving-relevant.
    static let displayOrder = [tripComputer, engine, temperature, pressure, fuelEmissions, electrical, brandSpecific]

    /// The derived (non-PID) fields the trip computer produces — also needed by the custom-section
    /// field picker's "known fields" list.
    static let tripComputerFields = ["instantFuelConsumption", "averageFuelConsumption", "acceleration", "tripDistance", "tripDuration"]

    /// Seeded into the custom section / driving-dynamics side panels the first time the app runs
    /// (before the user has picked anything) — a reasonable "most owners care about this" set,
    /// restricted to universal (never brand-specific) fields so it's meaningful on any vehicle.
    /// The user can freely add/remove from here afterward, including clearing it entirely; this is
    /// only ever consulted when nothing has been configured yet (see `CustomSectionStore`).
    static let defaultCustomFields: Set<String> = [
        "coolantTempC", "fuelLevelPercent", "controlModuleVoltage", "engineLoadPercent",
        "instantFuelConsumption", "averageFuelConsumption", "tripDistance", "ambientAirTempC",
    ]
}
