import SwiftUI

/// Shared sheets/dialogs used by both the standard and driving-dynamics dashboard layouts —
/// direct SwiftUI equivalents of the dialogs in Android's `DashboardScreen.kt`.

struct ParameterStatCard: View {
    var field: String
    var value: String
    @EnvironmentObject var controller: DashboardController

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: iconName(for: field))
                .foregroundStyle(.tint)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(value).font(.headline).bold()
                Text(controller.parameterMetadata.displayName(field))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private func iconName(for field: String) -> String {
        let lower = field.lowercased()
        if lower.contains("temp") { return "thermometer" }
        if lower.contains("voltage") || lower.contains("battery") { return "bolt.fill" }
        if lower.contains("fuel") { return "fuelpump.fill" }
        if lower.contains("pressure") || lower.contains("rpm") || lower.contains("torque") || lower.contains("timing") || lower.contains("advance") { return "speedometer" }
        return "info.circle"
    }
}

struct SectionLabel: View {
    var text: String
    var body: some View {
        Text(text)
            .font(.subheadline).bold()
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct TroubleCodeDetailView: View {
    var codes: [String]
    var brand: String?
    @EnvironmentObject var controller: DashboardController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(codes, id: \.self) { code in
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(code)（\(DtcDescriptions.categoryName(code))）").font(.headline)
                    Text(controller.dtcDescriptions.describe(code, brand: brand)).font(.body)
                }
                .padding(.vertical, 4)
            }
            .navigationTitle("故障詳情")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("關閉") { dismiss() } } }
        }
    }
}

struct DevicePickerView: View {
    @EnvironmentObject var controller: DashboardController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            let namedDevices = controller.state.devices.filter { !($0.name ?? "").trimmingCharacters(in: .whitespaces).isEmpty }
            Group {
                if namedDevices.isEmpty {
                    ContentUnavailableFallback(text: controller.state.isScanning ? "掃描中…尚未發現裝置" : "尚未發現裝置")
                } else {
                    List(namedDevices) { device in
                        HStack {
                            VStack(alignment: .leading) {
                                Text("\(device.isPreferred ? "★ " : "")\(device.name ?? "")")
                                Text("\(device.address)  RSSI \(device.rssi)").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("連線") { controller.connect(device); dismiss() }
                                .buttonStyle(.borderedProminent)
                        }
                    }
                }
            }
            .navigationTitle("選擇連線裝置")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("關閉") { dismiss() } } }
            .onAppear { controller.startScan() }
            .onDisappear { controller.stopScan() }
        }
    }
}

struct ContentUnavailableFallback: View {
    var text: String
    var body: some View {
        VStack { Spacer(); Text(text).foregroundStyle(.secondary); Spacer() }
    }
}

struct DiagnosticsView: View {
    @EnvironmentObject var controller: DashboardController
    @Environment(\.dismiss) private var dismiss
    @State private var command = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Raw: \(controller.state.rawResponse.isEmpty ? "(尚無資料)" : controller.state.rawResponse)")
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))

                    HStack {
                        TextField("例如 010C / 0105 / ATI", text: $command)
                            .textFieldStyle(.roundedBorder)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                        Button("送出") { controller.sendManualCommand(command); command = "" }
                            .buttonStyle(.borderedProminent)
                            .disabled(command.trimmingCharacters(in: .whitespaces).isEmpty)
                    }

                    // One tap, no gap between steps — typing ATSH6A8/ATCRA688/22D409 in one at a
                    // time left enough of a delay at driving speed for the bus to go idle before
                    // the real request landed (that's what a "NO DATA" from a manually-typed probe
                    // usually means, not that the value genuinely isn't there).
                    Button {
                        controller.probeGearRaw()
                    } label: {
                        Label("即時查詢檔位原始值（連續送出）", systemImage: "bolt.fill")
                    }
                    .buttonStyle(.bordered)
                    .disabled(!controller.state.isReady)

                    // Same back-to-back pattern, probing all 4 tyre-pressure DIDs at once — these
                    // have never returned data; a NO DATA vs. a timeout tells apart "wrong DID" vs.
                    // "this module probably isn't fitted to this car" (see the driving-dynamics
                    // side panel discussion — the workshop manual documents tyre under-inflation
                    // detection as an optional fitment on some trims).
                    Button {
                        controller.probeTirePressures()
                    } label: {
                        Label("即時查詢胎壓原始值（連續送出）", systemImage: "bolt.fill")
                    }
                    .buttonStyle(.bordered)
                    .disabled(!controller.state.isReady)

                    HStack {
                        Text("紀錄").font(.subheadline).foregroundStyle(.secondary)
                        Spacer()
                        Button("清除紀錄") { controller.clearLogs() }
                    }

                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(controller.state.logs.suffix(50).enumerated()), id: \.offset) { _, line in
                            Text(line).font(.system(size: 11, design: .monospaced)).foregroundStyle(.secondary)
                        }
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
                }
                .padding()
            }
            .navigationTitle("診斷主控台")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("關閉") { dismiss() } } }
        }
    }
}

