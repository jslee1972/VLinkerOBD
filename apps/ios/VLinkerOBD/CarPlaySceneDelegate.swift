import CarPlay
import Combine
import SwiftUI

/// CarPlay scaffolding — mirrors Android's `car/` package (`VLinkerCarAppService`,
/// `VLinkerCarSession`, `DashboardCarScreen`): a read-only, template-only projection of the same
/// shared `DashboardController.state` the phone UI observes (connection status, detected brand,
/// speed, rpm), not a second copy of the gauge UI. CarPlay's template system doesn't allow
/// arbitrary custom drawing outside the Navigation app category this app doesn't belong to — same
/// constraint that keeps Android's car screen to a plain `PaneTemplate`, not the Compose gauges.
///
/// IMPORTANT — this code alone does not put the app on CarPlay. Apple gates CarPlay to a fixed set
/// of app categories and requires a per-app entitlement request/approval before iOS will ever
/// route a CarPlay connection to `templateApplicationScene(_:didConnect:to:)` below, regardless of
/// what's declared here or in Info.plist. Until that entitlement is granted and added to the
/// target (an `.entitlements` file with the granted `com.apple.developer.carplay-*` key), this
/// class simply never gets instantiated — the app runs on the phone exactly as before.
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private var cancellable: AnyCancellable?

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didConnect interfaceController: CPInterfaceController
    ) {
        self.interfaceController = interfaceController
        let controller = AppDependencies.shared

        let template = CPListTemplate(title: "行車通", sections: [])
        cancellable = controller.$state
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in
                self?.applyState(state, to: template)
            }
        interfaceController.setRootTemplate(template, animated: false, completion: nil)
    }

    func templateApplicationScene(
        _ templateApplicationScene: CPTemplateApplicationScene,
        didDisconnectInterfaceController interfaceController: CPInterfaceController
    ) {
        cancellable?.cancel()
        cancellable = nil
        self.interfaceController = nil
    }

    /// Rebuilds the list's rows from current state — CarPlay templates are immutable-content
    /// value objects (like `DashboardCarScreen`'s `Pane` on Android), so a live update means
    /// replacing the section/items, not mutating an existing row in place.
    private func applyState(_ state: DashboardState, to template: CPListTemplate) {
        let brandLabel = state.detectedBrand ?? "行車通"
        let subtitle = state.detectedVin.map { "\(brandLabel)（\($0)）" } ?? brandLabel

        let statusItem = CPListItem(text: state.connectionLabel, detailText: subtitle)
        let speedItem = CPListItem(
            text: "車速",
            detailText: state.vehicleData.speedKph.map { "\($0) km/h" } ?? "--"
        )
        let rpmItem = CPListItem(
            text: "轉速",
            detailText: state.vehicleData.rpm.map { "\($0) rpm" } ?? "--"
        )
        let dtcCount = state.troubleCodes?.count ?? 0
        let dtcItem = CPListItem(
            text: "故障碼",
            detailText: state.troubleCodes == nil ? "尚未查詢" : (dtcCount > 0 ? "\(dtcCount) 個" : "無")
        )

        let section = CPListSection(items: [statusItem, speedItem, rpmItem, dtcItem])
        template.updateSections([section])
    }
}
