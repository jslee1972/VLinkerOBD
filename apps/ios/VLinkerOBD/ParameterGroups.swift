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
    /// Fields the phone itself produces (currently just GPS altitude — accelerometer G-force
    /// now feeds the existing "acceleration" trip-computer field instead of its own group, see
    /// `phoneSensorFields`'s own doc comment) — no OBD PID backs any of these at all, unlike every
    /// other group here.
    static let phoneSensor = "手機感測器"

    /// Render order for the sections — trip computer and engine first as the most driving-relevant.
    static let displayOrder = [tripComputer, phoneSensor, engine, temperature, pressure, fuelEmissions, electrical, brandSpecific]

    /// The derived (non-PID) fields the trip computer produces — also needed by the custom-section
    /// field picker's "known fields" list. `acceleration` is CoreMotion-sourced (combined G-force,
    /// see `MotionDataSource`), not OBD-derived like the rest of this group, but stays grouped and
    /// named the same as before that change — it's still conceptually a trip-computer reading, and
    /// moving/renaming it would only have meant re-pinning it for anyone who already had it saved.
    static let tripComputerFields = ["instantFuelConsumption", "averageFuelConsumption", "acceleration", "tripDistance", "tripDuration"]

    /// Fields sourced from the phone's own sensors rather than any OBD PID — GPS altitude and GPS
    /// horizontal accuracy (see `GpsSpeedSource`). Heading (`gpsCourseDegrees`) and body roll
    /// (`vehicleRollDegrees`) are deliberately *not* in this list: both are fixed UI elements
    /// placed above/on the ring gauge, not pinnable fields — and the accelerometer G-force feeds
    /// `tripComputerFields`'s own `acceleration` field instead of living here.
    static let phoneSensorFields = ["altitudeM", "gpsAccuracyM"]

    /// Seeded into the custom section / driving-dynamics side panels the first time the app runs
    /// (before the user has picked anything) — a reasonable "most owners care about this" set,
    /// restricted to universal (never brand-specific) fields so it's meaningful on any vehicle.
    /// Ordered (not a set) since this is also the initial display order the user's own
    /// drag-to-reorder then adjusts. The user can freely add/remove/reorder from here afterward,
    /// including clearing it entirely; this is only ever consulted when nothing has been
    /// configured yet (see `CustomSectionStore`).
    static let defaultCustomFields = [
        "coolantTempC", "fuelLevelPercent", "controlModuleVoltage", "engineLoadPercent",
        "instantFuelConsumption", "averageFuelConsumption", "tripDistance", "ambientAirTempC",
    ]

    /// The ring gauge's own center legend defaults to 水溫 + 油量 — the two figures that used to
    /// be hardcoded there before it became user-configurable. Only ever consulted before the user
    /// has picked their own pair (see `RingLegendFieldsStore`).
    static let defaultRingLegendFields = ["coolantTempC", "fuelLevelPercent"]
}
