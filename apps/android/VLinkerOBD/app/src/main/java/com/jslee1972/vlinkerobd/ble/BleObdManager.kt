package com.jslee1972.vlinkerobd.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import com.jslee1972.vlinkerobd.obd.ObdTransport
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android BLE implementation of [BleObdClient]. Discovers a compatible notify/write
 * characteristic pair via [GattCharacteristicSelector] rather than assuming a fixed vLinker
 * UUID (see apps/android/VLinkerOBD/docs design notes) and serializes GATT writes by awaiting
 * each `onCharacteristicWrite` callback before completing [write].
 *
 * Permission checks happen in the UI layer before scan/connect are called; BLE calls here are
 * wrapped where a [SecurityException] is realistically possible so a revoked/missing permission
 * surfaces as an error state instead of crashing.
 */
@SuppressLint("MissingPermission")
class BleObdManager(private val context: Context) : BleObdClient, ObdTransport {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter

    private val _devices = MutableStateFlow<List<ScannedBleDevice>>(emptyList())
    override val devices: StateFlow<List<ScannedBleDevice>> = _devices.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingWrite: CompletableDeferred<Result<Unit>>? = null
    private val seenAddresses = mutableSetOf<String>()

    // Owns the service-discovery watchdog (see startDiscoveryWatchdog) — outlives any single
    // connection attempt, so it isn't cancelled and recreated on every connect() call.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discoveryWatchdog: Job? = null
    private var discoveryAttempt = 0
    private var lastConnectDevice: ScannedBleDevice? = null

    private fun log(message: String) {
        _logs.tryEmit(message)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name = result.device.name
            val isNew = seenAddresses.add(address)
            val updated = if (isNew) {
                _devices.value + ScannedBleDevice(address, name, result.rssi, BleDeviceRanking.isPreferred(name))
            } else {
                _devices.value.map { if (it.address == address) it.copy(rssi = result.rssi) else it }
            }
            _devices.value = BleDeviceRanking.rank(updated)
            if (isNew) log("掃描到新裝置：${name ?: "(未知名稱)"} [$address]")
        }

