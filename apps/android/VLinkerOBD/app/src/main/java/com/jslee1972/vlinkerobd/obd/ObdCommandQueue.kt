package com.jslee1972.vlinkerobd.obd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Serializes ELM/STN command execution: only one command is ever in flight, each attempt waits
 * for the `>` prompt (or [ObdCommand.timeoutMs]), and a timed-out attempt is retried once before
 * the command is reported as [ObdCommandResult.Timeout]. A manual command is queued the same as
 * any other — callers (the ViewModel) decide whether to pause automatic polling first.
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
    private var currentAttempt: Deferred<*>? = null

    init {
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
        currentAttempt?.cancel()
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
        val bytes = (command.text.trim().uppercase() + "\r").toByteArray()
        transport.write(bytes)

        val attempt = scope.async {
            withTimeout(command.timeoutMs) {
                val buffer = StringBuilder()
                transport.incoming.first { chunk ->
                    buffer.append(String(chunk))
                    buffer.contains(">")
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
