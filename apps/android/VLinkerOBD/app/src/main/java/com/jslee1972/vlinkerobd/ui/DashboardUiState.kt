package com.jslee1972.vlinkerobd.ui

import com.jslee1972.vlinkerobd.ble.ScannedBleDevice
import com.jslee1972.vlinkerobd.model.VehicleData

const val UNIVERSAL_BRAND = "通用"

enum class EcuTestStatus { SUPPORTED, NO_DATA, NEGATIVE, UNRECOGNIZED, TIMEOUT }

/** One probe result from the "ECU 支援測試" tool (see DashboardViewModel.testEcuSupport). */
data class EcuTestResult(
    val command: String,
    val description: String,
    val raw: String?,
    val status: EcuTestStatus,
    val statusMessage: String,
)

data class DashboardUiState(
    val connectionLabel: String = "尚未連線",
    val connectedDeviceName: String? = null,
    val devices: List<ScannedBleDevice> = emptyList(),
    val vehicleData: VehicleData = VehicleData(),
    val rawResponse: String = "",
    val logs: List<String> = emptyList(),
    val isScanning: Boolean = false,
    val isReady: Boolean = false,
    val errorMessage: String? = null,
    val availableBrands: List<String> = listOf(UNIVERSAL_BRAND),
    val selectedBrand: String = UNIVERSAL_BRAND,
    val extraReadings: Map<String, String> = emptyMap(),
    val standardReadings: Map<String, String> = emptyMap(),
    val speedHistory: List<Float> = emptyList(),
    val rpmHistory: List<Float> = emptyList(),
    /** null = 尚未查詢過；空清單 = 已查詢且目前無故障碼。*/
    val troubleCodes: List<String>? = null,
    val isReadingTroubleCodes: Boolean = false,
    val detectedVin: String? = null,
    val detectedBrand: String? = null,
    val ecuTestResults: List<EcuTestResult> = emptyList(),
    val isTestingEcu: Boolean = false,
    /** Phone GPS-derived speed (km/h), shown alongside the OBD-reported speed for comparison. */
    val gpsSpeedKph: Float? = null,
)
