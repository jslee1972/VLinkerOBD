package com.jslee1972.vlinkerobd.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads speed off the phone's own GPS_PROVIDER (not the fused/network provider — the user
 * specifically wants the phone's GPS, not Play Services' best-effort location) via the plain
 * [LocationManager] API, so this needs no extra Play Services dependency. Updates roughly once a
 * second — [MIN_UPDATE_INTERVAL_MS] — matching how often a GPS fix's speed reading is meaningful.
 *
 * Permission checks happen in the UI layer (see MainActivity) before [start] is called; the
 * [SecurityException] guard here is a safety net, not the primary gate, matching the same
 * judgment call [com.jslee1972.vlinkerobd.ble.BleObdManager] makes for Bluetooth.
 */
@SuppressLint("MissingPermission")
class AndroidGpsSpeedSource(private val context: Context) : GpsSpeedSource {

    private val locationManager = context.getSystemService(LocationManager::class.java)

    private val _speedKph = MutableStateFlow<Float?>(null)
    override val speedKph: StateFlow<Float?> = _speedKph.asStateFlow()

    private var listening = false

    private val listener = LocationListener { location: Location ->
        // Not every fix carries a speed reading (e.g. the very first fix after a cold start) —
        // skip those rather than overwriting a good reading with a stale/absent one.
        if (location.hasSpeed()) {
            _speedKph.value = location.speed * 3.6f
        }
    }

    override fun start() {
        if (listening) return
        val manager = locationManager ?: return
        if (manager.allProviders.none { it == LocationManager.GPS_PROVIDER }) return
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_UPDATE_INTERVAL_MS, 0f, listener)
            listening = true
        } catch (e: SecurityException) {
            // No location permission — leave speedKph at null rather than crashing.
        }
    }

    override fun stop() {
        if (!listening) return
        try {
            locationManager?.removeUpdates(listener)
        } catch (e: SecurityException) {
            // Already effectively stopped from this app's point of view.
        }
        listening = false
        _speedKph.value = null
    }

    companion object {
        private const val MIN_UPDATE_INTERVAL_MS = 1000L
    }
}
