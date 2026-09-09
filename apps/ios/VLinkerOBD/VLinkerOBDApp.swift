import SwiftUI

/// Builds the app-lifetime `DashboardController` and its dependencies. Mirrors Android's
/// `VLinkerObdApplication.onCreate`: universal profile always loaded, Citroën/Peugeot merge the
/// ICE (`citroen.json`) and EV (`citroen-ev.json`) PID lists into one profile shared by both
/// detected-brand keys, and the comprehensive WMI database (not the small built-in fallback) is
/// used for brand detection.
enum AppDependencies {
    /// One `DashboardController` for the whole process — shared by the phone UI and (see
    /// `CarPlaySceneDelegate`) the CarPlay scene, exactly like Android's `VLinkerObdApplication`
    /// shares a single `DashboardViewModel` with its car screen: there's only one BLE adapter, so
    /// there can only be one client of it, regardless of which surface the user is looking at.
    @MainActor
    static let shared: DashboardController = makeController()

    @MainActor
    private static func makeController() -> DashboardController {
        let universalProfile = (try? PidGroupRepository.loadUniversal()) ?? VehicleProfile(profileId: "universal-obd2")

        let mazda = try? PidGroupRepository.loadBrand("mazda")
        let ford = try? PidGroupRepository.loadBrand("ford")
        let honda = try? PidGroupRepository.loadBrand("honda")
        let psaIce = try? PidGroupRepository.loadBrand("citroen")
        let psaEv = try? PidGroupRepository.loadBrand("citroen-ev")

        var brandProfiles: [String: VehicleProfile] = [:]
        if let mazda { brandProfiles["Mazda"] = mazda }
        if let ford { brandProfiles["Ford"] = ford }
        if let honda { brandProfiles["Honda"] = honda }
        if var psaProfile = psaIce {
            if let psaEv { psaProfile.pids += psaEv.pids }
            brandProfiles["Citroen"] = psaProfile
            brandProfiles["Peugeot"] = psaProfile
        }

        return DashboardController(
            bleClient: OBDBLEManager(),
            universalProfile: universalProfile,
            brandProfiles: brandProfiles,
            deviceMemory: UserDefaultsDeviceMemory(),
            brandDetector: VehicleBrandDetector.loadFromBundle(),
            gpsSpeedSource: CoreLocationGpsSpeedSource(),
            customSectionStore: UserDefaultsCustomSectionStore(),
            dashboardModeStore: UserDefaultsDashboardModeStore(),
            dtcDescriptions: DtcDescriptions.load()
        )
    }
}

@main
struct VLinkerOBDApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var controller = AppDependencies.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(controller)
        }
    }
}
