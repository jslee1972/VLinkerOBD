package com.jslee1972.vlinkerobd.ble

/**
 * Ranks scanned BLE devices so likely vLinker adapters float to the top of the list without
 * being auto-connected (the user still picks the device manually).
 */
object BleDeviceRanking {

    private val preferredNamePattern = Regex("V[\\s-]?LINK(?:ER)?", RegexOption.IGNORE_CASE)

    fun isPreferred(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return preferredNamePattern.containsMatchIn(name)
    }

    /** Preferred devices first, then by RSSI descending, with address as a stable tiebreaker. */
    fun rank(devices: List<ScannedBleDevice>): List<ScannedBleDevice> {
        return devices.sortedWith(
            compareByDescending<ScannedBleDevice> { it.isPreferred }
                .thenByDescending { it.rssi }
                .thenBy { it.address }
        )
    }
}
