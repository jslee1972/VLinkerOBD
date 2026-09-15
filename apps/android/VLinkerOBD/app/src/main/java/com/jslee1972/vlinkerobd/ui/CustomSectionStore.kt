package com.jslee1972.vlinkerobd.ui

/** Remembers which parameter fields the user pinned into the dashboard's "自訂" section — an
 * ordered list (not a Set), since the order is itself the user's own drag-to-reorder arrangement
 * (the driving-dynamics ring gauge's side panels render pinned fields in this exact order). */
interface CustomSectionStore {
    fun selectedFields(): List<String>
    fun setSelectedFields(fields: List<String>)
}

object NoOpCustomSectionStore : CustomSectionStore {
    override fun selectedFields(): List<String> = emptyList()
    override fun setSelectedFields(fields: List<String>) = Unit
}
