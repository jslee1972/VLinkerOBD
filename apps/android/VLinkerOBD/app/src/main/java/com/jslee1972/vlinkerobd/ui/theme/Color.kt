package com.jslee1972.vlinkerobd.ui.theme

import androidx.compose.ui.graphics.Color

// Dark instrument-cluster palette.
val ClusterBackground = Color(0xFF0A0D12)
val ClusterSurface = Color(0xFF12151C)
val ClusterSurfaceVariant = Color(0xFF1B2029)
val ClusterOutline = Color(0xFF2A303C)

val ClusterAccentCyan = Color(0xFF22D3EE)
val ClusterAccentCyanDim = Color(0xFF0E7490)
val ClusterAccentAmber = Color(0xFFFBBF24)
val ClusterRedline = Color(0xFFEF4444)
val ClusterRedlineContainer = Color(0xFF3A1414)

val ClusterOnBackground = Color(0xFFE7ECF3)
val ClusterOnSurfaceMuted = Color(0xFF8B93A3)

/** Generic per-brand accent colors for the vehicle badge — never the manufacturer's own logo/mark. */
val BrandAccentColors: Map<String, Color> = mapOf(
    "Mazda" to Color(0xFFE0333F),
    "Ford" to Color(0xFF2C6FE0),
    "Honda" to Color(0xFFDB2C2C),
    "Citroen" to Color(0xFFC8102E),
    "Peugeot" to Color(0xFF1E5AA8),
)
val BrandAccentDefault = ClusterAccentCyan
