import Foundation

/// App-lifetime orchestrator: owns the BLE connection, the OBD command queue, every polling
/// ticker, the trip computer, brand/VIN detection, DTC reads, GPS speed, and the custom-field
/// selection. Faithful port of Android's `DashboardViewModel.kt` — same constants, same
/// mutual-exclusion policy between the fast gauge loop and the brand/standard PID tickers (only
/// one OBD command in flight at any instant), same give-up-after-5-failures policy, same trip
/// computer formulas.
@MainActor
final class DashboardController: ObservableObject {
    @Published private(set) var state = DashboardState()

    private let bleClient: BleObdClient
    private let queue: ObdCommandQueue
    private let universalProfile: VehicleProfile
    private let brandProfiles: [String: VehicleProfile]
    private let deviceMemory: DeviceMemory
    private let brandDetector: VehicleBrandDetector
    private let gpsSpeedSource: GpsSpeedSource
    private let customSectionStore: CustomSectionStore
    private let ringLegendFieldsStore: RingLegendFieldsStore
    let parameterMetadata: ParameterMetadata
    let dtcDescriptions: DtcDescriptions

    // MARK: Tuning constants (mirrors DashboardViewModel.kt exactly)

    private static let pollIntervalMs = 200
    private static let brandPollIntervalMs = 3000
    private static let fastBrandPollIntervalMs = 800
    private static let giveUpThreshold = 5
    /// How many of this field's own poll turns to skip after hitting `giveUpThreshold`, before
    /// giving it one more chance — real-world testing showed a brand PID (gear position) that
    /// works most of the time can still fail 5 times in a row during a brief lull (e.g. idling at
    /// a light, the bus going briefly quiet), and the give-up used to be permanent for the rest of
    /// the connection: once tripped, that field stayed "--" for the whole drive even once the ECU
    /// started answering again. A field that's genuinely never going to respond (no such module on
    /// the bus at all, e.g. an unfitted TPMS option) just keeps cycling through this — the
    /// occasional wasted retry is cheap next to staying permanently blank if it turns out to
    /// recover.
    private static let giveUpCooldownTurns = 25
    private static let historySize = 60
    private static let staleThreshold = 5
    private static let minFuelRateForEconomyLph = 0.05
    private static let minFuelForAverageEconomyL = 0.01
    private static let initSequence = ["ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATCFC1", "0100"]
    /// Spoken once per speeding excursion, not every tick above the line — see
    /// `announceSpeedIfNeeded`.
    private static let speedAnnounceThresholds = [110, 120, 130]
    /// These three universal fields are read by the dedicated fast loop (`fastLoopTick`), which
    /// writes straight to `state.vehicleData`/trip-computer state — never to `standardReadings`/
    /// `extraReadings`, i.e. never to `liveReadings`. Excluded here from *both* the generic
    /// ticker (so it doesn't redundantly re-poll them) and `relevantFieldsForCurrentVehicle` (so
    /// they can't be picked for the custom section or ring legend, where they'd only ever show
    /// "--" forever — a real gap: the ring already displays both prominently).
    private static let fastLoopExclusiveFields: Set<String> = ["speedKPH", "rpm", "mafGramsPerSec"]
    /// Citroën's 4-wheel tyre pressure/temperature DIDs (0x22D60B–0x22D612), confirmed via real
    /// diagnostic-console logs to return NO DATA on every poll attempt across all 8 fields — this
    /// vehicle's tyre under-inflation detection module is an optional PSA fitment and appears not
    /// to be installed, not a formula/addressing bug. Excluded from polling entirely (no point
    /// spending bus time on a DID that never answers) and from both field pickers, so these can no
    /// longer be (re)selected and clutter the side panel/ring legend with permanent "--" cards.
    private static let unavailableTireFields: Set<String> = [
        "tireFrontLeftPressureBar", "tireFrontRightPressureBar",
        "tireRearLeftPressureBar", "tireRearRightPressureBar",
        "tireFrontLeftTempC", "tireFrontRightTempC",
        "tireRearLeftTempC", "tireRearRightTempC",
    ]

    // MARK: Ticker jobs

    private var pollingJob: Task<Void, Never>?
    private var brandPollingJob: Task<Void, Never>?
    private var fastBrandPollingJob: Task<Void, Never>?
    private var standardPollingJob: Task<Void, Never>?
    private var fastStandardPollingJob: Task<Void, Never>?

    /// Mutual-exclusion flag between the fast gauge loop and the brand/standard tickers — only
    /// one OBD command may be in flight at any instant (single ELM/STN link, single ECU header
    /// active at a time). Safe as a plain Bool because everything here runs cooperatively on the
    /// main actor, same as Android's single-threaded `Dispatchers.Main.immediate` scope.
    private var fastLoopPaused = false

    private var autoConnectAttempted = false
    private var failureStreak: [String: Int] = [:]
    private var giveUpCooldownRemaining: [String: Int] = [:]

    // MARK: Trip computer state

