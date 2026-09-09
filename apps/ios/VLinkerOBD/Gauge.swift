import SwiftUI

/// Custom-drawn analog needle gauge (SwiftUI `Canvas`, no charting library) — mirrors Android's
/// `Gauge.kt`: 240° sweep starting at 150° (0° = 3 o'clock, clockwise), 9 tick marks, an optional
/// redline arc, and a digital readout with an optional smaller secondary value (used for the GPS
/// speed comparison) anchored at the bottom-right of the main number.
struct Gauge: View {
    var value: Double
    var minValue: Double
    var maxValue: Double
    var label: String
    var unit: String
    var redlineStart: Double? = nil
    var secondaryValueText: String? = nil
    var trend: [Float] = []
    /// Lets a specific instance (the speed gauge) stand out from the rest, which all keep the
    /// default `.primary`/rounded look.
    var valueColor: Color = .primary
    var valueDesign: Font.Design = .rounded

    private static let startAngleDegrees = 150.0
    private static let sweepAngleDegrees = 240.0
    private static let tickCount = 9

    @State private var animatedValue: Double = 0

    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            let center = CGPoint(x: geo.size.width / 2, y: geo.size.height / 2)
            let radius = size / 2 - 10

            ZStack {
                Canvas { context, _ in
                    drawTrack(context: context, center: center, radius: radius)
                    if let redlineStart, redlineStart < maxValue {
                        drawRedline(context: context, center: center, radius: radius, redlineStart: redlineStart)
                    }
                    drawTicks(context: context, center: center, radius: radius)
                    drawNeedle(context: context, center: center, radius: radius, size: size)
                }

                VStack(spacing: 2) {
                    Text(label)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    ZStack(alignment: .bottomTrailing) {
                        Text(displayValueText)
                            .font(.system(size: size * 0.2, weight: .bold, design: valueDesign))
                            .monospacedDigit()
                            .foregroundStyle(valueColor)
                        if let secondaryValueText {
                            Text(secondaryValueText)
                                .font(.system(size: size * 0.08, weight: .semibold, design: .rounded))
                                .foregroundStyle(.secondary)
                                .offset(x: 2, y: -2)
                        }
                    }
                    Text(unit)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    if trend.count >= 2 {
                        TrendChart(values: trend, lineColor: .accentColor, compact: true)
                            .frame(width: size * 0.55, height: 18)
                    }
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .onChange(of: value) { newValue in
            withAnimation(.easeOut(duration: 0.25)) { animatedValue = newValue }
        }
        .onAppear { animatedValue = value }
    }

    private var displayValueText: String {
        animatedValue.isFinite ? "\(Int(animatedValue.rounded()))" : "--"
    }

    private func angle(for value: Double) -> Double {
        GaugeMath.valueToAngleDegrees(value: value, minValue: minValue, maxValue: maxValue, startAngleDegrees: Self.startAngleDegrees, sweepAngleDegrees: Self.sweepAngleDegrees)
    }

    private func point(center: CGPoint, radius: CGFloat, angleDegrees: Double) -> CGPoint {
        let radians = angleDegrees * .pi / 180
        return CGPoint(x: center.x + radius * cos(radians), y: center.y + radius * sin(radians))
    }

    private func arcPath(center: CGPoint, radius: CGFloat, fromDegrees: Double, toDegrees: Double) -> Path {
        var path = Path()
        let segments = max(2, Int(abs(toDegrees - fromDegrees) / 3))
        for i in 0...segments {
            let t = Double(i) / Double(segments)
            let deg = fromDegrees + (toDegrees - fromDegrees) * t
            let p = point(center: center, radius: radius, angleDegrees: deg)
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        return path
    }

    private func drawTrack(context: GraphicsContext, center: CGPoint, radius: CGFloat) {
        let path = arcPath(center: center, radius: radius, fromDegrees: Self.startAngleDegrees, toDegrees: Self.startAngleDegrees + Self.sweepAngleDegrees)
        context.stroke(path, with: .color(.secondary.opacity(0.25)), style: StrokeStyle(lineWidth: 8, lineCap: .round))
    }

    private func drawRedline(context: GraphicsContext, center: CGPoint, radius: CGFloat, redlineStart: Double) {
        let fromDeg = angle(for: redlineStart)
        let toDeg = Self.startAngleDegrees + Self.sweepAngleDegrees
        let path = arcPath(center: center, radius: radius, fromDegrees: fromDeg, toDegrees: toDeg)
        context.stroke(path, with: .color(.red.opacity(0.85)), style: StrokeStyle(lineWidth: 8, lineCap: .round))
    }

    private func drawTicks(context: GraphicsContext, center: CGPoint, radius: CGFloat) {
        for i in 0...Self.tickCount {
            let fraction = Double(i) / Double(Self.tickCount)
            let deg = Self.startAngleDegrees + fraction * Self.sweepAngleDegrees
            let outer = point(center: center, radius: radius, angleDegrees: deg)
            let inner = point(center: center, radius: radius - 10, angleDegrees: deg)
            var path = Path()
            path.move(to: inner)
            path.addLine(to: outer)
            context.stroke(path, with: .color(.secondary.opacity(0.6)), style: StrokeStyle(lineWidth: 2))
        }
    }

    private func drawNeedle(context: GraphicsContext, center: CGPoint, radius: CGFloat, size: CGFloat) {
        let deg = angle(for: animatedValue)
        let needleRadius = radius - 14
        let tip = point(center: center, radius: needleRadius, angleDegrees: deg)

        var glow = Path()
        glow.move(to: center)
        glow.addLine(to: tip)
        context.stroke(glow, with: .color(.accentColor.opacity(0.35)), style: StrokeStyle(lineWidth: 6, lineCap: .round))

        var core = Path()
        core.move(to: center)
        core.addLine(to: tip)
        context.stroke(core, with: .color(.accentColor), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))

        let hubRadius = size * 0.02
        context.fill(Path(ellipseIn: CGRect(x: center.x - hubRadius, y: center.y - hubRadius, width: hubRadius * 2, height: hubRadius * 2)), with: .color(.accentColor))
    }
}
