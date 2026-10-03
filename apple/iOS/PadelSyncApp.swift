import PadelSyncCore
import SwiftUI
import UIKit

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
    @Environment(\.scenePhase) private var scenePhase
    @State private var screen: Screen = .home
    /// Set when the host picks "New match" from the scoreboard.
    @State private var replacingMatch = false
    /// The app went to the background, or the phone was locked, with a court open.
    @State private var hiddenWithCourtOpen = false

    /// A court in the sun is no place for a screen that dims itself, and an
    /// open court can only be found by Android devices while this app is on
    /// screen. So the screen stays awake while a match screen is showing or a
    /// court is open, whichever screen that is: the setup for a new match on
    /// an open court counts too.
    private var keepAwake: Bool {
        (store.mode != .idle && !replacingMatch) || store.courtOpen
    }

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
        // The one place that decides whether the screen may dim.
        .onAppear { UIApplication.shared.isIdleTimerDisabled = keepAwake }
        .onChange(of: keepAwake) { _, awake in
            UIApplication.shared.isIdleTimerDisabled = awake
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background {
                if store.courtOpen { hiddenWithCourtOpen = true }
            } else if phase == .active && hiddenWithCourtOpen {
                // Not on the first activation: only after a spell out of sight.
                hiddenWithCourtOpen = false
                store.courtWasInBackground()
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
