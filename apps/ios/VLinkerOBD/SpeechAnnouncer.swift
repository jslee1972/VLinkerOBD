import AVFoundation

/// Thin wrapper around `AVSpeechSynthesizer` for the app's two spoken announcements (battery
/// voltage, gear changes — see `DashboardController.announceIfNeeded`). Keeping this as its own
/// tiny class rather than reaching for `AVSpeechSynthesizer` directly in the controller keeps
/// AVFoundation specifics out of the polling/trip-computer logic.
@MainActor
final class SpeechAnnouncer {
    private let synthesizer = AVSpeechSynthesizer()
    private var hasConfiguredAudioSession = false

    func speak(_ text: String) {
        configureAudioSessionIfNeeded()
        let utterance = AVSpeechUtterance(string: text)
        // Falls back to Simplified Chinese only if a Traditional voice genuinely isn't installed
        // on the device — both read this app's short phrases the same way either lets it speak
        // rather than silently drop the announcement.
        utterance.voice = AVSpeechSynthesisVoice(language: "zh-TW") ?? AVSpeechSynthesisVoice(language: "zh-CN")
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        synthesizer.speak(utterance)
    }

    /// Deferred to the first actual announcement rather than done in `init()` — this class is
    /// constructed as soon as the app launches (as a `DashboardController` property), and
    /// `.duckOthers` takes over any other app's audio (Spotify, a podcast) the instant the session
    /// is activated. Doing that unconditionally at launch — before the user has even connected to
    /// a vehicle, let alone triggered a real announcement — ducked other apps' audio for no reason.
    private func configureAudioSessionIfNeeded() {
        guard !hasConfiguredAudioSession else { return }
        // `.playback` (as opposed to the default `.soloAmbient`) is what lets these announcements
        // through even when the phone's silent switch is flipped — the same choice navigation
        // apps make for turn-by-turn voice prompts, since a driving-time alert that goes silent
        // whenever the ringer is muted defeats the point of it being spoken at all.
        //
        // The flag only latches on success (a real `do`/`catch`, not `try?`) — `setActive(true)`
        // is exactly the kind of call that can fail transiently (an in-progress phone call, some
        // other app briefly holding an exclusive session), and latching on a failed attempt would
        // have permanently left every later announcement running against the default category
        // with no retry, silenced by the ringer switch for the rest of the session.
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .voicePrompt, options: [.duckOthers])
            try AVAudioSession.sharedInstance().setActive(true)
            hasConfiguredAudioSession = true
        } catch {
            // Left false so the next speak() call retries from scratch.
        }
    }
}
