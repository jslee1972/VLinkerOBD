package com.jslee1972.vlinkerobd.ui.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A minimal Canvas-drawn sparkline for a rolling window of recent PID samples — no charting
 * library, matching the hand-drawn approach used by [Gauge]. Auto-scales to the window's own
 * min/max so a fixed y-axis isn't needed for a "how is this trending right now" view.
 */
@Composable
fun TrendChart(
    values: List<Float>,
    label: String,
    unit: String,
    lineColor: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!compact) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val latest = values.lastOrNull()
                if (latest != null) {
                    Text(
                        text = "${latest.roundToInt()} $unit",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = lineColor,
                    )
                }
            }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(if (compact) 22.dp else 64.dp)) {
            if (values.size < 2) return@Canvas

            val min = values.min()
            val max = values.max()
            val stepX = size.width / (values.size - 1).toFloat()
            val verticalInset = size.height * 0.1f

            fun yOf(value: Float): Float {
                val fraction = TrendChartMath.yFraction(value, min, max)
                return size.height - verticalInset - fraction * (size.height - 2 * verticalInset)
            }

            val linePath = Path()
            values.forEachIndexed { index, value ->
                val x = index * stepX
                val y = yOf(value)
                if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            }

            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            val strokeWidth = if (compact) 2f else 4f
            drawPath(fillPath, color = lineColor.copy(alpha = 0.12f), style = Fill)
            drawPath(
                linePath,
                color = lineColor,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawCircle(color = lineColor, radius = strokeWidth * 0.7f, center = Offset(size.width, yOf(values.last())))
        }
    }
}
