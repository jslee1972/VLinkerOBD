import CoreLocation
import Foundation

/// Phone GPS-derived speed/course/altitude, kept deliberately separate from the OBD-reported
/// speed so the two can be shown side-by-side as a sanity check on the vehicle's own speed
/// sensor. Mirrors Android's `GpsSpeedSource`/`AndroidGpsSpeedSource` for the speed piece; course
/// and altitude are iOS-only additions (phone-native driving-relevant data that needs no OBD
/// connection at all).
///
/// Platform gap vs. Android: `LocationManager.GPS_PROVIDER` lets Android force GPS-chip-only
/// positioning with no WiFi/cell fusion; CoreLocation has no equivalent switch — `start()` here
/// requests the best available accuracy and lets iOS fuse whatever positioning sources it judges
/// best. This is a known, unavoidable platform difference (see shared/protocol-docs), not a bug.
protocol GpsSpeedSource: AnyObject {
    var speedKph: Float? { get }
    /// True compass heading of travel in degrees (0–360, 0 = north), nil until the first valid
    /// fix or while stationary (course is meaningless without motion).
    var courseDegrees: Float? { get }
    var altitudeMeters: Float? { get }
    /// Radius (metres) of the circle CoreLocation is 68% confident the true position falls
    /// within — smaller is better. Negative/absent on CLLocation itself means "invalid," which
    /// this surfaces as `nil`, same convention as the other GPS-derived values here.
    var horizontalAccuracyMeters: Float? { get }
    var onSpeedChange: ((Float?) -> Void)? { get set }
    var onCourseChange: ((Float?) -> Void)? { get set }
    var onAltitudeChange: ((Float?) -> Void)? { get set }
    var onHorizontalAccuracyChange: ((Float?) -> Void)? { get set }
    func start()
    func stop()
}

final class CoreLocationGpsSpeedSource: NSObject, GpsSpeedSource, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private(set) var speedKph: Float? {
        didSet { onSpeedChange?(speedKph) }
    }
    private(set) var courseDegrees: Float? {
        didSet { onCourseChange?(courseDegrees) }
    }
    private(set) var altitudeMeters: Float? {
        didSet { onAltitudeChange?(altitudeMeters) }
    }
    private(set) var horizontalAccuracyMeters: Float? {
        didSet { onHorizontalAccuracyChange?(horizontalAccuracyMeters) }
    }
    var onSpeedChange: ((Float?) -> Void)?
    var onCourseChange: ((Float?) -> Void)?
    var onAltitudeChange: ((Float?) -> Void)?
    var onHorizontalAccuracyChange: ((Float?) -> Void)?

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
        courseDegrees = nil
        altitudeMeters = nil
        horizontalAccuracyMeters = nil
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        // CLLocation.speed is -1 when the value is invalid (e.g. the first fix after cold start),
        // matching Android's `!hasSpeed()` guard against overwriting a good value with garbage.
        guard location.speed >= 0 else { return }
        speedKph = Float(location.speed) * 3.6

        // Same -1-means-invalid convention as speed — course is meaningless standing still or
        // right after a cold-start fix, so leave the last good value in place rather than
        // overwrite it with garbage.
        if location.course >= 0 {
            courseDegrees = Float(location.course)
        }
        // verticalAccuracy < 0 is CLLocation's own "this altitude reading is invalid" signal.
        if location.verticalAccuracy >= 0 {
            altitudeMeters = Float(location.altitude)
        }
        // Same convention again — horizontalAccuracy < 0 means the position fix itself is invalid,
        // not just imprecise.
        if location.horizontalAccuracy >= 0 {
            horizontalAccuracyMeters = Float(location.horizontalAccuracy)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Best-effort telemetry only — a failed GPS fix should not disrupt OBD polling.
    }
}
