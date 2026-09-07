package com.jslee1972.vlinkerobd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jslee1972.vlinkerobd.ble.BleObdClient
import com.jslee1972.vlinkerobd.ble.ConnectionState
import com.jslee1972.vlinkerobd.ble.ScannedBleDevice
import com.jslee1972.vlinkerobd.obd.ObdCommand
import com.jslee1972.vlinkerobd.obd.ObdCommandKind
import com.jslee1972.vlinkerobd.obd.ObdCommandQueue
import com.jslee1972.vlinkerobd.obd.ObdCommandResult
import com.jslee1972.vlinkerobd.obd.DtcParser
import com.jslee1972.vlinkerobd.obd.ObdResponseParser
import com.jslee1972.vlinkerobd.obd.ObdResponseStatus
import com.jslee1972.vlinkerobd.obd.PidDefinition
import com.jslee1972.vlinkerobd.obd.VehicleBrandDetector
import com.jslee1972.vlinkerobd.obd.VehicleProfile
import com.jslee1972.vlinkerobd.model.VehicleData
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Orchestrates permission-agnostic BLE state, ELM/STN initialization, the fast speed/RPM poll,
 * an optional slower brand-PID poll, and manual commands, exposing a single immutable
 * [DashboardUiState]. Depends only on [BleObdClient] (fakeable in tests) — never touches Android
 * Bluetooth classes directly.
 */
