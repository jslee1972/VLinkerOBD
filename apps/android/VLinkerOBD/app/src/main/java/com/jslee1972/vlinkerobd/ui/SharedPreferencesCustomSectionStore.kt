package com.jslee1972.vlinkerobd.ui

import android.content.Context

class SharedPreferencesCustomSectionStore(context: Context) : CustomSectionStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Stored as a delimited string, not a StringSet — order matters here (it's the user's own
    // drag-to-reorder arrangement) and StringSet iteration order is unspecified. contains(...) is
    // what distinguishes "the user has never touched the picker yet" (fall back to
    // ParameterGroups.DEFAULT_CUSTOM_FIELDS, so a first launch shows useful data instead of an
    // empty section) from "the user explicitly cleared it via 全部移除" (an empty list that must
    // stay empty, not silently get re-seeded with defaults every time).
    override fun selectedFields(): List<String> =
        if (prefs.contains(KEY_FIELDS)) {
            prefs.getString(KEY_FIELDS, "").orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }
        } else {
            ParameterGroups.DEFAULT_CUSTOM_FIELDS
        }

    override fun setSelectedFields(fields: List<String>) {
        prefs.edit().putString(KEY_FIELDS, fields.joinToString(SEPARATOR)).apply()
    }

    companion object {
        private const val PREFS_NAME = "vlinkerobd_custom_section"

        // Deliberately a different key than the old Set<String>-based storage this replaced — the
        // old key's value was written with putStringSet, and calling getString on a key holding a
        // StringSet throws ClassCastException, not just returning null/empty.
        private const val KEY_FIELDS = "selected_fields_ordered"
        private const val SEPARATOR = ","
    }
}
