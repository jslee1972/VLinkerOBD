package com.jslee1972.vlinkerobd.ble

data class ScannedBleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val isPreferred: Boolean,
)

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    DISCOVERING_GATT,
    INITIALIZING,
    READY,
    DISCONNECTED_AFTER_ERROR,
    ERROR,
}
