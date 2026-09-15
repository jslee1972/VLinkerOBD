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
import com.jslee1972.vlinkerobd.speech.NoOpSpeechAnnouncer
import com.jslee1972.vlinkerobd.speech.SpeechAnnouncer
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
    private val customSectionStore: CustomSectionStore = NoOpCustomSectionStore,
    private val speechAnnouncer: SpeechAnnouncer = NoOpSpeechAnnouncer,
    private val ringLegendFieldsStore: RingLegendFieldsStore = NoOpRingLegendFieldsStore,
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
    private var tripStartTimeMs: Long? = null
    // OBD-only (never the GPS-fallback speed) and its real wall-clock sample time — acceleration
    // is deliberately computed only from these two, kept apart from the GPS-inclusive speed used
    // for distance/fuel below. See updateTripComputer's doc comment for why.
    private var previousSpeedKph: Double? = null
    private var previousSpeedSampleTimeMs: Long? = null

    // Only a cold app launch or the user's own disconnect() should zero the trip computer — a
    // transient BLE dropout that auto-reconnects (DISCONNECTED_AFTER_ERROR -> startScan() ->
    // READY -> startPolling() again) must NOT wipe out 行駛里程/平均油耗/行駛時間 just because the
    // signal briefly dropped mid-drive.
    private var pendingTripReset = true

    // Standard PIDs beyond speed/RPM that are always available (no brand profile needed), polled
    // on a slow ticker like brand PIDs so they don't compete with the fast speed/RPM loop.
    private val standardExtraPids = STANDARD_EXTRA_FIELDS.mapNotNull { field ->
        universalProfile.pids.firstOrNull { it.field == field }
    }

    /**
     * Universal + trip-computer fields (always relevant) plus the *currently selected* brand's
     * own fields — deliberately not every loaded brand's fields at once, so switching brand (or
     * detecting one from the VIN) doesn't leave every other brand's PIDs sitting in the picker as
     * dead entries the connected vehicle will never answer. Recomputed on every [selectBrand] call
     * rather than cached once, so the picker's field list actually tracks which vehicle is
     * connected instead of accumulating every brand this setup has ever loaded a profile for.
     *
     * Also excludes [FAST_LOOP_EXCLUSIVE_FIELDS] (read by the dedicated fast loop straight into
     * `vehicleData`/trip-computer state, never into `standardReadings`/`extraReadings` — i.e.
     * never into `liveReadings` — so picking them here would only ever show "--" forever; the ring
     * gauge already displays both prominently) and [UNAVAILABLE_TIRE_FIELDS] (confirmed dead on
     * this vehicle, see that constant's own doc comment).
     */
    private fun relevantFieldsFor(brand: String): List<String> = (
        universalProfile.pids.map { it.field } +
            ParameterGroups.TRIP_COMPUTER_FIELDS +
            (brandProfiles[brand]?.let { profile ->
                profile.pids.map { it.field } + profile.models.flatMap { model -> model.pids.map { it.field } }
            } ?: emptyList())
        ).distinct() - FAST_LOOP_EXCLUSIVE_FIELDS - UNAVAILABLE_TIRE_FIELDS

    // Drops any tyre-pressure/temp field a user pinned before it was confirmed dead
    // (UNAVAILABLE_TIRE_FIELDS) so those cards vanish on next launch instead of sitting there
    // showing "--" forever until manually unchecked.
    private val cleanedCustomFields = run {
        val stored = customSectionStore.selectedFields()
        val cleaned = stored.filter { it !in UNAVAILABLE_TIRE_FIELDS }
        if (cleaned.size != stored.size) customSectionStore.setSelectedFields(cleaned)
        cleaned
    }
    private val cleanedRingLegendFields = run {
        val stored = ringLegendFieldsStore.fields()
        val cleaned = stored.filter { it !in UNAVAILABLE_TIRE_FIELDS }
        if (cleaned.size != stored.size) ringLegendFieldsStore.setFields(cleaned)
        cleaned
    }

    private val _uiState = MutableStateFlow(
        DashboardUiState(
            availableBrands = listOf(UNIVERSAL_BRAND) + brandProfiles.keys,
            allKnownFields = relevantFieldsFor(UNIVERSAL_BRAND),
            selectedCustomFields = cleanedCustomFields,
            ringLegendFields = cleanedRingLegendFields,
        ),
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

    // Spoken announcements (battery voltage once per connection, speed-threshold alerts) — no
    // on/off toggle, matching the iOS behavior these mirror: rare, short, and exactly the kind of
    // glanceable-while-driving information worth interrupting whatever's playing for. Reset on
    // every (re)connect, including a transient BLE-drop auto-reconnect — unlike the trip computer,
    // re-announcing on reconnect is harmless (and for speed thresholds, actively wanted: stale
    // thresholds from before the drop could otherwise suppress a real speeding alert after
    // reconnecting mid-excursion).
    private var hasAnnouncedBatteryVoltage = false
    private val announcedSpeedThresholds = mutableSetOf<Int>()

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

    /** Adds/removes [field] from the dashboard's "自訂" section and persists the selection. */
    /** Toggles [field] in the side-panel custom section. Deselecting always just removes it;
     * selecting beyond the 8 pinned-card slots bumps the oldest field out (FIFO) instead of
     * silently appending past what the side panel ever displays — appending unbounded made
     * newly-checked fields invisible once 8 were already selected, which read as the picker being
     * hardcoded/unresponsive. */
    fun toggleCustomField(field: String) {
        val updated = _uiState.value.selectedCustomFields.let { current ->
            if (field in current) {
                current - field
            } else {
                (current + field).let { if (it.size > 8) it.drop(1) else it }
            }
        }
        customSectionStore.setSelectedFields(updated)
        _uiState.update { it.copy(selectedCustomFields = updated) }
    }

    /** Clears every pinned custom field at once. Explicitly persists the empty set (rather than
     * just clearing in-memory state) so [CustomSectionStore.selectedFields] — which only ever
     * falls back to the seeded defaults when nothing has been configured *yet* — respects this as
     * a deliberate choice and doesn't silently repopulate the defaults on next launch. */
    fun clearAllCustomFields() {
        customSectionStore.setSelectedFields(emptyList())
        _uiState.update { it.copy(selectedCustomFields = emptyList()) }
    }

    /** Drag-to-reorder in the driving-dynamics ring gauge's side panels: moves [field] to sit
     * just before [target] in the pinned-fields order. A no-op if either field isn't actually
     * pinned (e.g. a stray drop) or they're the same field (dropping a card on itself). */
    fun moveCustomField(field: String, target: String) {
        if (field == target) return
        val current = _uiState.value.selectedCustomFields
        val fromIndex = current.indexOf(field)
        if (fromIndex < 0 || target !in current) return
        val withoutField = current.toMutableList().apply { removeAt(fromIndex) }
        val toIndex = withoutField.indexOf(target)
        withoutField.add(toIndex, field)
        customSectionStore.setSelectedFields(withoutField)
        _uiState.update { it.copy(selectedCustomFields = withoutField) }
    }

    /** Toggles [field] in the driving-dynamics ring gauge's 2-slot center legend. Deselecting
     * always just removes it; selecting a 3rd field bumps the oldest of the current two out
     * (FIFO) rather than blocking the tap — "pick your two" reads more naturally as "the two
     * most recent taps" than as a hard capacity limit the user has to manage explicitly. */
    fun toggleRingLegendField(field: String) {
        val current = _uiState.value.ringLegendFields
        val updated = if (field in current) {
            current - field
        } else {
            (current + field).let { if (it.size > 2) it.drop(1) else it }
        }
        ringLegendFieldsStore.setFields(updated)
        _uiState.update { it.copy(ringLegendFields = updated) }
    }

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
        pendingTripReset = true
        stopPolling()
        bleClient.disconnect()
    }

    fun clearLogs() {
        log.clear()
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun selectBrand(brand: String) {
        _uiState.update { it.copy(selectedBrand = brand, extraReadings = emptyMap(), allKnownFields = relevantFieldsFor(brand)) }
        if (_uiState.value.isReady) restartBrandPolling()
    }

    fun sendManualCommand(text: String) {
        sendCommandSequence(listOf(text))
    }

    /**
     * Sends a sequence of raw commands back-to-back, pausing the fast loop once for the whole
     * sequence rather than per-command — typing a multi-step probe (switch header, switch receive
     * filter, then the real request) into the single-command field one line at a time left enough
     * of a gap between steps, at highway speed, for the ECU/bus to go idle before the actual
     * request landed, which is what made those manual multi-step probes unreliable — the classic
     * symptom is a "NO DATA" on the final command that a fully-connected back-to-back send doesn't
     * reproduce.
     */
    fun sendCommandSequence(commands: List<String>) {
        val trimmedCommands = commands.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (trimmedCommands.isEmpty()) return
        scope.launch {
            fastLoopPaused = true
            for (command in trimmedCommands) {
                val result = queue.execute(ObdCommand(command, ObdCommandKind.AT))
                appendLog("手動指令 $command -> $result")
                (result as? ObdCommandResult.Success)?.let { success ->
                    _uiState.update { it.copy(rawResponse = success.raw) }
                }
            }
            fastLoopPaused = false
        }
    }

    /** One-tap version of the ATSH6A8 → ATCRA688 → 22D409 probe (restoring the standard header/
     * filter afterward, same cleanup [launchPidTicker] does) — captures the raw response at a
     * specific gear without the manual-typing delay [sendCommandSequence]'s doc comment describes. */
    fun probeGearRaw() {
        sendCommandSequence(listOf("ATSH6A8", "ATCRA688", "22D409", "ATCRA", "ATSH7DF"))
    }

    /**
     * Same one-tap pattern, for the 4 tire-pressure DIDs — these have never returned data at all,
     * and unlike the engine-ECU PIDs (header `6A8`) they go through a separate module (header
     * `6AF`, the tyre under-inflation detection ECU) that this vehicle's own workshop manual
     * documents as an *optional* fitment on some trims. A `NO DATA` reply means the module is
     * present but these specific DIDs are wrong for it; a timeout on all four means there's likely
     * no such module on the bus at all.
     */
    fun probeTirePressures() {
        sendCommandSequence(listOf("ATSH6AF", "ATCRA68F", "22D610", "22D60F", "22D612", "22D611", "ATCRA", "ATSH7DF"))
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
        hasAnnouncedBatteryVoltage = false
        announcedSpeedThresholds.clear()
        if (pendingTripReset) {
            tripDistanceKm = 0.0
            tripFuelLiters = 0.0
            tripStartTimeMs = System.currentTimeMillis()
            pendingTripReset = false
            // Without clearing these, the dashboard kept showing the *previous* trip's
            // average/instant economy and last acceleration reading — describing zero km of new
            // driving — until the new trip's own numbers caught up past updateTripComputer's
            // thresholds.
            _uiState.update {
                it.copy(
                    standardReadings = it.standardReadings - "averageFuelConsumption" -
                        "instantFuelConsumption" - "acceleration",
                )
            }
        }
        previousSpeedKph = null
        previousSpeedSampleTimeMs = null
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
                        announceSpeedIfNeeded(speed)
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
                // Distance/fuel fall back to phone GPS speed when OBD speed has gone stale (a BLE
                // hiccup, not necessarily a full disconnect) so 行駛里程/平均油耗 keep accumulating
                // through the gap instead of freezing; acceleration stays OBD-only (see
                // updateTripComputer's doc comment for why mixing sources there is worse, not
                // better).
                val effectiveSpeedKph = latestSpeedKph ?: _uiState.value.gpsSpeedKph?.toDouble()
                updateTripComputer(effectiveSpeedKph, latestSpeedKph, latestMafGramsPerSec, accumulateMotion = !fastLoopPaused)
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
     *
     * [speedKph] is OBD speed falling back to phone GPS when OBD has gone stale (see the call
     * site) and drives distance/fuel/economy, so those keep accumulating through a signal gap
     * instead of freezing. [obdSpeedKph] is OBD-only (nil during that same gap) and is what
     * acceleration is diffed from — never [speedKph] — because OBD and GPS speed routinely
     * disagree by a few km/h, and dividing that disagreement by the small delta-t below the
     * instant the fallback kicks in or clears would read as a spurious hard-brake/acceleration
     * spike that never actually happened.
     */
    private fun updateTripComputer(speedKph: Double?, obdSpeedKph: Double?, mafGramsPerSec: Double?, accumulateMotion: Boolean) {
        val tickHours = (POLL_INTERVAL_MS / 1000.0) / 3600.0
        // Wall-clock elapsed time, not a per-tick accumulator — a tick counter silently stops
        // advancing whenever this polling coroutine is suspended for a stretch (e.g. Android
        // backgrounds/Dozes the process), understating 行駛時間 even though real time kept passing.
        val now = System.currentTimeMillis()
        val elapsedSeconds = tripStartTimeMs?.let { (now - it) / 1000.0 } ?: 0.0
        _uiState.update { it.copy(standardReadings = it.standardReadings + ("tripDuration" to "%.1f 分鐘".format(elapsedSeconds / 60.0))) }
        if (!accumulateMotion) return

        // Distance only needs *a* speed, GPS fallback included — kept out from under the MAF
        // guard below so it keeps accumulating through an OBD comm gap (MAF is exclusively
        // OBD-sourced and always null during one) instead of freezing for the whole gap.
        if (speedKph != null) {
            tripDistanceKm += speedKph * tickHours
        }

        if (speedKph != null && mafGramsPerSec != null) {
            val fuelLitersPerHour = mafGramsPerSec * 3600.0 / (14.7 * 750.0)
            tripFuelLiters += fuelLitersPerHour * tickHours

            // km/L ("每公升跑幾公里"), not L/100km — the requested display convention.
            val instantFormatted = if (speedKph > 1.0 && fuelLitersPerHour > MIN_FUEL_RATE_FOR_ECONOMY_LPH) {
                "%.1f 公里/公升".format(speedKph / fuelLitersPerHour)
            } else {
                "%.2f 公升/小時".format(fuelLitersPerHour)
            }
            _uiState.update { it.copy(standardReadings = it.standardReadings + ("instantFuelConsumption" to instantFormatted)) }

            if (tripDistanceKm > 0.05 && tripFuelLiters > MIN_FUEL_FOR_AVERAGE_ECONOMY_L) {
                val avgFormatted = "%.1f 公里/公升".format(tripDistanceKm / tripFuelLiters)
                _uiState.update { it.copy(standardReadings = it.standardReadings + ("averageFuelConsumption" to avgFormatted)) }
            }
        }

        // Real elapsed time since the last OBD sample, not an assumed fixed POLL_INTERVAL_MS — a
        // brand/standard PID ticker pausing the fast loop for a stretch (or a slow queue timeout)
        // means the actual gap between two consecutive OBD speed readings isn't always exactly
        // one tick; dividing a real speed change by an assumed-too-short interval would spike to
        // a nonsense reading. A gap of 2s or more is treated as "no valid delta this tick" instead
        // of publishing a number computed across a discontinuity.
        val previous = previousSpeedKph
        val previousTime = previousSpeedSampleTimeMs
        if (previous != null && obdSpeedKph != null && previousTime != null) {
            val dtSeconds = (now - previousTime) / 1000.0
            if (dtSeconds > 0 && dtSeconds < 2.0) {
                val deltaMps = (obdSpeedKph - previous) / 3.6
                val acceleration = deltaMps / dtSeconds
                val formatted = "%.2f 米/秒²".format(acceleration)
                _uiState.update { it.copy(standardReadings = it.standardReadings + ("acceleration" to formatted)) }
            }
        }
        previousSpeedKph = obdSpeedKph
        previousSpeedSampleTimeMs = now

        _uiState.update { it.copy(standardReadings = it.standardReadings + ("tripDistance" to "%.2f 公里".format(tripDistanceKm))) }
    }

    /**
     * The spoken announcement made from the generic PID ticker: battery voltage once per
     * connection, right after the first successful reading. Speeding announcements are separate
     * — see [announceSpeedIfNeeded], called from the fast loop where speed itself is read, since
     * speedKPH never flows through this generic ticker (see [FAST_LOOP_EXCLUSIVE_FIELDS]).
     */
    private fun announceIfNeeded(field: String, value: Double) {
        when (field) {
            "controlModuleVoltage" -> if (!hasAnnouncedBatteryVoltage) {
                hasAnnouncedBatteryVoltage = true
                speechAnnouncer.speak("電池電壓 %.1f 伏特".format(value))
            }
        }
    }

    /**
     * Speaks once per threshold the first time speed reaches it, not on every tick spent above it
     * — otherwise cruising at 125 km/h would repeat "車速已達 120 公里" every poll. All thresholds
     * re-arm together only once speed drops back under the lowest one (110), so a single speeding
     * excursion that peaks at 130 and eases back to 115 doesn't re-announce 110 or 120 on the way
     * down, but a genuinely new excursion after slowing back into normal traffic does.
     */
    private fun announceSpeedIfNeeded(speedKph: Double) {
        val lowest = SPEED_ANNOUNCE_THRESHOLDS.firstOrNull() ?: return
        if (speedKph < lowest) {
            announcedSpeedThresholds.clear()
            return
        }
        for (threshold in SPEED_ANNOUNCE_THRESHOLDS) {
            if (speedKph >= threshold && announcedSpeedThresholds.add(threshold)) {
                speechAnnouncer.speak("車速已達每小時 $threshold 公里")
            }
        }
    }

    /**
     * 0 = P（停車檔）, 7 = R（倒車檔） — confirmed by real-world testing on this automatic-gearbox
     * PSA vehicle (see citroen.json's own descriptionZh for `gearRaw`). 1–6 are the
     * already-verified forward gear numbers; N's raw value is still unconfirmed, so anything else
     * just falls back to the plain number rather than guessing.
     */
    private fun formatGearDisplay(value: Double): String = when (value.roundToInt()) {
        0 -> "P 檔"
        7 -> "R 檔（倒車）"
        else -> "%.0f 檔".format(value)
    }

    /** `runtimeSinceStartSec`'s raw seconds value (e.g. a 40-hour-old readiness-monitor runtime
     * some ECUs never reset) is unreadable as "%.1f 秒" — rendered as hours/minutes instead. */
    private fun formatHoursMinutes(seconds: Double): String {
        val totalMinutes = seconds.toInt() / 60
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "$hours 小時 $minutes 分鐘" else "$minutes 分鐘"
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
        }.filter { it.field !in UNAVAILABLE_TIRE_FIELDS }
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
        val giveUpCooldownRemaining = mutableMapOf<String, Int>()
        return scope.launch {
            while (isActive) {
                for (pid in pids) {
                    // A vehicle that never answers a PID (protocol doesn't support it, wrong ECU,
                    // etc.) shouldn't keep getting asked every single cycle forever — that's pure
                    // wasted bandwidth and log noise for something that won't change mid-drive.
                    // Once it's failed enough times in a row to call it unsupported, back off for
                    // GIVE_UP_COOLDOWN_TURNS turns rather than staying silenced permanently — a
                    // field that recovers (e.g. a brief bus lull, not genuine lack of support)
                    // shouldn't stay stuck at "--" for the rest of the connection. Still delay
                    // before the next PID even while backed off — if every PID in the list is
                    // backed off, skipping the delay too would busy-loop this coroutine with no
                    // suspension point at all.
                    if ((failureStreak[pid.field] ?: 0) >= GIVE_UP_THRESHOLD) {
                        val remaining = (giveUpCooldownRemaining[pid.field] ?: GIVE_UP_COOLDOWN_TURNS) - 1
                        if (remaining > 0) {
                            giveUpCooldownRemaining[pid.field] = remaining
                            delay(intervalMs)
                            continue
                        }
                        // Cooldown elapsed — give it one more chance instead of staying given-up
                        // for the rest of the connection.
                        failureStreak[pid.field] = 0
                        giveUpCooldownRemaining.remove(pid.field)
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
                        announceIfNeeded(pid.field, value)
                        val formatted = when (pid.field) {
                            "gearRaw" -> formatGearDisplay(value)
                            "runtimeSinceStartSec" -> formatHoursMinutes(value)
                            else -> "%.1f %s".format(value, pid.unit)
                        }
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
        // Below these, fuel flow is negligible (e.g. deceleration fuel cut-off) and dividing
        // distance by it would blow up to an absurd km/L figure — fall back to the L/h reading
        // (still meaningful, just small) instead of a number that looks like a data error.
        private const val MIN_FUEL_RATE_FOR_ECONOMY_LPH = 0.05
        private const val MIN_FUEL_FOR_AVERAGE_ECONOMY_L = 0.01
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
        // A PID that's failed this many consecutive tries backs off — see GIVE_UP_COOLDOWN_TURNS
        // for why this is a cooldown, not a permanent skip.
        private const val GIVE_UP_THRESHOLD = 5
        // How many of this field's own poll turns to skip after hitting GIVE_UP_THRESHOLD, before
        // giving it one more chance — real-world testing showed a brand PID (gear position) that
        // works most of the time can still fail 5 times in a row during a brief lull (e.g. idling
        // at a light, the bus going briefly quiet); giving up permanently for the rest of the
        // connection left it stuck at "--" for the whole drive even once the ECU started answering
        // again. A field that's genuinely never going to respond (no such module on the bus at
        // all) just keeps cycling through this — the occasional wasted retry is cheap next to
        // staying permanently blank if it turns out to recover.
        private const val GIVE_UP_COOLDOWN_TURNS = 25
        // These three universal fields are read by the dedicated fast loop (see startPolling),
        // which writes straight to vehicleData/trip-computer state — never to standardReadings/
        // extraReadings, i.e. never to what the custom section or ring legend actually display.
        // Excluded from relevantFieldsFor's picker-eligible list so they can't be picked there,
        // where they'd only ever show "--" forever (the ring gauge already displays both
        // prominently) — and named here so restartStandardPolling's own exclusion (mafGramsPerSec
        // aside, which STANDARD_EXTRA_FIELDS above simply never lists) stays obviously in sync.
        private val FAST_LOOP_EXCLUSIVE_FIELDS = setOf("speedKPH", "rpm", "mafGramsPerSec")
        // Citroën's 4-wheel tyre pressure/temperature DIDs, confirmed via real diagnostic-console
        // logs (the "查詢胎壓原始值" probe) to return NO DATA on every poll attempt across all 8
        // fields — this vehicle's tyre under-inflation detection module is an optional PSA fitment
        // and appears not to be installed, not a formula/addressing bug. Excluded from polling
        // entirely (no point spending bus time on a DID that never answers) and from both field
        // pickers, so these can no longer be (re)selected and clutter the side panel/ring legend
        // with permanent "--" cards. Left defined in citroen.json itself (not deleted) since a
        // different Berlingo/Citroën with the option actually fitted might still answer them.
        private val UNAVAILABLE_TIRE_FIELDS = setOf(
            "tireFrontLeftPressureBar", "tireFrontRightPressureBar",
            "tireRearLeftPressureBar", "tireRearRightPressureBar",
            "tireFrontLeftTempC", "tireFrontRightTempC",
            "tireRearLeftTempC", "tireRearRightTempC",
        )
        // Spoken once per speeding excursion, not every tick above the line — see
        // announceSpeedIfNeeded.
        private val SPEED_ANNOUNCE_THRESHOLDS = listOf(110, 120, 130)
    }
}
