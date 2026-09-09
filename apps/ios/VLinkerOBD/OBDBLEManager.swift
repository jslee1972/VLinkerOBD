import CoreBluetooth
import Foundation

/// What `DashboardController` needs from the BLE layer — scanning, connecting, and a byte
/// transport once connected (`ObdTransport`). Mirrors Android's `BleObdClient` interface split
/// from `BleObdManager`'s CoreBluetooth-specific implementation.
protocol BleObdClient: ObdTransport {
    var devices: [ScannedBleDevice] { get }
    var onDevicesChange: (([ScannedBleDevice]) -> Void)? { get set }
    var connectionState: BleConnectionState { get }
    var onConnectionStateChange: ((BleConnectionState) -> Void)? { get set }
    var onLog: ((String) -> Void)? { get set }
    var connectedDeviceName: String? { get }
    var connectedDeviceAddress: String? { get }

    func startScan()
    func stopScan()
    func connect(to device: ScannedBleDevice)
    func disconnect()
}

/// CoreBluetooth-backed `BleObdClient`. Faithful port of Android's `BleObdManager.kt`:
/// - No fixed vendor UUID assumed; characteristic selection goes through
///   `GattCharacteristicSelector` once every service/characteristic has been discovered.
/// - A discovery watchdog (15s timeout, up to 2 attempts, each attempt a *fresh* connect rather
///   than just re-calling `discoverServices`) guards against GATT discovery silently never
///   completing — a real failure mode Android hit in the field, first fixed too aggressively (5s/
///   retry-in-place), then corrected to this more patient policy after it made first-launch
///   auto-reconnect worse. Same timing is used here since there's no reason to expect this
///   adapter class behaves any faster over CoreBluetooth than over Android's BLE stack.
/// - Distinguishes an unexpected drop while `.ready`/`.discoveringGatt` (`.disconnectedAfterError`,
///   which `DashboardController` uses to retry auto-reconnect) from a normal user-initiated
///   disconnect (`.disconnected`, which does not retry).
@MainActor
final class OBDBLEManager: NSObject, BleObdClient {
    private let targetNameHints = ["VLINKER", "V-LINK", "VLINK"]
    private static let discoveryTimeoutSeconds: Double = 15
    private static let maxDiscoveryAttempts = 2

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var discoveredPeripherals: [String: CBPeripheral] = [:]
    private var writeCharacteristic: CBCharacteristic?
    private var notifyCharacteristic: CBCharacteristic?
    private var writeType: CBCharacteristicWriteType = .withoutResponse

    private var discoveryAttempt = 0
    private var discoveryWatchdog: Task<Void, Never>?
    private var rxBuffer = ""

    /// Set right before *we* call `cancelPeripheralConnection` (user-initiated disconnect, or the
    /// discovery watchdog giving up) so `didDisconnectPeripheral` — which fires for every
    /// disconnect, ours or a genuine link-loss — knows the resulting state was already decided
    /// deliberately and shouldn't re-classify it. Without this, a self-requested cancel (which
    /// iOS often reports with a nil/success error) would be indistinguishable from a clean
    /// disconnect, and `disconnect()`-then-immediately-set-state would race the async callback —
    /// either way defeating the watchdog's `.disconnectedAfterError` retry signal.
    private var isDisconnectingIntentionally = false

    private(set) var devices: [ScannedBleDevice] = [] {
        didSet { onDevicesChange?(devices) }
    }
    var onDevicesChange: (([ScannedBleDevice]) -> Void)?

    private(set) var connectionState: BleConnectionState = .disconnected {
        didSet { onConnectionStateChange?(connectionState) }
    }
    var onConnectionStateChange: ((BleConnectionState) -> Void)?

    var onLog: ((String) -> Void)?
    var onIncoming: ((String) -> Void)?

    private(set) var connectedDeviceName: String?
    private(set) var connectedDeviceAddress: String?

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    /// Set on every `startScan()` call so a not-yet-authorized/powered-on adapter starts scanning
    /// as soon as it becomes available — iOS doesn't have Android's separate runtime BLE
    /// permission request; the system prompt fires the first time `CBCentralManager` is used, and
    /// `centralManagerDidUpdateState` is how the app finds out permission was actually granted.
    private var wantsScanning = false

    func startScan() {
        wantsScanning = true
        guard central.state == .poweredOn else {
            log("Bluetooth 尚未可用：\(central.state.rawValue)")
            return
        }
        central.stopScan()
        devices = []
        discoveredPeripherals = [:]
        connectionState = .scanning
        log("開始掃描 BLE...")
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
    }

