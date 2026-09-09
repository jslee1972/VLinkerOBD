import SwiftUI
import UIKit

/// 行車動態介面 — landscape concentric-ring dashboard: center speed (+ GPS comparison) and RPM
/// ring in the middle, an outer ring split between engine temperature (left half) and fuel level
/// (right half), user-pinned parameters as glass-card side panels, and every other live-reading
/// group as its own swipeable page (a `TabView` page, not a scrolling list) instead of one long
/// scroll — this mode is meant to be read at a glance while driving, not scrolled through.
struct DrivingDynamicsDashboardView: View {
    @EnvironmentObject var controller: DashboardController

    @State private var page = 0
    @State private var showMenu = false
    @State private var showTroubleCodeDetail = false
    @State private var showDiagnostics = false
    @State private var showDevicePicker = false
    @State private var showEcuTest = false
    @State private var showCustomFieldPicker = false

    var body: some View {
        ZStack {
            DashboardBackground()

            // topBar/pageIndicator are laid out as ordinary siblings above and below the TabView
            // — not overlaid on top of it — so their buttons never share screen coordinates with
            // TabView(.page)'s own full-bleed pan gesture recognizer. An overlay here previously
            // meant a tap on the "..." menu button could occasionally get claimed by the page-swipe
            // gesture instead (any tiny finger movement reads as a drag), making it feel stuck.
            VStack(spacing: 0) {
                topBar
                TabView(selection: $page) {
                    mainGaugePage.tag(0)
                    ForEach(Array(mergedGroupPages.enumerated()), id: \.offset) { index, groups in
                        groupPage(groups: groups).tag(index + 1)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                pageIndicator
            }
        }
        .preferredColorScheme(.dark)
        // Driving-dynamics mode is meant to sit on a dash mount and be glanced at, not tapped —
        // don't let the screen auto-lock while it's the active view. Restored on disappear so the
        // idle timer doesn't stay disabled (and drain battery) once the user leaves this screen.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .sheet(isPresented: $showTroubleCodeDetail) {
            TroubleCodeDetailView(codes: controller.state.troubleCodes ?? [], brand: dtcBrand)
        }
        .sheet(isPresented: $showDiagnostics) { DiagnosticsView() }
        .sheet(isPresented: $showDevicePicker) { DevicePickerView() }
        .sheet(isPresented: $showEcuTest) { EcuSupportTestView() }
        .sheet(isPresented: $showCustomFieldPicker) { CustomFieldPickerView() }
        .confirmationDialog("選單", isPresented: $showMenu) {
            Button("選擇連線裝置") { showDevicePicker = true }
            Button("中斷連線") { controller.disconnect() }.disabled(!controller.state.isReady)
            Button("診斷主控台") { showDiagnostics = true }
            Button("ECU 支援測試") { showEcuTest = true }.disabled(!controller.state.isReady)
            Button("編輯自訂參數") { showCustomFieldPicker = true }
            Button("切換回標準模式") { controller.setDashboardMode(.standard) }
        }
    }

    private var dtcBrand: String? {
        controller.state.selectedBrand != universalBrand ? controller.state.selectedBrand : controller.state.detectedBrand
    }

    /// Groups sharing a page so a group with only a field or two doesn't get a whole screen to
    /// itself — see `GroupPaging`.
    private var mergedGroupPages: [[String]] {
        let fieldCounts = Dictionary(grouping: controller.state.liveReadings.keys) { controller.parameterMetadata.groupFor($0) }
            .mapValues(\.count)
        return GroupPaging.mergedPages(order: ParameterGroups.displayOrder, fieldCounts: fieldCounts)
    }

    // MARK: Top bar

    private var topBar: some View {
        HStack(spacing: 14) {
            HStack(spacing: 6) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 7, height: 7)
                    .shadow(color: statusColor.opacity(0.8), radius: 4)
                Text(controller.state.connectionLabel)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.white.opacity(0.75))
            }

