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
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
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
    onSendManualCommand: (String) -> Unit,
    onClearLogs: () -> Unit,
    onTestEcuSupport: () -> Unit,
    dtcDescriptions: DtcDescriptions,
    modifier: Modifier = Modifier,
) {
    var showTroubleCodeDetail by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showEcuTest by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    val brandLabel = state.detectedBrand ?: "行車通"
                    val titleText = if (state.detectedBrand != null && state.detectedVin != null) {
                        "$brandLabel（${state.detectedVin}）"
                    } else {
                        brandLabel
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("top_bar_brand"),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                // The title is a single line now (brand + VIN merged) — the default 64dp bar
                // height was sized for a two-line title and left a visibly oversized gap above
                // the status row below it.
                expandedHeight = 48.dp,
                actions = {
                    val troubleCodeCount = state.troubleCodes?.size ?: 0
                    if (troubleCodeCount > 0) {
                        IconButton(
                            onClick = { showTroubleCodeDetail = true },
                            modifier = Modifier.testTag("dtc_badge"),
                        ) {
                            BadgedBox(badge = { Badge { Text(troubleCodeCount.toString()) } }) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = "偵測到 $troubleCodeCount 個故障碼",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showOverflowMenu = true }, modifier = Modifier.testTag("overflow_menu")) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多選項")
                    }
                    DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("選擇連線裝置") },
                            onClick = {
                                showOverflowMenu = false
                                showDevicePicker = true
                            },
                            modifier = Modifier.testTag("menu_device_picker"),
                        )
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
                            text = { Text("ECU 支援測試") },
                            enabled = state.isReady,
                            onClick = {
                                showOverflowMenu = false
                                showEcuTest = true
                            },
                            modifier = Modifier.testTag("menu_ecu_test"),
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
            // Less top inset than bottom — the TopAppBar above already carries its own padding,
            // so a full 12dp on top of that left a visibly oversized gap before the status row.
            contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
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
                            // GPS speed shown small underneath for a real-world sanity check
                            // against the vehicle's own OBD-reported speed.
                            secondaryValueText = state.gpsSpeedKph?.let { "GPS %.0f".format(it) },
                            trendContent = if (state.speedHistory.size >= 2) {
                                {
                                    TrendChart(
                                        values = state.speedHistory,
                                        label = "車速",
                                        unit = "km/h",
                                        lineColor = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.fillMaxWidth(0.7f),
                                        compact = true,
                                    )
                                }
                            } else null,
                        )
                        Gauge(
                            value = (state.vehicleData.rpm ?: 0).toFloat(),
                            minValue = 0f,
                            maxValue = 8000f,
                            label = "轉速",
                            unit = "rpm",
                            redlineStart = 6500f,
                            modifier = Modifier.weight(1f),
                            trendContent = if (state.rpmHistory.size >= 2) {
                                {
                                    TrendChart(
                                        values = state.rpmHistory,
                                        label = "轉速",
                                        unit = "rpm",
                                        lineColor = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.fillMaxWidth(0.7f),
                                        compact = true,
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }

            state.troubleCodes?.let { codes ->
                if (codes.isEmpty()) {
                    item {
                        Text(
                            "無故障碼",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("trouble_codes"),
                        )
                    }
                } else {
                    item {
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

            // Standard PIDs and brand-specific PIDs are both "live vehicle readings" from the
            // user's point of view — auto-detected/auto-polled the moment data is available,
            // never gated behind the brand dropdown — so they're grouped together by subject
            // (engine, temperature, pressure, ...) the same way speed+RPM already share one gauge
            // card, rather than one long undifferentiated grid now that most of universal-obd2.json
            // is polled. Each group is sorted by field name and keyed in gridItems so a card's grid
            // position never jumps as new fields stream in.
            val liveReadings = state.standardReadings + state.extraReadings
            if (liveReadings.isNotEmpty()) {
                val grouped = liveReadings.entries.groupBy { ParameterGroups.groupFor(it.key) }
                for (group in ParameterGroups.displayOrder) {
                    val entries = grouped[group]?.sortedBy { it.key } ?: continue
                    item(key = "section_$group") { SectionLabel(group) }
                    item(key = "grid_$group") {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.heightIn(max = 600.dp),
                        ) {
                            gridItems(entries, key = { it.key }) { (field, value) ->
                                ParameterStatCard(field, value)
                            }
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

    if (showDevicePicker) {
        DevicePickerDialog(
            state = state,
            onStartScan = onStartScan,
            onStopScan = onStopScan,
            onConnect = { onConnect(it); showDevicePicker = false },
            onDismiss = { showDevicePicker = false },
        )
    }

    if (showEcuTest) {
        EcuSupportTestDialog(
            state = state,
            onRunTest = onTestEcuSupport,
            onDismiss = { showEcuTest = false },
        )
    }
}

@Composable
private fun StatusHeader(state: DashboardUiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val dotColor = when {
            state.errorMessage != null -> MaterialTheme.colorScheme.error
            state.isReady -> Color(0xFF22C55E)
            state.isScanning -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
        Text(
            text = if (state.isReady && state.connectedDeviceName != null) {
                "${state.connectionLabel}：${state.connectedDeviceName}"
            } else {
                state.connectionLabel
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("connection_status"),
        )
        state.errorMessage?.let {
            Text(text = " · $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
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
                    text = PidDisplayNames.displayName(field),
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
private fun DevicePickerDialog(
    state: DashboardUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (ScannedBleDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    // Scanning is scoped to the dialog's lifetime: start when it opens, stop when it closes,
    // so the main screen no longer needs a permanent scan button/device list taking up space.
    DisposableEffect(Unit) {
        onStartScan()
        onDispose { onStopScan() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("選擇連線裝置", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    if (state.isScanning) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                // Devices without a broadcast name are almost never a usable OBD adapter and just
                // add noise to the list, so they're filtered out here (unlike the auto-reconnect
                // matcher in the ViewModel, which still matches by address regardless of name).
                val namedDevices = state.devices.filter { !it.name.isNullOrBlank() }
                if (namedDevices.isEmpty()) {
                    Text(
                        text = if (state.isScanning) "掃描中…尚未發現裝置" else "尚未發現裝置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        namedDevices.forEach { device -> DeviceRow(device, onConnect) }
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("關閉") }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ScannedBleDevice, onConnect: (ScannedBleDevice) -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
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
                Text("$marker${device.name}", style = MaterialTheme.typography.bodyLarge)
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
private fun EcuSupportTestDialog(
    state: DashboardUiState,
    onRunTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Runs once as soon as the dialog opens; the retest button re-triggers it on demand.
    DisposableEffect(Unit) { onRunTest(); onDispose {} }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("ECU 支援測試", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    if (state.isTestingEcu) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = "測試這台車的 ECU 支援哪些讀取 VIN 的方式（Mode 09、UDS 22F190），可用來判斷此車是否只是協定不支援，而非硬體接線問題。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.ecuTestResults.isEmpty() && !state.isTestingEcu) {
                        Text(
                            "尚無測試結果",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.ecuTestResults.forEach { result -> EcuTestResultRow(result) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onRunTest, enabled = !state.isTestingEcu, modifier = Modifier.testTag("ecu_test_retry")) {
                        Text("重新測試")
                    }
                    Row(modifier = Modifier.weight(1f)) {}
                    TextButton(onClick = onDismiss) { Text("關閉") }
                }
            }
        }
    }
}

@Composable
private fun EcuTestResultRow(result: EcuTestResult) {
    val statusColor = when (result.status) {
        EcuTestStatus.SUPPORTED -> Color(0xFF22C55E)
        EcuTestStatus.NO_DATA -> MaterialTheme.colorScheme.onSurfaceVariant
        EcuTestStatus.NEGATIVE, EcuTestStatus.TIMEOUT, EcuTestStatus.UNRECOGNIZED -> MaterialTheme.colorScheme.error
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ecu_test_row_${result.command}"),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = result.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                )
                Text(result.statusMessage, color = statusColor, style = MaterialTheme.typography.labelMedium)
            }
            Text(result.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            result.raw?.let {
                Text(
                    text = "Raw: $it",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
