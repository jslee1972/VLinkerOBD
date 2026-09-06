package com.jslee1972.vlinkerobd.obd

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeObdTransport : ObdTransport {
    val writes = mutableListOf<String>()
    private val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    override val incoming: Flow<ByteArray> = flow

    override suspend fun write(bytes: ByteArray): Result<Unit> {
        writes += String(bytes)
        return Result.success(Unit)
    }

    suspend fun emit(text: String) {
        flow.emit(text.toByteArray())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ObdCommandQueueTest {

    @Test
    fun onlyOneCommandInFlightAtATime() = runTest {
        val transport = FakeObdTransport()
        val queue = ObdCommandQueue(transport, backgroundScope)

        val first = async { queue.execute(ObdCommand("010D", ObdCommandKind.OBD)) }
        runCurrent()
        assertEquals(1, transport.writes.size)

        val second = async { queue.execute(ObdCommand("010C", ObdCommandKind.OBD)) }
        runCurrent()
        assertEquals("second command must not write before the first completes", 1, transport.writes.size)

        transport.emit("41 0D 28\r>")
        val firstResult = first.await()
        assertTrue(firstResult is ObdCommandResult.Success)

        runCurrent()
        assertEquals(2, transport.writes.size)

        transport.emit("41 0C 00 00\r>")
        val secondResult = second.await()
        assertTrue(secondResult is ObdCommandResult.Success)
    }

    @Test
    fun doesNotCompleteUntilPromptSeenAcrossSplitChunks() = runTest {
        val transport = FakeObdTransport()
        val queue = ObdCommandQueue(transport, backgroundScope)

        val result = async { queue.execute(ObdCommand("010D", ObdCommandKind.OBD)) }
        runCurrent()

        transport.emit("41 0")
        runCurrent()
        assertTrue("must not complete before '>' prompt", result.isActive)

        transport.emit("D 28\r")
        runCurrent()
        assertTrue("must not complete before '>' prompt", result.isActive)

        transport.emit(">")
        val outcome = result.await()
        assertEquals(ObdCommandResult.Success("41 0D 28\r>"), outcome)
    }

    @Test
    fun retriesOnceThenReportsTimeout() = runTest {
        val transport = FakeObdTransport()
        val queue = ObdCommandQueue(transport, backgroundScope)

        val result = async {
            queue.execute(ObdCommand("010D", ObdCommandKind.OBD, timeoutMs = 2000, maxAttempts = 2))
        }

        advanceTimeBy(2_001)
        runCurrent()
        assertEquals("first timeout must trigger exactly one retry write", 2, transport.writes.size)

        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(ObdCommandResult.Timeout, result.await())
    }
}
