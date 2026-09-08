package com.jslee1972.vlinkerobd.ui

import android.content.Context

class SharedPreferencesCustomSectionStore(context: Context) : CustomSectionStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun selectedFields(): Set<String> = prefs.getStringSet(KEY_FIELDS, emptySet()).orEmpty()

    override fun setSelectedFields(fields: Set<String>) {
        // SharedPreferences.Editor.putStringSet keeps a live reference to the Set passed in if you
        // don't copy it — mutating the caller's set afterward would silently corrupt what's stored.
        prefs.edit().putStringSet(KEY_FIELDS, fields.toSet()).apply()
    }

    companion object {
        private const val PREFS_NAME = "vlinkerobd_custom_section"
        private const val KEY_FIELDS = "selected_fields"
    }
}