    private var tripDistanceKm: Double = 0
    private var tripFuelLiters: Double = 0
    /// Wall-clock, not a per-tick counter — see `updateTripComputer`'s doc comment for why: a
    /// tick-counted duration silently stops advancing for however long iOS suspends this app in
    /// the background (no BLE background mode is declared), which reads as "行駛時間" being stuck
    /// even after a real hour of driving with the screen locked or another app frontmost.
    private var tripStartDate: Date?
    /// True right after app launch and right after an explicit `disconnect()` — the two moments
    /// that should actually start a fresh trip. A weak-signal dropout instead goes through
    /// `.disconnectedAfterError`, which auto-rescans and reconnects via the same `startPolling()`
    /// path as a real new connection; without this flag every one of those transient reconnects
    /// silently zeroed 行駛里程/平均油耗/行駛時間 mid-drive — which is exactly what made the
    /// average look erratic (each reset restarts the average from a tiny, noisy sample) even
    /// though nothing was actually wrong with the fuel-economy math itself.
    private var pendingTripReset = true
    private var previousSpeedKph: Double?
    private var previousSpeedSampleDate: Date?
    private let speechAnnouncer = SpeechAnnouncer()
    private var hasAnnouncedBatteryVoltage = false
    /// Speed thresholds (km/h) already announced since the last time speed dropped back under the
    /// lowest one — see `announceSpeedIfNeeded`.
    private var announcedSpeedThresholds: Set<Int> = []
    private var lastSpeedKph: Double?
    private var rpmStaleCount = 0
    private var speedStaleCount = 0

    init(
        bleClient: BleObdClient,
        universalProfile: VehicleProfile,
        brandProfiles: [String: VehicleProfile] = [:],
        deviceMemory: DeviceMemory = UserDefaultsDeviceMemory(),
        brandDetector: VehicleBrandDetector = .FALLBACK,
        gpsSpeedSource: GpsSpeedSource,
        customSectionStore: CustomSectionStore = UserDefaultsCustomSectionStore(),
        ringLegendFieldsStore: RingLegendFieldsStore = UserDefaultsRingLegendFieldsStore(),
        dtcDescriptions: DtcDescriptions
    ) {
        self.bleClient = bleClient
        self.queue = ObdCommandQueue(transport: bleClient)
        self.universalProfile = universalProfile
        self.brandProfiles = brandProfiles
        self.deviceMemory = deviceMemory
        self.brandDetector = brandDetector
        self.gpsSpeedSource = gpsSpeedSource
        self.customSectionStore = customSectionStore
        self.ringLegendFieldsStore = ringLegendFieldsStore
        self.dtcDescriptions = dtcDescriptions

        let allProfiles = [universalProfile] + Array(Set(brandProfiles.values.map { $0.profileId })).compactMap { id in
            brandProfiles.values.first { $0.profileId == id }
        }
        self.parameterMetadata = ParameterMetadata(profiles: allProfiles)

        state.availableBrands = [universalBrand] + brandProfiles.keys.sorted()

        // Drop any tyre-pressure/temp fields a user pinned before they were confirmed dead
        // (`unavailableTireFields`) so those cards vanish on next launch instead of sitting there
        // showing "--" forever until manually unchecked.
        let storedCustomFields = customSectionStore.selectedFields()
        let cleanedCustomFields = storedCustomFields.filter { !Self.unavailableTireFields.contains($0) }
        if cleanedCustomFields.count != storedCustomFields.count {
            customSectionStore.setSelectedFields(cleanedCustomFields)
        }
        state.selectedCustomFields = cleanedCustomFields

        let storedRingLegendFields = ringLegendFieldsStore.fields()
        let cleanedRingLegendFields = storedRingLegendFields.filter { !Self.unavailableTireFields.contains($0) }
        if cleanedRingLegendFields.count != storedRingLegendFields.count {
            ringLegendFieldsStore.setFields(cleanedRingLegendFields)
        }
        state.ringLegendFields = cleanedRingLegendFields

        wireBleCallbacks()
        gpsSpeedSource.onSpeedChange = { [weak self] speed in
            self?.state.gpsSpeedKph = speed
        }
    }

    /// Universal fields (always relevant) plus the currently selected/detected brand's own fields
    /// — this excludes PIDs that belong to some *other* brand's profile and can never report data
    /// for the connected vehicle. Used by both the ring gauge's legend picker and the
    /// custom-section field picker, so neither lists fields the connected vehicle can't answer.
    var relevantFieldsForCurrentVehicle: [String] {
        var fields = Set(universalProfile.pids.map(\.field))
        fields.formUnion(ParameterGroups.tripComputerFields)
        if state.selectedBrand != universalBrand, let profile = brandProfiles[state.selectedBrand] {
            fields.formUnion(profile.pids.map(\.field))
            fields.formUnion(profile.models.flatMap { $0.pids.map(\.field) })
        }
        fields.subtract(Self.fastLoopExclusiveFields)
        fields.subtract(Self.unavailableTireFields)
        return Array(fields).sorted()
    }

