import SwiftUI

@main
struct VLinkerOBDApp: App {
    @StateObject private var obd = OBDBLEManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(obd)
        }
    }
}
