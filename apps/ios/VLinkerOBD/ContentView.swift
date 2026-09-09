import CoreLocation
import SwiftUI
import UIKit

/// Top-level view: starts BLE scanning + requests location permission once (mirrors Android's
/// `MainActivity.onCreate`/`LaunchedEffect`), tracks GPS only while the app is in the foreground
/// (mirrors `onStart`/`onStop`), and switches between the standard and driving-dynamics layouts —
/// forcing landscape for the latter, since that layout is designed to be read at a glance while
/// mounted, not held portrait.
struct RootView: View {
    @EnvironmentObject var controller: DashboardController
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var locationAuth = LocationAuthorizationRequester()

    /// Bumped on mode switch, app-resume, and physical rotation to force SwiftUI to tear down and
    /// rebuild the whole dashboard subtree via `.id()`. The gauges' `GeometryReader`/`Canvas` pair
    /// can otherwise keep the frame size captured right before one of those events — e.g. the
    /// `OrientationLock.apply` geometry request racing UIKit's own transition, or the window
    /// simply not re-running layout on unlock — which is what produced two Gauges rendering
    /// on top of each other after a lock/unlock or a physical rotation. A plain re-layout doesn't
    /// clear that stale snapshot; only recreating the views does.
    @State private var renderGeneration = 0

    var body: some View {
        Group {
            switch controller.state.dashboardMode {
            case .standard:
                StandardDashboardView()
            case .drivingDynamics:
                DrivingDynamicsDashboardView()
            }
        }
        .id(renderGeneration)
        .onAppear {
            controller.startScan()
            locationAuth.requestIfNeeded { controller.startGpsTracking() }
            applyOrientation(for: controller.state.dashboardMode)
            applyIdleTimer(for: controller.state.dashboardMode)
            UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        }
        .onChange(of: controller.state.dashboardMode) { mode in
            applyOrientation(for: mode)
            applyIdleTimer(for: mode)
            renderGeneration += 1
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                controller.startGpsTracking()
                // Re-assert rather than rely on the value having "stuck" — returning from the
                // background/lock screen is exactly the kind of UIKit-driven reset that can leave
                // isIdleTimerDisabled back at its default false.
                applyIdleTimer(for: controller.state.dashboardMode)
                renderGeneration += 1
            } else {
                controller.stopGpsTracking()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
            renderGeneration += 1
        }
    }

    private func applyOrientation(for mode: DashboardMode) {
        switch mode {
        case .standard:
            OrientationLock.apply(.portrait, preferring: .portrait)
        case .drivingDynamics:
            OrientationLock.apply(.landscape, preferring: .landscapeRight)
        }
    }

    /// Driven directly by `dashboardMode` here, not by 座艙模式's own `onAppear`/`onDisappear` —
    /// those fire on every `renderGeneration`-forced rebuild (mode switch, app resume, rotation),
    /// and SwiftUI doesn't guarantee the old view's `onDisappear` (which reset the flag to false)
    /// runs *before* the new view's `onAppear` (which set it back to true) within that same
    /// update. When it ran after instead, the flag was left `false` and the screen kept locking
    /// even while 座艙模式 was on screen — a real regression `renderGeneration` introduced. Setting
    /// it from one place, keyed on mode state instead of view lifecycle, has no such race.
    private func applyIdleTimer(for mode: DashboardMode) {
        UIApplication.shared.isIdleTimerDisabled = (mode == .drivingDynamics)
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
