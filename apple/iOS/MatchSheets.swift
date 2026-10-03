import PadelSyncCore
import SwiftUI

/// Host only: which of the joined devices may change the score.
struct WhoCanScoreSheet: View {
    @EnvironmentObject private var store: CourtStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SwitchRow(
                        title: "Everyone who joins",
                        caption: store.guestsCanScore
                            ? "New devices can add points as soon as they join"
                            : "New devices only watch until you allow them",
                        isOn: store.guestsCanScore
                    ) { store.setGuestsCanScore($0) }
                }
                Section("On this court") {
                    if store.guests.isEmpty {
                        Text("No other device has joined yet.")
                            .foregroundStyle(Palette.muted)
                    } else {
                        ForEach(store.guests, id: \.deviceId) { guest in
                            SwitchRow(
                                title: guest.deviceName.isEmpty ? "Unnamed device" : guest.deviceName,
                                caption: guest.deviceKind == DeviceKind.watch ? "Watch" : "Phone",
                                isOn: guest.canScore
                            ) { store.setCanScore(deviceId: guest.deviceId, allowed: $0) }
                        }
                    }
                }
                Section {
                    Text("This phone, as the host, can always score.")
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                }
            }
            .navigationTitle("Who can score")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

/// What this device says out loud, and how often.
struct VoiceSheet: View {
    @EnvironmentObject private var store: CourtStore
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let settings = store.speech
        let on = settings.enabled
        NavigationStack {
            Form {
                if !store.voiceAvailable {
                    Text(
                        "This phone has no text-to-speech voice installed. Add one in Settings under "
                            + "Accessibility, Spoken Content, then try again."
                    )
                    .foregroundStyle(Palette.danger)
                }
                Section {
                    SwitchRow(
                        title: "Call the score out loud",
                        caption: "On this phone only. Lowers any music while it speaks.",
                        isOn: on
                    ) { store.setSpeech(settings.with(enabled: $0)) }
                }
                Section {
                    SwitchRow(title: "Every point", caption: "\"30 15\", \"Deuce\"", isOn: settings.points, enabled: on) {
                        store.setSpeech(settings.with(points: $0))
                    }
                    SwitchRow(
                        title: "Games, sets and match",
                        caption: "\"Game, Ana and Leo\"",
                        isOn: settings.games,
                        enabled: on
                    ) { store.setSpeech(settings.with(games: $0)) }
                    SwitchRow(
                        title: "Big points",
                        caption: "Break, set and match point",
                        isOn: settings.stakes,
                        enabled: on
                    ) { store.setSpeech(settings.with(stakes: $0)) }
                    SwitchRow(
                        title: "Who serves",
                        caption: "At the start of each game",
                        isOn: settings.server,
                        enabled: on
                    ) { store.setSpeech(settings.with(server: $0)) }
                    SwitchRow(title: "Change ends", isOn: settings.changeEnds, enabled: on) {
                        store.setSpeech(settings.with(changeEnds: $0))
                    }
                }
                Section {
                    OptionGroup(
                        title: "Repeat the whole score every",
                        options: [0, 2, 5, 10],
                        selected: Int(settings.reminderMinutes),
                        label: { $0 == 0 ? "Never" : "\($0) min" },
                        onSelect: { store.setSpeech(settings.with(reminderMinutes: $0)) }
                    )
                    .disabled(!on)
                }
                Section {
                    Button("Say the score now") { store.sayScore() }
                }
            }
            .navigationTitle("Voice")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

/// Host only: correct or add the players' names during a match.
struct PlayersSheet: View {
    @EnvironmentObject private var store: CourtStore
    @Environment(\.dismiss) private var dismiss

    private let doubles: Bool
    @State private var a1: String
    @State private var a2: String
    @State private var b1: String
    @State private var b2: String

    init(score: ScoreView) {
        doubles = score.doubles
        _a1 = State(initialValue: PlayersSheet.name(score.playersA, 0))
        _a2 = State(initialValue: PlayersSheet.name(score.playersA, 1))
        _b1 = State(initialValue: PlayersSheet.name(score.playersB, 0))
        _b2 = State(initialValue: PlayersSheet.name(score.playersB, 1))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                PlayerFields(doubles: doubles, a1: $a1, a2: $a2, b1: $b1, b2: $b2)
                    .padding(20)
            }
            .navigationTitle("Players")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        store.updateRoster(rosterOf(doubles: doubles, a1: a1, a2: a2, b1: b1, b2: b2))
                        dismiss()
                    }
                }
            }
        }
    }

    private static func name(_ players: [String], _ index: Int) -> String {
        index < players.count ? players[index] : ""
    }
}
