package com.jslee1972.vlinkerobd.ble

/** Remembers the last successfully-connected vLinker address so the app can auto-reconnect. */
interface DeviceMemory {
    fun lastDeviceAddress(): String?
    fun rememberDevice(address: String)
}

object NoOpDeviceMemory : DeviceMemory {
    override fun lastDeviceAddress(): String? = null
    override fun rememberDevice(address: String) = Unit
}
