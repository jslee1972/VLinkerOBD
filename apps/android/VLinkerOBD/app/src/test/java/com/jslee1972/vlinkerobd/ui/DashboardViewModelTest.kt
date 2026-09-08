package com.jslee1972.vlinkerobd.ui

import com.jslee1972.vlinkerobd.ble.BleObdClient
import com.jslee1972.vlinkerobd.ble.ConnectionState
import com.jslee1972.vlinkerobd.ble.DeviceMemory
import com.jslee1972.vlinkerobd.ble.ScannedBleDevice
import com.jslee1972.vlinkerobd.gps.GpsSpeedSource
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
    val connectCalls = mutableListOf<ScannedBleDevice>()

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
    override fun connect(device: ScannedBleDevice) {
        connectCalls += device
    }
    override fun disconnect() {
        connectionStateFlow.value = ConnectionState.DISCONNECTED
    }

    suspend fun respond(text: String) = incomingFlow.emit(text.toByteArray())

    fun setReady() {
        connectionStateFlow.value = ConnectionState.READY
    }

    fun emitDevices(devices: List<ScannedBleDevice>) {
        devicesFlow.value = devices
    }
}

private class FakeDeviceMemory(private var address: String? = null) : DeviceMemory {
    val rememberedAddresses = mutableListOf<String>()
    override fun lastDeviceAddress(): String? = address
    override fun rememberDevice(address: String) {
        rememberedAddresses += address
        this.address = address
    }
}

private class FakeCustomSectionStore(initial: Set<String> = emptySet()) : CustomSectionStore {
    private var fields = initial
    val savedCalls = mutableListOf<Set<String>>()

    override fun selectedFields(): Set<String> = fields

    override fun setSelectedFields(fields: Set<String>) {
        this.fields = fields
        savedCalls += fields
    }
}

private class FakeGpsSpeedSource : GpsSpeedSource {
    private val _speedKph = MutableStateFlow<Float?>(null)
    override val speedKph: StateFlow<Float?> = _speedKph
    var started = false
        private set

    override fun start() {
        started = true
    }

    override fun stop() {
        started = false
        _speedKph.value = null
    }

    fun emit(speed: Float?) {
        _speedKph.value = speed
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

    /**
     * Drives past ready -> 8 init commands -> the VIN auto-detection request (answered with
     * NO DATA, as most test doubles don't care about it) so the caller lands right at the point
     * where the fast poll's first rpm command is in flight. The automatic post-init DTC read is
     * also answered with "no codes" so it doesn't interfere with unrelated assertions.
     */
    private suspend fun FakeBleObdClient.completeInitAndSkipVin() {
        setReady()
        dispatcher.scheduler.runCurrent()
        repeat(8) { respondToNextWrite(">") }
        respondToNextWrite("NO DATA\r>") // VIN request
        respondToNextWrite("43 00\r>") // automatic DTC read
    }

    @Test
    fun initializationSendsCommandsInStrictOrderBeforePolling() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.setReady()
        runCurrent()

        val expectedInit = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATCFC1", "0100")
        for (command in expectedInit) {
            client.respondToNextWrite(">")
        }

        // exactly the init commands were sent, in order, before any PID poll
        assertEquals(expectedInit.map { "$it\r" }, client.writes.take(expectedInit.size))
    }

    @Test
    fun detectsBrandFromVinAndAutoSelectsMatchingProfile() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val hondaProfile = VehicleProfile(profileId = "honda", brand = "Honda")
        val viewModel = DashboardViewModel(
            client,
            universalProfile,
            mapOf("Honda" to hondaProfile),
            externalScope = backgroundScope,
        )

        client.setReady()
        runCurrent()
        repeat(8) { client.respondToNextWrite(">") } // cascades into the "0902" VIN request

        assertEquals("0902\r", client.writes.last())
        client.respondToNextWrite("49 02 01 31 48 47 43 4D 38 32 36 33 33 41 31 32 33 34 35 36\r>")

