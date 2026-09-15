package com.jslee1972.vlinkerobd.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jslee1972.vlinkerobd.ui.gauge.DesignPalette

/**
 * A frosted, bordered stat tile used for both the ring gauge's side panels and the swipeable
 * group pages — the one visual building block the landscape driving-dynamics layout is built
 * from, so every reading looks consistent regardless of where it's shown. Ported from iOS's
 * GlassStatCard (DrivingDynamicsDashboardView.swift).
 */
@Composable
fun GlassStatCard(
    icon: ImageVector,
    label: String,
    value: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(12.dp))
            .padding(
                horizontal = if (compact) 12.dp else 20.dp,
                // Compact cards stack 4-deep in the ring gauge's side panels, where landscape
                // screens can be short on vertical room (see SidePanel's own comment) — trimmed
                // from the more generous 10.dp, then again from 6.dp and 4.dp after a real device
                // (2340x1080, 3x density) measured each card at 171px/57dp — 22dp too tall to fit
                // 4 in the 218dp actually available on that device — so 4 comfortably fit without
                // scrolling on more devices, not just the tallest ones.
                vertical = if (compact) 2.dp else 20.dp,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DesignPalette.accent,
            modifier = Modifier.size(if (compact) 13.dp else 22.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp)) {
            Text(
                text = value,
                fontSize = if (compact) 17.sp else 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                fontSize = if (compact) 11.sp else 15.sp,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
