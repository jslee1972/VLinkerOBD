package com.jslee1972.vlinkerobd.ui

/**
 * Section labels and display order for the "即時參數" grid. Which field belongs to which group is
 * now data (each PID's own `group` in shared/vehicle-profiles/ JSON files, schema v5 — see
 * [ParameterMetadata]); this object only keeps the fixed set of section names and the order they
 * render in, which is a layout concern, not vehicle data.
 */
object ParameterGroups {
    const val TRIP_COMPUTER = "行車電腦"
    const val ENGINE = "引擎與動力"
    const val TEMPERATURE = "溫度"
    const val PRESSURE = "壓力"
    const val FUEL_EMISSIONS = "燃油與排放"
    const val ELECTRICAL = "電力與診斷"
    const val BRAND_SPECIFIC = "廠牌專屬"

    /** Render order for the sections — trip computer and engine first as the most driving-relevant. */
    val displayOrder = listOf(TRIP_COMPUTER, ENGINE, TEMPERATURE, PRESSURE, FUEL_EMISSIONS, ELECTRICAL, BRAND_SPECIFIC)

    /** The derived (non-PID) fields DashboardViewModel.updateTripComputer produces — also needed
     * by the custom-section field picker's "known fields" list, so exposed rather than duplicated. */
    val TRIP_COMPUTER_FIELDS = listOf(
        "instantFuelConsumption", "averageFuelConsumption", "acceleration", "tripDistance", "tripDuration",
    )

    /** Restricted to universal (never brand-specific) fields so it's meaningful on any vehicle.
     * Only ever consulted by [SharedPreferencesCustomSectionStore] when nothing has been
     * configured yet — the user can freely add/remove from here afterward, including clearing it
     * entirely via the picker's "全部移除". */
    val DEFAULT_CUSTOM_FIELDS = listOf(
        "coolantTempC", "fuelLevelPercent", "controlModuleVoltage", "engineLoadPercent",
        "instantFuelConsumption", "averageFuelConsumption", "tripDistance", "ambientAirTempC",
    )

    /** The driving-dynamics ring gauge's own center legend defaults to 水溫 + 油量 — the two
     * figures that used to be hardcoded there before it became user-configurable. Only ever
     * consulted before the user has picked their own pair (see [RingLegendFieldsStore]). */
    val DEFAULT_RING_LEGEND_FIELDS = listOf("coolantTempC", "fuelLevelPercent")
}
