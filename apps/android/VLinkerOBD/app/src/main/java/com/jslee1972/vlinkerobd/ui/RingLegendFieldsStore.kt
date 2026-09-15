package com.jslee1972.vlinkerobd.ui

/** Remembers which (at most 2) fields the user chose for the driving-dynamics ring gauge's own
 * center legend — separate from the custom section's field set, since this is a much smaller,
 * fixed-size slot with room for only a couple of compact readouts next to the ring. */
interface RingLegendFieldsStore {
    fun fields(): List<String>
    fun setFields(fields: List<String>)
}

object NoOpRingLegendFieldsStore : RingLegendFieldsStore {
    override fun fields(): List<String> = emptyList()
    override fun setFields(fields: List<String>) = Unit
}
