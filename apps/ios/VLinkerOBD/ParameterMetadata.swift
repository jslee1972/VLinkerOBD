import Foundation

/// Per-field Chinese display name / long description / UI group, sourced from each PID's own
/// `displayNameZh`/`descriptionZh`/`group` in shared/vehicle-profiles JSON (schema v5) — the same
/// source Android's `ParameterMetadata.kt` reads, so both platforms render identical labels
/// without maintaining separate lookup tables.
///
/// A handful of fields aren't PIDs at all — the trip computer's derived values (see
/// `DashboardController.updateTripComputer`) — and so can't live in the JSON; those keep a small
/// hardcoded table here.
final class ParameterMetadata {
    private let displayNames: [String: String]
    private let descriptions: [String: String]
    private let groups: [String: String]

    init(profiles: [VehicleProfile]) {
        let allPids = profiles.flatMap { profile in profile.pids + profile.models.flatMap { $0.pids } }

        var names = Self.tripComputerDisplayNames
        var descs = Self.tripComputerDescriptions
        var grps = Self.tripComputerGroups
        for pid in allPids {
            if let name = pid.displayNameZh { names[pid.field] = name }
            if let desc = pid.descriptionZh { descs[pid.field] = desc }
            if let group = pid.group { grps[pid.field] = group }
        }
        displayNames = names
        descriptions = descs
        groups = grps
    }

    /// Traditional Chinese display name for `field`, or the raw field key if untranslated.
    func displayName(_ field: String) -> String { displayNames[field] ?? field }

    /// Traditional-Chinese explanation for `field`, or a generic fallback if not yet documented.
    func description(_ field: String) -> String { descriptions[field] ?? "尚無詳細說明。" }

    /// Which section `field` belongs to in the live-readings grid; unlisted fields default to
    /// brand-specific, so any brand profile's PIDs group correctly without needing an entry here.
    func groupFor(_ field: String) -> String { groups[field] ?? ParameterGroups.brandSpecific }

    private static let tripComputerDisplayNames: [String: String] = [
        "instantFuelConsumption": "瞬時油耗",
        "averageFuelConsumption": "平均油耗",
        "acceleration": "加速度",
        "tripDistance": "行駛里程",
        "tripDuration": "行駛時間",
    ]

    private static let tripComputerDescriptions: [String: String] = [
        "instantFuelConsumption": "用進氣流量（MAF）換算出的瞬時油耗估算值，單位是每公升可以跑幾公里。這不是車輛直接回報的數字，是這個 App 自己算出來的估計值，僅供參考。",
        "averageFuelConsumption": "本次連線以來的累計油耗估算值（公里/公升），從瞬時油耗持續累加距離與耗油量算出來的，同樣是估算值，不是原廠儀表板的油耗數字。",
        "acceleration": "由車速變化率計算出的加速度，正值代表加速、負值代表減速。想觀察開車習慣是否平順（急加速/急煞車）時適合勾選。",
        "tripDistance": "本次連線後累計行駛的距離估算值，斷線或重新連線會歸零，不是車輛本身的累積里程表。",
        "tripDuration": "本次連線後經過的時間，跟引擎是否發動、是否行駛無關，純粹是連線後經過的分鐘數。",
    ]

    private static let tripComputerGroups: [String: String] = Dictionary(
        uniqueKeysWithValues: ParameterGroups.tripComputerFields.map { ($0, ParameterGroups.tripComputer) }
    )
}