    // MARK: BLE wiring

    private func wireBleCallbacks() {
        bleClient.onLog = { [weak self] line in self?.appendLog(line) }
        bleClient.onDevicesChange = { [weak self] devices in
            self?.state.devices = devices
            self?.maybeAutoConnect(devices)
        }
        bleClient.onConnectionStateChange = { [weak self] connectionState in
            self?.onConnectionStateChanged(connectionState)
        }
    }

    private func appendLog(_ line: String) {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .medium
        state.logs.append("[\(formatter.string(from: Date()))] \(line)")
        if state.logs.count > 500 {
            state.logs.removeFirst(state.logs.count - 500)
        }
    }

    private func maybeAutoConnect(_ devices: [ScannedBleDevice]) {
        guard !autoConnectAttempted else { return }
        guard let savedAddress = deviceMemory.lastDeviceAddress() else { return }
        guard let match = devices.first(where: { $0.address == savedAddress }) else { return }
        autoConnectAttempted = true
        connect(match, isUserInitiated: false)
    }

    private func onConnectionStateChanged(_ connectionState: BleConnectionState) {
        switch connectionState {
        case .disconnected:
            state.connectionLabel = "已中斷"; state.isScanning = false; state.isReady = false
            stopPolling(); state.connectedDeviceName = nil
        case .scanning:
            state.connectionLabel = "掃描中"; state.isScanning = true; state.isReady = false; state.errorMessage = nil
        case .connecting:
            state.connectionLabel = "連線中"; state.isScanning = false
        case .discoveringGatt:
            state.connectionLabel = "探索服務中"
        case .initializing:
            state.connectionLabel = "初始化中"
        case .ready:
            state.connectionLabel = "已連線"; state.isReady = true; state.errorMessage = nil
            state.connectedDeviceName = bleClient.connectedDeviceName
            if let address = bleClient.connectedDeviceAddress {
                deviceMemory.rememberDevice(address: address)
            }
            Task { await startInitialization() }
        case .disconnectedAfterError:
            state.connectionLabel = "已中斷"; state.isReady = false
            stopPolling(); state.connectedDeviceName = nil
            autoConnectAttempted = false
            bleClient.startScan()
        case .error(let message):
            state.connectionLabel = "發生錯誤"; state.errorMessage = message; state.isScanning = false; state.isReady = false
            stopPolling()
        }
    }

    // MARK: Public actions (UI-facing)

    func startScan() { bleClient.startScan() }
    func stopScan() { bleClient.stopScan() }
    /// The user picking a device from the picker — including switching to a *different* one while
    /// already connected to another — always means "start a new session": `OBDBLEManager.connect
    /// (to:)` jumps straight into a new GATT connection without routing through `.disconnected`
    /// first, so without resetting here, switching vehicles mid-session skipped `pendingTripReset`
    /// entirely and the new vehicle's trip computer silently inherited the old one's accumulated
    /// distance/fuel.
    ///
    /// `isUserInitiated` defaults to true for that path; `maybeAutoConnect`'s auto-recovery after
    /// a transient BLE dropout calls this same method with `false` — that path must *not* reset
    /// the trip (it's resuming the same session, not starting a new one), which an earlier version
    /// of this fix got backwards by resetting unconditionally here, silently zeroing the trip on
    /// every dropout-triggered reconnect exactly like the bug this whole flag exists to prevent.
    func connect(_ device: ScannedBleDevice, isUserInitiated: Bool = true) {
        if isUserInitiated { pendingTripReset = true }
        bleClient.connect(to: device)
    }
    /// Explicit user action — "I'm done with this session" — so the next connection (auto or
    /// manual) starts a fresh trip. Contrast `.disconnectedAfterError`'s auto-recovery path, which
    /// does *not* touch `pendingTripReset`.
    func disconnect() {
        stopPolling()
        bleClient.disconnect()
        pendingTripReset = true
    }
    func clearLogs() { state.logs = [] }

    func startGpsTracking() { gpsSpeedSource.start() }
    func stopGpsTracking() { gpsSpeedSource.stop() }

    /// Toggles `field` in the side-panel custom section. Deselecting always just removes it;
    /// selecting beyond the 8 pinned-card slots bumps the oldest field out (FIFO) instead of
    /// silently appending past what `pinnedFields` ever displays — appending unbounded made
    /// newly-checked fields invisible once 8 were already selected, which read as the picker
    /// being hardcoded/unresponsive.
    func toggleCustomField(_ field: String) {
        var fields = state.selectedCustomFields
        if let index = fields.firstIndex(of: field) {
            fields.remove(at: index)
        } else {
            fields.append(field)
            if fields.count > 8 { fields.removeFirst() }
        }
        state.selectedCustomFields = fields
        customSectionStore.setSelectedFields(fields)
    }

