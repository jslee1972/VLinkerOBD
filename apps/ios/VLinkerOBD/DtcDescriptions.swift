import Foundation

private struct DtcCodeEntry: Codable {
    let code: String
    let description: String
}

/// Human-readable descriptions for DTCs. Layered lookup, most-trusted first:
/// 1. A curated set of Traditional Chinese translations for the most common SAE J2012 generic
///    codes.
/// 2. The brand-specific English database for `brand`, when given and loaded.
/// 3. The English generic (SAE J2012, manufacturer-independent) database.
/// 4. An honest "no built-in description" message — never a fabricated translation or guess.
///
/// Faithful port of Android's `DtcDescriptions.kt`. The English databases are the same
/// shared/dtc-codes JSON files bundled into this app's Resources.
final class DtcDescriptions {
    private let genericEn: [String: String]
    private let brandEn: [String: [String: String]]

    init(genericEn: [String: String], brandEn: [String: [String: String]]) {
        self.genericEn = genericEn
        self.brandEn = brandEn
    }

    func describe(_ code: String, brand: String? = nil) -> String {
        let upper = code.uppercased()
        if let curated = Self.curatedZh[upper] { return curated }
        if let brand, let table = brandEn[brand.uppercased()], let text = table[upper] {
            return "\(text)（英文原文，尚無中文翻譯）"
        }
        if let text = genericEn[upper] {
            return "\(text)（英文原文，尚無中文翻譯）"
        }
        return Self.fallbackMessage
    }

    private static let fallbackMessage = "此故障碼尚無內建說明，建議查詢車廠維修手冊或委由專業技師診斷。"

    /// Brands with a synced dtc-codes/<brand>.json — single source of truth for `load`'s default.
    static let supportedBrands = ["Ford", "Mazda", "Honda", "BMW", "Toyota", "Mercedes-Benz", "Volkswagen", "Kia", "Mitsubishi"]

    /// Chinese category label from the DTC's leading letter (P/C/B/U). Needs no loaded data.
    static func categoryName(_ code: String) -> String {
        switch code.first.map({ Character($0.uppercased()) }) {
        case "P": return "動力系統"
        case "C": return "底盤"
        case "B": return "車身"
        case "U": return "網路通訊"
        default: return "未知類別"
        }
    }

    /// Loads the English generic + brand-specific DTC databases bundled from shared/dtc-codes.
    static func load(brands: [String] = DtcDescriptions.supportedBrands.map { $0.lowercased() }) -> DtcDescriptions {
        let generic = parseCodeMap(resourceName: "generic")
        var byBrand: [String: [String: String]] = [:]
        for brand in brands {
            byBrand[brand.uppercased()] = parseCodeMap(resourceName: brand)
        }
        return DtcDescriptions(genericEn: generic, brandEn: byBrand)
    }

    /// Only the curated Chinese descriptions and fallback message — no English data loaded.
    static func withoutEnglishData() -> DtcDescriptions {
        DtcDescriptions(genericEn: [:], brandEn: [:])
    }

    private static func parseCodeMap(resourceName: String) -> [String: String] {
        guard let url = Bundle.main.url(forResource: resourceName, withExtension: "json", subdirectory: "dtc-codes"),
              let data = try? Data(contentsOf: url),
              let items = try? JSONDecoder().decode([DtcCodeEntry].self, from: data) else {
            return [:]
        }
        var map: [String: String] = [:]
        for item in items {
            map[item.code.uppercased()] = item.description
        }
        return map
    }

