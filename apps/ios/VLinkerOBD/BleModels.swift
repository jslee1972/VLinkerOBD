import Foundation

struct ScannedBleDevice: Equatable, Identifiable {
    var address: String
    var name: String?
    var rssi: Int
    var isPreferred: Bool

    var id: String { address }
}

enum BleConnectionState: Equatable {
    case disconnected
    case scanning
    case connecting
    case discoveringGatt
    case initializing
    case ready
    case disconnectedAfterError
    case error(String)
}

/// Picks a compatible notify/indicate + write/write-without-response characteristic pair from a
/// GATT discovery without assuming any fixed vendor UUID (vLinker firmware varies). Faithful port
/// of Android's `GattCharacteristicSelector.kt`: Notify preferred over Indicate,
/// Write-Without-Response over Write, same-service/same-characteristic pairs preferred, remaining
/// ties broken on UUID string ordering for determinism.
struct GattCharacteristicCandidate: Equatable {
    var serviceUuid: String
    var characteristicUuid: String
    var canNotify: Bool
    var canIndicate: Bool
    var canWrite: Bool
    var canWriteWithoutResponse: Bool
}

struct GattSelection: Equatable {
    var notify: GattCharacteristicCandidate
    var write: GattCharacteristicCandidate
}

enum GattCharacteristicSelector {
    static func select(_ candidates: [GattCharacteristicCandidate]) -> GattSelection? {
        let receivers = candidates
            .filter { $0.canNotify || $0.canIndicate }
            .sorted {
                if $0.canNotify != $1.canNotify { return $0.canNotify && !$1.canNotify }
                if $0.serviceUuid != $1.serviceUuid { return $0.serviceUuid < $1.serviceUuid }
                return $0.characteristicUuid < $1.characteristicUuid
            }
        let senders = candidates
            .filter { $0.canWrite || $0.canWriteWithoutResponse }
            .sorted {
                if $0.canWriteWithoutResponse != $1.canWriteWithoutResponse { return $0.canWriteWithoutResponse && !$1.canWriteWithoutResponse }
                if $0.serviceUuid != $1.serviceUuid { return $0.serviceUuid < $1.serviceUuid }
                return $0.characteristicUuid < $1.characteristicUuid
            }

        guard !receivers.isEmpty, !senders.isEmpty else { return nil }

        var best: (GattCharacteristicCandidate, GattCharacteristicCandidate)?
        for notify in receivers {
            for write in senders {
                guard let current = best else { best = (notify, write); continue }
                if isBetter((notify, write), than: current) {
                    best = (notify, write)
                }
            }
        }
        guard let result = best else { return nil }
        return GattSelection(notify: result.0, write: result.1)
    }

    /// True if `candidate` should be preferred over `incumbent`, using the same priority chain as
    /// Android's descending comparator: same characteristic > same service > notify-not-indicate >
    /// write-without-response-not-write > UUID string ascending.
    private static func isBetter(
        _ candidate: (GattCharacteristicCandidate, GattCharacteristicCandidate),
        than incumbent: (GattCharacteristicCandidate, GattCharacteristicCandidate)
    ) -> Bool {
        let candidateSameChar = candidate.0.characteristicUuid == candidate.1.characteristicUuid
        let incumbentSameChar = incumbent.0.characteristicUuid == incumbent.1.characteristicUuid
        if candidateSameChar != incumbentSameChar { return candidateSameChar }

        let candidateSameService = candidate.0.serviceUuid == candidate.1.serviceUuid
        let incumbentSameService = incumbent.0.serviceUuid == incumbent.1.serviceUuid
        if candidateSameService != incumbentSameService { return candidateSameService }

        if candidate.0.canNotify != incumbent.0.canNotify { return candidate.0.canNotify }
        if candidate.1.canWriteWithoutResponse != incumbent.1.canWriteWithoutResponse { return candidate.1.canWriteWithoutResponse }

        if candidate.0.characteristicUuid != incumbent.0.characteristicUuid {
            return candidate.0.characteristicUuid < incumbent.0.characteristicUuid
        }
        return candidate.1.characteristicUuid < incumbent.1.characteristicUuid
    }
}

/// Ranks scanned BLE devices so likely vLinker adapters float to the top of the list without
/// being auto-connected (the user still picks the device manually). Faithful port of Android's
/// `BleDeviceRanking.kt`.
enum BleDeviceRanking {
    private static let preferredNameRegex = try! NSRegularExpression(pattern: "V[\\s-]?LINK(?:ER)?", options: .caseInsensitive)

    static func isPreferred(_ name: String?) -> Bool {
        guard let name, !name.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        return preferredNameRegex.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)) != nil
    }

    /// Preferred devices first, then by RSSI descending, with address as a stable tiebreaker.
    static func rank(_ devices: [ScannedBleDevice]) -> [ScannedBleDevice] {
        devices.sorted {
            if $0.isPreferred != $1.isPreferred { return $0.isPreferred && !$1.isPreferred }
            if $0.rssi != $1.rssi { return $0.rssi > $1.rssi }
            return $0.address < $1.address
        }
    }
}
