import CarPlay
import Combine
import UIKit

/// CarPlay Dashboard scaffolding — the mechanism apps like 神盾 (a speed-camera-alert app) use to
/// show a small supplementary panel next to a *different*, main app (typically a navigation app
/// like Google Maps) on cars whose display has room for one, instead of taking over the whole
/// screen the way `CarPlaySceneDelegate`'s `CPTemplateApplicationScene` does. This is a distinct
/// CarPlay scene type — `CPTemplateApplicationDashboardSceneDelegate` — with its own entry in
/// project.yml's `UIApplicationSceneManifest`, not a variant of the main scene.
///
/// The dashboard surface is much narrower than the main scene's list template: `CPDashboardButton`
/// only carries a title, a subtitle, and an icon (no arbitrary layout), and Apple caps how many
/// can be shown at once — a simplified first version sticks to the three figures that matter most
/// glanced at from a mounted phone while another app has the main screen: connection/DTC status,
/// speed, and RPM.
///
/// Same caveat as `CarPlaySceneDelegate` — this code alone does not put anything on a car's
/// screen. It's gated behind the same Apple CarPlay entitlement request/approval this app doesn't
/// have yet (Dashboard access is part of that one request, not a separate approval); until
/// granted, this class is simply never instantiated.
final class CarPlayDashboardSceneDelegate: UIResponder, CPTemplateApplicationDashboardSceneDelegate {
    private var dashboardController: CPDashboardController?
    private var cancellable: AnyCancellable?

    func templateApplicationDashboardScene(
        _ templateApplicationDashboardScene: CPTemplateApplicationDashboardScene,
        didConnect dashboardController: CPDashboardController,
        to window: UIWindow
    ) {
        self.dashboardController = dashboardController
        let controller = AppDependencies.shared

        cancellable = controller.$state
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in
                self?.applyState(state, to: dashboardController)
            }
    }

    func templateApplicationDashboardScene(
        _ templateApplicationDashboardScene: CPTemplateApplicationDashboardScene,
        didDisconnect dashboardController: CPDashboardController,
        from window: UIWindow
    ) {
        cancellable?.cancel()
        cancellable = nil
        self.dashboardController = nil
    }

    /// Rebuilds the shortcut buttons from current state — like the main scene's list sections,
    /// `CPDashboardController.shortcutButtons` is a plain array you replace wholesale, not
    /// something you mutate a row of in place. No `handler` on any of these: they're read-only
    /// glances at the phone app's own state, same as the main scene's list rows, not actions that
    /// do anything when tapped.
    private func applyState(_ state: DashboardState, to dashboardController: CPDashboardController) {
        let speedButton = CPDashboardButton(
            titleVariants: ["車速"],
            subtitleVariants: [state.vehicleData.speedKph.map { "\($0) km/h" } ?? "--"],
            image: UIImage(systemName: "speedometer") ?? UIImage()
        )
        let rpmButton = CPDashboardButton(
            titleVariants: ["轉速"],
            subtitleVariants: [state.vehicleData.rpm.map { "\($0) rpm" } ?? "--"],
            image: UIImage(systemName: "gauge") ?? UIImage()
        )
        let dtcCount = state.troubleCodes?.count ?? 0
        let statusButton = CPDashboardButton(
            titleVariants: [state.connectionLabel],
            subtitleVariants: [state.troubleCodes == nil ? "尚未查詢故障碼" : (dtcCount > 0 ? "\(dtcCount) 個故障碼" : "無故障碼")],
            image: UIImage(systemName: dtcCount > 0 ? "exclamationmark.triangle.fill" : "checkmark.circle.fill") ?? UIImage()
        )

        dashboardController.shortcutButtons = [statusButton, speedButton, rpmButton]
    }
}
