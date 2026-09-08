package com.jslee1972.vlinkerobd.gps

import kotlinx.coroutines.flow.StateFlow

/**
 * Phone-GPS-derived speed, kept separate from the vehicle's own OBD-reported speed so the two can
 * be shown side by side for comparison (a real-world sanity check on the vehicle's speed sensor).
 * Abstracted behind an interface — like [com.jslee1972.vlinkerobd.ble.BleObdClient] — so
 * [com.jslee1972.vlinkerobd.ui.DashboardViewModel] never touches Android's location APIs directly
 * and stays unit-testable with a fake.
 */
interface GpsSpeedSource {
    /** Current GPS-derived speed in km/h, or null before a first fix / while stopped listening. */
    val speedKph: StateFlow<Float?>

    /** Starts listening for location updates. A no-op if already started or permission is missing. */
    fun start()

    /** Stops listening; [speedKph] resets to null. */
    fun stop()
}
