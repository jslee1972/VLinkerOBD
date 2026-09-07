package com.jslee1972.vlinkerobd.obd

/**
 * Human-readable descriptions for DTCs. Layered lookup, most-trusted first:
 * 1. A curated set of Traditional Chinese translations for the most common SAE J2012 generic
 *    codes (cross-checked against the English source below — same meaning, native-language UX).
 * 2. The brand-specific English database for [brand], when given and loaded.
 * 3. The English generic (SAE J2012, manufacturer-independent) database.
 * 4. An honest "no built-in description" message — never a fabricated translation or guess.
 *
 * The English databases (2 and 3) are synced from shared/dtc-codes, sourced from
 * https://github.com/Wal33D/dtc-database (MIT License; see shared/dtc-codes/README.md for the
 * spot-check that validated it against the curated Chinese set).
 */
class DtcDescriptions(
    private val genericEn: Map<String, String>,
    private val brandEn: Map<String, Map<String, String>>,
) {
    fun describe(code: String, brand: String? = null): String {
        val upper = code.uppercase()
        curatedZh[upper]?.let { return it }
        brand?.let { brandEn[it.uppercase()] }?.get(upper)?.let { return "$it（英文原文，尚無中文翻譯）" }
        genericEn[upper]?.let { return "$it（英文原文，尚無中文翻譯）" }
        return FALLBACK_MESSAGE
    }

    companion object {
        private const val FALLBACK_MESSAGE = "此故障碼尚無內建說明，建議查詢車廠維修手冊或委由專業技師診斷。"

        /** Chinese category label from the DTC's leading letter (P/C/B/U). Needs no loaded data. */
        fun categoryName(code: String): String = when (code.firstOrNull()?.uppercaseChar()) {
            'P' -> "動力系統"
            'C' -> "底盤"
            'B' -> "車身"
            'U' -> "網路通訊"
            else -> "未知類別"
        }

        /** Loads the English generic + brand-specific DTC databases synced from shared/dtc-codes. */
        fun load(readAsset: (String) -> String, brands: List<String> = listOf("ford", "mazda", "honda")): DtcDescriptions {
            val generic = parseCodeMap(readAsset("dtc-codes/generic.json"))
            val byBrand = brands.associate { brand -> brand.uppercase() to parseCodeMap(readAsset("dtc-codes/$brand.json")) }
            return DtcDescriptions(generic, byBrand)
        }

        /** Only the curated Chinese descriptions and fallback message — no English data loaded. */
        fun withoutEnglishData(): DtcDescriptions = DtcDescriptions(emptyMap(), emptyMap())

        private fun parseCodeMap(json: String): Map<String, String> {
            val items = MiniJson.parse(json).asArray()
            return items.associate { item ->
                val obj = item.asObject()
                val code = (obj["code"] as JsonValue.JsonString).value.uppercase()
                val description = (obj["description"] as JsonValue.JsonString).value
                code to description
            }
        }

        private val curatedZh: Map<String, String> = mapOf(
            // U0xxx - network / module communication
            "U0001" to "高速 CAN 通訊匯流排異常",
            "U0073" to "控制模組通訊匯流排關閉（Bus Off）",
            "U0100" to "與引擎/傳動控制模組（ECM/PCM）失去通訊",
            "U0101" to "與變速箱控制模組（TCM）失去通訊",
            "U0121" to "與 ABS（防鎖死煞車系統）控制模組失去通訊",
            "U0140" to "與車身控制模組（BCM）失去通訊",
            "U0155" to "與儀表板顯示模組（IPC）失去通訊",
            "U0164" to "與空調控制模組失去通訊",
            "U0184" to "與音響主機失去通訊",
            "U0300" to "控制模組軟體版本不相容",
            // P01xx - fuel/air metering, sensors
            "P0100" to "空氣流量感知器（MAF）電路異常",
            "P0101" to "空氣流量感知器（MAF）訊號範圍/效能異常",
            "P0102" to "空氣流量感知器（MAF）訊號過低",
            "P0103" to "空氣流量感知器（MAF）訊號過高",
            "P0110" to "進氣溫度感知器電路異常",
            "P0115" to "引擎冷卻液溫度感知器電路異常",
            "P0116" to "引擎冷卻液溫度感知器訊號範圍/效能異常",
            "P0117" to "引擎冷卻液溫度感知器訊號過低",
            "P0118" to "引擎冷卻液溫度感知器訊號過高",
            "P0120" to "節氣門位置感知器電路異常",
            "P0125" to "冷卻液溫度不足，無法進入閉環燃油控制",
            "P0128" to "冷卻液溫度低於節溫器（水龜）應達到的調節溫度",
            "P0130" to "氧感應器電路異常（Bank 1 Sensor 1）",
            "P0131" to "氧感應器電壓過低（Bank 1 Sensor 1）",
            "P0132" to "氧感應器電壓過高（Bank 1 Sensor 1）",
            "P0133" to "氧感應器反應遲緩（Bank 1 Sensor 1）",
            "P0134" to "氧感應器無反應（Bank 1 Sensor 1）",
            "P0135" to "氧感應器加熱電路異常（Bank 1 Sensor 1）",
            "P0150" to "氧感應器電路異常（Bank 2 Sensor 1）",
            "P0171" to "燃油系統過稀（Bank 1）",
            "P0172" to "燃油系統過濃（Bank 1）",
            "P0174" to "燃油系統過稀（Bank 2）",
            "P0175" to "燃油系統過濃（Bank 2）",
            "P0200" to "噴油嘴電路異常",
            "P0217" to "引擎過熱",
            "P0300" to "隨機／多缸失火",
            "P0301" to "第 1 缸失火",
            "P0302" to "第 2 缸失火",
            "P0303" to "第 3 缸失火",
            "P0304" to "第 4 缸失火",
            "P0305" to "第 5 缸失火",
            "P0306" to "第 6 缸失火",
            "P0307" to "第 7 缸失火",
            "P0308" to "第 8 缸失火",
            "P0325" to "爆震感知器電路異常（Bank 1）",
            "P0335" to "曲軸位置感知器電路異常",
            "P0340" to "凸輪軸位置感知器電路異常",
            "P0401" to "廢氣再循環（EGR）流量不足",
            "P0420" to "觸媒轉換器效率低於標準（Bank 1）",
            "P0430" to "觸媒轉換器效率低於標準（Bank 2）",
            "P0440" to "油氣蒸發排放控制系統異常",
            "P0442" to "油氣蒸發系統偵測到小型洩漏",
            "P0446" to "油氣蒸發系統通氣控制電路異常",
            "P0455" to "油氣蒸發系統偵測到大型洩漏",
            "P0500" to "車速感知器異常",
            "P0505" to "怠速控制系統異常",
            "P0562" to "系統電壓過低",
            "P0563" to "系統電壓過高",
            "P0601" to "控制模組記憶體檢查碼錯誤",
            "P0603" to "控制模組長效記憶體（KAM）錯誤",
            "P0606" to "ECM/PCM 處理器故障",
            "P0700" to "變速箱控制系統異常（請另查變速箱專屬故障碼）",
            // C0xxx - chassis, ABS wheel speed sensors are fairly standardized
            "C0035" to "左前輪速感知器電路異常",
            "C0040" to "右前輪速感知器電路異常",
            "C0045" to "左後輪速感知器電路異常",
            "C0050" to "右後輪速感知器電路異常",
            "C0110" to "ABS 泵浦馬達電路異常",
            "C0121" to "ABS 電磁閥繼電器電路異常",
        )
    }
}
