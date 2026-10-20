import PadelSyncCore
import SwiftUI

/// The first screen: three ways into a match, and the history.
struct HomeView: View {
    let hasSavedMatch: Bool
    /// Shows PRO and the trial on "Host a match".
    let pro: ProStatus
    let onNewMatch: () -> Void
    let onHostMatch: () -> Void
    let onJoin: () -> Void
    let onResume: () -> Void
    let onHistory: () -> Void

    var body: some View {
        GeometryReader { proxy in
            ScrollView {
                VStack(spacing: 14) {
                    // A small court as the app's signature.
                    CourtBackground(sport: Sport.padel, server: Team.a, serveSide: ServeSide.right)
                        .frame(width: 92, height: 184)
                    Text("PadelSync")
                        .font(.system(size: 44, weight: .black, design: .rounded))
                        .foregroundStyle(Palette.accent)
                    Text("One score on every phone and watch on the court.")
                        .font(.title3)
                        .foregroundStyle(Palette.muted)
                        .multilineTextAlignment(.center)
                        .padding(.bottom, 18)

                    HomeAction(
                        title: "New match",
                        caption: "Keep score on this phone · \(ProText.shared.FREE)",
                        surface: Palette.accent,
                        titleColor: Palette.onAccent,
                        captionColor: Palette.onAccent.opacity(0.75),
                        action: onNewMatch
                    )
                    HomeAction(
                        title: "Host a match",
                        caption: "Play with others: every phone and watch shows the score",
                        surface: Palette.teamA.opacity(0.22),
                        titleColor: Color.white,
                        captionColor: Palette.muted,
                        proMark: pro.showsProMark,
                        line: pro.homeLine,
                        action: onHostMatch
                    )
                    HomeAction(
                        title: "Join a court",
                        caption: "Follow or score a match someone nearby is hosting · \(ProText.shared.FREE)",
                        surface: Palette.teamB.opacity(0.22),
                        titleColor: Color.white,
                        captionColor: Palette.muted,
                        action: onJoin
                    )
                    if hasSavedMatch {
                        HomeAction(
                            title: "Resume last match",
                            caption: "Pick up where this phone left off",
                            surface: Palette.surface,
                            titleColor: Color.white,
                            captionColor: Palette.muted,
                            action: onResume
                        )
                    }
                    Button("Match history", action: onHistory)
                        .font(.title3)
                        .padding(.top, 4)
                }
                .padding(24)
                .frame(maxWidth: .infinity, minHeight: proxy.size.height)
            }
        }
    }
}

private struct HomeAction: View {
    let title: String
    let caption: String
    let surface: Color
    let titleColor: Color
    let captionColor: Color
    /// A small PRO label after the title.
    var proMark = false
    /// A third line in the accent colour, for the trial.
    var line: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(title)
                            .font(.system(size: 22, weight: .black))
                            .foregroundStyle(titleColor)
                        if proMark { ProMark() }
                    }
                    Text(caption)
                        .font(.subheadline)
                        .foregroundStyle(captionColor)
                        .multilineTextAlignment(.leading)
                    if let line {
                        Text(line)
                            .font(.subheadline.weight(.bold))
                            .foregroundStyle(Palette.accent)
                    }
                }
                Spacer(minLength: 0)
                Text("›")
                    .font(.system(size: 30, weight: .black))
                    .foregroundStyle(titleColor)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: 20).fill(surface))
            .contentShape(RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain)
        // Spoken, and found by the walkthrough test, by its title alone.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityHint([caption, line].compactMap { $0 }.joined(separator: ". "))
        .accessibilityAddTraits(.isButton)
    }
}

/// The small PRO label next to a feature that needs Pro.
struct ProMark: View {
    var body: some View {
        Text("PRO")
            .font(.system(size: 11, weight: .black))
            .foregroundStyle(Palette.onAccent)
            .padding(.horizontal, 7)
            .padding(.vertical, 1)
            .background(RoundedRectangle(cornerRadius: 8).fill(Palette.accent))
    }
}
