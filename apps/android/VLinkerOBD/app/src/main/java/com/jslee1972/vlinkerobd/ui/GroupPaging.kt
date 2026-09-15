package com.jslee1972.vlinkerobd.ui

/**
 * Shared by the landscape driving-dynamics dashboard: greedily combines adjacent small groups (by
 * field count, following [ParameterGroups.displayOrder]) into one shared page, so a group with
 * only one or two live fields doesn't get an entire screen to itself while a large group still
 * gets its own. "Adjacent" is defined purely by that display order — groups are never reordered
 * to make a better fit, only chunked along the sequence they'd otherwise render in. Ported from
 * iOS's GroupPaging.swift, same algorithm.
 */
object GroupPaging {
    fun mergedPages(order: List<String>, fieldCounts: Map<String, Int>, maxFieldsPerPage: Int = 6): List<List<String>> {
        val pages = mutableListOf<List<String>>()
        var currentPage = mutableListOf<String>()
        var currentCount = 0
        for (group in order) {
            val count = fieldCounts[group] ?: continue
            if (count <= 0) continue
            if (currentPage.isNotEmpty() && currentCount + count > maxFieldsPerPage) {
                pages.add(currentPage)
                currentPage = mutableListOf()
                currentCount = 0
            }
            currentPage.add(group)
            currentCount += count
        }
        if (currentPage.isNotEmpty()) pages.add(currentPage)
        return pages
    }
}
