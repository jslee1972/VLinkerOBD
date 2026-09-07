package com.jslee1972.vlinkerobd.ui

import com.jslee1972.vlinkerobd.ble.ScannedBleDevice
import com.jslee1972.vlinkerobd.model.VehicleData

const val UNIVERSAL_BRAND = "通用"

data class DashboardUiState(
    val connectionLabel: String = "尚未連線",
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
    /** null = 尚未查詢過；空清單 = 已查詢且目前無故障碼。*/
    val troubleCodes: List<String>? = null,
    val isReadingTroubleCodes: Boolean = false,
    val detectedVin: String? = null,
    val detectedBrand: String? = null,
)