struct EcuSupportTestView: View {
    @EnvironmentObject var controller: DashboardController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text("測試這台車的 ECU 支援哪些讀取 VIN 的方式（Mode 09、UDS 22F190），可用來判斷此車是否只是協定不支援，而非硬體接線問題。")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                if controller.state.ecuTestResults.isEmpty && !controller.state.isTestingEcu {
                    Text("尚無測試結果").foregroundStyle(.secondary)
                }

                ScrollView {
                    VStack(spacing: 8) {
                        ForEach(controller.state.ecuTestResults) { result in
                            EcuTestResultRow(result: result)
                        }
                    }
                }

                HStack {
                    Button("重新測試") { controller.testEcuSupport() }
                        .disabled(controller.state.isTestingEcu)
                    Spacer()
                    if controller.state.isTestingEcu { ProgressView() }
                }
            }
            .padding()
            .navigationTitle("ECU 支援測試")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("關閉") { dismiss() } } }
            .onAppear { controller.testEcuSupport() }
        }
    }
}

struct EcuTestResultRow: View {
    var result: EcuTestResult

    private var statusColor: Color {
        switch result.status {
        case .supported: return Color(red: 0.13, green: 0.77, blue: 0.37)
        case .noData: return .secondary
        case .negative, .timeout, .unrecognized: return .red
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(result.command).font(.system(.body, design: .monospaced)).bold()
                Spacer()
                Text(result.statusMessage).font(.caption).foregroundStyle(statusColor)
            }
            Text(result.description).font(.caption2).foregroundStyle(.secondary)
            if let raw = result.raw {
                Text("Raw: \(raw)").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
    }
}

/// Picks the (at most 2) fields shown next to the ring gauge's center readout — see
/// `DashboardController.toggleRingLegendField`. Same list/grouping as `CustomFieldPickerView`,
/// just capped at 2 selections instead of unbounded — and scoped to
/// `relevantFieldsForCurrentVehicle` rather than every loaded brand's fields, since with only 2
/// slots, picking a field that belongs to some other brand's profile (and so can never report
/// data for the connected vehicle) wastes half the legend on a permanent "--". Always includes
/// whatever's already in `ringLegendFields` too, even if it falls outside the current vehicle's
/// relevant set — otherwise a field picked while one brand was connected would vanish from this
/// list (without ever being deselected) the moment a different brand connects, leaving it stuck
/// in the legend with no way to uncheck it short of picking two new fields to FIFO-evict it.
struct RingLegendFieldPickerView: View {
    @EnvironmentObject var controller: DashboardController
    @Environment(\.dismiss) private var dismiss

    private let columns = [GridItem(.flexible(), spacing: 16, alignment: .leading), GridItem(.flexible(), alignment: .leading)]