            if let name = controller.state.connectedDeviceName, controller.state.isReady {
                Text(name)
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.4))
                    .lineLimit(1)
            }

            Spacer()

            Text(brandTitle)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white.opacity(0.55))
                .lineLimit(1)

            Spacer()

            Text(Date(), style: .time)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.75))

            let count = controller.state.troubleCodes?.count ?? 0
            if count > 0 {
                Button { showTroubleCodeDetail = true } label: {
                    HStack(spacing: 3) {
                        Image(systemName: "exclamationmark.triangle.fill")
                        Text("\(count)")
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.red)
                    .padding(.horizontal, 8)
                    .frame(height: 44)
                    .background(Color.red.opacity(0.15), in: Capsule())
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }

            Button { showMenu = true } label: {
                Image(systemName: "ellipsis")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.75))
                    .frame(width: 44, height: 44)
                    .background(.white.opacity(0.08), in: Circle())
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .padding(.bottom, 6)
        .background(Color.black.opacity(0.35))
    }

    private var brandTitle: String {
        let brand = controller.state.detectedBrand ?? "行車通"
        guard let vin = controller.state.detectedVin else { return brand }
        return "\(brand) · \(vin.suffix(6))"
    }

    private var statusColor: Color {
        if controller.state.errorMessage != nil { return .red }
        if controller.state.isReady { return DesignPalette.good }
        if controller.state.isScanning { return DesignPalette.accent }
        return .gray
    }

    /// Custom-styled dots (not the system `.page` index) so they land in the brand's cyan rather
    /// than the default white-on-white that gets lost against a light page background. The
    /// chevron below is a tap, not a drag, back to 標準模式 — a downward drag here sits right
    /// above the home indicator, where iOS's own Reachability edge gesture wins the touch before
    /// our view does (see the matching note in `StandardDashboardView.pageIndicator`).
    private var pageIndicator: some View {
        VStack(spacing: 4) {
            HStack(spacing: 6) {
                ForEach(0...mergedGroupPages.count, id: \.self) { index in
                    Capsule()
                        .fill(index == page ? DesignPalette.accent : Color.white.opacity(0.25))
                        .frame(width: index == page ? 16 : 6, height: 6)
                        .animation(.easeOut(duration: 0.2), value: page)
                }
            }
            Button { controller.setDashboardMode(.standard) } label: {
                Label("返回標準模式", systemImage: "chevron.down")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.35))
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 10)
    }

    // MARK: Main gauge page

    private var mainGaugePage: some View {
        HStack(spacing: 0) {
            sidePanel(fields: leftFields).frame(maxWidth: .infinity)

            DrivingRingGauge(
                speedKph: Double(controller.state.vehicleData.speedKph ?? 0),
                gpsSpeedKph: controller.state.gpsSpeedKph,
                rpm: Double(controller.state.vehicleData.rpm ?? 0),
                fuelPercent: numericValue(field: "fuelLevelPercent"),
                coolantTempC: numericValue(field: "coolantTempC")
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.vertical, 12)

            sidePanel(fields: rightFields).frame(maxWidth: .infinity)
        }
        .padding(.vertical, 12)
    }

    /// Capped to 8 (4 per side) — the side panels sit in the same glanceable-while-driving frame
    /// as the ring gauge, so more than a handful of cards per side just gets cramped and undoes
    /// the point of a mode built to be read at a glance. The full set the user pinned is still
    /// shown in full in the standard mode's 自訂 section.
    private var pinnedFieldsSorted: [String] {
        Array(controller.state.selectedCustomFields.sorted().prefix(8))
    }

    private var leftFields: [String] { Array(pinnedFieldsSorted.prefix(4)) }
    private var rightFields: [String] { Array(pinnedFieldsSorted.dropFirst(4)) }

    private func sidePanel(fields: [String]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if fields.isEmpty {
                Text("點選單「編輯自訂參數」\n新增想觀察的欄位")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.35))
            } else {
                ForEach(fields, id: \.self) { field in
                    GlassStatCard(
                        icon: iconName(for: field),
                        label: controller.parameterMetadata.displayName(field),
                        value: controller.state.liveReadings[field] ?? "--",
                        compact: true
                    )
                }
            }
            Spacer()
        }
        .padding(.horizontal, 18)
    }

    private func numericValue(field: String) -> Double? {
        guard let text = controller.state.liveReadings[field] else { return nil }
        let numberPart = text.split(separator: " ").first.map(String.init) ?? text
        return Double(numberPart)
    }

    // MARK: Group pages (swipe between blocks instead of scrolling)

    /// Renders one or more groups (merged by `GroupPaging` when they're individually small) on a
    /// single page, each with its own subheader, sharing one scroll region.
    private func groupPage(groups: [String]) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                ForEach(groups, id: \.self) { group in
                    let fields = controller.state.liveReadings.keys.filter { controller.parameterMetadata.groupFor($0) == group }.sorted()
                    VStack(alignment: .leading, spacing: 12) {
                        Text(group).font(.title3.weight(.bold)).foregroundStyle(.white)
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12), GridItem(.flexible())], spacing: 12) {
                            ForEach(fields, id: \.self) { field in
                                GlassStatCard(
                                    icon: iconName(for: field),
                                    label: controller.parameterMetadata.displayName(field),
                                    value: controller.state.liveReadings[field] ?? "--",
                                    compact: false
                                )
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            .padding(.bottom, 40)
        }
    }
}

