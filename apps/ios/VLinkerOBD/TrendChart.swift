import SwiftUI

/// Auto-scaling sparkline (Canvas, no charting library) over the trailing history window —
/// mirrors Android's `TrendChart.kt`: scales to the window's own min/max (no fixed y-axis), fills
/// the area under the line at low opacity, and highlights the latest point.
struct TrendChart: View {
    var values: [Float]
    var lineColor: Color = .accentColor
    var compact: Bool = false

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let height = geo.size.height
            let minValue = Double(values.min() ?? 0)
            let maxValue = Double(values.max() ?? 1)

            let points: [CGPoint] = values.enumerated().map { index, value in
                let x = width * CGFloat(index) / CGFloat(max(values.count - 1, 1))
                let fraction = TrendChartMath.yFraction(value: Double(value), min: minValue, max: maxValue)
                let y = height * (1 - CGFloat(fraction))
                return CGPoint(x: x, y: y)
            }

            Canvas { context, _ in
                guard points.count >= 2 else { return }

                var linePath = Path()
                linePath.move(to: points[0])
                for p in points.dropFirst() { linePath.addLine(to: p) }

                var fillPath = linePath
                fillPath.addLine(to: CGPoint(x: points.last!.x, y: height))
                fillPath.addLine(to: CGPoint(x: points[0].x, y: height))
                fillPath.closeSubpath()

                context.fill(fillPath, with: .color(lineColor.opacity(0.12)))
                context.stroke(linePath, with: .color(lineColor), style: StrokeStyle(lineWidth: compact ? 1.2 : 2, lineCap: .round, lineJoin: .round))

                if let last = points.last {
                    let dotRadius: CGFloat = compact ? 1.5 : 2.5
                    context.fill(Path(ellipseIn: CGRect(x: last.x - dotRadius, y: last.y - dotRadius, width: dotRadius * 2, height: dotRadius * 2)), with: .color(lineColor))
                }
            }
        }
    }
}
