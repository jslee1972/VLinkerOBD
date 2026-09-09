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
}
