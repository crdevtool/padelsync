import SwiftUI

/// Lists courts being hosted nearby and joins the one the player picks.
struct JoinView: View {
    @EnvironmentObject private var store: CourtStore
    let onBack: () -> Void

    @State private var chosen: NearbyCourt?
    @State private var code = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button("Back", action: onBack)
                Text("Join a court")
                    .font(.title.weight(.bold))
                    .foregroundStyle(.white)
            }

            if let error = store.error {
                Text(error).foregroundStyle(Palette.danger)
                Button("Try again") { store.startScan() }
            } else if store.nearby.isEmpty {
                Text("Looking for courts nearby…")
                    .font(.title3)
                    .foregroundStyle(Palette.muted)
                Text("On the host's device, open the match menu and choose \"Play with others\".")
                    .font(.subheadline)
                    .foregroundStyle(Palette.muted)
            }

            ScrollView {
                VStack(spacing: 10) {
                    ForEach(store.nearby) { court in
                        Button {
                            code = ""
                            chosen = court
                        } label: {
                            HStack {
                                Text(court.name)
                                    .font(.title3.weight(.bold))
                                    .foregroundStyle(.white)
                                Spacer()
                                Text(signalLabel(court.rssi))
                                    .font(.footnote)
                                    .foregroundStyle(Palette.muted)
                            }
                            .padding(.horizontal, 20)
                            .padding(.vertical, 22)
                            .background(RoundedRectangle(cornerRadius: 16).fill(Palette.surface))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .onAppear { store.startScan() }
        .onDisappear { store.stopScan() }
        .alert(
            "Join \(chosen?.name ?? "court")",
            isPresented: Binding(get: { chosen != nil }, set: { if !$0 { chosen = nil } })
        ) {
            TextField("4-digit code", text: $code)
                .keyboardType(.numberPad)
            Button("Join") {
                if let court = chosen { store.join(court, code: Int(code)) }
                chosen = nil
            }
            Button("Cancel", role: .cancel) { chosen = nil }
        } message: {
            Text("Enter the code shown on the host's screen.")
        }
    }

    private func signalLabel(_ rssi: Int) -> String {
        if rssi >= -60 { return "Very close" }
        if rssi >= -75 { return "Close" }
        return "Far"
    }
}