private func iconName(for field: String) -> String {
    let lower = field.lowercased()
    if lower.contains("temp") { return "thermometer" }
    if lower.contains("voltage") || lower.contains("battery") { return "bolt.fill" }
    if lower.contains("fuel") { return "fuelpump.fill" }
    if lower.contains("pressure") || lower.contains("rpm") || lower.contains("torque") || lower.contains("timing") || lower.contains("advance") { return "speedometer" }
    if lower.contains("distance") || lower.contains("range") { return "map.fill" }
    if lower.contains("duration") { return "clock.fill" }
    return "waveform"
}

/// Shared brand palette for the driving-dynamics layout — kept in one place so the gauge, cards,
/// and page indicator all agree on the same cyan the app icon uses.
enum DesignPalette {
    static let accent = Color(red: 0x22 / 255, green: 0xD3 / 255, blue: 0xEE / 255)
    static let accentDim = Color(red: 0x0E / 255, green: 0x74 / 255, blue: 0x90 / 255)
    static let good = Color(red: 0x22 / 255, green: 0xC5 / 255, blue: 0x5E / 255)
    static let warn = Color(red: 0xF5 / 255, green: 0x9E / 255, blue: 0x0B / 255)
    static let danger = Color(red: 0xEF / 255, green: 0x44 / 255, blue: 0x44 / 255)
    /// The RPM ring's normal-state color — deliberately not `accent` (cyan), which the outer
    /// ring's temperature half uses; sharing one cyan for both rings made them read as "two
    /// identical blue circles" with no way to tell which was which at a glance.
    static let rpmNormal = Color(red: 0xA5 / 255, green: 0x5A / 255, blue: 0xF7 / 255)
    /// The speed readout's own color in both layouts — a warm instrument-cluster amber, distinct
    /// from the cyan brand accent so the single most important number on screen doesn't blend into
    /// everything else that's also tinted cyan (rpm ring, icons, needle).
    static let speedOrange = Color(red: 0xFF / 255, green: 0x8A / 255, blue: 0x1E / 255)
}

/// Full-screen backdrop: a dark radial lift behind the gauge (echoing the app icon's own
/// background treatment) over a near-black base, instead of flat `Color.black` — reads as
/// deliberately designed rather than a placeholder background.
struct DashboardBackground: View {
    private static let base = Color(red: 0x05 / 255, green: 0x06 / 255, blue: 0x09 / 255)
    private static let lift = Color(red: 0x11 / 255, green: 0x16 / 255, blue: 0x1F / 255)

    var body: some View {
        ZStack {
            Self.base.ignoresSafeArea()
            RadialGradient(colors: [Self.lift, Self.base], center: .center, startRadius: 0, endRadius: 520)
                .ignoresSafeArea()
        }
    }
}

