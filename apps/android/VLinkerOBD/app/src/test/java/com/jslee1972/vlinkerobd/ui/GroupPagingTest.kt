package com.jslee1972.vlinkerobd.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupPagingTest {

    @Test
    fun mergesSmallAdjacentGroups() {
        val pages = GroupPaging.mergedPages(
            order = listOf("A", "B", "C"),
            fieldCounts = mapOf("A" to 1, "B" to 2, "C" to 1),
            maxFieldsPerPage = 6,
        )
        assertEquals(listOf(listOf("A", "B", "C")), pages)
    }

    @Test
    fun startsNewPageWhenOverCapacity() {
        val pages = GroupPaging.mergedPages(
            order = listOf("A", "B", "C"),
            fieldCounts = mapOf("A" to 4, "B" to 4, "C" to 1),
            maxFieldsPerPage = 6,
        )
        assertEquals(listOf(listOf("A"), listOf("B", "C")), pages)
    }

    @Test
    fun largeGroupAlwaysGetsItsOwnPageEvenAlone() {
        val pages = GroupPaging.mergedPages(
            order = listOf("A"),
            fieldCounts = mapOf("A" to 12),
            maxFieldsPerPage = 6,
        )
        assertEquals(listOf(listOf("A")), pages)
    }

    @Test
    fun skipsGroupsWithNoFields() {
        val pages = GroupPaging.mergedPages(
            order = listOf("A", "B", "C"),
            fieldCounts = mapOf("A" to 2, "C" to 2),
            maxFieldsPerPage = 6,
        )
        assertEquals(listOf(listOf("A", "C")), pages)
    }

    @Test
    fun preservesDisplayOrderNotSizeOrder() {
        val pages = GroupPaging.mergedPages(
            order = listOf("big", "small"),
            fieldCounts = mapOf("big" to 5, "small" to 1),
            maxFieldsPerPage = 6,
        )
        assertEquals(listOf(listOf("big", "small")), pages)
    }
}
