package com.jslee1972.vlinkerobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jslee1972.vlinkerobd.ble.ScannedBleDevice
import com.jslee1972.vlinkerobd.obd.DtcDescriptions
import com.jslee1972.vlinkerobd.ui.gauge.DesignPalette
import com.jslee1972.vlinkerobd.ui.gauge.DrivingRingGauge
import kotlin.math.roundToInt

/**
 * 行車動態介面 — the app's one screen (landscape-only, see AndroidManifest.xml): a concentric
 * ring gauge (speed outer, RPM inner, each with a peak-hold marker), user-pinned fields as
 * glass-card side panels (drag-to-reorder, double-tap to open the field picker), and every other
 * live-reading group as its own swipeable page instead of one long scroll — meant to be read at a
 * glance while driving, not scrolled through. Ported from iOS's DrivingDynamicsDashboardView.swift
 * — same layout, same colors, same mechanics (peak-hold, GPS speed fallback, group paging,
 * drag-to-reorder side panels).
 */
@Composable
fun DrivingDynamicsDashboardScreen(
    state: DashboardUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (ScannedBleDevice) -> Unit,
    onDisconnect: () -> Unit,
    onSendManualCommand: (String) -> Unit,
    onClearLogs: () -> Unit,
    onToggleCustomField: (String) -> Unit,
    onClearAllCustomFields: () -> Unit,
    onMoveCustomField: (field: String, target: String) -> Unit,
    onToggleRingLegendField: (String) -> Unit,
    onProbeGearRaw: () -> Unit,
    onProbeTirePressures: () -> Unit,
    onEnterPipMode: () -> Unit,
    dtcDescriptions: DtcDescriptions,
    parameterMetadata: ParameterMetadata,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showTroubleCodeDetail by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showDevicePicker by remember { mutableStateOf(false) }
    var showCustomFieldPicker by remember { mutableStateOf(false) }
    var showRingLegendPicker by remember { mutableStateOf(false) }

    val liveReadings = state.standardReadings + state.extraReadings
    val mergedGroupPages = remember(liveReadings.keys, parameterMetadata) {
        val fieldCounts = liveReadings.keys.groupBy { parameterMetadata.groupFor(it) }.mapValues { it.value.size }
        GroupPaging.mergedPages(order = ParameterGroups.displayOrder, fieldCounts = fieldCounts)
    }
    val pageCount = mergedGroupPages.size + 1
    val pagerState = rememberPagerState(initialPage = 0) { pageCount }

    // Capped to 8 (4 per side) — the side panels sit in the same glanceable-while-driving frame as
    // the ring gauge, so more than a handful of cards per side just gets cramped. In the user's
    // own drag-to-reorder order — selectedCustomFields is itself the persisted display order (see
    // CustomSectionStore's own doc comment) — not sorted by group/name.
    val pinnedFields = state.selectedCustomFields.take(8)
    val leftFields = pinnedFields.take(4)
    val rightFields = pinnedFields.drop(4)

    val dtcBrand = state.selectedBrand.takeIf { it != UNIVERSAL_BRAND } ?: state.detectedBrand

    Box(
        modifier = modifier.fillMaxSize().background(DesignPalette.backgroundBase),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(DesignPalette.backgroundLift, DesignPalette.backgroundBase),
                    radius = 1400f,
                ),
            ),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            DrivingTopBar(
                state = state,
                onShowMenu = { showMenu = true },
                onShowTroubleCodeDetail = { showTroubleCodeDetail = true },
            )

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { pageIndex ->
                if (pageIndex == 0) {
                    MainGaugePage(
                        state = state,
                        leftFields = leftFields,
                        rightFields = rightFields,
                        liveReadings = liveReadings,
                        parameterMetadata = parameterMetadata,
                        onOpenCustomFieldPicker = { showCustomFieldPicker = true },
                        onMoveCustomField = onMoveCustomField,
                    )
                } else {
                    GroupPage(
                        groups = mergedGroupPages[pageIndex - 1],
                        liveReadings = liveReadings,
                        parameterMetadata = parameterMetadata,
                    )
                }
            }

            DrivingPageIndicator(pageCount = pageCount, currentPage = pagerState.currentPage)
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text("選擇連線裝置") }, onClick = { showMenu = false; showDevicePicker = true })
            DropdownMenuItem(text = { Text("中斷連線") }, enabled = state.isReady, onClick = { showMenu = false; onDisconnect() })
            DropdownMenuItem(text = { Text("診斷主控台") }, onClick = { showMenu = false; showDiagnostics = true })
            DropdownMenuItem(text = { Text("編輯自訂參數") }, onClick = { showMenu = false; showCustomFieldPicker = true })
            DropdownMenuItem(text = { Text("選擇中央顯示參數") }, onClick = { showMenu = false; showRingLegendPicker = true })
            DropdownMenuItem(text = { Text("開啟浮動車速視窗") }, onClick = { showMenu = false; onEnterPipMode() })
        }
    }

    if (showTroubleCodeDetail) {
        TroubleCodeDetailDialog(
            codes = state.troubleCodes ?: emptyList(),
            brand = dtcBrand,
            dtcDescriptions = dtcDescriptions,
            onDismiss = { showTroubleCodeDetail = false },
        )
    }
    if (showDiagnostics) {
        DiagnosticsDialog(
            state = state,
            onSendManualCommand = onSendManualCommand,
            onClearLogs = onClearLogs,
            onProbeGearRaw = onProbeGearRaw,
            onProbeTirePressures = onProbeTirePressures,
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
    if (showCustomFieldPicker) {
        CustomFieldPickerDialog(
            allFields = state.allKnownFields,
            selectedFields = state.selectedCustomFields,
            onToggleField = onToggleCustomField,
            onClearAll = onClearAllCustomFields,
            onDismiss = { showCustomFieldPicker = false },
            parameterMetadata = parameterMetadata,
        )
    }
    if (showRingLegendPicker) {
        RingLegendFieldPickerDialog(
            allFields = (state.allKnownFields + state.ringLegendFields).distinct(),
            selectedFields = state.ringLegendFields,
            onToggleField = onToggleRingLegendField,
            onDismiss = { showRingLegendPicker = false },
            parameterMetadata = parameterMetadata,
        )
    }
}

@Composable
private fun DrivingTopBar(state: DashboardUiState, onShowMenu: () -> Unit, onShowTroubleCodeDetail: () -> Unit) {
    val statusColor = when {
        state.errorMessage != null -> DesignPalette.danger
        state.isReady -> DesignPalette.good
        state.isScanning -> DesignPalette.accent
        else -> Color.Gray
    }
    val brandTitle = state.detectedBrand ?: "平安行車通"
    val brandTitleWithVin = state.detectedVin?.let { "$brandTitle · ${it.takeLast(6)}" } ?: brandTitle
    val troubleCodeCount = state.troubleCodes?.size ?: 0

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Box(modifier = Modifier.size(7.dp).background(statusColor, CircleShape))
        Text(state.connectionLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.75f))

        if (state.isReady && state.connectedDeviceName != null) {
            Text(
                state.connectedDeviceName,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Text(brandTitleWithVin, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
        Spacer(modifier = Modifier.weight(1f))

        if (troubleCodeCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(DesignPalette.danger.copy(alpha = 0.15f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onShowTroubleCodeDetail() }) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("driving_dtc_badge"),
            ) {
                Icon(Icons.Default.WarningAmber, contentDescription = null, tint = DesignPalette.danger, modifier = Modifier.size(14.dp))
                Text("$troubleCodeCount", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = DesignPalette.danger)
            }
        }

        IconButton(onClick = onShowMenu, modifier = Modifier.testTag("driving_overflow_menu")) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多選項", tint = Color.White.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun DrivingPageIndicator(pageCount: Int, currentPage: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        Spacer(modifier = Modifier.weight(1f))
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(width = if (index == currentPage) 16.dp else 6.dp, height = 6.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (index == currentPage) DesignPalette.accent else Color.White.copy(alpha = 0.25f)),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MainGaugePage(
    state: DashboardUiState,
    leftFields: List<String>,
    rightFields: List<String>,
    liveReadings: Map<String, String>,
    parameterMetadata: ParameterMetadata,
    onOpenCustomFieldPicker: () -> Unit,
    onMoveCustomField: (field: String, target: String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)) {
        SidePanel(
            fields = leftFields,
            liveReadings = liveReadings,
            parameterMetadata = parameterMetadata,
            onOpenCustomFieldPicker = onOpenCustomFieldPicker,
            onMoveCustomField = onMoveCustomField,
            modifier = Modifier.weight(1f),
        )

        val legendFields = state.ringLegendFields.map { field ->
            parameterMetadata.displayName(field) to (liveReadings[field] ?: "--")
        }
        DrivingRingGauge(
            obdSpeedKph = state.vehicleData.speedKph?.toFloat(),
            gpsSpeedKph = state.gpsSpeedKph,
            rpm = (state.vehicleData.rpm ?: 0).toFloat(),
            legendFields = legendFields,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )

        SidePanel(
            fields = rightFields,
            liveReadings = liveReadings,
            parameterMetadata = parameterMetadata,
            onOpenCustomFieldPicker = onOpenCustomFieldPicker,
            onMoveCustomField = onMoveCustomField,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SidePanel(
    fields: List<String>,
    liveReadings: Map<String, String>,
    parameterMetadata: ParameterMetadata,
    onOpenCustomFieldPicker: () -> Unit,
    onMoveCustomField: (field: String, target: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (fields.isEmpty()) {
        Column(modifier = modifier.padding(horizontal = 18.dp)) {
            Text(
                "點選單「編輯自訂參數」\n新增想觀察的欄位",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.35f),
            )
        }
        return
    }

    // Cards are never forced to a computed height — a fixed/compressed height that comes out even
    // slightly smaller than what the compact content (icon + two text lines) actually needs clips
    // the text itself, not just crowds neighboring cards (found the hard way: an estimated 48dp
    // floor still clipped real rendered text on-device, since font metrics/line-height aren't
    // knowable precisely without measuring). Each card renders at its own natural size instead, and
    // the Column is unconditionally scrollable — a no-op when everything already fits, and the only
    // way to guarantee the 4th/8th card is reachable rather than silently clipped on a device whose
    // landscape height this layout doesn't comfortably fit 4 natural-sized cards into.
    BoxWithConstraints(modifier = modifier.padding(horizontal = 18.dp)) {
        val spacing = 2.dp
        // Only used as a rough step size for the drag-to-reorder distance-to-position math below —
        // doesn't need to be pixel-exact the way an enforced layout height would, just proportional.
        val totalSpacing = spacing * (fields.size - 1).coerceAtLeast(0)
        val approxCardHeight = (maxHeight - totalSpacing) / fields.size
        val density = LocalDensity.current
        val stepPx = with(density) { (approxCardHeight + spacing).toPx() }

        // Long-press-then-drag to reorder — the same idea as iOS's system-driven Home Screen icon
        // reordering (hold, the card lifts, drag it onto another card to swap positions), hand-
        // rolled here since Compose's stable Foundation API has no direct equivalent of SwiftUI's
        // .draggable/.dropDestination. The reorder only actually commits once, in onDragEnd, not
        // continuously during the drag.
        var draggedField by remember { mutableStateOf<String?>(null) }
        var dragOffsetPx by remember { mutableFloatStateOf(0f) }

        Column(
            verticalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            fields.forEachIndexed { index, field ->
                val isDragged = field == draggedField
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, if (isDragged) dragOffsetPx.roundToInt() else 0) }
                        .pointerInput(field, fields) {
                            detectTapGestures(onDoubleTap = { onOpenCustomFieldPicker() })
                        }
                        .pointerInput(field, fields) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedField = field; dragOffsetPx = 0f },
                                onDragEnd = {
                                    if (stepPx > 0f) {
                                        val moveBy = (dragOffsetPx / stepPx).roundToInt()
                                        val targetIndex = (index + moveBy).coerceIn(0, fields.lastIndex)
                                        if (targetIndex != index) onMoveCustomField(field, fields[targetIndex])
                                    }
                                    draggedField = null
                                    dragOffsetPx = 0f
                                },
                                onDragCancel = { draggedField = null; dragOffsetPx = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetPx += dragAmount.y
                                },
                            )
                        },
                ) {
                    GlassStatCard(
                        icon = iconForField(field),
                        label = parameterMetadata.displayName(field),
                        value = liveReadings[field] ?: "--",
                        compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupPage(groups: List<String>, liveReadings: Map<String, String>, parameterMetadata: ParameterMetadata) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp)
            .padding(top = 24.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        groups.forEach { group ->
            val fields = liveReadings.keys.filter { parameterMetadata.groupFor(it) == group }.sorted()
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(group, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp * ((fields.size + 2) / 3)),
                ) {
                    items(fields, key = { it }) { field ->
                        GlassStatCard(
                            icon = iconForField(field),
                            label = parameterMetadata.displayName(field),
                            value = liveReadings[field] ?: "--",
                            compact = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RingLegendFieldPickerDialog(
    allFields: List<String>,
    selectedFields: List<String>,
    onToggleField: (String) -> Unit,
    onDismiss: () -> Unit,
    parameterMetadata: ParameterMetadata,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "選 2 個顯示在中央圓環旁——再點第 3 個會換掉最先選的那個。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val grouped = allFields.groupBy { parameterMetadata.groupFor(it) }
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    for (group in ParameterGroups.displayOrder) {
                        val fields = grouped[group]?.sorted() ?: continue
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel(group)
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.heightIn(max = 200.dp),
                            ) {
                                items(fields, key = { it }) { field ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .pointerInput(field) { detectTapGestures(onTap = { onToggleField(field) }) }
                                            .testTag("ring_legend_row_$field"),
                                    ) {
                                        Checkbox(
                                            checked = field in selectedFields,
                                            onCheckedChange = { onToggleField(field) },
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Text(
                                            parameterMetadata.displayName(field),
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("關閉") }
                }
            }
        }
    }
}