    /// Clears every pinned custom field at once. Explicitly persists the empty array (rather than
    /// just clearing in-memory state) so `CustomSectionStore.selectedFields()` — which only ever
    /// falls back to the seeded defaults when nothing has been configured *yet* — respects this as
    /// a deliberate choice and doesn't silently repopulate the defaults on next launch.
    func clearAllCustomFields() {
        state.selectedCustomFields = []
        customSectionStore.setSelectedFields([])
    }

    /// Drag-to-reorder in the side panels: moves `field` to sit just before `target` in the
    /// pinned-fields order. A no-op if either field isn't actually pinned (e.g. a stray drop) or
    /// they're the same field (dropping a card on itself).
    func moveCustomField(_ field: String, before target: String) {
        guard field != target else { return }
        var fields = state.selectedCustomFields
        guard let fromIndex = fields.firstIndex(of: field), fields.contains(target) else { return }
        fields.remove(at: fromIndex)
        guard let toIndex = fields.firstIndex(of: target) else { return }
        fields.insert(field, at: toIndex)
        state.selectedCustomFields = fields
        customSectionStore.setSelectedFields(fields)
    }

    /// Toggles `field` in the ring gauge's 2-slot center legend. Deselecting always just removes
    /// it; selecting a 3rd field bumps the oldest of the current two out (FIFO) rather than
    /// blocking the tap or popping an error — "pick your two" reads more naturally as "the two
    /// most recent taps" than as a hard capacity limit the user has to manage explicitly.
    func toggleRingLegendField(_ field: String) {
        var fields = state.ringLegendFields
        if let index = fields.firstIndex(of: field) {
            fields.remove(at: index)
        } else {
            fields.append(field)
            if fields.count > 2 { fields.removeFirst() }
        }
        state.ringLegendFields = fields
        ringLegendFieldsStore.setFields(fields)
    }

    func selectBrand(_ brand: String) {
        state.selectedBrand = brand
        restartBrandPolling()
    }

    func sendManualCommand(_ text: String) {
        let command = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !command.isEmpty else { return }
        sendCommandSequence([command])
    }

    /// Sends a sequence of raw commands back-to-back, pausing the fast loop once for the whole
    /// sequence rather than per-command — typing a multi-step probe (switch header, switch
    /// receive filter, then finally the real request) into the single-command field one line at a
    /// time left enough of a gap between steps, at highway speed, for the ECU/bus to go idle
    /// before the actual request landed, which is what made those manual multi-step probes
    /// unreliable — the classic symptom is a "NO DATA" on the final command that a fully-connected
    /// back-to-back send doesn't reproduce.
    func sendCommandSequence(_ commands: [String]) {
        guard !commands.isEmpty else { return }
        Task {
            fastLoopPaused = true
            for command in commands {
                appendLog("TX > \(command)")
                let result = await queue.execute(ObdCommand(text: command))
                if case .success(let raw) = result {
                    state.rawResponse = raw
                    appendLog("RX < \(raw.replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "\n", with: " "))")
                } else {
                    appendLog("逾時：\(command)")
                }
            }
            fastLoopPaused = false
        }
    }

    /// One-tap version of the ATSH6A8 → ATCRA688 → 22D409 probe (restoring the standard header/
    /// filter afterward, same as `pollPid`'s own cleanup) — for capturing the raw response at a
    /// specific gear (7th/8th, still unconfirmed) without the manual-typing delay above.
    func probeGearRaw() {
        sendCommandSequence(["ATSH6A8", "ATCRA688", "22D409", "ATCRA", "ATSH7DF"])
    }

    /// Same one-tap pattern as `probeGearRaw`, but for the 4 tire-pressure DIDs — these have never
    /// returned data at all, and unlike the engine-ECU PIDs (header `6A8`) they go through a
    /// separate module (header `6AF`, the tyre under-inflation detection ECU) that the vehicle's
    /// own workshop manual documents as an *optional* fitment on some trims. A `NO DATA` reply
    /// here means the module is present but these specific DIDs are wrong for it; a timeout on all
    /// four means there's likely no such module on the bus at all.
    func probeTirePressures() {
        sendCommandSequence(["ATSH6AF", "ATCRA68F", "22D610", "22D60F", "22D612", "22D611", "ATCRA", "ATSH7DF"])
    }

    // MARK: Initialization

    private func startInitialization() async {
        for cmd in Self.initSequence {
            if case .timeout = await queue.execute(ObdCommand(text: cmd)) {
                appendLog("初始化逾時：\(cmd)")
                disconnect()
                return
            }
        }
        await detectVehicleBrand()
        await performTroubleCodeRead()
        startPolling()
        restartBrandPolling()
        restartStandardPolling()
    }

    private func detectVehicleBrand() async {
        guard case .success(let raw) = await queue.execute(ObdCommand(text: "0902")) else { return }
        guard let vin = ObdResponseParser.parseVin(raw) else { return }
        state.detectedVin = vin
        guard let brand = brandDetector.detectBrand(vin: vin) else { return }
        state.detectedBrand = brand
        if brandProfiles[brand] != nil {
            selectBrand(brand)
        }
    }

