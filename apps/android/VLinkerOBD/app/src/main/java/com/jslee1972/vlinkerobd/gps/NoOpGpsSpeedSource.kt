package com.jslee1972.vlinkerobd.gps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Default [GpsSpeedSource] for contexts (tests, previews) that don't wire up real GPS. */
object NoOpGpsSpeedSource : GpsSpeedSource {
    override val speedKph: StateFlow<Float?> = MutableStateFlow(null)
    override fun start() = Unit
    override fun stop() = Unit
}
