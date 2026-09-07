package com.jslee1972.vlinkerobd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jslee1972.vlinkerobd.ble.ScannedBleDevice
import com.jslee1972.vlinkerobd.obd.DtcDescriptions
import com.jslee1972.vlinkerobd.ui.gauge.Gauge

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
    modifier: Modifier = Modifier,
) {
    var showTroubleCodeDetail by remember { mutableStateOf(false) }

    Scaffold(modifier = modifier) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.connectionLabel,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("connection_status"),
                    )
                    state.errorMessage?.let {
                        Text(text = it, color = MaterialTheme.colorScheme.error)
                    }
                    state.detectedBrand?.let { brand ->
                        Text(
                            text = "偵測到車款：$brand（VIN ${state.detectedVin}）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("detected_brand"),
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth()) {
                    Gauge(
                        value = (state.vehicleData.speedKph ?: 0).toFloat(),
                        minValue = 0f,
                        maxValue = 220f,
                        label = "車速",
                        unit = "km/h",
                        modifier = Modifier
                            .weight(1f)
                            .testTag("speed_value"),
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

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = if (state.isScanning) onStopScan else onStartScan) {
                        Text(if (state.isScanning) "停止掃描" else "掃描 vLinker")
                    }
                    if (state.isReady) {
                        OutlinedButton(onClick = onDisconnect) { Text("中斷連線") }
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
                        Text("無故障碼", modifier = Modifier.testTag("trouble_codes"))
                    } else {
                        Card(
                            onClick = { showTroubleCodeDetail = true },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .testTag("trouble_codes"),
                        ) {
                            Text(
                                text = "⚠ 偵測到 ${codes.size} 個故障碼，點擊查看詳情",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
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

            if (state.extraReadings.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("廠牌 PID", style = MaterialTheme.typography.titleSmall)
                            state.extraReadings.forEach { (field, value) ->
                                Text("$field：$value")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Raw: ${state.rawResponse.ifBlank { "(尚無資料)" }}",
                    modifier = Modifier.testTag("raw_response"),
                )
            }

            item {
                ManualCommandRow(onSendManualCommand)
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("紀錄", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = onClearLogs) { Text("清除紀錄") }
                }
            }

            items(state.logs) { line ->
                Text(text = line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("obd_log"))
            }
        }
    }

    if (showTroubleCodeDetail) {
        TroubleCodeDetailDialog(
            codes = state.troubleCodes.orEmpty(),
            onDismiss = { showTroubleCodeDetail = false },
        )
    }
}

@Composable
private fun TroubleCodeDetailDialog(codes: List<String>, onDismiss: () -> Unit) {
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
                            text = DtcDescriptions.describe(code),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            val marker = if (device.isPreferred) "★ " else ""
            Text("$marker${device.name ?: "(未知名稱)"}")
            Text("${device.address}  RSSI ${device.rssi}", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { onConnect(device) }) { Text("連線") }
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
