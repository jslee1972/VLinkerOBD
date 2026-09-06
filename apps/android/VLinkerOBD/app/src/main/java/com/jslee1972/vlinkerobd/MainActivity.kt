package com.jslee1972.vlinkerobd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jslee1972.vlinkerobd.ble.BleObdManager
import com.jslee1972.vlinkerobd.obd.PidGroupRepository
import com.jslee1972.vlinkerobd.obd.VehicleProfile
import com.jslee1972.vlinkerobd.ui.DashboardScreen
import com.jslee1972.vlinkerobd.ui.DashboardViewModel

private const val PERMISSION_DENIED_MESSAGE = "需要藍牙掃描權限才能尋找裝置"

class DashboardViewModelFactory(
    private val bleClient: BleObdManager,
    private val universalProfile: VehicleProfile,
    private val brandProfiles: Map<String, VehicleProfile>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return DashboardViewModel(bleClient, universalProfile, brandProfiles) as T
    }
}

class MainActivity : ComponentActivity() {

    private fun requiredBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasBlePermissions(): Boolean = requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = PidGroupRepository { path -> assets.open(path).bufferedReader().use { it.readText() } }
        val universalProfile = repository.loadUniversal()
        val brandProfiles = mapOf(
            "Mazda" to repository.loadBrand("mazda.json"),
            "Ford" to repository.loadBrand("ford.json"),
            "Honda" to repository.loadBrand("honda.json"),
        )
        val bleClient = BleObdManager(applicationContext)
        val factory = DashboardViewModelFactory(bleClient, universalProfile, brandProfiles)

        setContent {
            MaterialTheme {
                val viewModel: DashboardViewModel = viewModel(factory = factory)
                val uiState by viewModel.uiState.collectAsState()
                var permissionDenied by remember { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { results ->
                    if (results.values.all { it }) {
                        permissionDenied = false
                        viewModel.startScan()
                    } else {
                        permissionDenied = true
                    }
                }

                val displayState = if (permissionDenied && uiState.errorMessage == null) {
                    uiState.copy(errorMessage = PERMISSION_DENIED_MESSAGE)
                } else {
                    uiState
                }

                DashboardScreen(
                    state = displayState,
                    onStartScan = {
                        if (hasBlePermissions()) {
                            permissionDenied = false
                            viewModel.startScan()
                        } else {
                            permissionLauncher.launch(requiredBlePermissions())
                        }
                    },
                    onStopScan = viewModel::stopScan,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onSelectBrand = viewModel::selectBrand,
                    onSendManualCommand = viewModel::sendManualCommand,
                    onClearLogs = viewModel::clearLogs,
                    onReadTroubleCodes = viewModel::readTroubleCodes,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
