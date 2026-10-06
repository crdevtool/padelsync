import Foundation
import PadelSyncCore

/// What a player chose on the setup screen.
struct MatchSetup {
    let config: MatchConfig
    let roster: Roster
    /// Whether devices that join may change the score. Only matters when hosting.
    let guestsCanScore: Bool
}

/// This device's own choices, kept between launches: how it announces the
/// score and the last match that was set up, so the same four friends do not
/// have to type their names every week. The Android counterpart is
/// `SettingsStore`.
final class SettingsStore {
    private let defaults = UserDefaults.standard

    /// Voice settings for this device. The voice is off until the player
    /// switches it on. The choice is kept apart for hosting and for joining,
    /// so a phone that speaks when it hosts stays quiet as a guest and four
    /// phones on one court do not all talk at once.
    func speech(hosting: Bool) -> SpeechSettings {
        let enabled = bool(hosting ? Keys.voiceHost : Keys.voiceGuest, fallback: false)
        // The core refuses a reminder outside 0...60 minutes.
        let minutes = min(max(defaults.integer(forKey: Keys.voiceReminder), 0), 60)
        return SpeechSettings(
            enabled: enabled,
            points: bool(Keys.voicePoints, fallback: true),
            games: bool(Keys.voiceGames, fallback: true),
            stakes: bool(Keys.voiceStakes, fallback: true),
            server: bool(Keys.voiceServer, fallback: true),
            changeEnds: bool(Keys.voiceEnds, fallback: true),
            reminderMinutes: Int32(minutes)
        )
    }

    func saveSpeech(_ settings: SpeechSettings, hosting: Bool) {
        defaults.set(settings.enabled, forKey: hosting ? Keys.voiceHost : Keys.voiceGuest)
        defaults.set(settings.points, forKey: Keys.voicePoints)
        defaults.set(settings.games, forKey: Keys.voiceGames)
        defaults.set(settings.stakes, forKey: Keys.voiceStakes)
        defaults.set(settings.server, forKey: Keys.voiceServer)
        defaults.set(settings.changeEnds, forKey: Keys.voiceEnds)
        defaults.set(Int(settings.reminderMinutes), forKey: Keys.voiceReminder)
    }

    /// The last match set up on this device, or nil on first use.
    func lastSetup() -> MatchSetup? {
        guard let saved = defaults.data(forKey: Keys.setup),
              let snapshot = HostSession.companion.restoreSnapshot(saved: saved.toKotlinByteArray())
        else { return nil }
        return MatchSetup(
            config: snapshot.config,
            roster: snapshot.roster,
            guestsCanScore: bool(Keys.guestsScore, fallback: true)
        )
    }

    func saveSetup(_ setup: MatchSetup) {
        // Stored as an empty match, which reuses the versioned wire format.
        // A throwaway host session is the way to that format from Swift; the
        // device id, code (-1 is the core's "no code") and clock do not matter.
        let empty = Sessions.shared.host(
            config: setup.config,
            roster: setup.roster,
            hostDeviceId: 1,
            joinCode: -1,
            guestsCanScore: setup.guestsCanScore,
            nowMillis: 0
        )
        defaults.set(empty.savedState().toData(), forKey: Keys.setup)
        defaults.set(setup.guestsCanScore, forKey: Keys.guestsScore)
    }

    /// `UserDefaults.bool` cannot tell "off" from "never set".
    private func bool(_ key: String, fallback: Bool) -> Bool {
        defaults.object(forKey: key) == nil ? fallback : defaults.bool(forKey: key)
    }

    private enum Keys {
        static let voiceHost = "voice_when_hosting"
        static let voiceGuest = "voice_when_guest"
        static let voicePoints = "voice_points"
        static let voiceGames = "voice_games"
        static let voiceStakes = "voice_stakes"
        static let voiceServer = "voice_server"
        static let voiceEnds = "voice_change_ends"
        static let voiceReminder = "voice_reminder_minutes"
        static let setup = "last_setup"
        static let guestsScore = "guests_can_score"
    }
}
