import CoreLocation
import Foundation

/// Phone GPS-derived speed, kept deliberately separate from the OBD-reported speed so the two can
/// be shown side-by-side as a sanity check on the vehicle's own speed sensor. Mirrors Android's
/// `GpsSpeedSource`/`AndroidGpsSpeedSource`.
///
/// Platform gap vs. Android: `LocationManager.GPS_PROVIDER` lets Android force GPS-chip-only
/// positioning with no WiFi/cell fusion; CoreLocation has no equivalent switch — `start()` here
/// requests the best available accuracy and lets iOS fuse whatever positioning sources it judges
/// best. This is a known, unavoidable platform difference (see shared/protocol-docs), not a bug.
protocol GpsSpeedSource: AnyObject {
    var speedKph: Float? { get }
    var onSpeedChange: ((Float?) -> Void)? { get set }
    func start()
    func stop()
}

final class CoreLocationGpsSpeedSource: NSObject, GpsSpeedSource, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private(set) var speedKph: Float? {
        didSet { onSpeedChange?(speedKph) }
    }
    var onSpeedChange: ((Float?) -> Void)?

    override init() {
        super.init()
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.delegate = self
    }

    func start() {
        let status = manager.authorizationStatus
        guard status == .authorizedWhenInUse || status == .authorizedAlways else { return }
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
        speedKph = nil
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        // CLLocation.speed is -1 when the value is invalid (e.g. the first fix after cold start),
        // matching Android's `!hasSpeed()` guard against overwriting a good value with garbage.
        guard location.speed >= 0 else { return }
        speedKph = Float(location.speed) * 3.6
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Best-effort telemetry only — a failed GPS fix should not disrupt OBD polling.
    }
}
