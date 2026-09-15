package com.jslee1972.vlinkerobd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * What the Activity renders while in Android's system Picture-in-Picture mode (see MainActivity's
 * `onPictureInPictureModeChanged` + `enterSpeedPipMode()`) — just the current speed, big enough to
 * read at a glance in the small floating window, so it survives switching to another app (e.g.
 * Maps) the same way iOS's FloatingSpeedPiP keeps a speed readout on top of other apps.
 */
@Composable
fun PipSpeedView(state: DashboardUiState) {
    val obdSpeedKph = state.vehicleData.speedKph
    val gpsSpeedKph = state.gpsSpeedKph
    val displaySpeed = obdSpeedKph ?: gpsSpeedKph?.let { Math.round(it) }
    val unitLabel = if (obdSpeedKph == null && gpsSpeedKph != null) "GPS訊號" else "km/h"

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = displaySpeed?.toString() ?: "--",
            fontSize = 64.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = unitLabel,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
