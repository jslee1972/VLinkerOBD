package com.jslee1972.vlinkerobd.ble

import android.content.Context

class SharedPreferencesDeviceMemory(context: Context) : DeviceMemory {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastDeviceAddress(): String? = prefs.getString(KEY_ADDRESS, null)

    override fun rememberDevice(address: String) {
        prefs.edit().putString(KEY_ADDRESS, address).apply()
    }

    companion object {
        private const val PREFS_NAME = "vlinkerobd_device_memory"
        private const val KEY_ADDRESS = "last_device_address"
    }
}
