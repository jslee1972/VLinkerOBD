import CoreMotion
import Foundation

/// Combined G-force and body roll (left-right tilt), derived entirely from the phone's own
/// accelerometer/gyroscope via CoreMotion — no OBD connection needed at all, and this is what now
/// backs the dashboard's "加速度" (acceleration) reading (replacing the old OBD speed-delta
/// estimate — see `DashboardController.updateTripComputer`'s git history for that version). Mirrors
/// `GpsSpeedSource`'s closure-callback shape rather than `ObservableObject`, for the same reason:
/// `DashboardController` is the single place that decides what state actually changes, not this
/// source directly.
///
/// The phone is dash/windshield-mounted in landscape, tilted back by some unknown amount to angle
/// the screen toward the driver ("pitch") — but never twisted sideways relative to the car ("the
/// mount is always left-right neutral," per how this app is actually used). That assumption is
/// what makes a clean measurement possible at all: the device's own long edge (its hardware Y
/// axis — landscape rotates it to run horizontally on screen, but the raw accelerometer axes are
/// fixed to the hardware, not to the current UI orientation) stays parallel to the car's actual
/// left-right axis regardless of how much the mount's pitch changes, because pitch is exactly a
/// rotation *around* that same Y axis. A rotation can never change a vector's own component along
/// its own axis — so gravity's Y-component in device coordinates is pitch-invariant, and directly
/// encodes only how much the *car* itself is banked sideways: `asin(gravity.y)`.
///
/// The car's forward axis needs actual pitch-correction, since it isn't a single fixed device
/// axis — it's derived fresh from each sample's gravity reading instead of assumed constant:
/// `cross(down, Y-axis)`, which is exactly the horizontal axis perpendicular to the (always
/// lateral) Y axis, however much the current pitch happens to be.
///
/// Known limitation: `userAcceleration` already has CoreMotion's own gravity/gyro sensor fusion
/// applied to isolate real linear acceleration, but a phone in a loose or vibrating mount will
/// still add noise on rough roads — treat these as a glanceable trend, not an instrumentation-
/// grade accelerometer reading.
final class MotionDataSource {
    private let manager = CMMotionManager()
    private static let updateIntervalSec = 0.1

    /// Combined magnitude of lateral (cornering) and longitudinal (accel/brake) G-force —
    /// `sqrt(lateral² + longitudinal²)`, always ≥ 0. A single number can't distinguish "hard
    /// braking" from "hard cornering" from "both at once," but that's the deliberate trade for
    /// folding two axes into the one "加速度" reading instead of two separate fields.
    var onCombinedGForceChange: ((Double?) -> Void)?
    /// The car's own left-right tilt in degrees, positive/negative meaning unverified against a
    /// real vehicle (the Simulator has no real accelerometer to test this against at all) — see
    /// this type's own doc comment for the derivation.
    var onRollDegreesChange: ((Double?) -> Void)?

    func start() {
        guard manager.isDeviceMotionAvailable else { return }
        manager.deviceMotionUpdateInterval = Self.updateIntervalSec
        manager.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: .main) { [weak self] motion, _ in
            guard let self, let motion else { return }
            self.process(motion)
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
        onCombinedGForceChange?(nil)
        onRollDegreesChange?(nil)
    }

    private func process(_ motion: CMDeviceMotion) {
        let g = motion.gravity
        let magnitude = (g.x * g.x + g.y * g.y + g.z * g.z).squareRoot()
        guard magnitude > 0.01 else { return }
        let dx = g.x / magnitude
        let dy = g.y / magnitude
        let dz = g.z / magnitude

        let clampedDy = max(-1, min(1, dy))
        onRollDegreesChange?(asin(clampedDy) * 180 / .pi)

        // cross(down, Ŷ) where Ŷ = (0,1,0): (dy*0 - dz*1, dz*0 - dx*0, dx*1 - dy*0) = (-dz, 0, dx).
        let forwardX = -dz
        let forwardZ = dx
        let forwardMagnitude = (forwardX * forwardX + forwardZ * forwardZ).squareRoot()
        guard forwardMagnitude > 0.01 else { return }

        let ua = motion.userAcceleration
        let lateral = ua.y
        let longitudinal = (ua.x * forwardX + ua.z * forwardZ) / forwardMagnitude
        onCombinedGForceChange?((lateral * lateral + longitudinal * longitudinal).squareRoot())
    }
}
