package com.jslee1972.vlinkerobd

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
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
import com.jslee1972.vlinkerobd.ui.DrivingDynamicsDashboardScreen
import com.jslee1972.vlinkerobd.ui.PipSpeedView
import com.jslee1972.vlinkerobd.ui.theme.VLinkerObdTheme

private const val PERMISSION_DENIED_MESSAGE = "需要藍牙掃描權限才能尋找裝置"

class MainActivity : ComponentActivity() {

    // Mirrors iOS's floating speed PiP window using Android's own system Picture-in-Picture mode
    // instead of iOS's AVPictureInPictureController/fake-video-frame approach — Android's PiP
    // just shrinks whatever the Activity is currently showing, so PipSpeedView below is all that's
    // needed on the Compose side. A plain Compose-runtime MutableState field (not `remember`,
    // since this is set from the non-Compose onPictureInPictureModeChanged callback) drives which
    // screen setContent shows.
    private var isInPip by mutableStateOf(false)

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

        // A phone mounted on a dash is exactly the "glanceable while driving" use case this app
        // exists for — letting the screen lock mid-drive defeats that, so keep it awake the whole
        // time the Activity is visible (cleared automatically when it isn't, unlike a wake lock).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Shared with the Android Auto car screen (see the `car` package) via VLinkerObdApplication
        // — there's only one BLE adapter, so both surfaces must observe the same ViewModel/connection
        // rather than each owning their own.
        val app = application as VLinkerObdApplication
        val viewModel = app.dashboardViewModel
        val dtcDescriptions = app.dtcDescriptions
        val parameterMetadata = app.parameterMetadata

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
                // reading the phone's actual GPS still does, on every version. FINE and COARSE are
                // requested together (Android 12+ requirement for the "precise/approximate" dialog
                // to render correctly) even though only a FINE grant actually unlocks GPS_PROVIDER.
                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { results -> if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) viewModel.startGpsTracking() }

                // Auto-start scanning once when the app opens, so a remembered device can be
                // auto-reconnected without the user tapping anything (see DashboardViewModel).
                LaunchedEffect(Unit) {
                    requestScanOrStart()
                    if (hasLocationPermission()) {
                        viewModel.startGpsTracking()
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    }
                }

                val displayState = if (permissionDenied && uiState.errorMessage == null) {
                    uiState.copy(errorMessage = PERMISSION_DENIED_MESSAGE)
                } else {
                    uiState
                }

                // Landscape is the app's only screen orientation now (see AndroidManifest.xml's
                // screenOrientation="sensorLandscape"), matching iOS's own move to a
                // landscape-only driving-dynamics dashboard — no portrait branch to pick between.
                if (isInPip) {
                    PipSpeedView(state = displayState)
                } else {
                    DrivingDynamicsDashboardScreen(
                        state = displayState,
                        onStartScan = ::requestScanOrStart,
                        onStopScan = viewModel::stopScan,
                        onConnect = viewModel::connect,
                        onDisconnect = viewModel::disconnect,
                        onSendManualCommand = viewModel::sendManualCommand,
                        onClearLogs = viewModel::clearLogs,
                        onToggleCustomField = viewModel::toggleCustomField,
                        onClearAllCustomFields = viewModel::clearAllCustomFields,
                        onMoveCustomField = viewModel::moveCustomField,
                        onToggleRingLegendField = viewModel::toggleRingLegendField,
                        onProbeGearRaw = viewModel::probeGearRaw,
                        onProbeTirePressures = viewModel::probeTirePressures,
                        onEnterPipMode = ::enterSpeedPipMode,
                        dtcDescriptions = dtcDescriptions,
                        parameterMetadata = parameterMetadata,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    /** Shrinks the app into a small floating window showing just the current speed (see
     * PipSpeedView), via Android's system Picture-in-Picture — the window keeps running and
     * stays visible on top of whatever app the user switches to next (e.g. Maps). */
    private fun enterSpeedPipMode() {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(3, 2))
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
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