        override fun onScanFailed(errorCode: Int) {
            log("掃描失敗，錯誤代碼 $errorCode")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    override fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (adapter?.isEnabled != true || scanner == null) {
            log("藍牙未開啟或裝置不支援 BLE")
            _connectionState.value = ConnectionState.ERROR
            return
        }
        seenAddresses.clear()
        _devices.value = emptyList()
        _connectionState.value = ConnectionState.SCANNING
        log("開始掃描 BLE 裝置")
        try {
            scanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            log("掃描權限不足：${e.message}")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    override fun stopScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        try {
            scanner.stopScan(scanCallback)
        } catch (e: SecurityException) {
            log("停止掃描失敗：${e.message}")
        }
        log("停止掃描")
        if (_connectionState.value == ConnectionState.SCANNING) {
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    override fun connect(device: ScannedBleDevice) {
        stopScan()
        discoveryAttempt = 0
        lastConnectDevice = device
        beginGattConnection(device)
    }

    /** Does the actual connectGatt() call — shared by [connect] and the discovery watchdog's
     * own-device retry, which must NOT reset [discoveryAttempt] the way a fresh [connect] does. */
    private fun beginGattConnection(device: ScannedBleDevice) {
        val remote = adapter?.getRemoteDevice(device.address)
        if (remote == null) {
            log("找不到裝置 ${device.address}")
            _connectionState.value = ConnectionState.ERROR
            return
        }
        _connectionState.value = ConnectionState.CONNECTING
        log("連線至 ${device.name ?: device.address}")
        try {
            gatt = remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            log("連線權限不足：${e.message}")
            _connectionState.value = ConnectionState.ERROR
        }
    }

    override fun disconnect() {
        discoveryWatchdog?.cancel()
        discoveryWatchdog = null
        discoveryAttempt = 0
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            log("中斷連線時發生權限錯誤：${e.message}")
        }
        gatt = null
        writeCharacteristic = null
        pendingWrite?.complete(Result.failure(IllegalStateException("連線已中斷")))
        pendingWrite = null
        _connectionState.value = ConnectionState.DISCONNECTED
        log("已中斷連線")
    }

    /**
     * Android's BLE stack occasionally takes a long time — or, rarely, never — to call back
     * [BluetoothGattCallback.onServicesDiscovered] after [BluetoothGatt.discoverServices],
     * observed in the field specifically on a phone's first auto-reconnect to a remembered device
     * right after the app starts. A short timeout here turned out to be actively harmful: it cut
     * off connections that would have completed fine given a bit more time, so this waits a
     * generous [SERVICE_DISCOVERY_TIMEOUT_MS] before doing anything. If it does time out, the
     * retry is a full fresh GATT connection (not just re-calling discoverServices on the same,
     * possibly wedged, gatt object) since the earlier stale-cache/race theories point at the
     * connection itself, not just the discovery call. After [MAX_DISCOVERY_ATTEMPTS] full
     * attempts, give up and disconnect — [onConnectionStateChanged] in DashboardViewModel resets
     * the auto-reconnect latch and restarts scanning on that, so the app keeps trying instead of
     * going permanently idle until a manual restart.
     */
    private fun startDiscoveryWatchdog(gattRef: BluetoothGatt) {
        discoveryWatchdog?.cancel()
        discoveryWatchdog = scope.launch {
            delay(SERVICE_DISCOVERY_TIMEOUT_MS)
            if (_connectionState.value != ConnectionState.DISCOVERING_GATT) return@launch
            discoveryAttempt++
            val device = lastConnectDevice
            if (discoveryAttempt >= MAX_DISCOVERY_ATTEMPTS || device == null) {
                log("服務探索逾時（已重試 $discoveryAttempt 次），中斷連線")
                discoveryAttempt = 0
                disconnect()
                return@launch
            }
            log("服務探索逾時，重新建立連線（第 $discoveryAttempt 次重試）")
            try {
                gattRef.close()
            } catch (e: SecurityException) {
                log("關閉連線權限不足：${e.message}")
            }
            beginGattConnection(device)
        }
    }

    override suspend fun write(bytes: ByteArray): Result<Unit> {
        val gattRef = gatt
        val characteristic = writeCharacteristic
        if (gattRef == null || characteristic == null) {
            return Result.failure(IllegalStateException("尚未就緒，無法送出指令"))
        }

        val deferred = CompletableDeferred<Result<Unit>>()
        pendingWrite = deferred

        val accepted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattRef.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                gattRef.writeCharacteristic(characteristic)
            }
        } catch (e: SecurityException) {
            log("寫入權限不足：${e.message}")
            false
        }

        if (!accepted) {
            pendingWrite = null
            return Result.failure(IllegalStateException("BLE 寫入失敗"))
        }
        return deferred.await()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.DISCOVERING_GATT
                    log("GATT 已連線，開始探索服務")
                    try {
                        g.discoverServices()
                        startDiscoveryWatchdog(g)
                    } catch (e: SecurityException) {
                        log("探索服務權限不足：${e.message}")
                        _connectionState.value = ConnectionState.ERROR
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    discoveryWatchdog?.cancel()
                    discoveryWatchdog = null
                    val wasReady = _connectionState.value == ConnectionState.READY ||
                        _connectionState.value == ConnectionState.INITIALIZING
                    log("GATT 已斷線（status=$status）")
                    writeCharacteristic = null
                    pendingWrite?.complete(Result.failure(IllegalStateException("GATT 已斷線")))
                    pendingWrite = null
                    _connectionState.value = if (wasReady) {
                        ConnectionState.DISCONNECTED_AFTER_ERROR
                    } else {
                        ConnectionState.DISCONNECTED
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discoveryWatchdog?.cancel()
            discoveryWatchdog = null
            discoveryAttempt = 0
            val candidates = mutableListOf<GattCharacteristicCandidate>()
            val lookup = mutableMapOf<Pair<String, String>, BluetoothGattCharacteristic>()
            for (service in g.services) {
                for (characteristic in service.characteristics) {
                    val props = characteristic.properties
                    candidates += GattCharacteristicCandidate(
                        serviceUuid = service.uuid.toString(),
                        characteristicUuid = characteristic.uuid.toString(),
                        canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                        canIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                        canWrite = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
                        canWriteWithoutResponse = props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
                    )
                    lookup[service.uuid.toString() to characteristic.uuid.toString()] = characteristic
                    log("發現 characteristic ${characteristic.uuid}（service ${service.uuid}）properties=$props")
                }
            }

            val selection = GattCharacteristicSelector.select(candidates)
            if (selection == null) {
                log("找不到相容的 notify/write characteristic 組合，保留探索紀錄")
                _connectionState.value = ConnectionState.ERROR
                return
            }

            val notifyChar = lookup[selection.notify.serviceUuid to selection.notify.characteristicUuid]
            val writeChar = lookup[selection.write.serviceUuid to selection.write.characteristicUuid]
            if (notifyChar == null || writeChar == null) {
                log("GATT characteristic 對應失敗")
                _connectionState.value = ConnectionState.ERROR
                return
            }
            writeCharacteristic = writeChar

            try {
                g.setCharacteristicNotification(notifyChar, true)
                val cccd = notifyChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                if (cccd == null) {
                    log("找不到 CCCD descriptor")
                    _connectionState.value = ConnectionState.ERROR
                    return
                }
                val enableValue = if (selection.notify.canNotify) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, enableValue)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = enableValue
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } catch (e: SecurityException) {
                log("訂閱通知權限不足：${e.message}")
                _connectionState.value = ConnectionState.ERROR
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("通知訂閱成功，裝置就緒")
                _connectionState.value = ConnectionState.READY
            } else {
                log("通知訂閱失敗 status=$status")
                _connectionState.value = ConnectionState.ERROR
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val deferred = pendingWrite
            pendingWrite = null
            deferred?.complete(
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("BLE 寫入失敗 status=$status"))
                },
            )
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            _incoming.tryEmit(value.copyOf())
        }

        @Deprecated("Deprecated in Android API, kept for devices below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                characteristic.value?.copyOf()?.let { _incoming.tryEmit(it) }
            }
        }
    }

    companion object {
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // 5s turned out to be actively harmful in the field — it cut off first-launch
        // auto-reconnects that would have completed fine given more time. 15s per attempt, up to
        // 2 full attempts, is a more patient balance between "recover from a genuine stall" and
        // "don't kill a connection that's just slow to start."
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 15_000L
        private const val MAX_DISCOVERY_ATTEMPTS = 2
    }
}
