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
                .onAppear {
                    if ProcessInfo.processInfo.arguments.contains("-demoMatch") { store.runDemo() }
                }
        }
    }
}

/// Chooses the screen. A live match always wins, so the app reopens on the scoreboard.
struct RootView: View {
    private enum Screen { case home, setup, join }

    @EnvironmentObject private var store: CourtStore
    @State private var screen: Screen = .home
    /// Set when the host picks "New match" from the scoreboard.
    @State private var replacingMatch = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if store.mode != .idle && !replacingMatch {
                MatchView(onNewMatch: { replacingMatch = true })
            } else if replacingMatch || screen == .setup {
                SetupView(
                    onStart: { config in
                        if replacingMatch { store.startNewMatch(config) } else { store.startMatch(config) }
                        replacingMatch = false
                        screen = .home
                    },
                    onBack: {
                        replacingMatch = false
                        screen = .home
                    }
                )
            } else if screen == .join {
                JoinView(onBack: { screen = .home })
            } else {
                HomeView(
                    onNewMatch: { screen = .setup },
                    onJoin: { screen = .join }
                )
            }
        }
    }
}

struct HomeView: View {
    @EnvironmentObject private var store: CourtStore
    let onNewMatch: () -> Void
    let onJoin: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Text("PadelSync")
                .font(.system(size: 46, weight: .black, design: .rounded))
                .foregroundStyle(Palette.accent)
            Text("One score on every phone and watch on the court.")
                .font(.title3)
                .foregroundStyle(Palette.muted)
                .multilineTextAlignment(.center)
            Spacer().frame(height: 32)

            BigButton(title: "New match", filled: true, action: onNewMatch)
            BigButton(title: "Join a court", filled: false, action: onJoin)
            if store.hasSavedMatch {
                BigButton(title: "Resume last match", filled: false) { store.resumeSavedMatch() }
            }
            Spacer()
        }
        .padding(24)
    }
}

/// A full-width button tall enough to hit without looking.
struct BigButton: View {
    let title: String
    let filled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.title3.weight(.bold))
                .frame(maxWidth: .infinity, minHeight: 64)
                .foregroundStyle(filled ? Palette.onAccent : Palette.accent)
                .background(
                    RoundedRectangle(cornerRadius: 32)
                        .fill(filled ? Palette.accent : Color.clear)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 32)
                        .stroke(Palette.muted, lineWidth: filled ? 0 : 1)
                )
        }
        .buttonStyle(.plain)
    }
}