class DashboardViewModel(
    private val bleClient: BleObdClient,
    private val universalProfile: VehicleProfile,
    private val brandProfiles: Map<String, VehicleProfile> = emptyMap(),
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    // Production uses viewModelScope (survives config changes, cancelled in onCleared); tests
    // inject runTest's backgroundScope so the infinite polling loop doesn't hang test teardown.
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val queue = ObdCommandQueue(bleClient, scope)
    private val log = BoundedLog()

    private val speedPid = universalProfile.pids.first { it.field == "speedKPH" }
    private val rpmPid = universalProfile.pids.first { it.field == "rpm" }

    private val _uiState = MutableStateFlow(
        DashboardUiState(availableBrands = listOf(UNIVERSAL_BRAND) + brandProfiles.keys),
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var brandPollingJob: Job? = null

    @Volatile
    private var fastLoopPaused = false

    private var speedStaleCount = 0
    private var rpmStaleCount = 0

    init {
        scope.launch { bleClient.logs.collect { appendLog(it) } }
        scope.launch { bleClient.devices.collect { devices -> _uiState.update { it.copy(devices = devices) } } }
        scope.launch { bleClient.connectionState.collect { state -> onConnectionStateChanged(state) } }
    }

    fun startScan() = bleClient.startScan()
    fun stopScan() = bleClient.stopScan()
    fun connect(device: ScannedBleDevice) = bleClient.connect(device)

    fun disconnect() {
        stopPolling()
        bleClient.disconnect()
    }

    fun clearLogs() {
        log.clear()
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun selectBrand(brand: String) {
        _uiState.update { it.copy(selectedBrand = brand, extraReadings = emptyMap()) }
        if (_uiState.value.isReady) restartBrandPolling()
    }

    fun sendManualCommand(text: String) {
        val trimmed = text.trim().uppercase()
        if (trimmed.isEmpty()) return
        scope.launch {
            fastLoopPaused = true
            val result = queue.execute(ObdCommand(trimmed, ObdCommandKind.AT))
            appendLog("手動指令 $trimmed -> $result")
            (result as? ObdCommandResult.Success)?.let { success ->
                _uiState.update { it.copy(rawResponse = success.raw) }
            }
            fastLoopPaused = false
        }
    }

    /** Reads current (Mode 03) DTCs. Pauses the fast loop like a manual command so it doesn't race the poll. */
    fun readTroubleCodes() {
        scope.launch { performTroubleCodeRead() }
    }

    private suspend fun performTroubleCodeRead() {
        fastLoopPaused = true
        _uiState.update { it.copy(isReadingTroubleCodes = true) }
        val result = queue.execute(ObdCommand("03", ObdCommandKind.OBD))
        val raw = (result as? ObdCommandResult.Success)?.raw
        if (raw == null) {
            appendLog("讀取故障碼逾時")
        } else {
            _uiState.update { it.copy(rawResponse = raw) }
            val codes = DtcParser.parse(raw, "03")
            if (codes != null) {
                _uiState.update { it.copy(troubleCodes = codes) }
                appendLog(if (codes.isEmpty()) "讀取故障碼：無故障碼" else "讀取故障碼：${codes.joinToString(", ")}")
            } else {
                appendLog("讀取故障碼失敗")
            }
        }
        _uiState.update { it.copy(isReadingTroubleCodes = false) }
        fastLoopPaused = false
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        queue.close()
    }

    private fun appendLog(line: String) {
        log.append(line)
        _uiState.update { it.copy(logs = log.entries) }
    }

    private fun updateVehicleData(transform: (VehicleData) -> VehicleData) {
        _uiState.update { it.copy(vehicleData = transform(it.vehicleData)) }
    }

    private fun onConnectionStateChanged(state: ConnectionState) {
        _uiState.update {
            it.copy(
                connectionLabel = connectionLabelFor(state),
                isScanning = state == ConnectionState.SCANNING,
                isReady = state == ConnectionState.READY,
                errorMessage = if (state == ConnectionState.ERROR) "發生錯誤，請查看紀錄" else null,
            )
        }
        when (state) {
            ConnectionState.READY -> startInitialization()
            ConnectionState.DISCONNECTED, ConnectionState.DISCONNECTED_AFTER_ERROR, ConnectionState.ERROR -> stopPolling()
            else -> Unit
        }
    }

    private fun startInitialization() {
        scope.launch {
            appendLog("開始初始化 ELM/STN")
            for (command in INIT_SEQUENCE) {
                val result = queue.execute(ObdCommand(command, ObdCommandKind.AT))
                appendLog("初始化 $command -> $result")
                if (result is ObdCommandResult.Timeout) {
                    appendLog("初始化逾時，中斷連線")
                    disconnect()
                    return@launch
                }
            }
            appendLog("初始化完成，開始輪詢")
            detectVehicleBrand()
            performTroubleCodeRead()
            startPolling()
            restartBrandPolling()
        }
    }

    /** Reads the VIN (Mode 09 PID 02) once per connection and auto-selects a matching brand profile. */
    private suspend fun detectVehicleBrand() {
        val result = queue.execute(ObdCommand("0902", ObdCommandKind.OBD))
        val raw = (result as? ObdCommandResult.Success)?.raw
        if (raw == null) {
            appendLog("車款辨識：讀取 VIN 逾時")
            return
        }
        val vin = ObdResponseParser.parseVin(raw)
        if (vin.isNullOrBlank()) {
            appendLog("車款辨識：無法讀取 VIN（此車可能不支援 Mode 09）")
            return
        }
        val brand = VehicleBrandDetector.detectBrand(vin)
        _uiState.update { it.copy(detectedVin = vin, detectedBrand = brand) }
        if (brand == null) {
            appendLog("車款辨識：VIN=$vin，無法辨識廠牌")
            return
        }
        appendLog("車款辨識：VIN=$vin，廠牌=$brand")
        if (brandProfiles.containsKey(brand)) {
            selectBrand(brand)
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                if (!fastLoopPaused) {
                    val rpm = pollOnce(rpmPid)
                    if (rpm != null) {
                        rpmStaleCount = 0
                        updateVehicleData { it.copy(rpm = rpm.roundToInt()) }
                    } else if (++rpmStaleCount >= STALE_THRESHOLD) {
                        updateVehicleData { it.copy(rpm = null) }
                    }
                }
                if (!fastLoopPaused) {
                    val speed = pollOnce(speedPid)
                    if (speed != null) {
                        speedStaleCount = 0
                        updateVehicleData { it.copy(speedKph = speed.roundToInt()) }
                    } else if (++speedStaleCount >= STALE_THRESHOLD) {
                        updateVehicleData { it.copy(speedKph = null) }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun restartBrandPolling() {
        brandPollingJob?.cancel()
        val profile = brandProfiles[_uiState.value.selectedBrand] ?: return
        val pids = profile.pids.ifEmpty {
            profile.models.flatMap { model ->
                model.pids.map { pid -> pid.copy(ecuHeader = pid.ecuHeader ?: model.ecuHeader) }
            }
        }
        if (pids.isEmpty()) return

        brandPollingJob = scope.launch {
            while (isActive) {
                for (pid in pids) {
                    while (fastLoopPaused) delay(POLL_INTERVAL_MS)
                    fastLoopPaused = true
                    pid.ecuHeader?.let { queue.execute(ObdCommand("ATSH$it", ObdCommandKind.AT)) }
                    pid.ecuReceiveFilter?.let { queue.execute(ObdCommand("ATCRA$it", ObdCommandKind.AT)) }
                    val value = pollOnce(pid)
                    if (pid.ecuReceiveFilter != null) queue.execute(ObdCommand("ATCRA", ObdCommandKind.AT))
                    if (pid.ecuHeader != null) queue.execute(ObdCommand("ATSH00", ObdCommandKind.AT))
                    fastLoopPaused = false
                    if (value != null) {
                        val formatted = "%.1f %s".format(value, pid.unit)
                        _uiState.update { it.copy(extraReadings = it.extraReadings + (pid.field to formatted)) }
                    }
                    delay(BRAND_POLL_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun pollOnce(pid: PidDefinition): Double? {
        val result = queue.execute(ObdCommand(pid.request, ObdCommandKind.OBD))
        val raw = (result as? ObdCommandResult.Success)?.raw
        if (raw == null) {
            appendLog("${pid.field} 輪詢逾時")
            return null
        }
        when (val status = ObdResponseParser.classify(raw)) {
            ObdResponseStatus.NoData -> {
                appendLog("${pid.field}：NO DATA")
                return null
            }
            is ObdResponseStatus.NegativeResponse -> {
                appendLog("${pid.field}：${status.messageZh}")
                return null
            }
            else -> Unit
        }
        _uiState.update { it.copy(rawResponse = raw) }
        val value = pid.formula?.let { ObdResponseParser.parsePid(raw, pid.request, it) }
            ?: pid.bitField?.let { ObdResponseParser.parsePidBitField(raw, pid.request, it) }
        if (value == null) appendLog("${pid.field}：解析失敗")
        return value
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        brandPollingJob?.cancel()
        brandPollingJob = null
    }

    private fun connectionLabelFor(state: ConnectionState): String = when (state) {
        ConnectionState.DISCONNECTED -> "尚未連線"
        ConnectionState.SCANNING -> "掃描中"
        ConnectionState.CONNECTING -> "連線中"
        ConnectionState.DISCOVERING_GATT -> "探索服務中"
        ConnectionState.INITIALIZING -> "初始化中"
        ConnectionState.READY -> "已連線"
        ConnectionState.DISCONNECTED_AFTER_ERROR -> "已中斷"
        ConnectionState.ERROR -> "發生錯誤"
    }

    companion object {
        private val INIT_SEQUENCE = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100")
        private const val POLL_INTERVAL_MS = 200L
        private const val BRAND_POLL_INTERVAL_MS = 3000L
        private const val STALE_THRESHOLD = 5
    }
}
