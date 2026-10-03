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
            HStack {
                Button("Back", action: onBack)
                Text("Match history")
                    .font(.title.weight(.bold))
                    .foregroundStyle(.white)
            }

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
        let winner = score.winner
        let config = record.snapshot.config
        let finished = Date(timeIntervalSince1970: TimeInterval(record.finishedAtMillis) / 1000)
        return VStack(alignment: .leading, spacing: 2) {
            Text(winner.map { "\(Labels.team($0)) won" } ?? "Unfinished")
                .font(.headline.weight(.black))
                .foregroundStyle(winner == Team.b ? Palette.teamB : Palette.teamA)
            Text(score.setSummary)
                .font(.system(size: 26, weight: .black, design: .rounded))
                .foregroundStyle(.white)
            Text(
                "\(Labels.sport(config.sport)) · \(config.bestOf == 1 ? "1 set" : "Best of \(config.bestOf)")"
                    + " · \(Labels.deuceRule(config.deuceRule))"
                    + " · \(duration(record.durationMillis))"
            )
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

    private func summary(_ records: [MatchRecord]) -> String {
        let winsA = records.filter { $0.score.winner == Team.a }.count
        let total = records.reduce(Int64(0)) { $0 + $1.durationMillis }
        return "\(records.count) played · Team A won \(winsA) · Team B won \(records.count - winsA) · "
            + "\(duration(total)) on court"
    }

    /// A duration as "48 min" or "1 h 12 min".
    private func duration(_ millis: Int64) -> String {
        let minutes = Int(millis / 60_000)
        return minutes < 60 ? "\(minutes) min" : "\(minutes / 60) h \(minutes % 60) min"
    }
}
