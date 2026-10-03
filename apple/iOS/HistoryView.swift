import PadelSyncCore
import SwiftUI

/// Finished matches this device took part in, newest first.
struct HistoryView: View {
    @EnvironmentObject private var store: CourtStore
    let onBack: () -> Void

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    var body: some View {
        let records = store.history
        VStack(alignment: .leading, spacing: 12) {
            ScreenHeader(title: "Match history", onBack: onBack)

            if records.isEmpty {
                Text("No finished matches yet.")
                    .font(.title3)
                    .foregroundStyle(Palette.muted)
            } else {
                Text(summary(records))
                    .font(.subheadline)
                    .foregroundStyle(Palette.muted)

                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(records, id: \.matchId) { record in
                            row(record)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(20)
    }

    private func row(_ record: MatchRecord) -> some View {
        let score = record.score
        // A match stopped after it was decided still has a winner.
        let winner = score.winner ?? score.decidedWinner
        let finished = Date(timeIntervalSince1970: TimeInterval(record.finishedAtMillis) / 1000)
        return VStack(alignment: .leading, spacing: 2) {
            Text(headline(score, winner: winner))
                .font(.headline.weight(.black))
                .foregroundStyle(winner == Team.b ? Palette.teamB : Palette.teamA)
            Text(score.setSummary)
                .font(.system(size: 26, weight: .black, design: .rounded))
                .foregroundStyle(.white)
            Text("\(Labels.format(record.snapshot.config)) · \(Labels.duration(record.durationMillis))")
                .font(.footnote)
                .foregroundStyle(Palette.muted)
            Text(HistoryView.dateFormatter.string(from: finished))
                .font(.footnote)
                .foregroundStyle(Palette.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Palette.surface))
    }

    /// `Ana & Leo beat Mia & Sam`, or the two names when nobody won.
    private func headline(_ score: ScoreView, winner: Team?) -> String {
        guard let winner else { return "\(score.nameA) vs \(score.nameB)" }
        return "\(score.nameOf(team: winner)) beat \(score.nameOf(team: winner.opponent))"
    }

    private func summary(_ records: [MatchRecord]) -> String {
        let total = records.reduce(Int64(0)) { $0 + $1.durationMillis }
        return "\(records.count) played · \(Labels.duration(total)) on court"
    }
}
