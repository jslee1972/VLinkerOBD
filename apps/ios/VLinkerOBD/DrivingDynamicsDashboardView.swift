import SwiftUI
import UIKit

/// 行車動態介面 — 行車通's one screen: an outer speed ring and an inner RPM ring, each with a
/// 5-second peak-hold marker, center readouts the user picks two of, user-pinned parameters as
/// glass-card side panels, and every other live-reading group as its own swipeable page (a
/// `TabView` page, not a scrolling list) instead of one long scroll — meant to be read at a
/// glance while driving, not scrolled through.
struct DrivingDynamicsDashboardView: View {
    @EnvironmentObject var controller: DashboardController

    @State private var page = 0
    @State private var showMenu = false
    @State private var showTroubleCodeDetail = false
    @State private var showDiagnostics = false
    @State private var showDevicePicker = false
    @State private var showEcuTest = false
    @State private var showCustomFieldPicker = false
    @State private var showRingLegendPicker = false
    /// True after a long-press on a pinned side-panel card — shows a delete badge on every pinned
    /// card (iOS Home Screen jiggle-mode style) so a card can be unpinned with one tap instead of
    /// requiring the full field-picker sheet. Cleared by tapping the ring/background, or by
    /// tapping a card outside its delete badge.
    @State private var isEditingCustomFields = false
    /// Owned by `RootView`, not here — see `FloatingSpeedPiPController`'s doc comment for why.
    @EnvironmentObject var pipController: FloatingSpeedPiPController

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
        // Screen-awake handling lives in `RootView` (unconditional now that this is the app's
        // only screen), not here.
        .sheet(isPresented: $showTroubleCodeDetail) {
            TroubleCodeDetailView(codes: controller.state.troubleCodes ?? [], brand: dtcBrand)
        }
        .sheet(isPresented: $showDiagnostics) { DiagnosticsView() }
        .sheet(isPresented: $showDevicePicker) { DevicePickerView() }
        .sheet(isPresented: $showEcuTest) { EcuSupportTestView() }
        .sheet(isPresented: $showCustomFieldPicker) { CustomFieldPickerView() }
        .sheet(isPresented: $showRingLegendPicker) { RingLegendFieldPickerView() }
        .alert("無法開啟浮動視窗", isPresented: Binding(
            get: { pipController.lastError != nil },
            set: { if !$0 { pipController.lastError = nil } }
        )) {
            Button("關閉") { pipController.lastError = nil }
        } message: {
            Text(pipController.lastError ?? "")
        }
        .confirmationDialog("選單", isPresented: $showMenu) {
            Button("選擇連線裝置") { showDevicePicker = true }
            Button("中斷連線") { controller.disconnect() }.disabled(!controller.state.isReady)
            Button("診斷主控台") { showDiagnostics = true }
            Button("ECU 支援測試") { showEcuTest = true }.disabled(!controller.state.isReady)
            Button("編輯自訂參數") { showCustomFieldPicker = true }
            Button("選擇中央顯示參數") { showRingLegendPicker = true }
            Button(pipController.isActive ? "關閉浮動車速視窗" : "開啟浮動車速視窗") {
                if pipController.isActive {
                    pipController.stop()
                } else {
                    pipController.start(with: controller)
                }
            }
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
    /// than the default white-on-white that gets lost against a light page background.
    private var pageIndicator: some View {
        HStack(spacing: 6) {
            ForEach(0...mergedGroupPages.count, id: \.self) { index in
                Capsule()
                    .fill(index == page ? DesignPalette.accent : Color.white.opacity(0.25))
                    .frame(width: index == page ? 16 : 6, height: 6)
                    .animation(.easeOut(duration: 0.2), value: page)
            }
        }
        .padding(.vertical, 10)
    }

    // MARK: Main gauge page

    private var mainGaugePage: some View {
        HStack(spacing: 0) {
            sidePanel(fields: leftFields, isLeftSide: true).frame(maxWidth: .infinity)

            DrivingRingGauge(
                obdSpeedKph: controller.state.vehicleData.speedKph.map(Double.init),
                gpsSpeedKph: controller.state.gpsSpeedKph,
                rpm: Double(controller.state.vehicleData.rpm ?? 0)
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
            .onTapGesture {
                guard isEditingCustomFields else { return }
                withAnimation(.easeOut(duration: 0.2)) { isEditingCustomFields = false }
            }

            sidePanel(fields: rightFields, isLeftSide: false).frame(maxWidth: .infinity)
        }
        .padding(.vertical, 12)
    }

    /// Capped to 8 (4 per side) — the side panels sit in the same glanceable-while-driving frame
    /// as the ring gauge, so more than a handful of cards per side just gets cramped and undoes
    /// the point of a mode built to be read at a glance. In the user's own drag-to-reorder order
    /// now (see `sidePanel`), not alphabetical — `selectedCustomFields` is itself the persisted
    /// display order (see `DashboardState.selectedCustomFields`'s own doc comment).
    private var pinnedFields: [String] {
        Array(controller.state.selectedCustomFields.prefix(8))
    }

    private var leftFields: [String] { Array(pinnedFields.prefix(4)) }
    private var rightFields: [String] { Array(pinnedFields.dropFirst(4)) }

    /// Long-press-then-drag to reorder within a side (`.draggable`/`.dropDestination` — the same
    /// system gesture iOS uses for Home Screen icons: hold, the card lifts, drag it onto another
    /// card to swap positions), double-tap to jump straight to the field picker instead of going
    /// via the "..." menu's "編輯自訂參數", and — also Home-Screen-style — the same long-press
    /// additionally puts every pinned card into an edit state with a delete badge, so a single
    /// card can be unpinned with one tap without opening the picker sheet at all. Moving a card
    /// *between* the left/right panels is a tap on a second, arrow-shaped edit-mode badge instead
    /// of a drag — the two panels sit either side of the ring gauge inside a `TabView(.page)`, and
    /// a drag spanning that full width is exactly the kind of wide horizontal pan the TabView's
    /// own swipe-to-change-page gesture can end up claiming instead of the dragged card, which
    /// made cross-panel dragging unreliable; a tap has no such gesture-arbitration ambiguity.
    private func sidePanel(fields: [String], isLeftSide: Bool) -> some View {
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
                    .overlay(alignment: .topLeading) {
                        if isEditingCustomFields {
                            Button {
                                withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                                    controller.toggleCustomField(field)
                                }
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .font(.system(size: 20))
                                    .foregroundStyle(.white, DesignPalette.danger)
                                    .background(Circle().fill(Color.black).padding(2))
                            }
                            .buttonStyle(.plain)
                            .offset(x: -8, y: -8)
                            .transition(.scale.combined(with: .opacity))
                        }
                    }
                    .overlay(alignment: .topTrailing) {
                        // A right-panel card can always move left (left always has room/cards
                        // whenever a right panel card exists at all). A left-panel card can only
                        // move right once there's an actual right group to move into — with 4 or
                        // fewer pinned fields total, every field already renders on the left and
                        // there's nothing to overflow into a right slot, so the arrow would be a
                        // dead tap.
                        if isEditingCustomFields, isLeftSide == false || pinnedFields.count > 4 {
                            Button {
                                withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                                    controller.moveCustomField(field, toIndex: isLeftSide ? 4 : 3)
                                }
                            } label: {
                                Image(systemName: isLeftSide ? "arrow.right.circle.fill" : "arrow.left.circle.fill")
                                    .font(.system(size: 20))
                                    .foregroundStyle(.white, DesignPalette.accent)
                                    .background(Circle().fill(Color.black).padding(2))
                            }
                            .buttonStyle(.plain)
                            .offset(x: 8, y: -8)
                            .transition(.scale.combined(with: .opacity))
                        }
                    }
                    .draggable(field)
                    .dropDestination(for: String.self) { droppedFields, _ in
                        guard let dropped = droppedFields.first else { return false }
                        controller.moveCustomField(dropped, before: field)
                        return true
                    }
                    .simultaneousGesture(
                        LongPressGesture(minimumDuration: 0.45).onEnded { _ in
                            guard !isEditingCustomFields else { return }
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { isEditingCustomFields = true }
                        }
                    )
                    .onTapGesture(count: 2) {
                        guard !isEditingCustomFields else { return }
                        showCustomFieldPicker = true
                    }
                    .onTapGesture {
                        guard isEditingCustomFields else { return }
                        withAnimation(.easeOut(duration: 0.2)) { isEditingCustomFields = false }
                    }
                }
            }
            Spacer()
        }
        .padding(.horizontal, 18)
    }

    // MARK: Group pages (swipe between blocks instead of scrolling)

    /// Renders one or more groups (merged by `GroupPaging` when they're individually small) on a
    /// single page, each with its own subheader, sharing one scroll region.
    private func groupPage(groups: [String]) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                ForEach(groups, id: \.self) { group in
                    let fields = controller.state.liveReadings.keys.filter { controller.parameterMetadata.groupFor($0) == group }.sorted()
                    VStack(alignment: .leading, spacing: 16) {
                        Text(group).font(.title2.weight(.bold)).foregroundStyle(.white)
                        LazyVGrid(columns: [GridItem(.flexible(), spacing: 18), GridItem(.flexible(), spacing: 18), GridItem(.flexible())], spacing: 18) {
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
            .padding(.horizontal, 28)
            .padding(.top, 24)
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
        HStack(spacing: compact ? 10 : 14) {
            Image(systemName: icon)
                .font(.system(size: compact ? 15 : 22))
                .foregroundStyle(DesignPalette.accent)
                .frame(width: compact ? 22 : 30)

            VStack(alignment: .leading, spacing: compact ? 2 : 4) {
                Text(value)
                    // `.title2`, not `.title`/`.largeTitle` — a low `minimumScaleFactor` combined
                    // with a large base size meant short values ("87.0 度") rendered near full
                    // size while longer ones with a multi-character unit ("21.0 公里/公升")
                    // shrank much further to fit one line, so cards visibly disagreed on text
                    // size next to each other. A smaller base size needs far less shrinking for
                    // the longest realistic values, so the *range* between "just fits" and "short
                    // string, full size" is much narrower — the floor is raised to match.
                    .font(.system(.title2, design: .rounded).weight(.bold))
                    .foregroundStyle(.white)
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                Text(label)
                    .font(.system(size: compact ? 12 : 15))
                    .foregroundStyle(.white.opacity(0.5))
                    .lineLimit(1)
            }
            if !compact { Spacer(minLength: 0) }
        }
        .padding(.horizontal, compact ? 12 : 20)
        .padding(.vertical, compact ? 10 : 20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(.white.opacity(0.08), lineWidth: 1)
        )
    }
}

/// The concentric center dial: an outer speed ring (orange, matching the center digit) and an
/// inner RPM ring (purple, turning amber past 5500 and red past the 6500 rpm redline), each with
/// its own 5-second peak-hold marker — an arrow + number riding the ring at the highest value seen
/// in roughly the last 5 seconds, so a glance shows not just "how fast now" but "how fast a moment
/// ago," the way a lap-peak tachometer works. The center readout is the two fields the user picked
/// (`RingLegendFieldPickerView`), not a fixed pair — this vehicle's ECU has never returned a
/// fuel-level reading, so hardcoding 油量 here forever would've just stayed blank.
private struct DrivingRingGauge: View {
    @EnvironmentObject var controller: DashboardController

    /// nil whenever the OBD speed PID has gone stale (BLE dropout, weak signal, ECU not
    /// responding) — see `speedKph` below for the GPS fallback this makes possible.
    var obdSpeedKph: Double?
    var gpsSpeedKph: Float?
    var rpm: Double

    /// The value the ring/digit/peak-hold actually track: OBD speed when it's available, GPS
    /// speed when it isn't — rather than dropping to 0 and reading as "stopped" during a signal
    /// gap that has nothing to do with the car's actual speed.
    private var speedKph: Double {
        obdSpeedKph ?? gpsSpeedKph.map(Double.init) ?? 0
    }

    private var isGpsFallback: Bool { obdSpeedKph == nil && gpsSpeedKph != nil }

    private let startAngle = 150.0
    private let sweepAngle = 240.0
    private let redline = 6500.0
    private let maxSpeed = 220.0
    private let maxRpm = 8000.0

    @State private var animatedSpeed: Double = 0
    @State private var animatedRpm: Double = 0
    /// Highest value seen since the last 5-second reset — see the peak-hold marker doc above.
    @State private var speedPeak: Double = 0
    @State private var rpmPeak: Double = 0

    private let peakResetTimer = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            let center = CGPoint(x: geo.size.width / 2, y: geo.size.height / 2)
            let outerRadius = size / 2 - 10
            let rpmRadius = outerRadius - 22

            ZStack {
                Canvas { ctx, _ in
                    drawSpeedRing(ctx: ctx, center: center, radius: outerRadius)
                    drawRpmRing(ctx: ctx, center: center, radius: rpmRadius)
                }
                VStack(spacing: 4) {
                    Text("\(Int(animatedSpeed))")
                        .font(.system(size: size * 0.3, weight: .heavy, design: .default))
                        .monospacedDigit()
                        .foregroundStyle(DesignPalette.speedOrange)
                        .shadow(color: DesignPalette.speedOrange.opacity(0.45), radius: 14)
                    if isGpsFallback {
                        // The big number above is already the GPS reading here (OBD speed went
                        // stale), so this labels the source instead of repeating the same number
                        // a second time the way the two-source comparison line below does.
                        Text("GPS 訊號（OBD 中斷）")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(DesignPalette.warn)
                    } else if let gpsSpeedKph {
                        Text("GPS \(Int(gpsSpeedKph)) km/h")
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(.white.opacity(0.55))
                    } else {
                        Text("km/h")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.white.opacity(0.5))
                    }
                    legend
                }
                // Nudges the whole readout down from dead-center, away from the crowded upper arc
                // where the speed/RPM rings and their peak markers already sit.
                .offset(y: size * 0.08)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .onChange(of: speedKph) { newValue in
            withAnimation(.easeOut(duration: 0.3)) { animatedSpeed = newValue }
            if newValue > speedPeak { speedPeak = newValue }
        }
        .onChange(of: rpm) { newValue in
            withAnimation(.easeOut(duration: 0.2)) { animatedRpm = newValue }
            if newValue > rpmPeak { rpmPeak = newValue }
        }
        .onReceive(peakResetTimer) { _ in
            withAnimation(.easeOut(duration: 0.4)) {
                speedPeak = speedKph
                rpmPeak = rpm
            }
        }
        .onAppear {
            animatedSpeed = speedKph; animatedRpm = rpm
            speedPeak = speedKph; rpmPeak = rpm
        }
    }

    /// The user's own two picks (`RingLegendFieldPickerView`) — plain readouts, same building
    /// block used everywhere else live values are shown, just without the ring's own dedicated
    /// value/unit formatting since these are arbitrary fields with their own units already baked
    /// into `liveReadings`' formatted text.
    private var legend: some View {
        HStack(spacing: 16) {
            ForEach(controller.state.ringLegendFields, id: \.self) { field in
                legendItem(
                    label: controller.parameterMetadata.displayName(field),
                    value: controller.state.liveReadings[field] ?? "--"
                )
            }
        }
        .padding(.top, 4)
    }

    private var rpmColor: Color {
        rpm >= redline ? DesignPalette.danger : (rpm >= redline * 0.82 ? DesignPalette.warn : DesignPalette.rpmNormal)
    }

    private func legendItem(label: String, value: String) -> some View {
        VStack(spacing: 1) {
            Text(value).font(.footnote.weight(.bold)).foregroundStyle(.white).monospacedDigit()
            Text(label).font(.caption2).foregroundStyle(.white.opacity(0.5))
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

    /// A single crisp, round-capped stroke — no wide, faint pass underneath for a soft glow. That
    /// halo was exactly the "發散" (diffuse) edge the rings were asked to lose; a plain stroke
    /// reads far more like an instrument needle.
    private func drawArc(ctx: GraphicsContext, center: CGPoint, radius: CGFloat, width: CGFloat, fromDeg: Double, toDeg: Double, color: Color) {
        guard toDeg > fromDeg else { return }
        let path = arcPath(center: center, radius: radius, fromDeg: fromDeg, toDeg: toDeg)
        ctx.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: width, lineCap: .round))
    }

    /// Short radial marks at even intervals across a span — the same graduated-scale idea as
    /// `Gauge.drawTicks`, so a glance at the fill's position against the marks gives a rough
    /// number even before reading the digital readout in the center.
    private func drawTicks(ctx: GraphicsContext, center: CGPoint, radius: CGFloat, fromDeg: Double, toDeg: Double, count: Int) {
        guard count > 0 else { return }
        for i in 0...count {
            let t = Double(i) / Double(count)
            let deg = fromDeg + (toDeg - fromDeg) * t
            let outer = point(center: center, radius: radius + 4, deg: deg)
            let inner = point(center: center, radius: radius - 4, deg: deg)
            var path = Path()
            path.move(to: inner)
            path.addLine(to: outer)
            ctx.stroke(path, with: .color(.white.opacity(0.4)), style: StrokeStyle(lineWidth: 1.5))
        }
    }

    /// A small filled triangle straddling the ring itself at `value`'s angle, tip pointing back
    /// toward the arc, plus the value itself as upright text trailing behind the triangle's own
    /// tail (its wide base edge) — the 5-second peak-hold marker. `context.draw(_:at:)` always
    /// draws text upright regardless of the angle it's placed at, which is what keeps the number
    /// legible all the way around the ring instead of ending up sideways or upside-down on the
    /// left half of the sweep.
    /// The arrow rides tangent to the ring — pointing the way the fill sweeps as the value grows,
    /// not out away from the ring — with its number sitting behind the shape (opposite the tip,
    /// past the base edge), not out past the pointed tip, the way a flag trails behind its pole
    /// rather than floating beyond the point it's pointing at.
    private func drawPeakMarker(ctx: GraphicsContext, center: CGPoint, radius: CGFloat, value: Double, maxValue: Double, color: Color, suffix: String, ringWidth: CGFloat) {
        guard value > 0 else { return }
        let deg = angle(for: min(value, maxValue), maxValue: maxValue)
        let rad = deg * .pi / 180
        // Unit vectors at this point on the ring: `radial` points away from center. `back` points
        // the way the fill *came from* (opposite the direction the arc sweeps as the value
        // increases) — the arrow points back along the ring rather than forward, per feedback
        // that "forward" read as pointing where the value is heading, not where the peak sits.
        let radial = CGPoint(x: cos(rad), y: sin(rad))
        let back = CGPoint(x: sin(rad), y: -cos(rad))
        // Centered directly on the ring's own stroke (not offset outside it), so the marker
        // visibly straddles the line at a glance instead of reading as a separate mark floating
        // just past its edge.
        let base = CGPoint(x: center.x + radius * radial.x, y: center.y + radius * radial.y)

        func offset(_ p: CGPoint, _ v: CGPoint, _ distance: CGFloat) -> CGPoint {
            CGPoint(x: p.x + v.x * distance, y: p.y + v.y * distance)
        }

        // A bit wider than the ring's own stroke so the triangle's base fully spans (and slightly
        // overhangs) the line it's marking, rather than being narrower than a thick ring like the
        // rpm one and reading as a thin sliver against it.
        let halfWidth = ringWidth / 2 + 2
        let tip = offset(base, back, 9)
        let backCenter = offset(base, back, -3)
        let backLeft = offset(backCenter, radial, halfWidth)
        let backRight = offset(backCenter, radial, -halfWidth)
        var arrow = Path()
        arrow.move(to: tip)
        arrow.addLine(to: backLeft)
        arrow.addLine(to: backRight)
        arrow.closeSubpath()
        ctx.fill(arrow, with: .color(color))

        // On the opposite side from the tip — past the triangle's own wide base edge, in the
        // forward direction — so the number trails behind the arrow shape itself rather than
        // sitting out past the point it's pointing at.
        let labelPoint = offset(backCenter, back, -8)
        let label = ctx.resolve(Text("\(Int(value))\(suffix)").font(.system(size: 11, weight: .bold)).foregroundColor(color))
        ctx.draw(label, at: labelPoint, anchor: .center)
    }

    private func drawRpmRing(ctx: GraphicsContext, center: CGPoint, radius: CGFloat) {
        drawTrack(ctx: ctx, center: center, radius: radius, width: 11, color: .white.opacity(0.08))
        let clamped = min(rpm, maxRpm)
        drawArc(ctx: ctx, center: center, radius: radius, width: 11, fromDeg: startAngle, toDeg: angle(for: clamped, maxValue: maxRpm), color: rpmColor)
        drawTicks(ctx: ctx, center: center, radius: radius, fromDeg: startAngle, toDeg: startAngle + sweepAngle, count: 8) // every 1000 rpm
        drawPeakMarker(ctx: ctx, center: center, radius: radius, value: rpmPeak, maxValue: maxRpm, color: rpmColor, suffix: "", ringWidth: 11)
    }

    private func drawSpeedRing(ctx: GraphicsContext, center: CGPoint, radius: CGFloat) {
        drawTrack(ctx: ctx, center: center, radius: radius, width: 7, color: .white.opacity(0.06))
        let clamped = min(speedKph, maxSpeed)
        drawArc(ctx: ctx, center: center, radius: radius, width: 7, fromDeg: startAngle, toDeg: angle(for: clamped, maxValue: maxSpeed), color: DesignPalette.speedOrange)
        drawTicks(ctx: ctx, center: center, radius: radius, fromDeg: startAngle, toDeg: startAngle + sweepAngle, count: 11) // every 20 km/h
        drawPeakMarker(ctx: ctx, center: center, radius: radius, value: speedPeak, maxValue: maxSpeed, color: DesignPalette.speedOrange, suffix: "", ringWidth: 7)
    }
}
