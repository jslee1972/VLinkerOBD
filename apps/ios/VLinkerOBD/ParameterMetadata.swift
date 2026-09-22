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

        var names = Self.tripComputerDisplayNames.merging(Self.phoneSensorDisplayNames) { _, new in new }
        var descs = Self.tripComputerDescriptions.merging(Self.phoneSensorDescriptions) { _, new in new }
        var grps = Self.tripComputerGroups.merging(Self.phoneSensorGroups) { _, new in new }
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
        // Sourced from the phone's own accelerometer (CoreMotion), not an OBD speed-delta
        // estimate — see MotionDataSource for the derivation and why lateral/longitudinal are
        // combined into one magnitude here instead of two separate fields.
        "acceleration": "手機加速度計算出的目前受力大小（G 值），已扣除重力、也依手機掛架的前後傾角自動校正，不受掛架角度影響。這是前後（加速/煞車）跟左右（過彎）合併後的總和大小，急加速、急煞車、急轉彎都會讓這個數字變大，但沒辦法從數字分辨是哪一種。由手機自己的感測器算出，不經過 OBD。",
        "tripDistance": "本次連線後累計行駛的距離估算值，斷線或重新連線會歸零，不是車輛本身的累積里程表。",
        "tripDuration": "本次連線後經過的時間，跟引擎是否發動、是否行駛無關，純粹是連線後經過的分鐘數。",
    ]

    private static let tripComputerGroups: [String: String] = Dictionary(
        uniqueKeysWithValues: ParameterGroups.tripComputerFields.map { ($0, ParameterGroups.tripComputer) }
    )

    /// Phone-sensor-derived fields — see `GpsSpeedSource` and `ParameterGroups.phoneSensorFields`'s
    /// own doc comment for why heading/roll/G-force aren't included here.
    private static let phoneSensorDisplayNames: [String: String] = [
        "altitudeM": "海拔高度",
        "gpsAccuracyM": "GPS 定位精確度",
    ]

    private static let phoneSensorDescriptions: [String: String] = [
        "altitudeM": "手機 GPS 回報的目前海拔高度（公尺），跟車輛本身無關，純粹是手機定位系統算出來的估計值，山路/爬坡時可以看到變化。",
        "gpsAccuracyM": "目前 GPS 定位的誤差半徑（公尺），數字越小代表定位越準。大約 10 公尺以內算不錯，超過 30 公尺（常見於剛開機、隧道、高樓密集區）代表訊號不好，這時 GPS 車速／海拔／方向這幾個參數的可信度都會跟著變差。",
    ]

    private static let phoneSensorGroups: [String: String] = Dictionary(
        uniqueKeysWithValues: ParameterGroups.phoneSensorFields.map { ($0, ParameterGroups.phoneSensor) }
    )
}
