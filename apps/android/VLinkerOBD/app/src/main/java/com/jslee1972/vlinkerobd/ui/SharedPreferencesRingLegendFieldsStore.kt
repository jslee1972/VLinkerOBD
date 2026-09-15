package com.jslee1972.vlinkerobd.ui

import android.content.Context

class SharedPreferencesRingLegendFieldsStore(context: Context) : RingLegendFieldsStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Stored as a delimited string, not a StringSet — order matters here (which field shows
    // first next to the ring) and StringSet iteration order is unspecified. contains(...) is the
    // same "never configured vs. explicitly configured" distinction SharedPreferencesCustomSectionStore
    // uses: only fall back to the seeded default before the user has ever touched the picker.
    override fun fields(): List<String> =
        if (prefs.contains(KEY_FIELDS)) {
            prefs.getString(KEY_FIELDS, "").orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }
        } else {
            ParameterGroups.DEFAULT_RING_LEGEND_FIELDS
        }

    override fun setFields(fields: List<String>) {
        prefs.edit().putString(KEY_FIELDS, fields.joinToString(SEPARATOR)).apply()
    }

    companion object {
        private const val PREFS_NAME = "vlinkerobd_ring_legend"
        private const val KEY_FIELDS = "fields"
        private const val SEPARATOR = ","
    }
}
