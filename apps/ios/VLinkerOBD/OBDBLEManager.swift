import Foundation
import CoreBluetooth

@MainActor
final class OBDBLEManager: NSObject, ObservableObject {
    enum ConnectionState: String {
        case idle = "待機"
        case scanning = "掃描中"
        case connecting = "連線中"
        case discovering = "探索服務"
        case initializing = "初始化 OBD"
        case ready = "已連線"
        case disconnected = "已斷線"
        case failed = "錯誤"
    }

    @Published var state: ConnectionState = .idle
    @Published var speedKPH: Int = 0
    @Published var deviceName: String = "-"
    @Published var rawResponse: String = "-"
    @Published var logText: String = ""
    @Published var discoveredDevices: [CBPeripheral] = []

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var writeCharacteristic: CBCharacteristic?
    private var notifyCharacteristic: CBCharacteristic?

    private var rxBuffer = ""
    private var commandQueue: [String] = []
    private var commandInFlight: String?
    private var initComplete = false
    private var pollTimer: Timer?

    private let targetNameHints = ["VLINKER", "V-LINK", "VLINK"]

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func startScan() {
        guard central.state == .poweredOn else {
            appendLog("Bluetooth 尚未可用：\(central.state.rawValue)")
            return
        }

        disconnect(clearLog: false)
        discoveredDevices = []
        state = .scanning
        appendLog("開始掃描 BLE...")
        central.scanForPeripherals(withServices: nil,
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func connect(to p: CBPeripheral) {
        central.stopScan()
        peripheral = p
        p.delegate = self
        deviceName = p.name ?? "Unknown"
        state = .connecting
        appendLog("連線：\(deviceName)")
        central.connect(p, options: nil)
    }

    func connectBestCandidate() {
        if let preferred = discoveredDevices.first(where: {
            let n = ($0.name ?? "").uppercased()
            return targetNameHints.contains(where: n.contains)
        }) {
            connect(to: preferred)
        } else if let first = discoveredDevices.first {
            connect(to: first)
        } else {
            appendLog("目前沒有可連線的 BLE 裝置")
        }
    }

    func disconnect(clearLog: Bool = false) {
        pollTimer?.invalidate()
        pollTimer = nil
        commandQueue.removeAll()
        commandInFlight = nil
        initComplete = false
        rxBuffer = ""
        writeCharacteristic = nil
        notifyCharacteristic = nil

        if let p = peripheral {
            central.cancelPeripheralConnection(p)
        }
        peripheral = nil

        if clearLog {
            logText = ""
        }

        if state != .scanning {
            state = .disconnected
        }
    }

    func sendManual(_ command: String) {
        let cmd = command.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cmd.isEmpty else { return }
        enqueue(cmd)
        sendNextIfPossible()
    }

    private func beginInitialization() {
        state = .initializing
        commandQueue = [
            "ATZ",
            "ATE0",
            "ATL0",
            "ATS0",
            "ATH0",
            "ATSP0",
            "0100"
        ]
        appendLog("開始初始化 OBD...")
        sendNextIfPossible()
    }

    private func startPolling() {
        initComplete = true
        state = .ready
        appendLog("OBD 初始化完成，開始輪詢 010D 車速")

        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.20, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.commandInFlight == nil, self.commandQueue.isEmpty else { return }
                self.enqueue("010D")
                self.sendNextIfPossible()
            }
        }
    }

    private func enqueue(_ command: String) {
        commandQueue.append(command.uppercased())
    }

    private func sendNextIfPossible() {
        guard commandInFlight == nil,
              let p = peripheral,
              let ch = writeCharacteristic,
              !commandQueue.isEmpty else { return }

        let cmd = commandQueue.removeFirst()
        commandInFlight = cmd
        rxBuffer = ""

        let payload = (cmd + "\r").data(using: .utf8)!
        let type: CBCharacteristicWriteType =
            ch.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse

        appendLog("TX > \(cmd)")
        p.writeValue(payload, for: ch, type: type)
    }

    private func processIncoming(_ text: String) {
        rxBuffer += text
        guard rxBuffer.contains(">") else { return }

        let complete = rxBuffer
        rxBuffer = ""
        rawResponse = complete
        appendLog("RX < \(complete.replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "\n", with: " "))")

        let finishedCommand = commandInFlight
        commandInFlight = nil

        if finishedCommand == "010D", let speed = OBDParser.parseSpeedKPH(complete) {
            speedKPH = speed
        }

        if !initComplete {
            if commandQueue.isEmpty {
                startPolling()
            } else {
                sendNextIfPossible()
            }
        } else {
            sendNextIfPossible()
        }
    }