    var body: some View {
        NavigationStack {
            let selectableFields = Set(controller.relevantFieldsForCurrentVehicle).union(controller.state.ringLegendFields)
            let grouped = Dictionary(grouping: Array(selectableFields)) { controller.parameterMetadata.groupFor($0) }
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("選 2 個顯示在中央圓環旁——再點第 3 個會換掉最先選的那個。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    ForEach(ParameterGroups.displayOrder, id: \.self) { group in
                        if let fields = grouped[group]?.sorted() {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(group).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                                // Two columns, smaller text — one column of full-size rows meant
                                // scrolling through a long single-file list to find a field; this
                                // fits roughly twice as many on screen at once.
                                LazyVGrid(columns: columns, alignment: .leading, spacing: 10) {
                                    ForEach(fields, id: \.self) { field in
                                        Button {
                                            controller.toggleRingLegendField(field)
                                        } label: {
                                            HStack(spacing: 6) {
                                                Image(systemName: controller.state.ringLegendFields.contains(field) ? "checkmark.square.fill" : "square")
                                                    .font(.caption)
                                                Text(controller.parameterMetadata.displayName(field))
                                                    .font(.caption)
                                                    .lineLimit(1)
                                                    .minimumScaleFactor(0.8)
                                            }
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("選擇中央顯示參數")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("關閉") { dismiss() } }
            }
        }
    }
}

struct CustomFieldPickerView: View {
    @EnvironmentObject var controller: DashboardController
    @Environment(\.dismiss) private var dismiss
    @State private var infoField: String?

    private let columns = [GridItem(.flexible(), spacing: 16, alignment: .leading), GridItem(.flexible(), alignment: .leading)]

    var body: some View {
        NavigationStack {
            // Scoped to the current vehicle (universal + the detected/selected brand's own
            // fields), same as the ring legend picker — `allKnownFields` is every loaded brand's
            // fields regardless of what's actually connected, so without this a Honda would still
            // list Mazda/Ford/Citroën-only PIDs that can never report data. Always includes
            // whatever's already selected too, so a field picked under one brand doesn't vanish
            // (with no way to deselect it) the moment a different brand connects.
            let selectableFields = Set(controller.relevantFieldsForCurrentVehicle).union(controller.state.selectedCustomFields)
            let grouped = Dictionary(grouping: Array(selectableFields)) { controller.parameterMetadata.groupFor($0) }
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    // Already-pinned fields surfaced as their own section up front, in the same
                    // order they're pinned on the home screen — so unchecking one the user no
                    // longer wants doesn't mean hunting for it inside whichever category group it
                    // happens to belong to below; it's already right here at the top.
                    if !controller.state.selectedCustomFields.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("已選擇（\(controller.state.selectedCustomFields.count)）")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(DesignPalette.accent)
                            LazyVGrid(columns: columns, alignment: .leading, spacing: 10) {
                                ForEach(controller.state.selectedCustomFields, id: \.self) { field in
                                    fieldRow(field)
                                }
                            }
                        }
                        Divider()
                    }
                    ForEach(ParameterGroups.displayOrder, id: \.self) { group in
                        if let fields = grouped[group]?.sorted() {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(group).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                                // Two columns, smaller text — one column of full-size rows meant
                                // scrolling through a long single-file list to find a field; this
                                // fits roughly twice as many on screen at once.
                                LazyVGrid(columns: columns, alignment: .leading, spacing: 10) {
                                    ForEach(fields, id: \.self) { field in
                                        fieldRow(field)
                                    }
                                }
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle("選擇自訂區塊參數")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("關閉") { dismiss() } }
                ToolbarItem(placement: .cancellationAction) {
                    Button("全部移除", role: .destructive) { controller.clearAllCustomFields() }
                        .disabled(controller.state.selectedCustomFields.isEmpty)
                }
            }
            .alert(infoField.map { controller.parameterMetadata.displayName($0) } ?? "", isPresented: Binding(get: { infoField != nil }, set: { if !$0 { infoField = nil } })) {
                Button("關閉") { infoField = nil }
            } message: {
                Text(infoField.map { controller.parameterMetadata.description($0) } ?? "")
            }
        }
    }

    @ViewBuilder
    private func fieldRow(_ field: String) -> some View {
        HStack(spacing: 4) {
            Button {
                controller.toggleCustomField(field)
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: controller.state.selectedCustomFields.contains(field) ? "checkmark.square.fill" : "square")
                        .font(.caption)
                    Text(controller.parameterMetadata.displayName(field))
                        .font(.caption)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
            }
            .buttonStyle(.plain)
            Spacer(minLength: 0)
            Button { infoField = field } label: {
                Image(systemName: "info.circle")
                    .font(.caption)
            }
            .buttonStyle(.plain)
        }
    }
}
