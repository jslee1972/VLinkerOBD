import SwiftUI
import UIKit

/// The whole app is landscape-only (行車通 is meant to sit on a dash mount, not be held portrait).
/// SwiftUI has no direct "lock this app's orientation" API, so this goes through the one UIKit
/// hook that does: `UIApplicationDelegate.application(_:supportedInterfaceOrientationsFor:)`. The
/// mask is fixed at `.landscape` from the moment the process starts — nothing ever changes it —
/// so there's no cold-launch race to worry about: whenever UIKit first asks (even before any
/// SwiftUI view has mounted), the answer is already landscape.
enum OrientationLock {
    static let mask: UIInterfaceOrientationMask = .landscape

    /// Actively requests a rotation to landscape on launch — without this, iOS only re-checks the
    /// mask the next time it feels like re-evaluating orientation (e.g. the user physically
    /// rotates the device), which wouldn't satisfy "opens straight into landscape" if the phone
    /// happens to be held portrait when the app is launched.
    @MainActor
    static func applyOnLaunch() {
        guard let scene = UIApplication.shared.connectedScenes.first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene else { return }
        scene.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        OrientationLock.mask
    }
}