    func stopScan() {
        wantsScanning = false
        central.stopScan()
        if connectionState == .scanning {
            connectionState = .disconnected
        }
    }

    func connect(to device: ScannedBleDevice) {
        guard let target = discoveredPeripherals[device.address] else { return }
        central.stopScan()
        discoveryAttempt = 0
        connectedDeviceName = device.name ?? "Unknown"
        connectedDeviceAddress = device.address
        beginGattConnection(target)
    }

    func disconnect() {
        performDisconnect(resultingState: .disconnected)
    }

    /// Shared by the public user-initiated `disconnect()` and the discovery watchdog's give-up
    /// path — the only difference between the two is which `BleConnectionState` they land on.
    private func performDisconnect(resultingState: BleConnectionState) {
        cancelDiscoveryWatchdog()
        writeCharacteristic = nil
        notifyCharacteristic = nil
        rxBuffer = ""
        isDisconnectingIntentionally = true
        if let peripheral {
            central.cancelPeripheralConnection(peripheral)
        }
        peripheral = nil
        connectedDeviceName = nil
        connectedDeviceAddress = nil
        if connectionState != .scanning {
            connectionState = resultingState
        }
    }

    func write(_ data: Data) {
        guard let peripheral, let ch = writeCharacteristic else { return }
        peripheral.writeValue(data, for: ch, type: writeType)
    }

    private func beginGattConnection(_ target: CBPeripheral) {
        peripheral = target
        target.delegate = self
        writeCharacteristic = nil
        notifyCharacteristic = nil
        connectionState = .connecting
        log("連線：\(target.name ?? "Unknown")")
        central.connect(target, options: nil)
    }

    private func startDiscoveryWatchdog() {
        cancelDiscoveryWatchdog()
        discoveryWatchdog = Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.discoveryTimeoutSeconds))
            guard !Task.isCancelled, self.connectionState == .discoveringGatt else { return }
            self.discoveryAttempt += 1
            if self.discoveryAttempt >= Self.maxDiscoveryAttempts {
                self.log("服務探索逾時，中斷連線")
                // .disconnectedAfterError (not plain .disconnected) so DashboardController treats
                // this as retryable and re-scans for the remembered device automatically — the
                // whole point of the watchdog is to self-heal without the user reopening the app.
                self.performDisconnect(resultingState: .disconnectedAfterError)
            } else if let target = self.peripheral {
                self.log("服務探索逾時，重試一次（重新建立連線）")
                self.isDisconnectingIntentionally = true
                self.central.cancelPeripheralConnection(target)
                self.beginGattConnection(target)
            }
        }
    }

    private func cancelDiscoveryWatchdog() {
        discoveryWatchdog?.cancel()
        discoveryWatchdog = nil
    }

    private func tryBindCharacteristics(on peripheral: CBPeripheral) {
        var candidates: [GattCharacteristicCandidate] = []
        var lookup: [String: CBCharacteristic] = [:]
        for service in peripheral.services ?? [] {
            for ch in service.characteristics ?? [] {
                let key = "\(service.uuid.uuidString)|\(ch.uuid.uuidString)"
                lookup[key] = ch
                candidates.append(GattCharacteristicCandidate(
                    serviceUuid: service.uuid.uuidString,
                    characteristicUuid: ch.uuid.uuidString,
                    canNotify: ch.properties.contains(.notify),
                    canIndicate: ch.properties.contains(.indicate),
                    canWrite: ch.properties.contains(.write),
                    canWriteWithoutResponse: ch.properties.contains(.writeWithoutResponse)
                ))
            }
        }

        guard let selection = GattCharacteristicSelector.select(candidates) else {
            connectionState = .error("找不到可用的 notify/write characteristic 組合")
            log("找不到相容的 BLE UART characteristic。")
            return
        }

        let notifyKey = "\(selection.notify.serviceUuid)|\(selection.notify.characteristicUuid)"
        let writeKey = "\(selection.write.serviceUuid)|\(selection.write.characteristicUuid)"
        guard let notifyCh = lookup[notifyKey], let writeCh = lookup[writeKey] else { return }

        notifyCharacteristic = notifyCh
        writeCharacteristic = writeCh
        writeType = selection.write.canWriteWithoutResponse ? .withoutResponse : .withResponse

        log("Bind characteristic: notify=\(notifyCh.uuid.uuidString) write=\(writeCh.uuid.uuidString)")
        peripheral.setNotifyValue(true, for: notifyCh)
    }

    private func log(_ line: String) {
        onLog?(line)
    }
}

