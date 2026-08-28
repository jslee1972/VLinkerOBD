import SwiftUI

struct ContentView: View {
    @EnvironmentObject var obd: OBDBLEManager
    @State private var manualCommand = "010D"

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    statusCard
                    speedCard
                    controls
                    manualCommandCard
                    logCard
                }
                .padding()
            }
            .navigationTitle("vLinker OBD")
        }
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("狀態")
                    .font(.headline)
                Spacer()
                Text(obd.state.rawValue)
                    .fontWeight(.semibold)
            }

            HStack {
                Text("裝置")
                Spacer()
                Text(obd.deviceName)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var speedCard: some View {
        VStack(spacing: 2) {
            Text("\(obd.speedKPH)")
                .font(.system(size: 72, weight: .bold, design: .rounded))
                .monospacedDigit()
            Text("km/h")
                .font(.title3)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 20))
    }

    private var controls: some View {
        HStack(spacing: 12) {
            Button {
                obd.startScan()
            } label: {
                Label("掃描 / 連線", systemImage: "antenna.radiowaves.left.and.right")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)

            Button {
                obd.disconnect()
            } label: {
                Label("中斷", systemImage: "xmark.circle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }

    private var manualCommandCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("手動 OBD 指令")
                .font(.headline)

            HStack {
                TextField("例如 010C / 0105 / ATI", text: $manualCommand)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()

                Button("送出") {
                    obd.sendManual(manualCommand)
                }
                .buttonStyle(.borderedProminent)
            }

            Text("最近回應")
                .font(.caption)
                .foregroundStyle(.secondary)

            Text(obd.rawResponse)
                .font(.system(.caption, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
        }
        .padding()
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var logCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("BLE / OBD Log")
                .font(.headline)

            ScrollView(.horizontal) {
                Text(obd.logText.isEmpty ? "尚無資料" : obd.logText)
                    .font(.system(size: 11, design: .monospaced))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(minHeight: 220)
        }
        .padding()
        .background(.thinMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}
