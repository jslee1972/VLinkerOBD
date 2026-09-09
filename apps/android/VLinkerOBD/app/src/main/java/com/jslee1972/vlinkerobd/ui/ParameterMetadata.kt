package com.jslee1972.vlinkerobd.ui

import com.jslee1972.vlinkerobd.obd.PidDefinition
import com.jslee1972.vlinkerobd.obd.VehicleProfile

/**
 * Per-field Chinese display name / long description / UI group, sourced from each PID's own
 * `displayNameZh`/`descriptionZh`/`group` in shared/vehicle-profiles/ JSON files (schema v5) —
 * replaces the old hand-maintained PidDisplayNames/ParameterDescriptions/ParameterGroups tables,
 * which duplicated this exact content and had to be kept in sync with iOS by hand. Built once
 * from every loaded profile (universal + all brand profiles, not just the selected one — same
 * scope as DashboardViewModel.allKnownFields) so any field can be looked up regardless of which
 * brand is currently selected.
 *
 * A handful of fields aren't PIDs at all — the trip computer's derived values (see
 * DashboardViewModel.updateTripComputer) — and so can't live in the JSON; those keep a small
 * hardcoded table here, unchanged from before the migration.
 */
class ParameterMetadata(profiles: List<VehicleProfile>) {

    private val displayNames: Map<String, String>
    private val descriptions: Map<String, String>
    private val groups: Map<String, String>

    init {
        val allPids: List<PidDefinition> = profiles.flatMap { profile ->
            profile.pids + profile.models.flatMap { it.pids }
        }
        displayNames = TRIP_COMPUTER_DISPLAY_NAMES +
            allPids.mapNotNull { pid -> pid.displayNameZh?.let { pid.field to it } }.toMap()
        descriptions = TRIP_COMPUTER_DESCRIPTIONS +
            allPids.mapNotNull { pid -> pid.descriptionZh?.let { pid.field to it } }.toMap()
        groups = TRIP_COMPUTER_GROUPS +
            allPids.mapNotNull { pid -> pid.group?.let { pid.field to it } }.toMap()
    }

    /** Traditional Chinese display name for [field], or the raw field key if untranslated. */
    fun displayName(field: String): String = displayNames[field] ?: field

    /** Traditional-Chinese explanation for [field], or a generic fallback if not yet documented. */
    fun description(field: String): String = descriptions[field] ?: "尚無詳細說明。"

    /** Which section [field] belongs to in the live-readings grid; unlisted fields default to
     * brand-specific, so any brand profile's PIDs group correctly without needing an entry here. */
    fun groupFor(field: String): String = groups[field] ?: ParameterGroups.BRAND_SPECIFIC

    companion object {
        private val TRIP_COMPUTER_DISPLAY_NAMES: Map<String, String> = mapOf(
            "instantFuelConsumption" to "瞬時油耗",
            "averageFuelConsumption" to "平均油耗",
            "acceleration" to "加速度",
            "tripDistance" to "行駛里程",
            "tripDuration" to "行駛時間",
        )

        private val TRIP_COMPUTER_DESCRIPTIONS: Map<String, String> = mapOf(
            "instantFuelConsumption" to "用進氣流量（MAF）換算出的瞬時油耗估算值，單位是每公升可以跑幾公里。這不是車輛直接回報的數字，是這個 App 自己算出來的估計值，僅供參考。",
            "averageFuelConsumption" to "本次連線以來的累計油耗估算值（公里/公升），從瞬時油耗持續累加距離與耗油量算出來的，同樣是估算值，不是原廠儀表板的油耗數字。",
            "acceleration" to "由車速變化率計算出的加速度，正值代表加速、負值代表減速。想觀察開車習慣是否平順（急加速/急煞車）時適合勾選。",
            "tripDistance" to "本次連線後累計行駛的距離估算值，斷線或重新連線會歸零，不是車輛本身的累積里程表。",
            "tripDuration" to "本次連線後經過的時間，跟引擎是否發動、是否行駛無關，純粹是連線後經過的分鐘數。",
        )

        private val TRIP_COMPUTER_GROUPS: Map<String, String> =
            ParameterGroups.TRIP_COMPUTER_FIELDS.associateWith { ParameterGroups.TRIP_COMPUTER }
    }
}
