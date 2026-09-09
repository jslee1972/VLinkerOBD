import SwiftUI

/// 標準模式 — portrait layout matching Android's `DashboardScreen.kt` section by section: status
/// header, speed+RPM gauge card, and custom section together on an overview page, then every
/// other live-reading group as its own swipeable page (merged with a neighbor when small — see
/// `GroupPaging`) instead of one long vertical scroll, mirroring 座艙模式's block-swipe navigation.
struct StandardDashboardView: View {
    @EnvironmentObject var controller: DashboardController

    @State private var page = 0
    @State private var showTroubleCodeDetail = false
    @State private var showDiagnostics = false
    @State private var showDevicePicker = false
    @State private var showEcuTest = false
    @State private var showCustomFieldPicker = false

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                TabView(selection: $page) {
                    overviewPage.tag(0)
                    ForEach(Array(mergedGroupPages.enumerated()), id: \.offset) { index, groups in
                        groupPage(groups: groups).tag(index + 1)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                pageIndicator
            }
            // Forces the compact inline title always — the default large-title style expands when
            // a page scrolls back to its top, and a long "廠牌（VIN）" title clips awkwardly at that
            // larger size instead of just eliding with "..." like the compact title does.
            .navigationTitle(navigationTitleText)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
            .sheet(isPresented: $showTroubleCodeDetail) {
                TroubleCodeDetailView(codes: controller.state.troubleCodes ?? [], brand: dtcBrand)
            }
            .sheet(isPresented: $showDiagnostics) { DiagnosticsView() }
            .sheet(isPresented: $showDevicePicker) { DevicePickerView() }
            .sheet(isPresented: $showEcuTest) { EcuSupportTestView() }
            .sheet(isPresented: $showCustomFieldPicker) { CustomFieldPickerView() }
        }
    }

    private var navigationTitleText: String {
        let brandLabel = controller.state.detectedBrand ?? "行車通"
        if let brand = controller.state.detectedBrand, let vin = controller.state.detectedVin {
            return "\(brand)（\(vin)）"
        }
        return brandLabel
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

    /// On the last page, a tap on the chevron below the dots switches to 座艙模式 — "reached the
    /// end, keep going." This started as a downward drag on the same spot, but that sits right
    /// above the home indicator, where iOS's own Reachability edge gesture claims a downward drag
    /// before our view ever sees it (users kept sliding into the system's half-screen mode
    /// instead). A tap has no such system gesture to compete with.
    private var pageIndicator: some View {
        VStack(spacing: 4) {
            HStack(spacing: 6) {
                ForEach(0...mergedGroupPages.count, id: \.self) { index in
                    Capsule()
                        .fill(index == page ? Color.accentColor : Color.secondary.opacity(0.25))
                        .frame(width: index == page ? 16 : 6, height: 6)
                        .animation(.easeOut(duration: 0.2), value: page)
                }
            }
            if page == mergedGroupPages.count {
                Button { controller.setDashboardMode(.drivingDynamics) } label: {
                    Label("進入座艙模式", systemImage: "chevron.down")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .transition(.opacity)
            }
        }
        .padding(.vertical, 8)
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .navigationBarTrailing) {
            let count = controller.state.troubleCodes?.count ?? 0
            if count > 0 {
                Button { showTroubleCodeDetail = true } label: {
                    Label("\(count) 個故障碼", systemImage: "exclamationmark.triangle.fill")
                        .labelStyle(.iconOnly)
                        .foregroundStyle(.red)
                        .overlay(alignment: .topTrailing) {
                            Text("\(count)")
                                .font(.system(size: 10)).bold()
                                .foregroundStyle(.white)
                                .padding(3)
                                .background(Circle().fill(.red))
                                .offset(x: 8, y: -8)
                        }
                }
            }
        }
        ToolbarItem(placement: .navigationBarTrailing) {
            Menu {
                Button("選擇連線裝置") { showDevicePicker = true }
                Button("中斷連線") { controller.disconnect() }.disabled(!controller.state.isReady)
                Button("診斷主控台") { showDiagnostics = true }
                Button("ECU 支援測試") { showEcuTest = true }.disabled(!controller.state.isReady)
                Button("切換到\(DashboardMode.drivingDynamics.displayNameZh)") { controller.setDashboardMode(.drivingDynamics) }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
        }
    }

    // MARK: Overview page (status + gauges + custom section)

    private var overviewPage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                StatusHeaderView()
                gaugeCard
                customSection
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }

    private var gaugeCard: some View {
        HStack(spacing: 8) {
            Gauge(
                value: Double(controller.state.vehicleData.speedKph ?? 0),
                minValue: 0, maxValue: 220, label: "車速", unit: "km/h",
                secondaryValueText: controller.state.gpsSpeedKph.map { String(format: "%.0f", $0) },
                trend: controller.state.speedHistory,
                valueColor: DesignPalette.speedOrange, valueDesign: .default
            )
            Gauge(
                value: Double(controller.state.vehicleData.rpm ?? 0),
                minValue: 0, maxValue: 8000, label: "轉速", unit: "rpm",
                redlineStart: 6500,
                trend: controller.state.rpmHistory
            )
        }
        .padding(.vertical, 16)
        .padding(.horizontal, 8)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    private var customSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                SectionLabel(text: "自訂")
                Spacer()
                Button { showCustomFieldPicker = true } label: { Image(systemName: "pencil") }
            }
            let entries = controller.state.liveReadings.filter { controller.state.selectedCustomFields.contains($0.key) }
            if entries.isEmpty {
                Text("尚未選擇任何參數，點右上角編輯圖示新增")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                LazyVGrid(columns: columns, spacing: 10) {
                    ForEach(entries.keys.sorted(), id: \.self) { field in
                        ParameterStatCard(field: field, value: entries[field] ?? "")
                    }
                }
            }
        }
    }

    // MARK: Group pages (swipe between blocks instead of scrolling)

    /// Renders one or more groups (merged by `GroupPaging` when they're individually small) on a
    /// single page, each with its own subheader, sharing one scroll region.
    private func groupPage(groups: [String]) -> some View {
        let readings = controller.state.liveReadings
        return ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                ForEach(groups, id: \.self) { group in
                    let fields = readings.keys.filter { controller.parameterMetadata.groupFor($0) == group }.sorted()
                    VStack(alignment: .leading, spacing: 10) {
                        SectionLabel(text: group)
                        LazyVGrid(columns: columns, spacing: 10) {
                            ForEach(fields, id: \.self) { field in
                                ParameterStatCard(field: field, value: readings[field] ?? "")
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }
}

struct StatusHeaderView: View {
    @EnvironmentObject var controller: DashboardController

    private var dotColor: Color {
        if controller.state.errorMessage != nil { return .red }
        if controller.state.isReady { return Color(red: 0.13, green: 0.77, blue: 0.37) }
        if controller.state.isScanning { return .accentColor }
        return .secondary
    }

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(dotColor).frame(width: 8, height: 8)
            Text(statusText).font(.footnote).bold().foregroundStyle(.secondary)
            if let error = controller.state.errorMessage {
                Text(" · \(error)").font(.footnote).foregroundStyle(.red)
            }
        }
    }

    private var statusText: String {
        if controller.state.isReady, let name = controller.state.connectedDeviceName {
            return "\(controller.state.connectionLabel)：\(name)"
        }
        return controller.state.connectionLabel
    }
}
