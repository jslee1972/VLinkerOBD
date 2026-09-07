package com.jslee1972.vlinkerobd.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jslee1972.vlinkerobd.R

/**
 * Inter (SIL Open Font License — see licenses/inter-font-OFL.txt), loaded as a single variable
 * font file with explicit weight instances rather than bundling one static .ttf per weight.
 */
@OptIn(ExperimentalTextApi::class)
val InterFontFamily = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter_variable, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.inter_variable, weight = FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

val ClusterTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal),
        bodyMedium = base.bodyMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal),
        bodySmall = base.bodySmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal),
        labelLarge = base.labelLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
    )
}

/** Big tabular-style numerals for the gauge center readout. */
val GaugeNumeralStyle = TextStyle(
    fontFamily = InterFontFamily,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 40.sp,
    letterSpacing = (-1).sp,
)
