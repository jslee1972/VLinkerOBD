import Foundation

/// Remembers the last-connected BLE device address, for auto-reconnect on next launch. Mirrors
/// Android's `DeviceMemory`/`SharedPreferencesDeviceMemory`.
protocol DeviceMemory: AnyObject {
    func lastDeviceAddress() -> String?
    func rememberDevice(address: String)
}

final class UserDefaultsDeviceMemory: DeviceMemory {
    private let defaults = UserDefaults.standard
    private static let key = "vlinkerobd.device_memory.last_address"

    func lastDeviceAddress() -> String? { defaults.string(forKey: Self.key) }
    func rememberDevice(address: String) { defaults.set(address, forKey: Self.key) }
}

/// Remembers which parameter fields the user pinned into the dashboard's custom section (and, in
/// the driving-dynamics UI, the side panels). Mirrors Android's
/// `CustomSectionStore`/`SharedPreferencesCustomSectionStore`.
protocol CustomSectionStore: AnyObject {
    func selectedFields() -> Set<String>
    func setSelectedFields(_ fields: Set<String>)
}

final class UserDefaultsCustomSectionStore: CustomSectionStore {
    private let defaults = UserDefaults.standard
    private static let key = "vlinkerobd.custom_section.selected_fields"

    /// `stringArray(forKey:)` returns nil only when the key has never been written — i.e. the
    /// user has never touched the picker yet — which is when `ParameterGroups.defaultCustomFields`
    /// applies. Once `setSelectedFields` has been called even once, whatever it stored (including
    /// an explicitly empty set, e.g. after "全部移除") is respected as-is from then on.
    func selectedFields() -> Set<String> {
        guard let stored = defaults.stringArray(forKey: Self.key) else {
            return ParameterGroups.defaultCustomFields
        }
        return Set(stored)
    }

    func setSelectedFields(_ fields: Set<String>) {
        defaults.set(Array(fields), forKey: Self.key)
    }
}

/// Which top-level dashboard layout the user wants — see `DashboardMode`.
protocol DashboardModeStore: AnyObject {
    func mode() -> DashboardMode
    func setMode(_ mode: DashboardMode)
}

final class UserDefaultsDashboardModeStore: DashboardModeStore {
    private let defaults = UserDefaults.standard
    private static let key = "vlinkerobd.dashboard_mode"

    func mode() -> DashboardMode {
        DashboardMode(rawValue: defaults.string(forKey: Self.key) ?? "") ?? .standard
    }

    func setMode(_ mode: DashboardMode) {
        defaults.set(mode.rawValue, forKey: Self.key)
    }
}

/// 標準介面 (existing card/scroll layout) vs 行車動態介面 (landscape concentric-gauge layout).
enum DashboardMode: String, CaseIterable {
    case standard
    case drivingDynamics

    var displayNameZh: String {
        switch self {
        case .standard: return "標準模式"
        case .drivingDynamics: return "座艙模式"
        }
    }
}