/// A frosted, bordered stat tile used for both the side panels and the swipeable group pages —
/// the one visual building block the whole redesigned layout is built from, so every reading
/// looks consistent regardless of where it's shown.
struct GlassStatCard: View {
    var icon: String
    var label: String
    var value: String
    var compact: Bool

    var body: some View {
        HStack(spacing: compact ? 10 : 10) {
            Image(systemName: icon)
                .font(.system(size: compact ? 15 : 14))
                .foregroundStyle(DesignPalette.accent)
                .frame(width: compact ? 22 : 24)

            VStack(alignment: .leading, spacing: 2) {
                Text(value)
                    .font(.system(compact ? .title2 : .title3, design: .rounded).weight(.bold))
                    .foregroundStyle(.white)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(label)
                    .font(.system(size: compact ? 12 : 11))
                    .foregroundStyle(.white.opacity(0.5))
                    .lineLimit(1)
            }
            if !compact { Spacer(minLength: 0) }
        }
        .padding(.horizontal, compact ? 12 : 12)
        .padding(.vertical, compact ? 10 : 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(.white.opacity(0.08), lineWidth: 1)
        )
    }
}

/// The concentric center dial for the driving-dynamics layout: an outer ring split between engine
/// temperature (left half) and fuel level (right half), a glowing RPM ring just inside it (cyan,
/// turning amber past 5500 and red past the 6500 rpm redline — same threshold as the standard
/// gauge), and the digital speed readout (with the GPS comparison value) at the center.
private struct DrivingRingGauge: View {
    var speedKph: Double
    var gpsSpeedKph: Float?
    var rpm: Double
    var fuelPercent: Double?
    var coolantTempC: Double?

    private let startAngle = 150.0
    private let sweepAngle = 240.0
    private let redline = 6500.0

    @State private var animatedSpeed: Double = 0
    @State private var animatedRpm: Double = 0

    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            let center = CGPoint(x: geo.size.width / 2, y: geo.size.height / 2)
            let outerRadius = size / 2 - 10
            let rpmRadius = outerRadius - 20

            ZStack {
                Canvas { ctx, _ in
                    drawOuterRing(ctx: ctx, center: center, radius: outerRadius)
                    drawRpmRing(ctx: ctx, center: center, radius: rpmRadius)
                }
                VStack(spacing: 3) {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text("\(Int(animatedSpeed))")
                            .font(.system(size: size * 0.26, weight: .heavy, design: .default))
                            .monospacedDigit()
                            .foregroundStyle(DesignPalette.speedOrange)
                            .shadow(color: DesignPalette.speedOrange.opacity(0.45), radius: 14)
                    }
                    if let gpsSpeedKph {
                        Text("GPS \(Int(gpsSpeedKph)) km/h")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(.white.opacity(0.45))
                    } else {
                        Text("km/h")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.white.opacity(0.45))
                    }

                    Text("\(Int(animatedRpm)) RPM")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(rpm >= redline ? DesignPalette.danger : .white.opacity(0.65))
                        .padding(.top, 2)

