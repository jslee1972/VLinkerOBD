package com.jslee1972.vlinkerobd.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GattCharacteristicSelectorTest {

    private fun candidate(
        service: String = "0000-service",
        characteristic: String,
        notify: Boolean = false,
        indicate: Boolean = false,
        write: Boolean = false,
        writeNoResponse: Boolean = false,
    ) = GattCharacteristicCandidate(service, characteristic, notify, indicate, write, writeNoResponse)

    @Test
    fun selectsNotifyAndWritePair() {
        val notify = candidate(characteristic = "aaaa", notify = true)
        val write = candidate(characteristic = "bbbb", write = true)

        val selection = GattCharacteristicSelector.select(listOf(notify, write))

        assertEquals(notify, selection?.notify)
        assertEquals(write, selection?.write)
    }

    @Test
    fun selectsIndicateAndWriteWithoutResponsePair() {
        val indicate = candidate(characteristic = "aaaa", indicate = true)
        val writeNoResponse = candidate(characteristic = "bbbb", writeNoResponse = true)

        val selection = GattCharacteristicSelector.select(listOf(indicate, writeNoResponse))

        assertEquals(indicate, selection?.notify)
        assertEquals(writeNoResponse, selection?.write)
    }

    @Test
    fun prefersSingleCharacteristicThatBothNotifiesAndWrites() {
        val combined = candidate(characteristic = "cccc", notify = true, write = true)
        val notifyOnly = candidate(characteristic = "aaaa", notify = true)
        val writeOnly = candidate(characteristic = "bbbb", write = true)

        val selection = GattCharacteristicSelector.select(listOf(combined, notifyOnly, writeOnly))

        assertEquals(combined, selection?.notify)
        assertEquals(combined, selection?.write)
    }

    @Test
    fun prefersSameServicePairOverCrossServicePair() {
        val notifyServiceA = candidate(service = "service-a", characteristic = "aaaa", notify = true)
        val writeServiceA = candidate(service = "service-a", characteristic = "bbbb", write = true)
        val writeServiceB = candidate(service = "service-b", characteristic = "0000", write = true, writeNoResponse = true)

        val selection = GattCharacteristicSelector.select(listOf(notifyServiceA, writeServiceA, writeServiceB))

        assertEquals("service-a", selection?.write?.serviceUuid)
    }

    @Test
    fun returnsNullWhenNoReceiverCandidate() {
        val write = candidate(characteristic = "bbbb", write = true)
        assertNull(GattCharacteristicSelector.select(listOf(write)))
    }

    @Test
    fun returnsNullWhenNoSenderCandidate() {
        val notify = candidate(characteristic = "aaaa", notify = true)
        assertNull(GattCharacteristicSelector.select(listOf(notify)))
    }
}
