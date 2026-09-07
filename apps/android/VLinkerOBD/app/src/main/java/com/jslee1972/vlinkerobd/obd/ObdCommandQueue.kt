package com.jslee1972.vlinkerobd.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Serializes ELM/STN command execution: only one command is ever in flight, each attempt waits
 * for the `>` prompt (or [ObdCommand.timeoutMs]), and a timed-out attempt is retried once before
 * the command is reported as [ObdCommandResult.Timeout]. A manual command is queued the same as
 * any other — callers (the ViewModel) decide whether to pause automatic polling first.
 *
 * Incoming bytes are forwarded into [incomingChannel] by one persistent collector (started once,
 * outliving any single attempt) rather than each attempt subscribing to [ObdTransport.incoming]
 * directly. That persistence matters on slow, non-CAN links (ISO9141/KWP-era vehicles observed in
 * the field): a response can arrive just after its attempt gave up to a timeout/retry, and a
 * per-attempt `Flow.first{}` subscription has no way to catch bytes that land in that gap — they
 * either vanish or, worse, get picked up by the *next* attempt's fresh subscription and get
 * concatenated onto an unrelated response (surfaced as "解析失敗"/garbled multi-response logs).
 * Routing everything through one durable channel and explicitly draining it with
 * [drainStaleIncoming] before every write (first attempt or retry) guarantees a command always
 * starts reading from a clean slate.
 */
class ObdCommandQueue(
    private val transport: ObdTransport,
    private val scope: CoroutineScope,
) {
    private data class PendingCommand(
        val command: ObdCommand,
        val result: CompletableDeferred<ObdCommandResult>,
    )

    private sealed interface AttemptOutcome {
        data class Prompted(val raw: String) : AttemptOutcome
        data object TimedOut : AttemptOutcome
        data object Cancelled : AttemptOutcome
    }

    private val channel = Channel<PendingCommand>(Channel.UNLIMITED)
    private val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var currentAttempt: Deferred<*>? = null

    init {
        scope.launch {
            transport.incoming.collect { chunk -> incomingChannel.send(chunk) }
        }
        scope.launch {
            for (pending in channel) {
                pending.result.complete(runWithRetry(pending.command))
            }
        }
    }

    suspend fun execute(command: ObdCommand): ObdCommandResult {
        val deferred = CompletableDeferred<ObdCommandResult>()
        channel.send(PendingCommand(command, deferred))
        return deferred.await()
    }

    /** Cancels the attempt currently in flight (if any); the command resolves as [ObdCommandResult.Cancelled]. */
    fun cancelCurrent() {
        currentAttempt?.cancel()
    }

    fun close() {
        channel.close()
        incomingChannel.close()
        currentAttempt?.cancel()
    }

    /** Discards whatever is already sitting in [incomingChannel] — the tail of a previous attempt's
     * response that arrived too late to be read by it. Non-suspending, so it never delays a fresh
     * write and never risks eating bytes that belong to the command about to be sent. */
    private fun drainStaleIncoming() {
        while (incomingChannel.tryReceive().isSuccess) {
            // Deliberately discarded — see class doc for why this must happen before every write.
        }
    }

    private suspend fun runWithRetry(command: ObdCommand): ObdCommandResult {
        repeat(command.maxAttempts) { attemptIndex ->
            when (val outcome = runSingleAttempt(command)) {
                is AttemptOutcome.Prompted -> return ObdCommandResult.Success(outcome.raw)
                AttemptOutcome.Cancelled -> return ObdCommandResult.Cancelled
                AttemptOutcome.TimedOut -> if (attemptIndex == command.maxAttempts - 1) {
                    return ObdCommandResult.Timeout
                }
            }
        }
        return ObdCommandResult.Timeout
    }

    private suspend fun runSingleAttempt(command: ObdCommand): AttemptOutcome {
        drainStaleIncoming()
        val bytes = (command.text.trim().uppercase() + "\r").toByteArray()
        transport.write(bytes)

        val attempt = scope.async {
            withTimeout(command.timeoutMs) {
                val buffer = StringBuilder()
                while (!buffer.contains(">")) {
                    buffer.append(String(incomingChannel.receive()))
                }
                buffer.toString()
            }
        }
        currentAttempt = attempt

        return try {
            AttemptOutcome.Prompted(attempt.await())
        } catch (timeout: TimeoutCancellationException) {
            AttemptOutcome.TimedOut
        } catch (cancellation: CancellationException) {
            AttemptOutcome.Cancelled
        } finally {
            currentAttempt = null
        }
    }
}