    // MARK: DTC

    func readTroubleCodes() {
        Task { await performTroubleCodeRead() }
    }

    private func performTroubleCodeRead() async {
        fastLoopPaused = true
        state.isReadingTroubleCodes = true
        if case .success(let raw) = await queue.execute(ObdCommand(text: "03")) {
            state.troubleCodes = DtcParser.parse(raw, requestMode: "03")
        }
        state.isReadingTroubleCodes = false
        fastLoopPaused = false
    }

    // MARK: ECU support test

    func testEcuSupport() {
        Task { await runEcuSupportTest() }
    }

    private func runEcuSupportTest() async {
        fastLoopPaused = true
        state.isTestingEcu = true
        state.ecuTestResults = []

        state.ecuTestResults.append(await probe("0100", description: "確認標準匯流排是否有回應"))
        state.ecuTestResults.append(await probe("0902", description: "Mode 09 讀取 VIN"))

        var f190 = await probe("22F190", description: "UDS 讀取 VIN（DID F190）")
        if f190.status == .negative || f190.status == .noData {
            _ = await queue.execute(ObdCommand(text: "1003"))
            f190 = await probe("22F190", description: "UDS 讀取 VIN（切換延伸診斷 Session 後重試）")
            _ = await queue.execute(ObdCommand(text: "1001"))
        }
        state.ecuTestResults.append(f190)

        state.isTestingEcu = false
        fastLoopPaused = false
    }

    private func probe(_ command: String, description: String) async -> EcuTestResult {
        let result = await queue.execute(ObdCommand(text: command))
        switch result {
        case .timeout:
            return EcuTestResult(command: command, description: description, raw: nil, status: .timeout, statusMessage: "逾時")
        case .success(let raw):
            switch ObdResponseParser.classify(raw) {
            case .data:
                return EcuTestResult(command: command, description: description, raw: raw, status: .supported, statusMessage: "有回應")
            case .noData:
                return EcuTestResult(command: command, description: description, raw: raw, status: .noData, statusMessage: "NO DATA")
            case .negativeResponse(_, _, let messageZh):
                return EcuTestResult(command: command, description: description, raw: raw, status: .negative, statusMessage: "拒絕：\(messageZh)")
            case .unrecognized:
                return EcuTestResult(command: command, description: description, raw: raw, status: .unrecognized, statusMessage: "無法解析")
            }
        }
    }

    // MARK: Fast loop (speed / rpm / MAF / trip computer)

