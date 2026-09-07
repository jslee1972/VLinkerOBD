package com.jslee1972.vlinkerobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jslee1972.vlinkerobd.ble.ScannedBleDevice
import com.jslee1972.vlinkerobd.obd.DtcDescriptions
import com.jslee1972.vlinkerobd.ui.gauge.Gauge
import com.jslee1972.vlinkerobd.ui.gauge.TrendChart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (ScannedBleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSelectBrand: (String) -> Unit,
    onSendManualCommand: (String) -> Unit,
    onClearLogs: () -> Unit,
    onReadTroubleCodes: () -> Unit,
    dtcDescriptions: DtcDescriptions,
    modifier: Modifier = Modifier,
) {
    var showTroubleCodeDetail by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showSupportedBrands by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("行車通", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    IconButton(onClick = { showOverflowMenu = true }, modifier = Modifier.testTag("overflow_menu")) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多選項")
                    }
                    DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("中斷連線") },
                            enabled = state.isReady,
                            onClick = {
                                showOverflowMenu = false
                                onDisconnect()
                            },
                            modifier = Modifier.testTag("menu_disconnect"),
                        )
                        DropdownMenuItem(
                            text = { Text("診斷主控台") },
                            onClick = {
                                showOverflowMenu = false
                                showDiagnostics = true
                            },
                            modifier = Modifier.testTag("menu_diagnostics"),
                        )
                        DropdownMenuItem(
                            text = { Text("支援廠牌") },
                            onClick = {
                                showOverflowMenu = false
                                showSupportedBrands = true
                            },
                            modifier = Modifier.testTag("menu_supported_brands"),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item { StatusHeader(state) }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 8.dp),
                    ) {
                        Gauge(
                            value = (state.vehicleData.speedKph ?: 0).toFloat(),
                            minValue = 0f,
                            maxValue = 220f,
                            label = "車速",
                            unit = "km/h",
                            modifier = Modifier.weight(1f).testTag("speed_value"),
                        )
                        Gauge(
                            value = (state.vehicleData.rpm ?: 0).toFloat(),
                            minValue = 0f,
                            maxValue = 8000f,
                            label = "轉速",
                            unit = "rpm",
                            redlineStart = 6500f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (state.speedHistory.size >= 2 || state.rpmHistory.size >= 2) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            SectionLabel("趨勢圖")
                            TrendChart(
                                values = state.speedHistory,
                                label = "車速",
                                unit = "km/h",
                                lineColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TrendChart(
                                values = state.rpmHistory,
                                label = "轉速",
                                unit = "rpm",
                                lineColor = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = if (state.isScanning) onStopScan else onStartScan,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text(if (state.isScanning) "停止掃描" else "掃描 vLinker")
                    }
                    if (state.isReady) {
                        OutlinedButton(
                            onClick = onReadTroubleCodes,
                            enabled = !state.isReadingTroubleCodes,
                            modifier = Modifier.testTag("read_dtc"),
                        ) {
                            Text(if (state.isReadingTroubleCodes) "讀取中…" else "讀取故障碼")
                        }
                    }
                    BrandDropdown(state.availableBrands, state.selectedBrand, onSelectBrand)
                }
            }

            state.troubleCodes?.let { codes ->
                item {
                    if (codes.isEmpty()) {
                        Text(
                            "無故障碼",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("trouble_codes"),
                        )
                    } else {
                        Card(
                            onClick = { showTroubleCodeDetail = true },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("trouble_codes"),
                        ) {
                            Text(
                                text = "⚠ 偵測到 ${codes.size} 個故障碼，點擊查看詳情",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }
            }

            if (state.devices.isNotEmpty()) {
                items(state.devices) { device ->
                    DeviceRow(device, onConnect)
                }
            }

            // Standard PIDs and brand-specific PIDs are both "live vehicle readings" from the
            // user's point of view — auto-detected/auto-polled the moment data is available,
            // never gated behind the brand dropdown — so they render as one merged section.
            val liveReadings = state.standardReadings + state.extraReadings
            if (liveReadings.isNotEmpty()) {
                item { SectionLabel("即時參數") }
                item {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.heightIn(max = 600.dp),
                    ) {
                        gridItems(liveReadings.entries.toList()) { (field, value) ->
                            ParameterStatCard(field, value)
                        }
                    }
                }
            }
        }
    }

    if (showTroubleCodeDetail) {
        TroubleCodeDetailDialog(
            codes = state.troubleCodes.orEmpty(),
            // DTC descriptions only need a detected brand, not a brand with a live PID profile
            // (e.g. BMW has DTC descriptions but no PID profile yet) — prefer the user's manual
            // brand override (selectedBrand) but fall back to VIN auto-detection.
            brand = state.selectedBrand.takeIf { it != UNIVERSAL_BRAND } ?: state.detectedBrand,
            dtcDescriptions = dtcDescriptions,
            onDismiss = { showTroubleCodeDetail = false },
        )
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            state = state,
            onSendManualCommand = onSendManualCommand,
            onClearLogs = onClearLogs,
            onDismiss = { showDiagnostics = false },
        )
    }

    if (showSupportedBrands) {
        SupportedBrandsDialog(
            pidBrands = state.availableBrands.filter { it != UNIVERSAL_BRAND },
            onDismiss = { showSupportedBrands = false },
        )
    }
}

