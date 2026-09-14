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
/// Owned by `RootView`, not by `DrivingDynamicsDashboardView` — that view gets torn down and
/// rebuilt (via `RootView.renderGeneration`'s `.id()`) on every app-resume and device rotation,
/// which would otherwise silently orphan a live PiP session (leaked timer, no `stop()` call) the
/// moment the user returned from Maps. Living on `RootView` instead means a PiP session survives
/// exactly the transitions it exists to survive.
///
/// Simplified first version: shows just the current speed, at a modest 4fps (plenty for a number
/// that changes a few times a second at most, and easy on battery). `AVSampleBufferDisplayLayer`
/// needs to sit in the view hierarchy for PiP's start/stop animations to have something to
/// animate from/to, even though nothing about it is ever meant to be visible in the app's own UI
/// — `hostView` below is a zero-size, hidden container that exists solely for that.
@MainActor
final class FloatingSpeedPiPController: NSObject, ObservableObject {
    @Published private(set) var isActive = false
    /// Set when `start(with:)` can't proceed (e.g. PiP unsupported on this device/configuration)
    /// — the menu button used to just silently do nothing in that case. The view clears this by
    /// setting it back to nil once it's been shown.
    @Published var lastError: String?

    private weak var controller: DashboardController?
    private let displayLayer = AVSampleBufferDisplayLayer()
    private let hostView = UIView(frame: .zero)
    private var pipController: AVPictureInPictureController?
    private var renderTimer: Timer?
    private var pixelBufferPool: CVPixelBufferPool?
    private var formatDescription: CMVideoFormatDescription?
    private var renderer: ImageRenderer<FloatingSpeedView>?
    private var frameCount: Int64 = 0

    private let renderSize = CGSize(width: 320, height: 180)
    private let frameRate: Int32 = 4

    deinit {
        // `self` can't be touched here (deinit isn't actor-isolated even on a @MainActor class),
        // but invalidating a Timer is a plain, thread-safe Foundation call — this is just a last
        // safety net in case `stop()` was never called before the last strong reference dropped.
        renderTimer?.invalidate()
    }

    func start(with controller: DashboardController) {
        guard !isActive else { return }
        guard AVPictureInPictureController.isPictureInPictureSupported() else {
            lastError = "此裝置目前不支援子母畫面，無法開啟浮動車速視窗。"
            return
        }
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

    /// Only requests the stop — `pictureInPictureControllerDidStopPictureInPicture` is what
    /// actually tears things down, once the system confirms the (animated, asynchronous) stop
    /// has completed. Calling `teardown()` synchronously here too used to release `pipController`
    /// and detach `hostView` from its window mid-animation, orphaning the dismiss animation and
    /// then running the same cleanup a second time once the delegate callback landed regardless.
    func stop() {
        pipController?.stopPictureInPicture()
    }

    /// The one place PiP-has-ended cleanup happens — see `stop()`'s doc comment for why it's only
    /// ever called from the delegate callbacks below, never synchronously from `stop()` itself.
    private func teardown() {
        renderTimer?.invalidate()
        renderTimer = nil
        pipController = nil
        renderer = nil
        hostView.removeFromSuperview()
        isActive = false
    }

    private func installHostViewIfNeeded() {
        guard hostView.superview == nil else { return }
        hostView.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
        hostView.isHidden = true
        hostView.isUserInteractionEnabled = false
        if displayLayer.superlayer == nil {
            displayLayer.frame = hostView.bounds
            hostView.layer.addSublayer(displayLayer)
        }
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

        let speedKph = controller.state.effectiveSpeedKph.map { Int($0) }
        // One `ImageRenderer` reused across every frame (only its `.content` changes) instead of
        // constructing a fresh one 4 times a second — this view is meant to keep rendering for an
        // entire drive, and rebuilding the whole SwiftUI render graph from scratch on every tick
        // was avoidable sustained CPU/battery cost for a view whose only variation is one integer.
        let view = FloatingSpeedView(speedKph: speedKph, size: renderSize)
        let activeRenderer: ImageRenderer<FloatingSpeedView>
        if let existing = renderer {
            existing.content = view
            activeRenderer = existing
        } else {
            let created = ImageRenderer(content: view)
            created.scale = UIScreen.main.scale
            renderer = created
            activeRenderer = created
        }
        guard let cgImage = activeRenderer.cgImage else { return }

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
        teardown()
    }

    /// `start(with:)` sets `isActive = true` right after calling `startPictureInPicture()`, which
    /// only *requests* the start — without handling this failure callback, a start that the
    /// system rejects (any number of legitimate reasons: low memory, background app refresh off,
    /// PiP still settling from a previous session) left `isActive` latched true with the render
    /// timer running forever and no window ever appearing, and the `guard !isActive` at the top of
    /// `start(with:)` blocked every retry — the user's only way out was toggling the (wrongly
    /// labeled) "關閉浮動車速視窗" menu item.
    func pictureInPictureController(_ pictureInPictureController: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        lastError = "子母畫面啟動失敗：\(error.localizedDescription)"
        teardown()
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
