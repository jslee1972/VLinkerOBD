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
    /// Every field this profile setup could ever report — universal + trip computer + every
    /// loaded brand profile's own fields — for the custom-section field picker.
    var allKnownFields: [String] = []
    /// Fields the user pinned into the dashboard's own custom section, persisted across launches.
    var selectedCustomFields: Set<String> = []
    /// 標準介面 vs 行車動態介面 — see `DashboardMode`.
    var dashboardMode: DashboardMode = .standard

    var liveReadings: [String: String] { standardReadings.merging(extraReadings) { _, new in new } }
}
