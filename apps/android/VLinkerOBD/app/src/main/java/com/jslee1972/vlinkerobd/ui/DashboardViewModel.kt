package com.jslee1972.vlinkerobd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jslee1972.vlinkerobd.ble.BleObdClient
import com.jslee1972.vlinkerobd.ble.ConnectionState
import com.jslee1972.vlinkerobd.ble.DeviceMemory
import com.jslee1972.vlinkerobd.ble.NoOpDeviceMemory
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
    private val deviceMemory: DeviceMemory = NoOpDeviceMemory,
    externalScope: CoroutineScope? = null,
) : ViewModel() {

    // Production uses viewModelScope (survives config changes, cancelled in onCleared); tests
    // inject runTest's backgroundScope so the infinite polling loop doesn't hang test teardown.
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val queue = ObdCommandQueue(bleClient, scope)
    private val log = BoundedLog()

    private val speedPid = universalProfile.pids.first { it.field == "speedKPH" }
    private val rpmPid = universalProfile.pids.first { it.field == "rpm" }

    // Standard PIDs beyond speed/RPM that are always available (no brand profile needed), polled
    // on a slow ticker like brand PIDs so they don't compete with the fast speed/RPM loop.
    private val standardExtraPids = STANDARD_EXTRA_FIELDS.mapNotNull { field ->
        universalProfile.pids.firstOrNull { it.field == field }
    }

    private val _uiState = MutableStateFlow(
        DashboardUiState(availableBrands = listOf(UNIVERSAL_BRAND) + brandProfiles.keys),
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null
    private var brandPollingJob: Job? = null
    private var standardPollingJob: Job? = null

    @Volatile
    private var fastLoopPaused = false

    private var speedStaleCount = 0
    private var rpmStaleCount = 0

    private var autoConnectAttempted = false
    private var connectingDevice: ScannedBleDevice? = null

    init {
        scope.launch { bleClient.logs.collect { appendLog(it) } }
        scope.launch {
            bleClient.devices.collect { devices ->
                _uiState.update { it.copy(devices = devices) }
                maybeAutoConnect(devices)
            }
        }
        scope.launch { bleClient.connectionState.collect { state -> onConnectionStateChanged(state) } }
    }

    fun startScan() = bleClient.startScan()
    fun stopScan() = bleClient.stopScan()

    fun connect(device: ScannedBleDevice) {
        connectingDevice = device
        bleClient.connect(device)
    }

    /** Auto-reconnects to the last device we successfully connected to, once per app launch. */
    private fun maybeAutoConnect(devices: List<ScannedBleDevice>) {
        if (autoConnectAttempted) return
        val savedAddress = deviceMemory.lastDeviceAddress() ?: return
        val match = devices.firstOrNull { it.address == savedAddress } ?: return
        autoConnectAttempted = true
        appendLog("找到曾連線過的裝置 ${match.name ?: match.address}，自動連線")
        connect(match)
    }

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

    /**
     * Runs a short, read-only sequence of probes to check what this ECU actually supports on the
     * current connection: Mode 01 support bitmap, Mode 09 VIN, and UDS `22 F1 90` (Read Data By
     * Identifier for the standard VIN DID) — the last one as an alternative VIN path for ECUs
     * that don't implement Mode 09 but do speak UDS. If the plain `22F190` attempt is refused,
     * retries once after explicitly requesting an extended diagnostic session (`1003`), since some
     * ECUs gate service 0x22 outside the default session; the default session is restored (`1001`)
     * afterwards either way.
     */
    fun testEcuSupport() {
        scope.launch { performEcuSupportTest() }
    }

    private suspend fun performEcuSupportTest() {
        if (_uiState.value.isTestingEcu) return
        fastLoopPaused = true
        _uiState.update { it.copy(isTestingEcu = true, ecuTestResults = emptyList()) }

        val results = mutableListOf<EcuTestResult>()
        results += runEcuTestStep("0100", "Mode 01 PID 支援位元圖（確認標準匯流排是否有回應）")
        results += runEcuTestStep("0902", "Mode 09 讀取 VIN")
        val plainUdsVin = runEcuTestStep("22F190", "UDS 讀取 VIN（DID F190）")
        results += plainUdsVin

        if (plainUdsVin.status == EcuTestStatus.NO_DATA || plainUdsVin.status == EcuTestStatus.NEGATIVE) {
            queue.execute(ObdCommand("1003", ObdCommandKind.OBD))
            results += runEcuTestStep("22F190", "UDS 讀取 VIN（切換至延伸診斷 Session 1003 後重試）")
            queue.execute(ObdCommand("1001", ObdCommandKind.OBD))
        }

        _uiState.update { it.copy(ecuTestResults = results, isTestingEcu = false) }
        fastLoopPaused = false
    }

    private suspend fun runEcuTestStep(command: String, description: String): EcuTestResult {
        val result = queue.execute(ObdCommand(command, ObdCommandKind.OBD))
        val raw = (result as? ObdCommandResult.Success)?.raw
        appendLog("ECU 測試 $command -> $result")
        if (raw == null) {
            return EcuTestResult(command, description, null, EcuTestStatus.TIMEOUT, "逾時無回應")
        }
        return when (val status = ObdResponseParser.classify(raw)) {
            ObdResponseStatus.NoData ->
                EcuTestResult(command, description, raw, EcuTestStatus.NO_DATA, "NO DATA（ECU 未回應此服務/識別碼）")
            is ObdResponseStatus.NegativeResponse ->
                EcuTestResult(command, description, raw, EcuTestStatus.NEGATIVE, "拒絕：${status.messageZh}")
            ObdResponseStatus.Unrecognized ->
                EcuTestResult(command, description, raw, EcuTestStatus.UNRECOGNIZED, "無法解析的回應")
            is ObdResponseStatus.Data ->
                EcuTestResult(command, description, raw, EcuTestStatus.SUPPORTED, "有回應")
        }
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
            ConnectionState.READY -> {
                connectingDevice?.let { device ->
                    deviceMemory.rememberDevice(device.address)
                    _uiState.update { it.copy(connectedDeviceName = device.name ?: device.address) }
                }
                startInitialization()
            }
            ConnectionState.DISCONNECTED, ConnectionState.DISCONNECTED_AFTER_ERROR, ConnectionState.ERROR -> {
                stopPolling()
                _uiState.update { it.copy(connectedDeviceName = null) }
            }
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
            restartStandardPolling()
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
                        _uiState.update { it.copy(rpmHistory = (it.rpmHistory + rpm.toFloat()).takeLast(HISTORY_SIZE)) }
                    } else if (++rpmStaleCount >= STALE_THRESHOLD) {
                        updateVehicleData { it.copy(rpm = null) }
                    }
                }
                if (!fastLoopPaused) {
                    val speed = pollOnce(speedPid)
                    if (speed != null) {
                        speedStaleCount = 0
                        updateVehicleData { it.copy(speedKph = speed.roundToInt()) }
                        _uiState.update { it.copy(speedHistory = (it.speedHistory + speed.toFloat()).takeLast(HISTORY_SIZE)) }
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

        val failureStreak = mutableMapOf<String, Int>()
        brandPollingJob = scope.launch {
            var round = 0
            while (isActive) {
                round++
                for (pid in pids) {
                    // A vehicle that never answers a PID (protocol doesn't support it, wrong ECU,
                    // etc.) shouldn't keep getting asked every single cycle forever — that's pure
                    // wasted bandwidth and log noise. Back off to an occasional retry instead.
                    if ((failureStreak[pid.field] ?: 0) >= GIVE_UP_THRESHOLD && round % COOLDOWN_ROUNDS != 0) continue

                    while (fastLoopPaused) delay(POLL_INTERVAL_MS)
                    fastLoopPaused = true
                    pid.ecuHeader?.let { queue.execute(ObdCommand("ATSH$it", ObdCommandKind.AT)) }
                    pid.ecuReceiveFilter?.let { queue.execute(ObdCommand("ATCRA$it", ObdCommandKind.AT)) }
                    val value = pollOnce(pid)
                    if (pid.ecuReceiveFilter != null) queue.execute(ObdCommand("ATCRA", ObdCommandKind.AT))
                    // Restore the standard 11-bit functional broadcast header (7DF), not "00" — a
                    // 2-digit header is malformed (ELM327 expects 3 hex digits for an 11-bit ID),
                    // so a real adapter can silently reject/ignore it and leave the header stuck on
                    // this PID's custom value. Every following standard PID poll then gets sent to
                    // whichever ECU that header pointed at, which has no reason to answer Mode 01
                    // and can legitimately refuse with a negative response (e.g. NRC 0x22) instead
                    // of the timeout/NO DATA a wrong-but-unaddressed header would produce.
                    if (pid.ecuHeader != null) queue.execute(ObdCommand("ATSH7DF", ObdCommandKind.AT))
                    fastLoopPaused = false
                    if (value != null) {
                        failureStreak[pid.field] = 0
                        val formatted = "%.1f %s".format(value, pid.unit)
                        _uiState.update { it.copy(extraReadings = it.extraReadings + (pid.field to formatted)) }
                    } else {
                        failureStreak[pid.field] = (failureStreak[pid.field] ?: 0) + 1
                    }
                    delay(BRAND_POLL_INTERVAL_MS)
                }
            }
        }
    }

    /** Slow ticker for standard PIDs (battery voltage, coolant temp, timing advance) that need no ECU header. */
    private fun restartStandardPolling() {
        standardPollingJob?.cancel()
        if (standardExtraPids.isEmpty()) return
        val failureStreak = mutableMapOf<String, Int>()
        standardPollingJob = scope.launch {
            var round = 0
            while (isActive) {
                round++
                for (pid in standardExtraPids) {
                    if ((failureStreak[pid.field] ?: 0) >= GIVE_UP_THRESHOLD && round % COOLDOWN_ROUNDS != 0) continue

                    while (fastLoopPaused) delay(POLL_INTERVAL_MS)
                    fastLoopPaused = true
                    val value = pollOnce(pid)
                    fastLoopPaused = false
                    if (value != null) {
                        failureStreak[pid.field] = 0
                        val formatted = "%.1f %s".format(value, pid.unit)
                        _uiState.update { it.copy(standardReadings = it.standardReadings + (pid.field to formatted)) }
                    } else {
                        failureStreak[pid.field] = (failureStreak[pid.field] ?: 0) + 1
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
        standardPollingJob?.cancel()
        standardPollingJob = null
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
        private val INIT_SEQUENCE = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATCFC1", "0100")
        private const val POLL_INTERVAL_MS = 200L
        private const val BRAND_POLL_INTERVAL_MS = 3000L
        private const val STALE_THRESHOLD = 5
        private val STANDARD_EXTRA_FIELDS = listOf("controlModuleVoltage", "coolantTempC", "timingAdvanceDegrees")
        private const val HISTORY_SIZE = 60
        // A PID this vehicle never answers after this many consecutive tries stops being polled
        // every round; it's retried once every COOLDOWN_ROUNDS rounds instead of forever wasting
        // bandwidth (and flooding the log) on something the ECU has already shown it won't answer.
        private const val GIVE_UP_THRESHOLD = 5
        private const val COOLDOWN_ROUNDS = 20
    }
}