        assertEquals("1HGCM82633A123456", viewModel.uiState.value.detectedVin)
        assertEquals("Honda", viewModel.uiState.value.detectedBrand)
        assertEquals("Honda", viewModel.uiState.value.selectedBrand)
    }

    @Test
    fun unrecognizedVinLeavesBrandSelectionUnchanged() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.setReady()
        runCurrent()
        repeat(8) { client.respondToNextWrite(">") }
        client.respondToNextWrite("NO DATA\r>") // vehicle doesn't support Mode 09

        assertEquals(null, viewModel.uiState.value.detectedBrand)
        assertEquals(UNIVERSAL_BRAND, viewModel.uiState.value.selectedBrand)
    }

    @Test
    fun automaticallyReadsTroubleCodesRightAfterVinDetection() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.setReady()
        runCurrent()
        repeat(8) { client.respondToNextWrite(">") }
        client.respondToNextWrite("NO DATA\r>") // VIN request answered; cascades into the DTC read

        assertEquals("03\r", client.writes.last())
        client.respondToNextWrite("43 01 33\r>")

        assertEquals(listOf("P0133"), viewModel.uiState.value.troubleCodes)
    }

    @Test
    fun exposesGpsSpeedAlongsideObdSpeed() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val fakeGps = FakeGpsSpeedSource()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope, gpsSpeedSource = fakeGps)
        runCurrent()

        assertEquals(null, viewModel.uiState.value.gpsSpeedKph)
        fakeGps.emit(62.3f)
        runCurrent()
        assertEquals(62.3f, viewModel.uiState.value.gpsSpeedKph)

        viewModel.startGpsTracking()
        assertTrue(fakeGps.started)
        viewModel.stopGpsTracking()
        assertEquals(false, fakeGps.started)
    }

    @Test
    fun loadsAndPersistsCustomSectionFieldSelection() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val store = FakeCustomSectionStore(initial = setOf("coolantTempC"))
        val hondaProfile = VehicleProfile(
            profileId = "honda",
            brand = "Honda",
            pids = listOf(PidDefinition(request = "222662", field = "batteryVoltage", unit = "V", formula = "A")),
        )
        val viewModel = DashboardViewModel(
            client,
            universalProfile,
            mapOf("Honda" to hondaProfile),
            externalScope = backgroundScope,
            customSectionStore = store,
        )
        runCurrent()

        // Initial selection loaded from the store.
        assertEquals(setOf("coolantTempC"), viewModel.uiState.value.selectedCustomFields)
        // Known fields include universal + trip computer + every loaded brand's own fields.
        assertTrue("speedKPH" in viewModel.uiState.value.allKnownFields)
        assertTrue("batteryVoltage" in viewModel.uiState.value.allKnownFields)
        assertTrue("instantFuelConsumption" in viewModel.uiState.value.allKnownFields)

        viewModel.toggleCustomField("rpm")
        assertEquals(setOf("coolantTempC", "rpm"), viewModel.uiState.value.selectedCustomFields)
        assertEquals(setOf("coolantTempC", "rpm"), store.savedCalls.last())

        viewModel.toggleCustomField("coolantTempC")
        assertEquals(setOf("rpm"), viewModel.uiState.value.selectedCustomFields)
        assertEquals(setOf("rpm"), store.savedCalls.last())
    }

    @Test
    fun updatesSpeedFromPollResponse() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.completeInitAndSkipVin() // leaves an in-flight rpm poll

        client.respondToNextWrite("41 0C 00 00\r>") // rpm poll -> 0, cascades into the speed poll
        client.respondToNextWrite("41 0D 28\r>") // speed poll -> 40 km/h

        assertEquals(40, viewModel.uiState.value.vehicleData.speedKph)
        assertEquals(0, viewModel.uiState.value.vehicleData.rpm)
        assertEquals("41 0D 28\r>", viewModel.uiState.value.rawResponse)
        assertEquals(listOf(0f), viewModel.uiState.value.rpmHistory)
        assertEquals(listOf(40f), viewModel.uiState.value.speedHistory)
    }

    @Test
    fun estimatesInstantFuelConsumptionFromMafAndSpeed() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val profileWithMaf = universalProfile.copy(
            pids = universalProfile.pids +
                PidDefinition(request = "0110", field = "mafGramsPerSec", unit = "g/s", formula = "((A*256)+B)/100"),
        )
        val viewModel = DashboardViewModel(client, profileWithMaf, externalScope = backgroundScope)

        client.completeInitAndSkipVin() // leaves an in-flight rpm poll

        client.respondToNextWrite("41 0C 00 00\r>") // rpm -> 0, cascades into the speed poll
        client.respondToNextWrite("41 0D 3C\r>") // speed -> 60 km/h, cascades into the MAF poll
        assertEquals("0110\r", client.writes.last())

        // MAF = 0x2710/100 = 100.0 g/s. fuelLPerHour = 100*3600/(14.7*750) = 32.653...
        // instant km/L = speedKph / fuelLPerHour = 60/32.653 = 1.8375 -> 1.8
        client.respondToNextWrite("41 10 27 10\r>")
        assertEquals("1.8 公里/公升", viewModel.uiState.value.standardReadings["instantFuelConsumption"])
    }

    @Test
    fun showsInstantFuelConsumptionInLitersPerHourWhileStationary() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val profileWithMaf = universalProfile.copy(
            pids = universalProfile.pids +
                PidDefinition(request = "0110", field = "mafGramsPerSec", unit = "g/s", formula = "((A*256)+B)/100"),
        )
        val viewModel = DashboardViewModel(client, profileWithMaf, externalScope = backgroundScope)

        client.completeInitAndSkipVin() // leaves an in-flight rpm poll

        client.respondToNextWrite("41 0C 03 E8\r>") // rpm -> 250 (idling)
        client.respondToNextWrite("41 0D 00\r>") // speed -> 0 km/h, cascades into the MAF poll
        // km/L is meaningless at a standstill (division by ~0) — show L/h instead, matching how
        // idle-specific gauges on reference dashboards label this same quantity.
        client.respondToNextWrite("41 10 27 10\r>") // MAF -> 100.0 g/s -> 32.65 L/h
        assertEquals("32.65 公升/小時", viewModel.uiState.value.standardReadings["instantFuelConsumption"])
    }

    @Test
    fun accumulatesSpeedAndRpmHistoryAcrossPolls() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.completeInitAndSkipVin() // leaves an in-flight rpm poll

        client.respondToNextWrite("41 0C 03 E8\r>") // rpm -> 250
        client.respondToNextWrite("41 0D 32\r>") // speed -> 50 km/h
        dispatcher.scheduler.advanceTimeBy(201)
        runCurrent()

        client.respondToNextWrite("41 0C 07 D0\r>") // rpm -> 500
        client.respondToNextWrite("41 0D 3C\r>") // speed -> 60 km/h

        assertEquals(listOf(250f, 500f), viewModel.uiState.value.rpmHistory)
        assertEquals(listOf(50f, 60f), viewModel.uiState.value.speedHistory)
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

        client.completeInitAndSkipVin() // leaves an in-flight rpm poll

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
    fun ecuSupportTestRunsAllProbesAndRecordsSuccess() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.completeInitAndSkipVin() // leaves an in-flight rpm poll

        viewModel.testEcuSupport()
        runCurrent() // pauses the fast loop; "0100" is queued behind the in-flight rpm poll

        client.respondToNextWrite("41 0C 00 00\r>") // completes the in-flight rpm poll
        assertEquals("0100\r", client.writes.last())

        client.respondToNextWrite("41 00 BE 1F A8 13\r>")
        assertEquals("0902\r", client.writes.last())

        client.respondToNextWrite("49 02 01 31 48 47 43 4D 38 32 36 33 33 41 31 32 33 34 35 36\r>")
        assertEquals("22F190\r", client.writes.last())

        client.respondToNextWrite("62 F1 90 31 48 47 43 4D 38 32 36 33 33 41 31 32 33 34 35 36\r>")

        val results = viewModel.uiState.value.ecuTestResults
        assertEquals(3, results.size)
        assertEquals(listOf("0100", "0902", "22F190"), results.map { it.command })
        assertTrue(results.all { it.status == EcuTestStatus.SUPPORTED })
        assertEquals(false, viewModel.uiState.value.isTestingEcu)
    }

    @Test
    fun ecuSupportTestRetriesUdsVinAfterExtendedSessionWhenRefused() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.completeInitAndSkipVin() // leaves an in-flight rpm poll

        viewModel.testEcuSupport()
        runCurrent()

        client.respondToNextWrite("41 0C 00 00\r>") // completes the in-flight rpm poll
        client.respondToNextWrite("41 00 BE 1F A8 13\r>") // 0100
        client.respondToNextWrite("NO DATA\r>") // 0902 unsupported
        assertEquals("22F190\r", client.writes.last())

        client.respondToNextWrite("7F 22 11\r>") // plain 22F190 refused -> service not supported
        assertEquals("1003\r", client.writes.last())

        client.respondToNextWrite("50 03\r>") // extended diagnostic session accepted
        assertEquals("22F190\r", client.writes.last())

        client.respondToNextWrite("62 F1 90 31 48 47 43 4D 38 32 36 33 33 41 31 32 33 34 35 36\r>")
        assertEquals("1001\r", client.writes.last()) // restores the default session

        client.respondToNextWrite("50 01\r>")

        val results = viewModel.uiState.value.ecuTestResults
        assertEquals(4, results.size)
        assertEquals(EcuTestStatus.NEGATIVE, results[2].status)
        assertEquals(EcuTestStatus.SUPPORTED, results[3].status)
        assertEquals(false, viewModel.uiState.value.isTestingEcu)
    }

    @Test
    fun readTroubleCodesDecodesResponseIntoState() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val viewModel = DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.completeInitAndSkipVin() // leaves an in-flight rpm poll

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

        client.completeInitAndSkipVin()

        viewModel.readTroubleCodes()
        runCurrent()
        client.respondToNextWrite("41 0C 00 00\r>")
        client.respondToNextWrite("43 00\r>")

        assertEquals(emptyList<String>(), viewModel.uiState.value.troubleCodes)
    }

    @Test
    fun pollsStandardExtraPidsOnSlowTicker() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val profileWithCoolant = universalProfile.copy(
            pids = universalProfile.pids + PidDefinition(request = "0105", field = "coolantTempC", unit = "degC", formula = "A-40"),
        )
        val viewModel = DashboardViewModel(client, profileWithCoolant, externalScope = backgroundScope)

        client.completeInitAndSkipVin() // leaves an in-flight rpm poll; the coolant poll is queued behind it

        client.respondToNextWrite("41 0C 00 00\r>") // completes rpm poll, cascades into the queued coolant poll
        assertEquals("0105\r", client.writes.last())

        client.respondToNextWrite("41 05 5A\r>") // coolant temp -> 0x5A(90) - 40 = 50 degC
        assertEquals("50.0 degC", viewModel.uiState.value.standardReadings["coolantTempC"])
    }

    @Test
    fun stopsPollingStandardExtraPidPermanentlyAfterRepeatedFailures() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val profileWithCoolant = universalProfile.copy(
            pids = universalProfile.pids + PidDefinition(request = "0105", field = "coolantTempC", unit = "degC", formula = "A-40"),
        )
        DashboardViewModel(client, profileWithCoolant, externalScope = backgroundScope)

        client.completeInitAndSkipVin() // leaves an in-flight rpm poll

        // Drives the queue generically — the fast loop's rpm/speed polls interleave with the
        // coolant ticker in an order this test doesn't need to predict — answering whatever the
        // *newest* pending write is, and advancing time in small steps (well under any single
        // command's timeout) whenever nothing new has appeared yet, to let a queued `delay(...)`
        // elapse. Tracking the last-answered write avoids double-counting a response against a
        // write that hasn't actually changed since the previous step.
        var lastAnswered = ""
        suspend fun driveUntil(condition: () -> Boolean) {
            var guard = 0
            while (!condition()) {
                check(++guard < 2_000) { "drive loop did not converge" }
                runCurrent()
                val pending = client.writes.lastOrNull() ?: ""
                if (pending == lastAnswered) {
                    dispatcher.scheduler.advanceTimeBy(50)
                    continue
                }
                lastAnswered = pending
                when (pending) {
                    "0105\r" -> client.respond("NO DATA\r>")
                    "010C\r" -> client.respond("41 0C 00 00\r>")
                    "010D\r" -> client.respond("41 0D 00\r>")
                }
                runCurrent()
            }
        }

        driveUntil { client.writes.count { it == "0105\r" } >= 5 }
        val coolantWritesSoFar = client.writes.count { it == "0105\r" }
        assertEquals(5, coolantWritesSoFar)

        // It should now be permanently skipped. Fast-forwarding well past many more ticker cycles
        // — without answering anything, so the fast loop's own rpm/speed polls just time out and
        // retry on their own — must produce no further "0105" writes. Just as importantly, this
        // must not hang: if the give-up branch ever forgot to delay, an all-given-up PID list
        // would busy-loop this coroutine with no suspension point, and this call would never
        // return instead of the test passing.
        dispatcher.scheduler.advanceTimeBy(200_000)
        runCurrent()

        assertEquals(coolantWritesSoFar, client.writes.count { it == "0105\r" })
    }

    @Test
    fun fastPollBrandPidIsPolledMuchSoonerThanTheNormalBrandTicker() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val citroenProfile = VehicleProfile(
            profileId = "citroen",
            brand = "Citroen",
            pids = listOf(
                PidDefinition(request = "22D409", field = "gearRaw", unit = "檔", formula = "A", fastPoll = true),
            ),
        )
        val viewModel = DashboardViewModel(
            client,
            universalProfile,
            mapOf("Citroen" to citroenProfile),
            externalScope = backgroundScope,
        )

        client.setReady()
        runCurrent()
        repeat(8) { client.respondToNextWrite(">") } // cascades into the "0902" VIN request
        client.respondToNextWrite("49 02 01 56 46 37 41 42 43 44 45 46 47 48 31 32 33 34 35 36\r>") // VIN VF7...
        assertEquals("Citroen", viewModel.uiState.value.detectedBrand)
        client.respondToNextWrite("43 00\r>") // automatic DTC read, no codes

        // Drives the queue generically (see stopsPollingStandardExtraPidPermanentlyAfterRepeated-
        // Failures above for why) until the gear PID has been queried twice.
        var lastAnswered = ""
        var gearQueries = 0
        var guard = 0
        while (gearQueries < 2) {
            check(++guard < 5_000) { "drive loop did not converge" }
            runCurrent()
            val pending = client.writes.lastOrNull() ?: ""
            if (pending == lastAnswered) {
                dispatcher.scheduler.advanceTimeBy(20)
                continue
            }
            lastAnswered = pending
            when (pending) {
                "22D409\r" -> {
                    gearQueries++
                    client.respond("62 D4 09 03\r>")
                }
                "010C\r" -> client.respond("41 0C 00 00\r>")
                "010D\r" -> client.respond("41 0D 00\r>")
            }
            runCurrent()
        }

        assertEquals("3.0 檔", viewModel.uiState.value.extraReadings["gearRaw"])
        // Two gear queries completing within 3s (BRAND_POLL_INTERVAL_MS, the normal brand-PID
        // ticker's cadence) proves fastPoll actually got its own shorter-interval ticker rather
        // than sharing the slow round-robin — on the old shared ticker this alone would take at
        // least one full BRAND_POLL_INTERVAL_MS.
        assertTrue(dispatcher.scheduler.currentTime < 3_000)
    }

    @Test
    fun restoresStandardFunctionalHeaderAfterBrandPidWithEcuHeader() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val citroenProfile = VehicleProfile(
            profileId = "citroen",
            brand = "Citroen",
            pids = listOf(
                PidDefinition(request = "22D47E", field = "turboPressureBar", unit = "bar", ecuHeader = "6A8", formula = "A"),
            ),
        )
        val viewModel = DashboardViewModel(
            client,
            universalProfile,
            mapOf("Citroen" to citroenProfile),
            externalScope = backgroundScope,
        )

        client.setReady()
        runCurrent()
        repeat(8) { client.respondToNextWrite(">") } // cascades into the "0902" VIN request
        assertEquals("0902\r", client.writes.last())

        client.respondToNextWrite("49 02 01 56 46 37 41 42 43 44 45 46 47 48 31 32 33 34 35 36\r>") // VIN VF7ABCDEFGH123456
        assertEquals("Citroen", viewModel.uiState.value.detectedBrand)

        client.respondToNextWrite("43 00\r>") // automatic DTC read, no codes -> cascades into the fast loop's rpm poll
        assertEquals("010C\r", client.writes.last())

        // Completing the rpm poll hands the queue to the brand poller, which switches to the
        // turbo PID's ECU header before asking for it.
        client.respondToNextWrite("41 0C 00 00\r>")
        assertEquals("ATSH6A8\r", client.writes.last())

        client.respondToNextWrite(">")
        assertEquals("22D47E\r", client.writes.last())

        // After reading the PID, the header must be restored to the standard 11-bit functional
        // broadcast (7DF) — a malformed "ATSH00" can be silently ignored by the adapter, stranding
        // every later standard PID poll on this PID's ECU header (see DashboardViewModel).
        client.respondToNextWrite("62 D4 7E 64\r>")
        assertEquals("ATSH7DF\r", client.writes.last())
    }

    @Test
    fun autoConnectsToRememberedDeviceWhenSeenInScanResults() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val memory = FakeDeviceMemory(address = "AA:BB:CC:DD:EE:FF")
        DashboardViewModel(client, universalProfile, deviceMemory = memory, externalScope = backgroundScope)

        val remembered = ScannedBleDevice("AA:BB:CC:DD:EE:FF", "vLinker MC+", rssi = -60, isPreferred = true)
        val other = ScannedBleDevice("11:22:33:44:55:66", "OBDII", rssi = -50, isPreferred = false)
        client.emitDevices(listOf(other, remembered))
        runCurrent()

        assertEquals(listOf(remembered), client.connectCalls)
    }

    @Test
    fun doesNotAutoConnectWithoutARememberedDevice() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        DashboardViewModel(client, universalProfile, externalScope = backgroundScope)

        client.emitDevices(listOf(ScannedBleDevice("AA:BB:CC:DD:EE:FF", "vLinker MC+", rssi = -60, isPreferred = true)))
        runCurrent()

        assertTrue(client.connectCalls.isEmpty())
    }

    @Test
    fun remembersDeviceAddressOnceConnectionBecomesReady() = runTest(dispatcher) {
        val client = FakeBleObdClient()
        val memory = FakeDeviceMemory()
        val viewModel = DashboardViewModel(client, universalProfile, deviceMemory = memory, externalScope = backgroundScope)

        val device = ScannedBleDevice("AA:BB:CC:DD:EE:FF", "vLinker MC+", rssi = -60, isPreferred = true)
        viewModel.connect(device)
        client.setReady()
        runCurrent()

        assertEquals(listOf("AA:BB:CC:DD:EE:FF"), memory.rememberedAddresses)
    }
}
