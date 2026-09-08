package com.jslee1972.vlinkerobd.ui

/** Remembers which parameter fields the user pinned into the dashboard's "自訂" section. */
interface CustomSectionStore {
    fun selectedFields(): Set<String>
    fun setSelectedFields(fields: Set<String>)
}

object NoOpCustomSectionStore : CustomSectionStore {
    override fun selectedFields(): Set<String> = emptySet()
    override fun setSelectedFields(fields: Set<String>) = Unit
}
