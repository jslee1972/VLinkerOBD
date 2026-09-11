import AVFoundation

/// Thin wrapper around `AVSpeechSynthesizer` for the app's two spoken announcements (battery
/// voltage, gear changes — see `DashboardController.announceIfNeeded`). Keeping this as its own
/// tiny class rather than reaching for `AVSpeechSynthesizer` directly in the controller keeps
/// AVFoundation specifics out of the polling/trip-computer logic.
@MainActor
final class SpeechAnnouncer {
    private let synthesizer = AVSpeechSynthesizer()

    init() {
        // `.playback` (as opposed to the default `.soloAmbient`) is what lets these announcements
        // through even when the phone's silent switch is flipped — the same choice navigation
        // apps make for turn-by-turn voice prompts, since a driving-time alert that goes silent
        // whenever the ringer is muted defeats the point of it being spoken at all.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .voicePrompt, options: [.duckOthers])
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    func speak(_ text: String) {
        let utterance = AVSpeechUtterance(string: text)
        // Falls back to Simplified Chinese only if a Traditional voice genuinely isn't installed
        // on the device — both read this app's short phrases the same way either lets it speak
        // rather than silently drop the announcement.
        utterance.voice = AVSpeechSynthesisVoice(language: "zh-TW") ?? AVSpeechSynthesisVoice(language: "zh-CN")
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        synthesizer.speak(utterance)
    }
}
