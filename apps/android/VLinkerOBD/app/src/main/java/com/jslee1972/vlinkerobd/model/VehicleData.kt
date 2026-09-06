package com.jslee1972.vlinkerobd.model

/** Typed vehicle readings the UI renders. Never populated directly from raw OBD text. */
data class VehicleData(
    val speedKph: Int? = null,
    val rpm: Int? = null,
)
