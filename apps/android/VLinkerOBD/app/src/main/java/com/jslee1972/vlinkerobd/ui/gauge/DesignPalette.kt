package com.jslee1972.vlinkerobd.ui.gauge

import androidx.compose.ui.graphics.Color

/**
 * Shared brand palette for the landscape driving-dynamics dashboard — kept in one place so the
 * ring gauge, cards, and page indicator all agree on the same colors. Ported from iOS's own
 * `DesignPalette` enum (DrivingDynamicsDashboardView.swift) so both platforms render this screen
 * with identical colors, not just identical layout.
 */
object DesignPalette {
    val accent = Color(0xFF22D3EE)
    val accentDim = Color(0xFF0E7490)
    val good = Color(0xFF22C55E)
    val warn = Color(0xFFF59E0B)
    val danger = Color(0xFFEF4444)

    /** The RPM ring's normal-state color — deliberately not [accent] (cyan), which the outer
     * ring's speed half uses; sharing one cyan for both rings made them read as "two identical
     * blue circles" with no way to tell which was which at a glance. */
    val rpmNormal = Color(0xFFA55AF7)

    /** The speed readout's own color — a warm instrument-cluster amber, distinct from the cyan
     * brand accent so the single most important number on screen doesn't blend into everything
     * else that's also tinted cyan (rpm ring, icons). */
    val speedOrange = Color(0xFFFF8A1E)

    val backgroundBase = Color(0xFF050609)
    val backgroundLift = Color(0xFF11161F)
}
