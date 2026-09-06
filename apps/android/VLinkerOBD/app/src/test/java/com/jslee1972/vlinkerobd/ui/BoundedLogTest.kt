package com.jslee1972.vlinkerobd.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedLogTest {

    @Test
    fun retainsLatestFiveHundredEntries() {
        val log = BoundedLog()
        repeat(510) { log.append("訊息 $it") }
        assertEquals(500, log.entries.size)
        assertEquals("訊息 10", log.entries.first())
        assertEquals("訊息 509", log.entries.last())
    }

    @Test
    fun clearRemovesAllEntries() {
        val log = BoundedLog()
        log.append("a")
        log.append("b")
        log.clear()
        assertEquals(emptyList<String>(), log.entries)
    }
}
