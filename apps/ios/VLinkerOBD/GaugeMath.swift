import Foundation

/// Pure geometry for the analog gauges: maps a value onto a needle angle. 0 degrees points to
/// 3 o'clock, angles increase clockwise — matches Android's `GaugeMath.kt` (same convention as
/// `CGContext`/`Canvas` arc drawing once translated into SwiftUI's Canvas coordinate space).
enum GaugeMath {
    static func valueToAngleDegrees(value: Double, minValue: Double, maxValue: Double, startAngleDegrees: Double, sweepAngleDegrees: Double) -> Double {
        let clamped = min(max(value, minValue), maxValue)
        let range = maxValue - minValue
        let fraction = range > 0 ? (clamped - minValue) / range : 0
        return startAngleDegrees + fraction * sweepAngleDegrees
    }
}

/// Pure geometry for the trend-line sparkline: maps a value onto a vertical fraction (0 = bottom,
/// 1 = top). Mirrors Android's `TrendChartMath.kt`.
enum TrendChartMath {
    static func yFraction(value: Double, min minValue: Double, max maxValue: Double) -> Double {
        let range = maxValue - minValue
        guard range > 0 else { return 0.5 }
        return min(max((value - minValue) / range, 0), 1)
    }
}
