import Foundation

/// A byte-stream transport an `ObdCommandQueue` can write to and receive decoded text chunks
/// from. `OBDBLEManager` implements this over CoreBluetooth.
protocol ObdTransport: AnyObject {
    /// Set once by the queue; called with each decoded text chunk as it arrives.
    var onIncoming: ((String) -> Void)? { get set }
    func write(_ data: Data)
}

struct ObdCommand {
    var text: String
    var timeoutMs: Int = 2000
    var maxAttempts: Int = 2
}

enum ObdCommandResult: Equatable {
    case success(String)
    case timeout
}

/// Serializes ELM/STN command execution: only one command is ever in flight, each attempt waits
/// for the `>` prompt (or `timeoutMs`), and a timed-out attempt is retried once before the
/// command is reported as `.timeout`. Mirrors the guarantees of Android's `ObdCommandQueue.kt`
/// (single Channel-based FIFO on a main-immediate dispatcher) using a `@MainActor`-isolated class
/// instead — everything here runs cooperatively on the main actor, so there's no true data race
/// to guard against, only ordering to preserve.
///
/// Incoming bytes are appended to a durable buffer (`incomingBuffer`) rather than read directly
/// off the transport by each attempt, and that buffer is explicitly cleared (`drainStaleIncoming`)
/// before every write — the same fix Android needed for slow, non-CAN links where a response can
/// arrive just after its attempt already timed out: without draining, that stale response would
/// get concatenated onto the next command's reply.
@MainActor
final class ObdCommandQueue {
    private let transport: ObdTransport
    private var incomingBuffer = ""
    /// Fires (once) when new data arrives while an attempt is waiting — see `waitForIncoming`.
    private var wakeUp: (() -> Void)?

    private var commandQueue: [(ObdCommand, CheckedContinuation<ObdCommandResult, Never>)] = []
    private var isProcessing = false

    init(transport: ObdTransport) {
        self.transport = transport
        transport.onIncoming = { [weak self] text in
            Task { @MainActor in self?.receiveIncoming(text) }
        }
    }

    func execute(_ command: ObdCommand) async -> ObdCommandResult {
        await withCheckedContinuation { continuation in
            commandQueue.append((command, continuation))
            processNextIfNeeded()
        }
    }

    private func receiveIncoming(_ text: String) {
        incomingBuffer += text
        let wake = wakeUp
        wakeUp = nil
        wake?()
    }

    private func drainStaleIncoming() {
        incomingBuffer = ""
    }

    private func processNextIfNeeded() {
        guard !isProcessing, !commandQueue.isEmpty else { return }
        isProcessing = true
        let (command, continuation) = commandQueue.removeFirst()
        Task { @MainActor in
            let result = await runWithRetry(command)
            continuation.resume(returning: result)
            isProcessing = false
            processNextIfNeeded()
        }
    }

    private func runWithRetry(_ command: ObdCommand) async -> ObdCommandResult {
        for attempt in 0..<command.maxAttempts {
            switch await runSingleAttempt(command) {
            case .prompted(let raw):
                return .success(raw)
            case .timedOut:
                if attempt == command.maxAttempts - 1 { return .timeout }
            }
        }
        return .timeout
    }

    private enum AttemptOutcome {
        case prompted(String)
        case timedOut
    }

    private func runSingleAttempt(_ command: ObdCommand) async -> AttemptOutcome {
        drainStaleIncoming()
        let text = command.text.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() + "\r"
        transport.write(Data(text.utf8))

        let deadline = ContinuousClock.now.advanced(by: .milliseconds(command.timeoutMs))
        while !incomingBuffer.contains(">") {
            let remaining = deadline - ContinuousClock.now
            guard remaining > .zero else { return .timedOut }
            guard await waitForIncoming(timeout: remaining) else { return .timedOut }
        }
        let raw = incomingBuffer
        incomingBuffer = ""
        return .prompted(raw)
    }

    /// Suspends until either new data arrives (returns true) or `timeout` elapses (returns
    /// false). Both outcomes resume the same continuation, guarded so only the first one wins —
    /// the loser (usually the timeout firing after data already arrived) simply no-ops instead of
    /// leaving anything suspended, unlike a cancelled `TaskGroup` child task would.
    private func waitForIncoming(timeout: Duration) async -> Bool {
        await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            var resumed = false
            wakeUp = {
                guard !resumed else { return }
                resumed = true
                continuation.resume(returning: true)
            }
            Task { @MainActor in
                try? await Task.sleep(for: timeout)
                guard !resumed else { return }
                resumed = true
                self.wakeUp = nil
                continuation.resume(returning: false)
            }
        }
    }
}
