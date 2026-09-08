package com.jslee1972.vlinkerobd.ui

/**
 * Groups live-parameter fields into labeled sections for the "即時參數" grid, the same way the
 * dashboard already groups speed+RPM into one gauge card. Only universal-obd2.json's fields (plus
 * the derived trip-computer ones) are enumerated here — any field not listed falls back to
 * [BRAND_SPECIFIC], so a brand profile's own PIDs (Citroën, Mazda, ...) are grouped automatically
 * without needing to list every one of them here.
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

    private val groupByField: Map<String, String> = buildMap {
        putAllFor(TRIP_COMPUTER, *TRIP_COMPUTER_FIELDS.toTypedArray())
        putAllFor(
            ENGINE,
            "engineLoadPercent", "throttlePercent", "relativeThrottlePercent", "relativeAcceleratorPedalPercent",
            "driverDemandTorquePercent", "actualEngineTorquePercent", "engineReferenceTorqueNM",
            "timingAdvanceDegrees", "runtimeSinceStartSec",
        )
        putAllFor(
            TEMPERATURE,
            "coolantTempC", "intakeAirTempC", "ambientAirTempC", "engineOilTempC", "chargeAirCoolerTempC",
            "exhaustGasTempBank1Sensor1C", "exhaustGasTempBank1Sensor2C", "exhaustGasTempBank1Sensor3C",
        )
        putAllFor(
            PRESSURE,
            "intakeManifoldPressureKPA", "fuelPressureKPA", "barometricPressureKPA",
            "boostPressureCommandedKPA", "boostPressureActualKPA", "exhaustPressureBank1KPA",
            "dpfInletPressureKPA", "dpfOutletPressureKPA", "fuelRailPressureCommandedKPA",
            "fuelRailPressureActualKPA", "fuelRailPressureAbsoluteKPA",
        )
        putAllFor(
            FUEL_EMISSIONS,
            "fuelLevelPercent", "shortTermFuelTrimBank1Percent", "longTermFuelTrimBank1Percent",
            "shortTermFuelTrimBank2Percent", "longTermFuelTrimBank2Percent", "lambdaBank1Sensor1",
            "commandedEquivalenceRatio", "ethanolFuelPercent", "engineFuelRateLPH",
            "noxBank1Sensor1PPM", "absoluteLoadPercent",
        )
        putAllFor(ELECTRICAL, "controlModuleVoltage", "distanceWithMilOnKM")
    }

    fun groupFor(field: String): String = groupByField[field] ?: BRAND_SPECIFIC

    private fun MutableMap<String, String>.putAllFor(group: String, vararg fields: String) {
        fields.forEach { put(it, group) }
    }
}
