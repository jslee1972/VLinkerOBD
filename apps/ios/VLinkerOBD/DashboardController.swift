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
    private let dashboardModeStore: DashboardModeStore
    let parameterMetadata: ParameterMetadata
    let dtcDescriptions: DtcDescriptions

    // MARK: Tuning constants (mirrors DashboardViewModel.kt exactly)

    private static let pollIntervalMs = 200
    private static let brandPollIntervalMs = 3000
    private static let fastBrandPollIntervalMs = 800
    private static let giveUpThreshold = 5
    private static let historySize = 60
    private static let staleThreshold = 5
    private static let minFuelRateForEconomyLph = 0.05
    private static let minFuelForAverageEconomyL = 0.01
    private static let initSequence = ["ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATCFC1", "0100"]

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

    // MARK: Trip computer state

    private var tripDistanceKm: Double = 0
    private var tripFuelLiters: Double = 0
    /// Wall-clock, not a per-tick counter — see `updateTripComputer`'s doc comment for why: a
    /// tick-counted duration silently stops advancing for however long iOS suspends this app in
    /// the background (no BLE background mode is declared), which reads as "行駛時間" being stuck
    /// even after a real hour of driving with the screen locked or another app frontmost.
    private var tripStartDate: Date?
    private var previousSpeedKph: Double?
    private var previousSpeedSampleDate: Date?
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
        dashboardModeStore: DashboardModeStore = UserDefaultsDashboardModeStore(),
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
        self.dashboardModeStore = dashboardModeStore
        self.dtcDescriptions = dtcDescriptions

        let allProfiles = [universalProfile] + Array(Set(brandProfiles.values.map { $0.profileId })).compactMap { id in
            brandProfiles.values.first { $0.profileId == id }
        }
        self.parameterMetadata = ParameterMetadata(profiles: allProfiles)

        let allFields = Self.computeAllKnownFields(universalProfile: universalProfile, brandProfiles: brandProfiles)
        state.allKnownFields = allFields
        state.availableBrands = [universalBrand] + brandProfiles.keys.sorted()
        state.selectedCustomFields = customSectionStore.selectedFields()
        state.dashboardMode = dashboardModeStore.mode()

        wireBleCallbacks()
        gpsSpeedSource.onSpeedChange = { [weak self] speed in
            self?.state.gpsSpeedKph = speed
        }
    }

    private static func computeAllKnownFields(universalProfile: VehicleProfile, brandProfiles: [String: VehicleProfile]) -> [String] {
        var fields = universalProfile.pids.map(\.field)
        fields += ParameterGroups.tripComputerFields
        for profile in brandProfiles.values {
            fields += profile.pids.map(\.field)
            fields += profile.models.flatMap { $0.pids.map(\.field) }
        }
        return Array(Set(fields)).sorted()
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
        connect(match)
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
    func connect(_ device: ScannedBleDevice) { bleClient.connect(to: device) }
    func disconnect() { stopPolling(); bleClient.disconnect() }
    func clearLogs() { state.logs = [] }

    func startGpsTracking() { gpsSpeedSource.start() }
    func stopGpsTracking() { gpsSpeedSource.stop() }

    func setDashboardMode(_ mode: DashboardMode) {
        state.dashboardMode = mode
        dashboardModeStore.setMode(mode)
    }

    func toggleCustomField(_ field: String) {
        var fields = state.selectedCustomFields
        if fields.contains(field) { fields.remove(field) } else { fields.insert(field) }
        state.selectedCustomFields = fields
        customSectionStore.setSelectedFields(fields)
    }

    /// Clears every pinned custom field at once. Explicitly persists the empty set (rather than
    /// just clearing in-memory state) so `CustomSectionStore.selectedFields()` — which only ever
    /// falls back to the seeded defaults when nothing has been configured *yet* — respects this as
    /// a deliberate choice and doesn't silently repopulate the defaults on next launch.
    func clearAllCustomFields() {
        state.selectedCustomFields = []
        customSectionStore.setSelectedFields([])
    }

    func selectBrand(_ brand: String) {
        state.selectedBrand = brand
        restartBrandPolling()
    }

    func sendManualCommand(_ text: String) {
        let command = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !command.isEmpty else { return }
        Task {
            fastLoopPaused = true
            appendLog("TX > \(command)")
            let result = await queue.execute(ObdCommand(text: command))
            if case .success(let raw) = result {
                state.rawResponse = raw
                appendLog("RX < \(raw.replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "\n", with: " "))")
            } else {
                appendLog("逾時：\(command)")
            }
            fastLoopPaused = false
        }
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
        tripDistanceKm = 0
        tripFuelLiters = 0
        tripStartDate = Date()
        previousSpeedKph = nil
        previousSpeedSampleDate = nil
        lastSpeedKph = nil
        rpmStaleCount = 0
        speedStaleCount = 0

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

        updateTripComputer(speedKph: lastSpeedKph, mafGramsPerSec: mafGramsPerSec, accumulateMotion: !fastLoopPaused)
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
    private func updateTripComputer(speedKph: Double?, mafGramsPerSec: Double?, accumulateMotion: Bool) {
        let tickHours = (Double(Self.pollIntervalMs) / 1000.0) / 3600.0
        let elapsedSeconds = tripStartDate.map { Date().timeIntervalSince($0) } ?? 0
        state.standardReadings["tripDuration"] = String(format: "%.1f 分鐘", elapsedSeconds / 60.0)

        guard accumulateMotion else { return }

        if let speedKph, let mafGramsPerSec {
            let fuelLitersPerHour = mafGramsPerSec * 3600.0 / (14.7 * 750.0)
            tripDistanceKm += speedKph * tickHours
            tripFuelLiters += fuelLitersPerHour * tickHours

            if speedKph > 1.0 && fuelLitersPerHour > Self.minFuelRateForEconomyLph {
                state.standardReadings["instantFuelConsumption"] = String(format: "%.1f 公里/公升", speedKph / fuelLitersPerHour)
            } else {
                state.standardReadings["instantFuelConsumption"] = String(format: "%.2f 公升/小時", fuelLitersPerHour)
            }

            if tripDistanceKm > 0.05 && tripFuelLiters > Self.minFuelForAverageEconomyL {
                state.standardReadings["averageFuelConsumption"] = String(format: "%.1f 公里/公升", tripDistanceKm / tripFuelLiters)
            }
        }

        // Real elapsed time since the last sample, not an assumed fixed pollIntervalMs — the very
        // next tick after a background gap could be minutes after "previousSpeedKph" was captured,
        // and dividing a real speed change by an assumed 0.2s would spike to a nonsense reading.
        let now = Date()
        if let previous = previousSpeedKph, let speedKph, let previousDate = previousSpeedSampleDate {
            let dt = now.timeIntervalSince(previousDate)
            if dt > 0 && dt < 2.0 {
                let deltaMps = (speedKph - previous) / 3.6
                let acceleration = deltaMps / dt
                state.standardReadings["acceleration"] = String(format: "%.2f 米/秒²", acceleration)
            }
        }
        previousSpeedKph = speedKph
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
    }

    private func restartBrandPolling() {
        brandPollingJob?.cancel()
        fastBrandPollingJob?.cancel()
        guard let profile = brandProfiles[state.selectedBrand] else {
            brandPollingJob = nil
            fastBrandPollingJob = nil
            return
        }
        let allPids = profile.pids + profile.models.flatMap { model in
            model.pids.map { pid -> PidDefinition in
                var copy = pid
                if copy.ecuHeader == nil { copy.ecuHeader = model.ecuHeader }
                return copy
            }
        }
        let slow = allPids.filter { !$0.fastPoll }
        let fast = allPids.filter { $0.fastPoll }
        brandPollingJob = launchPidTicker(pids: slow, intervalMs: Self.brandPollIntervalMs, isBrand: true)
        fastBrandPollingJob = launchPidTicker(pids: fast, intervalMs: Self.fastBrandPollIntervalMs, isBrand: true)
    }

    private func restartStandardPolling() {
        standardPollingJob?.cancel()
        fastStandardPollingJob?.cancel()
        // speedKPH/rpm/mafGramsPerSec are handled by the dedicated fast loop, not this ticker.
        let extraPids = universalProfile.pids.filter { !["speedKPH", "rpm", "mafGramsPerSec"].contains($0.field) }
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
                        try? await Task.sleep(for: .milliseconds(intervalMs))
                        continue
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
        return String(format: "%.1f %@", value, unit)
    }

    private static func formatHoursMinutes(seconds: Double) -> String {
        let totalMinutes = Int(seconds / 60)
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60
        return hours > 0 ? "\(hours) 小時 \(minutes) 分鐘" : "\(minutes) 分鐘"
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
