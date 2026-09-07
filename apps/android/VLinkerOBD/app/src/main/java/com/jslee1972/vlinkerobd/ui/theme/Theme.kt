package com.jslee1972.vlinkerobd.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ClusterColorScheme = darkColorScheme(
    primary = ClusterAccentCyan,
    onPrimary = ClusterBackground,
    primaryContainer = ClusterAccentCyanDim,
    onPrimaryContainer = ClusterOnBackground,
    secondary = ClusterAccentAmber,
    onSecondary = ClusterBackground,
    background = ClusterBackground,
    onBackground = ClusterOnBackground,
    surface = ClusterSurface,
    onSurface = ClusterOnBackground,
    surfaceVariant = ClusterSurfaceVariant,
    onSurfaceVariant = ClusterOnSurfaceMuted,
    outline = ClusterOutline,
    error = ClusterRedline,
    onError = ClusterOnBackground,
    errorContainer = ClusterRedlineContainer,
    onErrorContainer = ClusterRedline,
)

/** Dark instrument-cluster theme: near-black surfaces, cyan accent, Inter typography. */
@Composable
fun VLinkerObdTheme(content: @Composable () -> Unit) {
    // The dashboard is deliberately always-dark (instrument cluster look), independent of the
    // system light/dark setting.
    MaterialTheme(
        colorScheme = ClusterColorScheme,
        typography = ClusterTypography,
        content = content,
    )
}
