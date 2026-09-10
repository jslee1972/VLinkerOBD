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

/// Remembers which (at most 2) fields the user chose for the ring gauge's own center legend —
/// separate from the custom section's field set above, since this is a much smaller, fixed-size
/// slot with room for only a couple of compact readouts next to the ring.
protocol RingLegendFieldsStore: AnyObject {
    func fields() -> [String]
    func setFields(_ fields: [String])
}

final class UserDefaultsRingLegendFieldsStore: RingLegendFieldsStore {
    private let defaults = UserDefaults.standard
    private static let key = "vlinkerobd.ring_legend.fields"

    /// Same "never configured vs. explicitly configured" distinction as `CustomSectionStore` —
    /// `stringArray(forKey:)` is nil only before the user has ever touched the picker.
    func fields() -> [String] {
        defaults.stringArray(forKey: Self.key) ?? ParameterGroups.defaultRingLegendFields
    }

    func setFields(_ fields: [String]) {
        defaults.set(fields, forKey: Self.key)
    }
}

