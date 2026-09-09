import SwiftUI
import UIKit

/// Lets the 行車動態介面 (driving-dynamics UI) force landscape while the 標準介面 stays portrait.
/// SwiftUI has no direct "lock this screen's orientation" API, so this goes through the one
/// UIKit hook that does: `UIApplicationDelegate.application(_:supportedInterfaceOrientationsFor:)`,
/// backed by a plain static var `AppDelegate` reads synchronously (UIKit calls that method
/// on demand, not reactively, so there's no SwiftUI state to observe here).
enum OrientationLock {
    static var mask: UIInterfaceOrientationMask = .portrait

    /// Updates the allowed mask and actively requests a rotation — without this, iOS only
    /// re-checks the mask the next time it feels like re-evaluating orientation (e.g. the user
    /// physically rotates the device), which wouldn't satisfy "the driving-dynamics UI opens
    /// straight into landscape."
    @MainActor
    static func apply(_ mask: UIInterfaceOrientationMask, preferring preferred: UIInterfaceOrientation) {
        self.mask = mask
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
