package com.jslee1972.vlinkerobd

import android.app.Application
import com.jslee1972.vlinkerobd.ble.BleObdManager
import com.jslee1972.vlinkerobd.ble.SharedPreferencesDeviceMemory
import com.jslee1972.vlinkerobd.obd.DtcDescriptions
import com.jslee1972.vlinkerobd.obd.PidGroupRepository
import com.jslee1972.vlinkerobd.ui.DashboardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Owns the single BLE connection and [DashboardViewModel] instance for the whole process. Both
 * the phone UI (MainActivity) and the Android Auto car screen (see the `car` package) observe
 * this same instance instead of each opening their own competing BLE connection — there is only
 * one vLinker adapter, so there can only be one client of it at a time.
 *
 * The ViewModel is constructed directly (not via ViewModelProvider) with an explicit
 * [CoroutineScope] scoped to the application's lifetime, so it survives exactly as long as the
 * process does rather than being tied to any one Activity's lifecycle.
 */
class VLinkerObdApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var dashboardViewModel: DashboardViewModel
        private set

    val dtcDescriptions: DtcDescriptions by lazy {
        DtcDescriptions.load(readAsset = { path -> assets.open(path).bufferedReader().use { it.readText() } })
    }

    override fun onCreate() {
        super.onCreate()
        val repository = PidGroupRepository { path -> assets.open(path).bufferedReader().use { it.readText() } }
        val universalProfile = repository.loadUniversal()
        // Citroen/Peugeot share the same PSA-platform engine/BSI generation this profile was
        // reverse-engineered from (see shared/vehicle-profiles/citroen.json notes), so both
        // detected brand names map to the one loaded profile.
        val psaProfile = repository.loadBrand("citroen.json")
        val brandProfiles = mapOf(
            "Mazda" to repository.loadBrand("mazda.json"),
            "Ford" to repository.loadBrand("ford.json"),
            "Honda" to repository.loadBrand("honda.json"),
            "Citroen" to psaProfile,
            "Peugeot" to psaProfile,
        )
        val bleClient = BleObdManager(applicationContext)
        val deviceMemory = SharedPreferencesDeviceMemory(applicationContext)
        dashboardViewModel = DashboardViewModel(
            bleClient = bleClient,
            universalProfile = universalProfile,
            brandProfiles = brandProfiles,
            deviceMemory = deviceMemory,
            externalScope = appScope,
        )
    }
}
