import Foundation

let universalBrand = "通用"

enum EcuTestStatus {
    case supported
    case noData
    case negative
    case unrecognized
    case timeout
}

/// One probe result from the "ECU 支援測試" tool — see `DashboardController.testEcuSupport`.
struct EcuTestResult: Identifiable {
    var command: String
    var description: String
    var raw: String?
    var status: EcuTestStatus
    var statusMessage: String

    var id: String { command }
}

/// Everything the UI renders. Mirrors Android's `DashboardUiState.kt` field for field so the two
/// ports stay easy to compare.
struct DashboardState {
    var connectionLabel: String = "尚未連線"
    var connectedDeviceName: String?
    var devices: [ScannedBleDevice] = []
    var vehicleData = VehicleData()
    var rawResponse: String = ""
    var logs: [String] = []
    var isScanning: Bool = false
    var isReady: Bool = false
    var errorMessage: String?
    var availableBrands: [String] = [universalBrand]
    var selectedBrand: String = universalBrand
    /// Brand-PID formatted readings, field -> "value unit".
    var extraReadings: [String: String] = [:]
    /// Universal-extra + trip-computer formatted readings, field -> "value unit".
    var standardReadings: [String: String] = [:]
    var speedHistory: [Float] = []
    var rpmHistory: [Float] = []
    /// nil = never queried; [] = queried, none found.
    var troubleCodes: [String]?
    var isReadingTroubleCodes: Bool = false
    var detectedVin: String?
    var detectedBrand: String?
    var ecuTestResults: [EcuTestResult] = []
    var isTestingEcu: Bool = false
    /// Phone GPS-derived speed (km/h), shown alongside the OBD-reported speed for comparison.
    var gpsSpeedKph: Float?
    /// Fields the user pinned into the dashboard's own custom section, in display order (an
    /// ordered array, not a set, so the side panels' drag-to-reorder has a position to persist).
    var selectedCustomFields: [String] = []
    /// The (at most 2) fields shown in the ring gauge's own center legend, persisted separately
    /// from `selectedCustomFields` — see `RingLegendFieldsStore`.
    var ringLegendFields: [String] = []

    var liveReadings: [String: String] { standardReadings.merging(extraReadings) { _, new in new } }

    /// OBD speed when it's available, GPS speed when it isn't — the one place this fallback rule
    /// is defined, so every consumer (trip computer, floating PiP window; the ring gauge's own
    /// digit computes this itself from the same two sources) agrees on when "no OBD signal" means
    /// "fall back to GPS" versus "show nothing."
    var effectiveSpeedKph: Double? {
        vehicleData.speedKph.map(Double.init) ?? gpsSpeedKph.map(Double.init)
    }
}