    private static let curatedZh: [String: String] = [
        // U0xxx - network / module communication
        "U0001": "高速 CAN 通訊匯流排異常",
        "U0073": "控制模組通訊匯流排關閉（Bus Off）",
        "U0100": "與引擎/傳動控制模組（ECM/PCM）失去通訊",
        "U0101": "與變速箱控制模組（TCM）失去通訊",
        "U0121": "與 ABS（防鎖死煞車系統）控制模組失去通訊",
        "U0140": "與車身控制模組（BCM）失去通訊",
        "U0155": "與儀表板顯示模組（IPC）失去通訊",
        "U0164": "與空調控制模組失去通訊",
        "U0184": "與音響主機失去通訊",
        "U0300": "控制模組軟體版本不相容",
        // P01xx - fuel/air metering, sensors
        "P0100": "空氣流量感知器（MAF）電路異常",
        "P0101": "空氣流量感知器（MAF）訊號範圍/效能異常",
        "P0102": "空氣流量感知器（MAF）訊號過低",
        "P0103": "空氣流量感知器（MAF）訊號過高",
        "P0110": "進氣溫度感知器電路異常",
        "P0115": "引擎冷卻液溫度感知器電路異常",
        "P0116": "引擎冷卻液溫度感知器訊號範圍/效能異常",
        "P0117": "引擎冷卻液溫度感知器訊號過低",
        "P0118": "引擎冷卻液溫度感知器訊號過高",
        "P0120": "節氣門位置感知器電路異常",
        "P0125": "冷卻液溫度不足，無法進入閉環燃油控制",
        "P0128": "冷卻液溫度低於節溫器（水龜）應達到的調節溫度",
        "P0130": "氧感應器電路異常（Bank 1 Sensor 1）",
        "P0131": "氧感應器電壓過低（Bank 1 Sensor 1）",
        "P0132": "氧感應器電壓過高（Bank 1 Sensor 1）",
        "P0133": "氧感應器反應遲緩（Bank 1 Sensor 1）",
        "P0134": "氧感應器無反應（Bank 1 Sensor 1）",
        "P0135": "氧感應器加熱電路異常（Bank 1 Sensor 1）",
        "P0150": "氧感應器電路異常（Bank 2 Sensor 1）",
        "P0171": "燃油系統過稀（Bank 1）",
        "P0172": "燃油系統過濃（Bank 1）",
        "P0174": "燃油系統過稀（Bank 2）",
        "P0175": "燃油系統過濃（Bank 2）",
        "P0200": "噴油嘴電路異常",
        "P0217": "引擎過熱",
        "P0300": "隨機／多缸失火",
        "P0301": "第 1 缸失火",
        "P0302": "第 2 缸失火",
        "P0303": "第 3 缸失火",
        "P0304": "第 4 缸失火",
        "P0305": "第 5 缸失火",
        "P0306": "第 6 缸失火",
        "P0307": "第 7 缸失火",
        "P0308": "第 8 缸失火",
        "P0325": "爆震感知器電路異常（Bank 1）",
        "P0335": "曲軸位置感知器電路異常",
        "P0340": "凸輪軸位置感知器電路異常",
        "P0401": "廢氣再循環（EGR）流量不足",
        "P0420": "觸媒轉換器效率低於標準（Bank 1）",
        "P0430": "觸媒轉換器效率低於標準（Bank 2）",
        "P0440": "油氣蒸發排放控制系統異常",
        "P0442": "油氣蒸發系統偵測到小型洩漏",
        "P0446": "油氣蒸發系統通氣控制電路異常",
        "P0455": "油氣蒸發系統偵測到大型洩漏",
        "P0500": "車速感知器異常",
        "P0505": "怠速控制系統異常",
        "P0562": "系統電壓過低",
        "P0563": "系統電壓過高",
        "P0601": "控制模組記憶體檢查碼錯誤",
        "P0603": "控制模組長效記憶體（KAM）錯誤",
        "P0606": "ECM/PCM 處理器故障",
        "P0700": "變速箱控制系統異常（請另查變速箱專屬故障碼）",
        // C0xxx - chassis, ABS wheel speed sensors are fairly standardized
        "C0035": "左前輪速感知器電路異常",
        "C0040": "右前輪速感知器電路異常",
        "C0045": "左後輪速感知器電路異常",
        "C0050": "右後輪速感知器電路異常",
        "C0110": "ABS 泵浦馬達電路異常",
        "C0121": "ABS 電磁閥繼電器電路異常",
    ]
}
