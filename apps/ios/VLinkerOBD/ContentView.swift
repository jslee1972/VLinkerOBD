import CoreLocation
import SwiftUI
import UIKit

/// Top-level view: starts BLE scanning + requests location permission once (mirrors Android's
/// `MainActivity.onCreate`/`LaunchedEffect`), tracks GPS only while the app is in the foreground
/// (mirrors `onStart`/`onStop`), and hosts the one landscape dashboard — the whole app is
/// landscape-only, meant to sit on a dash mount and be glanced at, not held portrait.
struct RootView: View {
    @EnvironmentObject var controller: DashboardController
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var locationAuth = LocationAuthorizationRequester()

    /// Bumped on app-resume and physical rotation to force SwiftUI to tear down and rebuild the
    /// whole dashboard subtree via `.id()`. The gauges' `GeometryReader`/`Canvas` pair can
    /// otherwise keep the frame size captured right before one of those events — e.g. the window
    /// simply not re-running layout on unlock — which is what produced two gauges rendering on
    /// top of each other after a lock/unlock or a physical rotation. A plain re-layout doesn't
    /// clear that stale snapshot; only recreating the views does.
    @State private var renderGeneration = 0

    var body: some View {
        DrivingDynamicsDashboardView()
            .id(renderGeneration)
            .onAppear {
                controller.startScan()
                locationAuth.requestIfNeeded { controller.startGpsTracking() }
                OrientationLock.applyOnLaunch()
                UIApplication.shared.isIdleTimerDisabled = true
                UIDevice.current.beginGeneratingDeviceOrientationNotifications()
            }
            .onChange(of: scenePhase) { phase in
                if phase == .active {
                    controller.startGpsTracking()
                    // Re-assert rather than rely on the value having "stuck" — returning from the
                    // background/lock screen is exactly the kind of UIKit-driven reset that can
                    // leave isIdleTimerDisabled back at its default false.
                    UIApplication.shared.isIdleTimerDisabled = true
                    renderGeneration += 1
                } else {
                    controller.stopGpsTracking()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
                renderGeneration += 1
            }
    }
}

/// Thin wrapper around `CLLocationManager`'s authorization request/callback — GPS speed
/// (`CoreLocationGpsSpeedSource`) only starts producing updates once permission is granted.
final class LocationAuthorizationRequester: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var onGranted: (() -> Void)?

    func requestIfNeeded(onGranted: @escaping () -> Void) {
        self.onGranted = onGranted
        manager.delegate = self
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            onGranted()
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        default:
            break
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways {
            onGranted?()
        }
    }
}