extension OBDBLEManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            switch central.state {
            case .poweredOn:
                self.log("Bluetooth 已開啟")
                if self.wantsScanning, self.peripheral == nil { self.startScan() }
            case .poweredOff: self.connectionState = .error("Bluetooth 已關閉"); self.log("Bluetooth 已關閉")
            case .unauthorized: self.connectionState = .error("App 沒有 Bluetooth 權限"); self.log("App 沒有 Bluetooth 權限")
            case .unsupported: self.connectionState = .error("此裝置不支援 BLE"); self.log("此裝置不支援 BLE")
            default: self.log("Bluetooth state: \(central.state.rawValue)")
            }
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        Task { @MainActor in
            let address = peripheral.identifier.uuidString
            self.discoveredPeripherals[address] = peripheral
            let isPreferred = BleDeviceRanking.isPreferred(peripheral.name)
            let entry = ScannedBleDevice(address: address, name: peripheral.name, rssi: RSSI.intValue, isPreferred: isPreferred)

            if let idx = self.devices.firstIndex(where: { $0.address == address }) {
                self.devices[idx] = entry
            } else {
                self.devices.append(entry)
                self.log("發現：\(peripheral.name ?? "Unknown") RSSI=\(RSSI)")
            }
            self.devices = BleDeviceRanking.rank(self.devices)
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        Task { @MainActor in
            self.connectionState = .discoveringGatt
            self.log("BLE 已連線，探索 services...")
            peripheral.discoverServices(nil)
            self.startDiscoveryWatchdog()
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            self.connectionState = .error("連線失敗：\(error?.localizedDescription ?? "unknown")")
            self.log("連線失敗：\(error?.localizedDescription ?? "unknown")")
        }
    }

    nonisolated func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        Task { @MainActor in
            self.log("BLE 已斷線：\(error?.localizedDescription ?? "normal")")

            // If we just called cancelPeripheralConnection ourselves (disconnect(), or the
            // discovery watchdog's give-up/retry), the resulting state was already decided
            // deliberately (performDisconnect, or beginGattConnection's own .connecting) —
            // don't re-classify and don't clear fields a fresh beginGattConnection may have
            // already repopulated (this callback can arrive after a retry's new connect started).
            if self.isDisconnectingIntentionally {
                self.isDisconnectingIntentionally = false
                return
            }

            self.cancelDiscoveryWatchdog()
            let wasActive = self.connectionState == .ready || self.connectionState == .discoveringGatt || self.connectionState == .connecting
            self.writeCharacteristic = nil
            self.notifyCharacteristic = nil
            self.connectedDeviceName = nil
            self.connectedDeviceAddress = nil
            self.connectionState = wasActive ? .disconnectedAfterError : .disconnected
        }
    }
}

extension OBDBLEManager: CBPeripheralDelegate {
    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor in
            if let error {
                self.connectionState = .error("Service discovery error: \(error.localizedDescription)")
                self.log("Service discovery error: \(error.localizedDescription)")
                return
            }
            for service in peripheral.services ?? [] {
                self.log("Service: \(service.uuid.uuidString)")
                peripheral.discoverCharacteristics(nil, for: service)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        Task { @MainActor in
            if let error {
                self.log("Characteristic discovery error: \(error.localizedDescription)")
                return
            }
            for ch in service.characteristics ?? [] {
                self.log("  Char \(ch.uuid.uuidString) properties=\(ch.properties.rawValue)")
            }
            let allDone = (peripheral.services ?? []).allSatisfy { $0.characteristics != nil }
            if allDone, self.writeCharacteristic == nil {
                self.cancelDiscoveryWatchdog()
                self.tryBindCharacteristics(on: peripheral)
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in
            if let error {
                self.connectionState = .error("Notify 啟用失敗：\(error.localizedDescription)")
                self.log("Notify 啟用失敗：\(error.localizedDescription)")
                return
            }
            if characteristic.isNotifying {
                self.log("Notify 已啟用")
                self.connectionState = .ready
            }
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        Task { @MainActor in
            if let error {
                self.log("RX error: \(error.localizedDescription)")
                return
            }
            guard let data = characteristic.value, let text = String(data: data, encoding: .utf8) else { return }
            self.onIncoming?(text)
        }
    }

    nonisolated func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error {
            Task { @MainActor in self.log("TX error: \(error.localizedDescription)") }
        }
    }
}