    private func tryBindKnownCharacteristics(on p: CBPeripheral) {
        let all = p.services?.flatMap { $0.characteristics ?? [] } ?? []

        func char(_ uuid: String) -> CBCharacteristic? {
            let target = CBUUID(string: uuid)
            return all.first { $0.uuid == target }
        }

        if let e101 = char("E101") {
            if e101.properties.contains(.notify) || e101.properties.contains(.indicate) {
                notifyCharacteristic = e101
            }
            if e101.properties.contains(.write) || e101.properties.contains(.writeWithoutResponse) {
                writeCharacteristic = e101
            }
            if writeCharacteristic != nil, notifyCharacteristic != nil {
                appendLog("Bind profile: E100/E101")
                finishBinding(p)
                return
            }
        }

        if let n = char("FFF1"), let w = char("FFF2") {
            notifyCharacteristic = n
            writeCharacteristic = w
            appendLog("Bind profile: FFF0/FFF1/FFF2")
            finishBinding(p)
            return
        }

        if let n = char("2AF0"), let w = char("2AF1") {
            notifyCharacteristic = n
            writeCharacteristic = w
            appendLog("Bind profile: 18F0/2AF0/2AF1")
            finishBinding(p)
            return
        }

        if let n = char("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
           let w = char("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") {
            notifyCharacteristic = n
            writeCharacteristic = w
            appendLog("Bind profile: Nordic UART")
            finishBinding(p)
            return
        }

        let notifyCandidates = all.filter {
            $0.properties.contains(.notify) || $0.properties.contains(.indicate)
        }
        let writeCandidates = all.filter {
            $0.properties.contains(.write) || $0.properties.contains(.writeWithoutResponse)
        }

        if let shared = all.first(where: {
            ($0.properties.contains(.notify) || $0.properties.contains(.indicate)) &&
            ($0.properties.contains(.write) || $0.properties.contains(.writeWithoutResponse))
        }) {
            notifyCharacteristic = shared
            writeCharacteristic = shared
            appendLog("Auto-bind shared characteristic: \(shared.uuid)")
            finishBinding(p)
            return
        }

        if let n = notifyCandidates.first, let w = writeCandidates.first {
            notifyCharacteristic = n
            writeCharacteristic = w
            appendLog("Auto-bind notify=\(n.uuid), write=\(w.uuid)")
            finishBinding(p)
            return
        }

        state = .failed
        appendLog("找不到可用的 BLE UART characteristic。請把上方 UUID Log 提供給我。")
    }

    private func finishBinding(_ p: CBPeripheral) {
        guard let notifyCharacteristic else { return }
        p.setNotifyValue(true, for: notifyCharacteristic)
        appendLog("啟用 Notify：\(notifyCharacteristic.uuid)")
    }

    private func appendLog(_ line: String) {
        let stamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
        logText += "[\(stamp)] \(line)\n"
        if logText.count > 20000 {
            logText = String(logText.suffix(16000))
        }
    }
}

extension OBDBLEManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            switch central.state {
            case .poweredOn:
                self.appendLog("Bluetooth 已開啟")
            case .poweredOff:
                self.state = .failed
                self.appendLog("Bluetooth 已關閉")
            case .unauthorized:
                self.state = .failed
                self.appendLog("App 沒有 Bluetooth 權限")
            case .unsupported:
                self.state = .failed
                self.appendLog("此裝置不支援 BLE")
            default:
                self.appendLog("Bluetooth state: \(central.state.rawValue)")
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didDiscover peripheral: CBPeripheral,
                                    advertisementData: [String : Any],
                                    rssi RSSI: NSNumber) {
        Task { @MainActor in
            if !self.discoveredDevices.contains(where: { $0.identifier == peripheral.identifier }) {
                self.discoveredDevices.append(peripheral)
                self.appendLog("發現：\(peripheral.name ?? "Unknown") RSSI=\(RSSI)")
            }

            let name = (peripheral.name ?? "").uppercased()
            if self.state == .scanning,
               self.targetNameHints.contains(where: name.contains) {
                self.connect(to: peripheral)
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            self.state = .discovering
            self.appendLog("BLE 已連線，探索 services...")
            peripheral.discoverServices(nil)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didFailToConnect peripheral: CBPeripheral,
                                    error: Error?) {
        Task { @MainActor in
            self.state = .failed
            self.appendLog("連線失敗：\(error?.localizedDescription ?? "unknown")")
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager,
                                    didDisconnectPeripheral peripheral: CBPeripheral,
                                    error: Error?) {
        Task { @MainActor in
            self.pollTimer?.invalidate()
            self.pollTimer = nil
            self.state = .disconnected
            self.appendLog("BLE 已斷線：\(error?.localizedDescription ?? "normal")")
        }
    }
}

extension OBDBLEManager: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didDiscoverServices error: Error?) {
        Task { @MainActor in
            if let error {
                self.state = .failed
                self.appendLog("Service discovery error: \(error.localizedDescription)")
                return
            }

            guard let services = peripheral.services else { return }
            for service in services {
                self.appendLog("Service: \(service.uuid)")
                peripheral.discoverCharacteristics(nil, for: service)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didDiscoverCharacteristicsFor service: CBService,
                                error: Error?) {
        Task { @MainActor in
            if let error {
                self.appendLog("Characteristic discovery error: \(error.localizedDescription)")
                return
            }

            for ch in service.characteristics ?? [] {
                self.appendLog("  Char \(ch.uuid) properties=\(ch.properties.rawValue)")
            }

            let allServicesDone = peripheral.services?.allSatisfy { $0.characteristics != nil } ?? false
            if allServicesDone, self.writeCharacteristic == nil {
                self.tryBindKnownCharacteristics(on: peripheral)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didUpdateNotificationStateFor characteristic: CBCharacteristic,
                                error: Error?) {
        Task { @MainActor in
            if let error {
                self.state = .failed
                self.appendLog("Notify 啟用失敗：\(error.localizedDescription)")
                return
            }

            if characteristic.isNotifying {
                self.appendLog("Notify 已啟用")
                self.beginInitialization()
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didUpdateValueFor characteristic: CBCharacteristic,
                                error: Error?) {
        Task { @MainActor in
            if let error {
                self.appendLog("RX error: \(error.localizedDescription)")
                return
            }

            guard let data = characteristic.value,
                  let text = String(data: data, encoding: .utf8) else {
                return
            }

            self.processIncoming(text)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral,
                                didWriteValueFor characteristic: CBCharacteristic,
                                error: Error?) {
        Task { @MainActor in
            if let error {
                self.appendLog("TX error: \(error.localizedDescription)")
                self.commandInFlight = nil
                self.sendNextIfPossible()
            }
        }
    }
}
