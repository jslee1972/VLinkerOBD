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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.jslee1972.vlinkerobd.ui.DashboardScreen
import com.jslee1972.vlinkerobd.ui.theme.VLinkerObdTheme

private const val PERMISSION_DENIED_MESSAGE = "需要藍牙掃描權限才能尋找裝置"

class MainActivity : ComponentActivity() {

    private fun requiredBlePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasBlePermissions(): Boolean = requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Shared with the Android Auto car screen (see the `car` package) via VLinkerObdApplication
        // — there's only one BLE adapter, so both surfaces must observe the same ViewModel/connection
        // rather than each owning their own.
        val app = application as VLinkerObdApplication
        val viewModel = app.dashboardViewModel
        val dtcDescriptions = app.dtcDescriptions

        setContent {
            VLinkerObdTheme {
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

                fun requestScanOrStart() {
                    if (hasBlePermissions()) {
                        permissionDenied = false
                        viewModel.startScan()
                    } else {
                        permissionLauncher.launch(requiredBlePermissions())
                    }
                }

                // GPS speed comparison (shown alongside the OBD speed gauge) is independent of the
                // BLE permission flow above — on API 31+ BLE no longer needs location at all, but
                // reading the phone's actual GPS still does, on every version.
                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> if (granted) viewModel.startGpsTracking() }

                // Auto-start scanning once when the app opens, so a remembered device can be
                // auto-reconnected without the user tapping anything (see DashboardViewModel).
                LaunchedEffect(Unit) {
                    requestScanOrStart()
                    if (hasLocationPermission()) {
                        viewModel.startGpsTracking()
                    } else {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }

                val displayState = if (permissionDenied && uiState.errorMessage == null) {
                    uiState.copy(errorMessage = PERMISSION_DENIED_MESSAGE)
                } else {
                    uiState
                }

                DashboardScreen(
                    state = displayState,
                    onStartScan = ::requestScanOrStart,
                    onStopScan = viewModel::stopScan,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onSendManualCommand = viewModel::sendManualCommand,
                    onClearLogs = viewModel::clearLogs,
                    onTestEcuSupport = viewModel::testEcuSupport,
                    onToggleCustomField = viewModel::toggleCustomField,
                    dtcDescriptions = dtcDescriptions,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // GPS is only meaningful for the on-screen speed comparison — no reason to keep the radio on
    // and drain battery while the app isn't visible (unlike the BLE connection itself, which stays
    // up across Activity lifecycle for Android Auto to share — see VLinkerObdApplication).
    override fun onStop() {
        super.onStop()
        (application as VLinkerObdApplication).dashboardViewModel.stopGpsTracking()
    }

    override fun onStart() {
        super.onStart()
        val viewModel = (application as VLinkerObdApplication).dashboardViewModel
        if (hasLocationPermission()) viewModel.startGpsTracking()
    }
}