                    legend
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .onChange(of: speedKph) { newValue in withAnimation(.easeOut(duration: 0.3)) { animatedSpeed = newValue } }
        .onChange(of: rpm) { newValue in withAnimation(.easeOut(duration: 0.2)) { animatedRpm = newValue } }
        .onAppear { animatedSpeed = speedKph; animatedRpm = rpm }
    }

    /// Small color-key row so the outer ring's two halves don't rely on the viewer remembering
    /// "blue = temp, orange = fuel" from memory alone.
    private var legend: some View {
        HStack(spacing: 12) {
            legendItem(color: temperatureColor, label: "水溫", value: coolantTempC.map { "\(Int($0))°C" } ?? "--")
            legendItem(color: fuelColor, label: "油量", value: fuelPercent.map { "\(Int($0))%" } ?? "--")
        }
        .padding(.top, 6)
    }

    private var temperatureColor: Color {
        guard let coolantTempC else { return .white.opacity(0.3) }
        return coolantTempC > 105 ? DesignPalette.danger : DesignPalette.accent
    }

    private var fuelColor: Color {
        guard let fuelPercent else { return .white.opacity(0.3) }
        return fuelPercent < 15 ? DesignPalette.danger : DesignPalette.warn
    }

    private func legendItem(color: Color, label: String, value: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 6, height: 6)
            Text("\(label) \(value)").font(.caption2).foregroundStyle(.white.opacity(0.55))
        }
    }

    private func angle(for value: Double, maxValue: Double) -> Double {
        GaugeMath.valueToAngleDegrees(value: value, minValue: 0, maxValue: maxValue, startAngleDegrees: startAngle, sweepAngleDegrees: sweepAngle)
    }

    private func point(center: CGPoint, radius: CGFloat, deg: Double) -> CGPoint {
        let radians = deg * .pi / 180
        return CGPoint(x: center.x + radius * cos(radians), y: center.y + radius * sin(radians))
    }

    private func arcPath(center: CGPoint, radius: CGFloat, fromDeg: Double, toDeg: Double) -> Path {
        var path = Path()
        let segments = max(2, Int(abs(toDeg - fromDeg) / 3))
        for i in 0...segments {
            let t = Double(i) / Double(segments)
            let deg = fromDeg + (toDeg - fromDeg) * t
            let p = point(center: center, radius: radius, deg: deg)
            if i == 0 { path.move(to: p) } else { path.addLine(to: p) }
        }
        return path
    }

    private func drawTrack(ctx: GraphicsContext, center: CGPoint, radius: CGFloat, width: CGFloat, color: Color) {
        let path = arcPath(center: center, radius: radius, fromDeg: startAngle, toDeg: startAngle + sweepAngle)
        ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }

    /// Draws the arc twice — a wide, faint pass first for a soft glow, then a crisp core on top —
    /// instead of relying on a real-time blur filter (cheaper, and predictable at 60fps).
    private func drawGlowArc(ctx: GraphicsContext, center: CGPoint, radius: CGFloat, width: CGFloat, fromDeg: Double, toDeg: Double, color: Color) {
        guard toDeg > fromDeg else { return }
        let path = arcPath(center: center, radius: radius, fromDeg: fromDeg, toDeg: toDeg)
        ctx.stroke(path, with: .color(color.opacity(0.35)), style: StrokeStyle(lineWidth: width * 2.2, lineCap: .round))
        ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }

    private func drawRpmRing(ctx: GraphicsContext, center: CGPoint, radius: CGFloat) {
        drawTrack(ctx: ctx, center: center, radius: radius, width: 16, color: .white.opacity(0.08))
        let clamped = min(rpm, 8000)
        let color: Color = rpm >= redline ? DesignPalette.danger : (rpm >= redline * 0.82 ? DesignPalette.warn : DesignPalette.rpmNormal)
        drawGlowArc(ctx: ctx, center: center, radius: radius, width: 16, fromDeg: startAngle, toDeg: angle(for: clamped, maxValue: 8000), color: color)
    }

    /// Left half of the sweep (150°→270°) is the coolant-temp gauge (cyan = normal, red = hot);
    /// right half (270°→390°) is the fuel gauge (amber, red once low).
    private func drawOuterRing(ctx: GraphicsContext, center: CGPoint, radius: CGFloat) {
        let mid = startAngle + sweepAngle / 2
        drawTrack(ctx: ctx, center: center, radius: radius, width: 10, color: .white.opacity(0.06))

        if let coolantTempC {
            let fraction = min(max((coolantTempC + 20) / 140, 0), 1) // -20°C..120°C mapped to the half-ring
            let toDeg = startAngle + fraction * (mid - startAngle)
            drawGlowArc(ctx: ctx, center: center, radius: radius, width: 10, fromDeg: startAngle, toDeg: toDeg, color: temperatureColor)
        }
        if let fuelPercent {
            let fraction = min(max(fuelPercent / 100, 0), 1)
            let toDeg = mid + fraction * (startAngle + sweepAngle - mid)
            drawGlowArc(ctx: ctx, center: center, radius: radius, width: 10, fromDeg: mid, toDeg: toDeg, color: fuelColor)
        }
    }
}
