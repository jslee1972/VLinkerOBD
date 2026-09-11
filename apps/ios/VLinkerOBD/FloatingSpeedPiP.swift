import AVFoundation
import AVKit
import CoreMedia
import SwiftUI
import UIKit

/// A floating "speed window" that survives switching to another app (Maps, Waze) — the same
/// Picture-in-Picture mechanism YouTube uses for its own video mini-player, just fed synthetic
/// frames (a repeatedly re-rendered SwiftUI view) instead of a real video. PiP is a public API;
/// unlike CarPlay, there is no Apple entitlement to request — only the `audio` background mode
/// declared in project.yml, so this works the moment it ships, no external approval pending.
///
/// Simplified first version: shows just the current speed, at a modest 4fps (plenty for a number
/// that changes a few times a second at most, and easy on battery). `AVSampleBufferDisplayLayer`
/// needs to sit in the view hierarchy for PiP's start/stop animations to have something to
/// animate from/to, even though nothing about it is ever meant to be visible in the app's own UI
/// — `hostView` below is a zero-size, hidden container that exists solely for that.
@MainActor
final class FloatingSpeedPiPController: NSObject, ObservableObject {
    @Published private(set) var isActive = false

    private weak var controller: DashboardController?
    private let displayLayer = AVSampleBufferDisplayLayer()
    private let hostView = UIView(frame: .zero)
    private var pipController: AVPictureInPictureController?
    private var renderTimer: Timer?
    private var pixelBufferPool: CVPixelBufferPool?
    private var formatDescription: CMVideoFormatDescription?
    private var frameCount: Int64 = 0

    private let renderSize = CGSize(width: 320, height: 180)
    private let frameRate: Int32 = 4

    func start(with controller: DashboardController) {
        guard !isActive else { return }
        guard AVPictureInPictureController.isPictureInPictureSupported() else { return }
        self.controller = controller

        installHostViewIfNeeded()
        setUpPixelBufferPoolIfNeeded()

        let contentSource = AVPictureInPictureController.ContentSource(
            sampleBufferDisplayLayer: displayLayer,
            playbackDelegate: self
        )
        let pip = AVPictureInPictureController(contentSource: contentSource)
        pip.delegate = self
        pipController = pip

        frameCount = 0
        renderTimer?.invalidate()
        renderTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / Double(frameRate), repeats: true) { [weak self] _ in
            self?.renderFrame()
        }
        renderFrame() // one immediate frame so PiP doesn't start on a blank layer
        pip.startPictureInPicture()
        isActive = true
    }

    func stop() {
        renderTimer?.invalidate()
        renderTimer = nil
        pipController?.stopPictureInPicture()
        isActive = false
    }

    private func installHostViewIfNeeded() {
        guard hostView.superview == nil else { return }
        hostView.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
        hostView.isHidden = true
        hostView.isUserInteractionEnabled = false
        displayLayer.frame = hostView.bounds
        hostView.layer.addSublayer(displayLayer)
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.windows.first }
            .first?
            .addSubview(hostView)
    }

    private func setUpPixelBufferPoolIfNeeded() {
        guard pixelBufferPool == nil else { return }
        let attrs: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: Int(renderSize.width),
            kCVPixelBufferHeightKey as String: Int(renderSize.height),
            kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any],
        ]
        var pool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(nil, nil, attrs as CFDictionary, &pool)
        pixelBufferPool = pool

        guard let pool else { return }
        var sample: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &sample)
        guard let sample else { return }
        var desc: CMVideoFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: sample, formatDescriptionOut: &desc)
        formatDescription = desc
    }

    private func renderFrame() {
        guard let controller, let pool = pixelBufferPool, let formatDescription, displayLayer.isReadyForMoreMediaData else { return }

        let speedKph = controller.state.vehicleData.speedKph ?? controller.state.gpsSpeedKph.map(Int.init)
        let renderer = ImageRenderer(content: FloatingSpeedView(speedKph: speedKph, size: renderSize))
        renderer.scale = UIScreen.main.scale
        guard let cgImage = renderer.cgImage else { return }

        var pixelBufferOut: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pixelBufferOut)
        guard let pixelBuffer = pixelBufferOut else { return }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        if let context = CGContext(
            data: CVPixelBufferGetBaseAddress(pixelBuffer),
            width: Int(renderSize.width),
            height: Int(renderSize.height),
            bitsPerComponent: 8,
            bytesPerRow: CVPixelBufferGetBytesPerRow(pixelBuffer),
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) {
            context.draw(cgImage, in: CGRect(origin: .zero, size: renderSize))
        }
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])

        frameCount += 1
        let pts = CMTime(value: frameCount, timescale: frameRate)
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: frameRate), presentationTimeStamp: pts, decodeTimeStamp: .invalid)

        var sampleBuffer: CMSampleBuffer?
        CMSampleBufferCreateForImageBuffer(
            allocator: nil,
            imageBuffer: pixelBuffer,
            dataReady: true,
            makeDataReadyCallback: nil,
            refcon: nil,
            formatDescription: formatDescription,
            sampleTiming: &timing,
            sampleBufferOut: &sampleBuffer
        )
        if let sampleBuffer {
            displayLayer.enqueue(sampleBuffer)
        }
    }
}

extension FloatingSpeedPiPController: AVPictureInPictureControllerDelegate {
    func pictureInPictureControllerDidStopPictureInPicture(_ pictureInPictureController: AVPictureInPictureController) {
        isActive = false
        renderTimer?.invalidate()
        renderTimer = nil
    }
}

/// This app has no real "playback" to control — speed just keeps updating on its own — so every
/// transport-control callback here is a no-op stub; PiP requires the protocol be implemented even
/// when there's nothing to pause, seek, or resize around.
extension FloatingSpeedPiPController: AVPictureInPictureSampleBufferPlaybackDelegate {
    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, setPlaying playing: Bool) {}

    func pictureInPictureControllerTimeRangeForPlayback(_ pictureInPictureController: AVPictureInPictureController) -> CMTimeRange {
        CMTimeRange(start: .zero, duration: .positiveInfinity)
    }

    func pictureInPictureControllerIsPlaybackPaused(_ pictureInPictureController: AVPictureInPictureController) -> Bool {
        false
    }

    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, didTransitionToRenderSize newRenderSize: CMVideoDimensions) {}

    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, skipByInterval skipInterval: CMTime, completion completionHandler: @escaping () -> Void) {
        completionHandler()
    }

    func pictureInPictureControllerShouldProhibitBackgroundAudioPlayback(_ pictureInPictureController: AVPictureInPictureController) -> Bool {
        true
    }
}

/// The floating window's own tiny layout — deliberately much simpler than the main dashboard
/// (just the number and its unit), since the whole point is legibility at PiP's small default
/// size, glanced at while another app has focus.
private struct FloatingSpeedView: View {
    var speedKph: Int?
    var size: CGSize

    var body: some View {
        ZStack {
            Color(red: 0x05 / 255, green: 0x06 / 255, blue: 0x09 / 255)
            VStack(spacing: 4) {
                Text(speedKph.map { "\($0)" } ?? "--")
                    .font(.system(size: size.height * 0.42, weight: .heavy, design: .default))
                    .monospacedDigit()
                    .foregroundStyle(DesignPalette.speedOrange)
                Text("km/h")
                    .font(.system(size: size.height * 0.12, weight: .medium))
                    .foregroundStyle(.white.opacity(0.6))
            }
        }
        .frame(width: size.width, height: size.height)
    }
}