    private func startPolling() {
        if pendingTripReset {
            tripDistanceKm = 0
            tripFuelLiters = 0
            tripStartDate = Date()
            pendingTripReset = false
            // These are only overwritten once enough new distance/fuel has accumulated to satisfy
            // updateTripComputer's own thresholds — without clearing them here, the dashboard kept
            // showing the *previous* trip's average/instant economy (describing zero km of new
            // driving) until the new trip caught up to those thresholds.
            state.standardReadings["averageFuelConsumption"] = nil
            state.standardReadings["instantFuelConsumption"] = nil
            state.standardReadings["acceleration"] = nil
        }
        previousSpeedKph = nil
        previousSpeedSampleDate = nil
        lastSpeedKph = nil
        rpmStaleCount = 0
        speedStaleCount = 0
        // Every connection (including a dropout's auto-reconnect) re-announces the battery
        // voltage and re-arms every speed threshold — unlike the trip computer above, there's no
        // harm in saying the voltage again after a reconnect, and stale announced-thresholds from
        // before the drop could otherwise suppress a real speeding announcement after reconnecting
        // mid-excursion.
        hasAnnouncedBatteryVoltage = false
        announcedSpeedThresholds = []

        pollingJob?.cancel()
        pollingJob = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                await self.fastLoopTick()
                try? await Task.sleep(for: .milliseconds(Self.pollIntervalMs))
            }
        }
    }

    private func fastLoopTick() async {
        // Unlike rpm/speed (which persist their last good value across a few failed ticks via a
        // stale-count threshold, since gauges shouldn't visibly blank out on one missed poll), MAF
        // has no such carry-over: it's read fresh every tick specifically for the trip computer,
        // so a failed read means "no fuel-economy contribution this tick," not "reuse the last
        // reading" — reusing a stale MAF value against a fresh speed reading would silently
        // mis-estimate fuel economy for however long MAF keeps failing.
        var mafGramsPerSec: Double?
        if !fastLoopPaused {
            if case .success(let raw) = await queue.execute(ObdCommand(text: "010C")),
               let value = ObdResponseParser.parsePid(raw, request: "010C", formula: "((A*256)+B)/4") {
                rpmStaleCount = 0
                state.vehicleData.rpm = Int(value)
                var history = state.rpmHistory
                history.append(Float(value))
                if history.count > Self.historySize { history.removeFirst(history.count - Self.historySize) }
                state.rpmHistory = history
            } else {
                rpmStaleCount += 1
                if rpmStaleCount >= Self.staleThreshold { state.vehicleData.rpm = nil }
            }

            if case .success(let raw) = await queue.execute(ObdCommand(text: "010D")),
               let value = ObdResponseParser.parsePid(raw, request: "010D", formula: "A") {
                speedStaleCount = 0
                state.vehicleData.speedKph = Int(value)
                lastSpeedKph = value
                announceSpeedIfNeeded(value)
                var history = state.speedHistory
                history.append(Float(value))
                if history.count > Self.historySize { history.removeFirst(history.count - Self.historySize) }
                state.speedHistory = history
            } else {
                speedStaleCount += 1
                if speedStaleCount >= Self.staleThreshold { state.vehicleData.speedKph = nil; lastSpeedKph = nil }
            }

            if case .success(let raw) = await queue.execute(ObdCommand(text: "0110")),
               let value = ObdResponseParser.parsePid(raw, request: "0110", formula: "((A*256)+B)/100") {
                mafGramsPerSec = value
            }
        }

        // `speedKph` (state.effectiveSpeedKph — OBD, falling back to GPS) drives distance/fuel so
        // 行駛里程/平均油耗 keep accumulating through an OBD signal gap instead of freezing; the
        // separate `obdSpeedKph` (lastSpeedKph — OBD only, nil during that same gap) is what
        // acceleration is computed from, so the two consecutive samples it diffs are never a
        // stale OBD reading against a live GPS one — that mismatch alone (the two sources
        // routinely disagree by a few km/h) was enough to read as a spurious hard-braking spike
        // the instant the fallback kicked in or cleared.
        updateTripComputer(speedKph: state.effectiveSpeedKph, obdSpeedKph: lastSpeedKph, mafGramsPerSec: mafGramsPerSec, accumulateMotion: !fastLoopPaused)
    }

    /// Estimates fuel economy from MAF (mode 01 PID `$10`), not any vehicle-reported economy PID
    /// — always an approximation (stoichiometric AFR 14.7:1, gasoline density 750 g/L, no diesel
    /// adjustment). Trip *duration* is wall-clock (`tripStartDate` to now) so it reads correctly
    /// even after the fast loop was suspended for a while (iOS backgrounding — see `tripStartDate`).
    /// Distance/fuel/acceleration are still skipped while paused (manual command, ECU test) so a
    /// stale frozen speed doesn't fabricate movement, and they're integrated per-tick (assuming a
    /// steady `pollIntervalMs` cadence) rather than wall-clock, which is correct while the loop is
    /// actually ticking — after a background gap there's simply no speed/MAF sample for the time
    /// that was missed, so that distance is honestly left untracked rather than guessed at.
    private func updateTripComputer(speedKph: Double?, obdSpeedKph: Double?, mafGramsPerSec: Double?, accumulateMotion: Bool) {
        let tickHours = (Double(Self.pollIntervalMs) / 1000.0) / 3600.0
        let elapsedSeconds = tripStartDate.map { Date().timeIntervalSince($0) } ?? 0
        state.standardReadings["tripDuration"] = String(format: "%.1f 分鐘", elapsedSeconds / 60.0)

        guard accumulateMotion else { return }

        // Distance only needs *a* speed, GPS-fallback included — kept out from under the MAF
        // guard below so it keeps accumulating through an OBD comm gap (MAF, mode 01 PID $10, is
        // exclusively OBD-sourced and always nil during one) instead of freezing for the whole gap.
        if let speedKph {
            tripDistanceKm += speedKph * tickHours
        }

        if let speedKph, let mafGramsPerSec {
            let fuelLitersPerHour = mafGramsPerSec * 3600.0 / (14.7 * 750.0)
            // Idle fuel (stopped at a light, warming up) used to count toward the average here
            // even though it adds nothing to tripDistanceKm — burning fuel for zero km is exactly
            // what drags the average below what the car's own trip computer shows. Only fold fuel
            // into the average while actually moving, matching the same >1 km/h floor already used
            // just below to decide instant-consumption's units.
            if speedKph > 1.0 {
                tripFuelLiters += fuelLitersPerHour * tickHours
            }

            if speedKph > 1.0 && fuelLitersPerHour > Self.minFuelRateForEconomyLph {
                state.standardReadings["instantFuelConsumption"] = String(format: "%.1f 公里/公升", speedKph / fuelLitersPerHour)
            } else {
                state.standardReadings["instantFuelConsumption"] = String(format: "%.2f 公升/小時", fuelLitersPerHour)
            }

            if tripDistanceKm > 0.05 && tripFuelLiters > Self.minFuelForAverageEconomyL {
                state.standardReadings["averageFuelConsumption"] = String(format: "%.1f 公里/公升", tripDistanceKm / tripFuelLiters)
            }
        }

        // Deliberately diffs `obdSpeedKph` (OBD only), not the GPS-fallback `speedKph` above — the
        // two sources routinely disagree by a few km/h, and dividing that offset by a small dt
        // (~0.2s) the instant the fallback kicks in or clears would read as a spurious hard-brake/
        // acceleration spike. Real elapsed time since the last sample, not an assumed fixed
        // pollIntervalMs, since the very next tick after a background gap could be minutes after
        // "previousSpeedKph" was captured, and dividing a real speed change by an assumed 0.2s
        // would spike to a nonsense reading the same way.
        let now = Date()
        if let previous = previousSpeedKph, let obdSpeedKph, let previousDate = previousSpeedSampleDate {
            let dt = now.timeIntervalSince(previousDate)
            if dt > 0 && dt < 2.0 {
                let deltaMps = (obdSpeedKph - previous) / 3.6
                let acceleration = deltaMps / dt
                state.standardReadings["acceleration"] = String(format: "%.2f 米/秒²", acceleration)
            }
        }
        previousSpeedKph = obdSpeedKph
        previousSpeedSampleDate = now

        state.standardReadings["tripDistance"] = String(format: "%.2f 公里", tripDistanceKm)
    }

    // MARK: Brand / standard PID tickers

    private func stopPolling() {
        pollingJob?.cancel(); pollingJob = nil
        brandPollingJob?.cancel(); brandPollingJob = nil
        fastBrandPollingJob?.cancel(); fastBrandPollingJob = nil
        standardPollingJob?.cancel(); standardPollingJob = nil
        fastStandardPollingJob?.cancel(); fastStandardPollingJob = nil
        failureStreak = [:]
        giveUpCooldownRemaining = [:]
    }

    private func restartBrandPolling() {
        brandPollingJob?.cancel()
        fastBrandPollingJob?.cancel()
        guard let profile = brandProfiles[state.selectedBrand] else {
            brandPollingJob = nil
            fastBrandPollingJob = nil
            return
        }
        let allPids = (profile.pids + profile.models.flatMap { model in
            model.pids.map { pid -> PidDefinition in
                var copy = pid
                if copy.ecuHeader == nil { copy.ecuHeader = model.ecuHeader }
                return copy
            }
        }).filter { !Self.unavailableTireFields.contains($0.field) }
        let slow = allPids.filter { !$0.fastPoll }
        let fast = allPids.filter { $0.fastPoll }
        brandPollingJob = launchPidTicker(pids: slow, intervalMs: Self.brandPollIntervalMs, isBrand: true)
        fastBrandPollingJob = launchPidTicker(pids: fast, intervalMs: Self.fastBrandPollIntervalMs, isBrand: true)
    }

    private func restartStandardPolling() {
        standardPollingJob?.cancel()
        fastStandardPollingJob?.cancel()
        let extraPids = universalProfile.pids.filter { !Self.fastLoopExclusiveFields.contains($0.field) }
        let slow = extraPids.filter { !$0.fastPoll }
        let fast = extraPids.filter { $0.fastPoll }
        standardPollingJob = launchPidTicker(pids: slow, intervalMs: Self.brandPollIntervalMs, isBrand: false)
        fastStandardPollingJob = launchPidTicker(pids: fast, intervalMs: Self.fastBrandPollIntervalMs, isBrand: false)
    }

    private func launchPidTicker(pids: [PidDefinition], intervalMs: Int, isBrand: Bool) -> Task<Void, Never>? {
        guard !pids.isEmpty else { return nil }
        return Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                for pid in pids {
                    if Task.isCancelled { return }
                    if (self.failureStreak[pid.field] ?? 0) >= Self.giveUpThreshold {
                        let remaining = (self.giveUpCooldownRemaining[pid.field] ?? Self.giveUpCooldownTurns) - 1
                        if remaining > 0 {
                            self.giveUpCooldownRemaining[pid.field] = remaining
                            try? await Task.sleep(for: .milliseconds(intervalMs))
                            continue
                        }
                        // Cooldown elapsed — give it one more chance instead of staying given-up
                        // for the rest of the connection.
                        self.failureStreak[pid.field] = 0
                        self.giveUpCooldownRemaining[pid.field] = nil
                    }
                    while self.fastLoopPaused {
                        try? await Task.sleep(for: .milliseconds(Self.pollIntervalMs))
                        if Task.isCancelled { return }
                    }
                    self.fastLoopPaused = true
                    let value = await self.pollPid(pid)
                    // pollPid can take several round-trips (ECU header switch + read + restore) and
                    // doesn't itself observe cancellation, so this ticker may have been cancelled
                    // (stopPolling() on disconnect, or restartBrandPolling() replacing it) while it
                    // was in flight. The mutex must still be released unconditionally — while it's
                    // held, no other ticker can even reach this point (they're blocked in the
                    // `while self.fastLoopPaused` spin-wait above), so there is no "newer holder" to
                    // step on; failing to release here would deadlock every future poller instead.
                    // What cancellation *should* skip is writing a reading from a ticker that's
                    // being torn down.
                    self.fastLoopPaused = false
                    if Task.isCancelled { return }

                    if let value {
                        self.failureStreak[pid.field] = 0
                        let formatted = Self.formatReading(field: pid.field, value: value, unit: pid.unit)
                        if isBrand {
                            self.state.extraReadings[pid.field] = formatted
                        } else {
                            self.state.standardReadings[pid.field] = formatted
                        }
                        self.announceIfNeeded(field: pid.field, value: value)
                    } else {
                        self.failureStreak[pid.field] = (self.failureStreak[pid.field] ?? 0) + 1
                    }
                    try? await Task.sleep(for: .milliseconds(intervalMs))
                }
            }
        }
    }

    /// Every polled field is a plain "%.1f <unit>" string except a couple of duration fields whose
    /// unit is raw seconds — those read far more naturally as "X小時Y分鐘" than e.g. "2441.0 秒".
    private static func formatReading(field: String, value: Double, unit: String) -> String {
        if field == "runtimeSinceStartSec" {
            return formatHoursMinutes(seconds: value)
        }
        if field == "gearRaw" {
            return formatGear(value)
        }
        return String(format: "%.1f %@", value, unit)
    }

    /// 0 = P（停車檔）, 7 = R（倒車檔）, confirmed by real-world testing on an automatic-gearbox
    /// vehicle — see the field's own descriptionZh in citroen.json. 1–6 are the already-verified
    /// forward gear numbers; N's raw value is still unconfirmed, so anything else just falls back
    /// to the plain number rather than guessing.
    private static func formatGear(_ value: Double) -> String {
        switch Int(value.rounded()) {
        case 0: return "P 檔"
        case 7: return "R 檔（倒車）"
        default: return String(format: "%.0f 檔", value)
        }
    }


    private static func formatHoursMinutes(seconds: Double) -> String {
        let totalMinutes = Int(seconds / 60)
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60
        return hours > 0 ? "\(hours) 小時 \(minutes) 分鐘" : "\(minutes) 分鐘"
    }

    /// The spoken announcement this app makes from the generic ticker: battery voltage once per
    /// connection, right after the first successful read. Opt-out-free by design — it's rare,
    /// short, and exactly the kind of glanceable-while-driving information this app's whole
    /// landscape layout already exists for. Speeding announcements are separate — see
    /// `announceSpeedIfNeeded`, called from the fast loop where speed itself is read.
    private func announceIfNeeded(field: String, value: Double) {
        switch field {
        case "controlModuleVoltage":
            guard !hasAnnouncedBatteryVoltage else { return }
            hasAnnouncedBatteryVoltage = true
            speechAnnouncer.speak(String(format: "電池電壓 %.1f 伏特", value))
        default:
            break
        }
    }

    /// Speaks once per threshold the first time speed reaches it, not on every tick spent above
    /// it — otherwise cruising at 125 km/h would repeat "車速已達 120 公里" every poll. All
    /// thresholds re-arm together only once speed drops back under the lowest one (110), so a
    /// single speeding excursion that peaks at 130 and eases back to 115 doesn't re-announce 110
    /// or 120 on the way down, but a genuinely new excursion after slowing back into normal traffic
    /// does.
    private func announceSpeedIfNeeded(_ speedKph: Double) {
        guard let lowest = Self.speedAnnounceThresholds.first else { return }
        if speedKph < Double(lowest) {
            announcedSpeedThresholds.removeAll()
            return
        }
        for threshold in Self.speedAnnounceThresholds where speedKph >= Double(threshold) {
            if announcedSpeedThresholds.insert(threshold).inserted {
                speechAnnouncer.speak("車速已達每小時 \(threshold) 公里")
            }
        }
    }

    /// Runs the ECU header/receive-filter sequence around a single PID read, restoring the
    /// standard 11-bit functional broadcast header (`ATSH7DF`) afterward — not `ATSH00`, which is
    /// a malformed 2-digit header some adapters silently ignore, leaving the header stuck on the
    /// previous brand PID's value (a real bug hit during Android field testing).
    private func pollPid(_ pid: PidDefinition) async -> Double? {
        if let header = pid.ecuHeader {
            _ = await queue.execute(ObdCommand(text: "ATSH\(header)"))
        }
        if let filter = pid.ecuReceiveFilter {
            _ = await queue.execute(ObdCommand(text: "ATCRA\(filter)"))
        }

        var value: Double?
        if case .success(let raw) = await queue.execute(ObdCommand(text: pid.request)) {
            if let formula = pid.formula {
                value = ObdResponseParser.parsePid(raw, request: pid.request, formula: formula)
            } else if let spec = pid.bitField {
                value = ObdResponseParser.parsePidBitField(raw, request: pid.request, spec: spec)
            }
        }

        if pid.ecuReceiveFilter != nil {
            _ = await queue.execute(ObdCommand(text: "ATCRA"))
        }
        if pid.ecuHeader != nil {
            _ = await queue.execute(ObdCommand(text: "ATSH7DF"))
        }
        return value
    }
}
