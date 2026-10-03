import PadelSyncCore
import SwiftUI

@main
struct PadelSyncWatchApp: App {
    @StateObject private var store = CourtStore()

    var body: some Scene {
        WindowGroup {
            WatchRootView()
                .environmentObject(store)
                .onAppear {
                    if ProcessInfo.processInfo.arguments.contains("-demoMatch") { store.runDemo() }
                }
        }
    }
}

struct WatchRootView: View {
    private enum Screen { case home, join, code }

    @EnvironmentObject private var store: CourtStore
    @State private var screen: Screen = .home
    @State private var chosen: NearbyCourt?

    var body: some View {
        if store.mode == .guest && store.guestRejected {
            WatchMessageView(
                title: "Could not join",
                message: Labels.rejection(store.rejection),
                button: "Back"
            ) {
                store.leave()
                screen = .home
            }
        } else if store.mode == .guest && store.guestEnded {
            WatchMessageView(
                title: Labels.courtClosedTitle,
                message: Labels.courtClosedBody,
                button: "Back",
                // The match could carry on from another device, but not from a watch.
                extra: store.canTakeOver ? Labels.askPhoneToHost : nil
            ) {
                store.leave()
                screen = .home
            }
        } else if store.mode != .idle {
            if let score = store.score {
                WatchMatchView(score: score)
            } else {
                WatchMessageView(title: "Connecting…", message: "Stay near the host.", button: "Cancel") {
                    store.leave()
                    screen = .home
                }
            }
        } else if screen == .join {
            WatchJoinView(
                onChoose: { court in
                    chosen = court
                    screen = .code
                },
                onBack: { screen = .home }
            )
        } else if screen == .code {
            WatchCodeView(
                onBack: { screen = .join },
                onDone: { code in
                    screen = .home
                    if let court = chosen { store.join(court, code: code) }
                }
            )
        } else {
            WatchHomeView(onJoin: { screen = .join })
        }
    }
}

/// The two formats a match can be started in from the watch, where there is
/// no room for a setup screen.
enum WatchFormats {
    /// Club padel: best of 3, golden point, doubles. Every set is played,
    /// as social padel usually is whatever the score.
    static var padel: MatchConfig {
        Sessions.shared.config(
            sport: Sport.padel,
            bestOf: 3,
            deuceRule: DeuceRule.goldenPoint,
            finalSetRule: FinalSetRule.sameAsOtherSets,
            firstServer: Team.a,
            playAllSets: true,
            doubles: true
        )
    }

    static var tennis: MatchConfig {
        MatchConfig.companion.tennis()
    }
}

struct WatchHomeView: View {
    @EnvironmentObject private var store: CourtStore
    let onJoin: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text("PadelSync")
                    .font(.headline.weight(.black))
                    .foregroundStyle(Palette.accent)
                Button("New padel match") { store.startMatch(WatchFormats.padel) }
                    .tint(Palette.accent)
                Button("New tennis match") { store.startMatch(WatchFormats.tennis) }
                Button("Join a court", action: onJoin)
                if store.hasSavedMatch {
                    Button("Resume last match") { store.resumeSavedMatch() }
                }
            }
        }
    }
}

struct WatchMessageView: View {
    let title: String
    let message: String
    let button: String
    /// A further line of advice, or nil for none.
    var extra: String?
    let action: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text(title).font(.headline)
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(Palette.muted)
                    .multilineTextAlignment(.center)
                if let extra = extra {
                    Text(extra)
                        .font(.footnote)
                        .multilineTextAlignment(.center)
                }
                Button(button, action: action)
            }
        }
    }
}

/// Lists courts being hosted nearby.
struct WatchJoinView: View {
    @EnvironmentObject private var store: CourtStore
    let onChoose: (NearbyCourt) -> Void
    let onBack: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text("Join a court").font(.headline)
                if let error = store.error {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(Palette.danger)
                        .multilineTextAlignment(.center)
                    Button("Try again") { store.startScan() }
                } else if store.nearby.isEmpty {
                    Text("Looking nearby…")
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                }
                ForEach(store.nearby) { court in
                    Button(court.name) { onChoose(court) }
                        .tint(Palette.accent)
                }
                Button("Back", action: onBack)
            }
        }
        .onAppear { store.startScan() }
        .onDisappear { store.stopScan() }
    }
}

/// A numeric keypad for the 4-digit join code.
struct WatchCodeView: View {
    let onBack: () -> Void
    let onDone: (Int?) -> Void

    @State private var code = ""
    private let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["<", "0", "OK"]]

    var body: some View {
        VStack(spacing: 3) {
            Text(code.padding(toLength: 4, withPad: "•", startingAt: 0))
                .font(.system(size: 18, weight: .black, design: .rounded))
                .foregroundStyle(Palette.accent)
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 3) {
                    ForEach(row, id: \.self) { key in
                        let isOk = key == "OK"
                        let enabled = !isOk || code.count == 4
                        Button {
                            press(key)
                        } label: {
                            Text(key)
                                .font(.system(size: 15, weight: .bold))
                                .frame(maxWidth: .infinity, maxHeight: .infinity)
                                .foregroundStyle(isOk && enabled ? Palette.onAccent : Color.white)
                                .background(
                                    RoundedRectangle(cornerRadius: 9)
                                        .fill(isOk && enabled ? Palette.accent : Palette.surface)
                                )
                        }
                        .buttonStyle(.plain)
                        .disabled(!enabled)
                    }
                }
            }
        }
        .padding(.horizontal, 4)
    }

    private func press(_ key: String) {
        switch key {
        case "<":
            if code.isEmpty { onBack() } else { code.removeLast() }
        case "OK":
            onDone(Int(code))
        default:
            if code.count < 4 { code += key }
        }
    }
}
