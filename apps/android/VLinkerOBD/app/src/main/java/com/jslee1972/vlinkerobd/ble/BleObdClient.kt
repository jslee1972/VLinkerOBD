package com.jslee1972.vlinkerobd.ble

import com.jslee1972.vlinkerobd.obd.ObdTransport
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** BLE-backed [ObdTransport] plus the scan/connect lifecycle the Dashboard drives. */
interface BleObdClient : ObdTransport {
    val devices: StateFlow<List<ScannedBleDevice>>
    val connectionState: StateFlow<ConnectionState>
    val logs: SharedFlow<String>

    fun startScan()
    fun stopScan()
    fun connect(device: ScannedBleDevice)
    fun disconnect()
}