@Composable
private fun StatusHeader(state: DashboardUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dotColor = when {
                state.errorMessage != null -> MaterialTheme.colorScheme.error
                state.isReady -> Color(0xFF22C55E)
                state.isScanning -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(modifier = Modifier.size(9.dp).background(dotColor, CircleShape))
            Text(
                text = state.connectionLabel,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.testTag("connection_status"),
            )
        }
        state.errorMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        state.detectedBrand?.let { brand ->
            BrandBadge(brand = brand, vin = state.detectedVin)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DiagnosticsDialog(
    state: DashboardUiState,
    onSendManualCommand: (String) -> Unit,
    onClearLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "關閉")
                    }
                    Text("診斷主控台", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }

                ConsoleCard {
                    Text(
                        text = "Raw: ${state.rawResponse.ifBlank { "(尚無資料)" }}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("raw_response"),
                    )
                }

                ManualCommandRow(onSendManualCommand)

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("紀錄", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onClearLogs) { Text("清除紀錄") }
                }

                ConsoleCard {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        state.logs.takeLast(50).forEach { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("obd_log"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(14.dp)) { content() }
    }
}

/** Maps a PID field's identifier to a representative icon by keyword — a rough visual cue, not a precise taxonomy. */
private fun iconForField(field: String): ImageVector {
    val lower = field.lowercase()
    return when {
        "temp" in lower -> Icons.Default.Thermostat
        "voltage" in lower || "battery" in lower -> Icons.Default.BatteryChargingFull
        "fuel" in lower -> Icons.Default.LocalGasStation
        "pressure" in lower || "rpm" in lower || "torque" in lower || "timing" in lower || "advance" in lower -> Icons.Default.Speed
        else -> Icons.Default.Info
    }
}

@Composable
private fun ParameterStatCard(field: String, value: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = iconForField(field),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = field,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TroubleCodeDetailDialog(
    codes: List<String>,
    brand: String?,
    dtcDescriptions: DtcDescriptions,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
        title = { Text("故障詳情") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                codes.forEach { code ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.testTag("dtc_detail_$code"),
                    ) {
                        Text(
                            text = "$code（${DtcDescriptions.categoryName(code)}）",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = dtcDescriptions.describe(code, brand),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SupportedBrandsDialog(pidBrands: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
        title = { Text("支援廠牌") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("即時參數（PID）", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(
                        text = pidBrands.joinToString("、").ifBlank { "（尚無）" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("故障碼說明", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text(
                        text = DtcDescriptions.SUPPORTED_BRANDS.joinToString("、"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "車款依 VIN 自動判斷；即時參數需要有對應廠牌的 PID 資料才會顯示，故障碼說明則只要偵測到廠牌即可，兩者不一定同步。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun BrandDropdown(brands: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }) { Text(selected) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            brands.forEach { brand ->
                DropdownMenuItem(text = { Text(brand) }, onClick = { onSelect(brand); expanded = false })
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ScannedBleDevice, onConnect: (ScannedBleDevice) -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                val marker = if (device.isPreferred) "★ " else ""
                Text("$marker${device.name ?: "(未知名稱)"}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${device.address}  RSSI ${device.rssi}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { onConnect(device) }) { Text("連線") }
        }
    }
}

@Composable
private fun ManualCommandRow(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("手動 AT/OBD 指令") },
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .testTag("manual_command"),
        )
        Button(
            onClick = { onSend(text); text = "" },
            enabled = text.isNotBlank(),
            modifier = Modifier.testTag("send_command"),
        ) {
            Text("傳送")
        }
    }
}
