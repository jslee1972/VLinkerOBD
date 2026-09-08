package com.jslee1972.vlinkerobd.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jslee1972.vlinkerobd.ble.BleObdClient
import com.jslee1972.vlinkerobd.ble.ConnectionState
import com.jslee1972.vlinkerobd.ble.DeviceMemory
import com.jslee1972.vlinkerobd.ble.NoOpDeviceMemory
import com.jslee1972.vlinkerobd.ble.ScannedBleDevice
import com.jslee1972.vlinkerobd.gps.GpsSpeedSource
import com.jslee1972.vlinkerobd.gps.NoOpGpsSpeedSource
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
    private val brandDetector: VehicleBrandDetector = VehicleBrandDetector.FALLBACK,
    private val gpsSpeedSource: GpsSpeedSource = NoOpGpsSpeedSource,
) : ViewModel() {

    // Production uses viewModelScope (survives config changes, cancelled in onCleared); tests
    // inject runTest's backgroundScope so the infinite polling loop doesn't hang test teardown.
    private val scope: CoroutineScope = externalScope ?: viewModelScope

    private val queue = ObdCommandQueue(bleClient, scope)
    private val log = BoundedLog()

    private val speedPid = universalProfile.pids.first { it.field == "speedKPH" }
    private val rpmPid = universalProfile.pids.first { it.field == "rpm" }

    // Trip computer: fuel consumption/distance/time/acceleration are never reported directly by
    // any PID — they're derived here from mafGramsPerSec + speedKPH, both already-verified
    // standard PIDs, the same way most OBD-II trip-computer apps do it. Polled on the fast loop
    // (not the slow standard-extra ticker) since an "instant" fuel figure needs to track speed
    // at the same cadence, not once every few seconds.
    private val mafPid = universalProfile.pids.firstOrNull { it.field == "mafGramsPerSec" }
    private var mafStaleCount = 0
    private var tripDistanceKm = 0.0
    private var tripFuelLiters = 0.0
    private var tripElapsedSeconds = 0.0
    private var previousSpeedKph: Double? = null

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
    private var fastBrandPollingJob: Job? = null
    private var standardPollingJob: Job? = null
    private var fastStandardPollingJob: Job? = null

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
        scope.launch { gpsSpeedSource.speedKph.collect { speed -> _uiState.update { it.copy(gpsSpeedKph = speed) } } }
    }

    fun startScan() = bleClient.startScan()
    fun stopScan() = bleClient.stopScan()

    /** MainActivity calls this once location permission is granted; stopGpsTracking on pause/destroy. */
    fun startGpsTracking() = gpsSpeedSource.start()
    fun stopGpsTracking() = gpsSpeedSource.stop()

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
            ConnectionState.DISCONNECTED_AFTER_ERROR -> {
                stopPolling()
                _uiState.update { it.copy(connectedDeviceName = null) }
                // A connection that was ready/initializing and then dropped unexpectedly (e.g.
                // the BLE service-discovery watchdog giving up after repeated timeouts) shouldn't
                // need a manual app restart to recover — clear the one-shot auto-connect latch
                // and rescan so the remembered device gets picked back up once it's reachable
                // again, instead of sitting idle forever.
                autoConnectAttempted = false
                startScan()
            }
            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
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
        val brand = brandDetector.detectBrand(vin)
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
        tripDistanceKm = 0.0
        tripFuelLiters = 0.0
        tripElapsedSeconds = 0.0
        previousSpeedKph = null
        var latestSpeedKph: Double? = null
        var latestMafGramsPerSec: Double? = null
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
                        latestSpeedKph = speed
                        updateVehicleData { it.copy(speedKph = speed.roundToInt()) }
                        _uiState.update { it.copy(speedHistory = (it.speedHistory + speed.toFloat()).takeLast(HISTORY_SIZE)) }
                    } else if (++speedStaleCount >= STALE_THRESHOLD) {
                        latestSpeedKph = null
                        updateVehicleData { it.copy(speedKph = null) }
                    }
                }
                if (!fastLoopPaused && mafPid != null) {
                    val maf = pollOnce(mafPid)
                    if (maf != null) {
                        mafStaleCount = 0
                        latestMafGramsPerSec = maf
                    } else if (++mafStaleCount >= STALE_THRESHOLD) {
                        latestMafGramsPerSec = null
                    }
                }
                // Skip integrating distance/fuel while paused (a manual command or the ECU test
                // tool can pause this loop for several seconds) — repeatedly re-integrating a
                // frozen last-known speed/MAF over a long pause would fabricate distance/fuel
                // that was never actually observed. Elapsed trip time keeps counting regardless.
                updateTripComputer(latestSpeedKph, latestMafGramsPerSec, accumulateMotion = !fastLoopPaused)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Estimates fuel consumption from mass airflow using the standard MAF-based approximation
     * most OBD-II trip-computer apps use: fuel mass rate = air mass rate / stoichiometric AFR,
     * converted to volume by fuel density. Assumes gasoline constants (AFR 14.7:1, density
     * 750 g/L) since the app has no reliable way to know the actual fuel type — diesel's slightly
     * different constants (AFR ~14.5, density ~832 g/L) would shift the result a little, but not
     * enough to change the order of magnitude. This is always an estimate, never a raw PID value.
     */
    private fun updateTripComputer(speedKph: Double?, mafGramsPerSec: Double?, accumulateMotion: Boolean) {
        val tickHours = (POLL_INTERVAL_MS / 1000.0) / 3600.0
        tripElapsedSeconds += POLL_INTERVAL_MS / 1000.0
        _uiState.update { it.copy(standardReadings = it.standardReadings + ("tripDuration" to "%.1f 分鐘".format(tripElapsedSeconds / 60.0))) }
        if (!accumulateMotion) return

        if (speedKph != null && mafGramsPerSec != null) {
            val fuelLitersPerHour = mafGramsPerSec * 3600.0 / (14.7 * 750.0)
            tripDistanceKm += speedKph * tickHours
            tripFuelLiters += fuelLitersPerHour * tickHours

            val instantFormatted = if (speedKph > 1.0) {
                "%.1f 升/百公里".format(fuelLitersPerHour / speedKph * 100.0)
            } else {
                "%.2f 升/小時".format(fuelLitersPerHour)
            }
            _uiState.update { it.copy(standardReadings = it.standardReadings + ("instantFuelConsumption" to instantFormatted)) }

            if (tripDistanceKm > 0.05) {
                val avgFormatted = "%.1f 升/百公里".format(tripFuelLiters / tripDistanceKm * 100.0)
                _uiState.update { it.copy(standardReadings = it.standardReadings + ("averageFuelConsumption" to avgFormatted)) }
            }
        }

        previousSpeedKph?.let { previous ->
            if (speedKph != null) {
                val deltaMps = (speedKph - previous) / 3.6
                val acceleration = deltaMps / (POLL_INTERVAL_MS / 1000.0)
                val formatted = "%.2f 米/秒²".format(acceleration)
                _uiState.update { it.copy(standardReadings = it.standardReadings + ("acceleration" to formatted)) }
            }
        }
        previousSpeedKph = speedKph

        _uiState.update { it.copy(standardReadings = it.standardReadings + ("tripDistance" to "%.2f 公里".format(tripDistanceKm))) }
    }

    /**
     * Brand PIDs aren't all equal in how often they're worth asking: things like tire pressure or
     * a turbo's rated setpoint barely change, but gear position changes every shift — sharing one
     * slow round-robin ticker across ~20 PIDs meant a fast-changing field like gear only got
     * re-asked once a minute. PIDs marked [PidDefinition.fastPoll] in the profile JSON get their
     * own short-interval ticker instead of waiting behind the rest of the list.
     */
    private fun restartBrandPolling() {
        brandPollingJob?.cancel()
        fastBrandPollingJob?.cancel()
        val profile = brandProfiles[_uiState.value.selectedBrand] ?: return
        val pids = profile.pids.ifEmpty {
            profile.models.flatMap { model ->
                model.pids.map { pid -> pid.copy(ecuHeader = pid.ecuHeader ?: model.ecuHeader) }
            }
        }
        if (pids.isEmpty()) return

        val (fastPids, slowPids) = pids.partition { it.fastPoll }
        val onResult = { field: String, formatted: String ->
            _uiState.update { it.copy(extraReadings = it.extraReadings + (field to formatted)) }
        }
        if (slowPids.isNotEmpty()) brandPollingJob = launchPidTicker(slowPids, BRAND_POLL_INTERVAL_MS, onResult)
        if (fastPids.isNotEmpty()) fastBrandPollingJob = launchPidTicker(fastPids, FAST_BRAND_POLL_INTERVAL_MS, onResult)
    }

    private fun launchPidTicker(pids: List<PidDefinition>, intervalMs: Long, onResult: (field: String, formatted: String) -> Unit): Job {
        val failureStreak = mutableMapOf<String, Int>()
        return scope.launch {
            while (isActive) {
                for (pid in pids) {
                    // A vehicle that never answers a PID (protocol doesn't support it, wrong ECU,
                    // etc.) shouldn't keep getting asked every single cycle forever — that's pure
                    // wasted bandwidth and log noise for something that won't change mid-drive.
                    // Once it's failed enough times in a row to call it unsupported, stop asking
                    // for the rest of this connection. Still delay before the next PID — if every
                    // PID in the list has given up, skipping the delay too would busy-loop this
                    // coroutine with no suspension point at all.
                    if ((failureStreak[pid.field] ?: 0) >= GIVE_UP_THRESHOLD) {
                        delay(intervalMs)
                        continue
                    }

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
                        onResult(pid.field, formatted)
                    } else {
                        failureStreak[pid.field] = (failureStreak[pid.field] ?: 0) + 1
                    }
                    delay(intervalMs)
                }
            }
        }
    }

    /**
     * Ticker(s) for standard (non-brand) PIDs beyond speed/RPM/MAF — battery voltage, temperatures,
     * pressures, fuel trims, etc. Every numeric field universal-obd2.json defines gets polled here;
     * a vehicle that doesn't support a given one just NO-DATAs and permanently backs off (see
     * launchPidTicker) like any other unsupported PID, so there's no harm in asking for all of
     * them rather than guessing which ones this specific car has. Split into fast/slow the same
     * way as restartBrandPolling().
     */
    private fun restartStandardPolling() {
        standardPollingJob?.cancel()
        fastStandardPollingJob?.cancel()
        if (standardExtraPids.isEmpty()) return
        val (fastPids, slowPids) = standardExtraPids.partition { it.fastPoll }
        val onResult = { field: String, formatted: String ->
            _uiState.update { it.copy(standardReadings = it.standardReadings + (field to formatted)) }
        }
        if (slowPids.isNotEmpty()) standardPollingJob = launchPidTicker(slowPids, BRAND_POLL_INTERVAL_MS, onResult)
        if (fastPids.isNotEmpty()) fastStandardPollingJob = launchPidTicker(fastPids, FAST_BRAND_POLL_INTERVAL_MS, onResult)
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
        fastBrandPollingJob?.cancel()
        fastBrandPollingJob = null
        standardPollingJob?.cancel()
        standardPollingJob = null
        fastStandardPollingJob?.cancel()
        fastStandardPollingJob = null
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
        private const val FAST_BRAND_POLL_INTERVAL_MS = 800L
        private const val STALE_THRESHOLD = 5
        // Every universal-obd2.json field except speedKPH/rpm (dedicated fast-loop gauges) and
        // mafGramsPerSec (polled separately, at fast-loop cadence, for the trip computer). A field
        // this vehicle doesn't support just NO-DATAs and permanently backs off like any other
        // unsupported PID (see launchPidTicker) — there's no need to hand-pick a subset per car.
        private val STANDARD_EXTRA_FIELDS = listOf(
            "coolantTempC", "engineLoadPercent", "throttlePercent", "controlModuleVoltage",
            "intakeAirTempC", "intakeManifoldPressureKPA", "fuelPressureKPA", "barometricPressureKPA",
            "fuelLevelPercent", "ambientAirTempC", "engineOilTempC", "runtimeSinceStartSec",
            "shortTermFuelTrimBank1Percent", "longTermFuelTrimBank1Percent",
            "shortTermFuelTrimBank2Percent", "longTermFuelTrimBank2Percent", "timingAdvanceDegrees",
            "distanceWithMilOnKM", "chargeAirCoolerTempC", "lambdaBank1Sensor1",
            "boostPressureCommandedKPA", "boostPressureActualKPA", "exhaustPressureBank1KPA",
            "dpfInletPressureKPA", "dpfOutletPressureKPA", "fuelRailPressureCommandedKPA",
            "fuelRailPressureActualKPA", "engineFuelRateLPH", "exhaustGasTempBank1Sensor1C",
            "exhaustGasTempBank1Sensor2C", "exhaustGasTempBank1Sensor3C", "noxBank1Sensor1PPM",
            "absoluteLoadPercent", "commandedEquivalenceRatio", "relativeThrottlePercent",
            "ethanolFuelPercent", "fuelRailPressureAbsoluteKPA", "relativeAcceleratorPedalPercent",
            "driverDemandTorquePercent", "actualEngineTorquePercent", "engineReferenceTorqueNM",
        )
        private const val HISTORY_SIZE = 60
        // A PID this vehicle never answers after this many consecutive tries is permanently
        // skipped for the rest of the connection — a vehicle's PID support doesn't change
        // mid-drive, so retrying is pure wasted bandwidth and log noise.
        private const val GIVE_UP_THRESHOLD = 5
    }
}
