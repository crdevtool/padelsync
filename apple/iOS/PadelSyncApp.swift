import PadelSyncCore
import SwiftUI

@main
struct PadelSyncApp: App {
    @StateObject private var store = CourtStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
                .tint(Palette.accent)
                .onAppear {
                    if ProcessInfo.processInfo.arguments.contains("-demoMatch") { store.runDemo() }
                }
        }
    }
}

/// Chooses the screen. A live match always wins, so the app reopens on the scoreboard.
struct RootView: View {
    private enum Screen { case home, setupSolo, setupHost, join, history }

    @EnvironmentObject private var store: CourtStore
    @State private var screen: Screen = .home
    /// Set when the host picks "New match" from the scoreboard.
    @State private var replacingMatch = false

    /// Whether the match being set up will be shared with other devices.
    private var settingUpHosted: Bool {
        replacingMatch ? store.courtOpen : screen == .setupHost
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if store.mode != .idle && !replacingMatch {
                MatchView(onNewMatch: { replacingMatch = true })
            } else if replacingMatch || screen == .setupSolo || screen == .setupHost {
                setup
            } else if screen == .join {
                JoinView(onBack: { screen = .home })
            } else if screen == .history {
                HistoryView(onBack: { screen = .home })
            } else {
                HomeView(
                    hasSavedMatch: store.hasSavedMatch,
                    onNewMatch: { screen = .setupSolo },
                    onHostMatch: { screen = .setupHost },
                    onJoin: { screen = .join },
                    onResume: { store.resumeSavedMatch() },
                    onHistory: { screen = .history }
                )
            }
        }
    }

    private var setup: some View {
        let hosting = settingUpHosted
        let opensCourt = hosting && !replacingMatch
        return SetupView(
            title: opensCourt ? "Host a match" : "New match",
            startLabel: opensCourt ? "Start and open the court" : "Start match",
            hosting: hosting,
            initial: store.lastSetup,
            voiceOn: store.speech.enabled,
            onVoiceChange: { store.setSpeech(store.speech.with(enabled: $0)) },
            onStart: { chosen in
                if replacingMatch {
                    store.startNewMatch(chosen)
                } else {
                    store.startMatch(chosen)
                    if hosting { store.openCourt() }
                }
                replacingMatch = false
                screen = .home
            },
            onBack: {
                replacingMatch = false
                screen = .home
            }
        )
    }
}
