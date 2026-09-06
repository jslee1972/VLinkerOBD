package com.jslee1972.vlinkerobd.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDeviceRankingTest {

    @Test
    fun recognizesVLinkerNameVariants() {
        assertTrue(BleDeviceRanking.isPreferred("vLinker MC+"))
        assertTrue(BleDeviceRanking.isPreferred("V-LINK"))
        assertTrue(BleDeviceRanking.isPreferred("my-vlink-adapter"))
    }

    @Test
    fun rejectsUnrelatedNames() {
        assertFalse(BleDeviceRanking.isPreferred("OBDII"))
        assertFalse(BleDeviceRanking.isPreferred(null))
    }

    @Test
    fun ranksPreferredDevicesFirstThenByRssiThenAddress() {
        val devices = listOf(
            ScannedBleDevice("AA:AA", "OBDII", rssi = -40, isPreferred = false),
            ScannedBleDevice("BB:BB", "vLinker MC+", rssi = -80, isPreferred = true),
            ScannedBleDevice("CC:CC", "vLinker MC+", rssi = -50, isPreferred = true),
        )

        val ranked = BleDeviceRanking.rank(devices)

        assertEquals(listOf("CC:CC", "BB:BB", "AA:AA"), ranked.map { it.address })
    }
}
