package com.jslee1972.vlinkerobd.ui

import com.jslee1972.vlinkerobd.ble.BleObdClient
import com.jslee1972.vlinkerobd.ble.ConnectionState
import com.jslee1972.vlinkerobd.ble.ScannedBleDevice
import com.jslee1972.vlinkerobd.obd.PidDefinition
import com.jslee1972.vlinkerobd.obd.VehicleProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeBleObdClient : BleObdClient {
    val writes = mutableListOf<String>()
    private val incomingFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming: Flow<ByteArray> = incomingFlow

    private val devicesFlow = MutableStateFlow<List<ScannedBleDevice>>(emptyList())
    override val devices: StateFlow<List<ScannedBleDevice>> = devicesFlow

    private val connectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = connectionStateFlow

    private val logsFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val logs: SharedFlow<String> = logsFlow

    override suspend fun write(bytes: ByteArray): Result<Unit> {
        writes += String(bytes)
        return Result.success(Unit)
    }

    override fun startScan() = Unit
    override fun stopScan() = Unit
    override fun connect(device: ScannedBleDevice) = Unit
    override fun disconnect() {
        connectionStateFlow.value = ConnectionState.DISCONNECTED
    }

    suspend fun respond(text: String) = incomingFlow.emit(text.toByteArray())

    fun setReady() {
        connectionStateFlow.value = ConnectionState.READY
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val universalProfile = VehicleProfile(
        profileId = "universal-obd2",
        pids = listOf(
            PidDefinition(request = "010D", field = "speedKPH", unit = "km/h", formula = "A"),
            PidDefinition(request = "010C", field = "rpm", unit = "rpm", formula = "((A*256)+B)/4"),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Lets the pending write happen, then feeds back its response and drains the cascade. */
    private suspend fun FakeBleObdClient.respondToNextWrite(response: String) {
        dispatcher.scheduler.runCurrent()
        respond(response)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun initializationSendsSevenCommandsInStrictOrderBeforePolling() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.setReady()
        runCurrent()

        val expectedInit = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "0100")
        for (command in expectedInit) {
            client.respondToNextWrite(">")
        }

        // exactly the seven init commands were sent, in order, before any PID poll
        assertEquals(expectedInit.map { "$it\r" }, client.writes.take(7))
    }

    @Test
    fun updatesSpeedFromPollResponse() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.setReady()
        runCurrent()
        repeat(7) { client.respondToNextWrite(">") } // init sequence; last one cascades into the rpm poll

        client.respondToNextWrite("41 0C 00 00\r>") // rpm poll -> 0, cascades into the speed poll
        client.respondToNextWrite("41 0D 28\r>") // speed poll -> 40 km/h

        assertEquals(40, viewModel.uiState.value.vehicleData.speedKph)
        assertEquals(0, viewModel.uiState.value.vehicleData.rpm)
        assertEquals("41 0D 28\r>", viewModel.uiState.value.rawResponse)
    }

    @Test
    fun initTimeoutDisconnects() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.setReady()
        runCurrent()

        // ATZ never gets a '>' -> two attempts of 2s each time out, then the ViewModel disconnects.
        dispatcher.scheduler.advanceTimeBy(2_001)
        runCurrent()
        dispatcher.scheduler.advanceTimeBy(2_001)
        runCurrent()

        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
    }

    @Test
    fun manualCommandPausesThenResumesAutomaticPolling() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.setReady()
        runCurrent()
        repeat(7) { client.respondToNextWrite(">") } // last one cascades into an in-flight rpm poll

        viewModel.sendManualCommand(" ati ")
        runCurrent() // pauses the fast loop; ATI is queued behind the in-flight rpm poll

        // completing the in-flight rpm poll lets the queue move on to the queued manual command
        // instead of the (now paused) speed poll
        client.respondToNextWrite("41 0C 00 00\r>")
        assertEquals("ATI\r", client.writes.last())

        client.respondToNextWrite("ELM327 v1.5\r>")
        assertEquals("ELM327 v1.5\r>", viewModel.uiState.value.rawResponse)

        // polling resumes once the 200ms cadence delay elapses
        dispatcher.scheduler.advanceTimeBy(201)
        runCurrent()
        assertEquals("010C\r", client.writes.last())
    }

    @Test
    fun blankManualCommandIsIgnored() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)
        viewModel.sendManualCommand("   ")
        runCurrent()
        assertTrue(client.writes.isEmpty())
    }

    @Test
    fun readTroubleCodesDecodesResponseIntoState() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.setReady()
        runCurrent()
        repeat(7) { client.respondToNextWrite(">") } // init; leaves an in-flight rpm poll

        viewModel.readTroubleCodes()
        runCurrent() // pauses the fast loop; "03" is queued behind the in-flight rpm poll

        client.respondToNextWrite("41 0C 00 00\r>") // completes the in-flight rpm poll
        assertEquals("03\r", client.writes.last())

        client.respondToNextWrite("43 01 33\r>")
        assertEquals(listOf("P0133"), viewModel.uiState.value.troubleCodes)
        assertEquals(false, viewModel.uiState.value.isReadingTroubleCodes)
    }

    @Test
    fun readTroubleCodesReportsNoCodesAsEmptyList() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.setReady()
        runCurrent()
        repeat(7) { client.respondToNextWrite(">") }

        viewModel.readTroubleCodes()
        runCurrent()
        client.respondToNextWrite("41 0C 00 00\r>")
        client.respondToNextWrite("43 00\r>")

        assertEquals(emptyList<String>(), viewModel.uiState.value.troubleCodes)
    }
}
