import AVFoundation
import Foundation

/// Speaks the score through the iPhone's or Apple Watch's text-to-speech
/// voice. The Android counterpart is `Announcer`.
///
/// A new announcement replaces one still being spoken: the score that
/// matters is the latest. While it talks, other audio (music, a podcast) is
/// turned down rather than stopped, and gets its volume back afterwards.
///
/// Call every method from the main thread.
final class Announcer: NSObject, AVSpeechSynthesizerDelegate {
    // Held for the life of the app: a synthesizer that is let go stops talking.
    private let synthesizer = AVSpeechSynthesizer()
    private var failed = false

    /// The voice to speak with, looked up on first use: a device with
    /// announcements switched off never asks for it.
    private var voice: AVSpeechSynthesisVoice?
    private var lookedForVoice = false

    /// Whether other audio is currently turned down for an announcement.
    private var ducking = false

    /// The announcement being spoken, so the end of an older one that was
    /// cut short is not mistaken for the end of this one.
    private var latest: AVSpeechUtterance?

    /// Whether the device turned out to have no usable voice.
    var unavailable: Bool { failed }

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Speaks `phrases` as one announcement. Does nothing for an empty list.
    func say(_ phrases: [String]) {
        if phrases.isEmpty { return }
        if !lookedForVoice { findVoice() }
        if failed { return }
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
        let utterance = AVSpeechUtterance(string: phrases.joined(separator: " "))
        // Without a voice of our choosing the system uses its default one.
        if let voice { utterance.voice = voice }
        latest = utterance
        duckOtherAudio()
        synthesizer.speak(utterance)
    }

    /// Forgets an earlier failure to find a voice, so the next `say` looks
    /// again. Call it when the player asks for speech explicitly.
    func retry() {
        if failed {
            failed = false
            lookedForVoice = false
        }
    }

    /// Stops talking at once.
    func silence() {
        latest = nil
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
        releaseAudio()
    }

    private func findVoice() {
        lookedForVoice = true
        // The wording is English; fall back to whatever the device has.
        voice = AVSpeechSynthesisVoice(language: "en-US")
            ?? AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
        failed = voice == nil && AVSpeechSynthesisVoice.speechVoices().isEmpty
    }

    // Spoken like a sat-nav prompt: over the top of any music, briefly lowering it.
    private func duckOtherAudio() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers])
            try session.setActive(true)
            ducking = true
        } catch {
            // Speech still works with the default audio session; only the ducking is lost.
        }
    }

    /// Gives the music its volume back.
    private func releaseAudio() {
        if !ducking { return }
        ducking = false
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    /// Releases the audio once the latest announcement has ended.
    private func ended(_ utterance: AVSpeechUtterance) {
        // The synthesizer reports from a thread of its own choosing.
        DispatchQueue.main.async { [weak self] in
            guard let self, utterance === self.latest else { return }
            self.latest = nil
            self.releaseAudio()
        }
    }

    // MARK: AVSpeechSynthesizerDelegate

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        ended(utterance)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        ended(utterance)
    }
}
