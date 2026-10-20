import PadelSyncCore
import SwiftUI

/// The Pro sheet: what Pro does, the plans with the App Store's prices, and
/// the purchase. Opened from "Host a match" or "Play with others" once the
/// trial is over, and closes by itself when Pro is bought.
struct ProSheetView: View {
    @EnvironmentObject private var pro: ProStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var chosen: ProStore.Plan = .yearly

    private static let sheetColor = Color(red: 0x14 / 255, green: 0x18 / 255, blue: 0x1F / 255)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text(ProText.shared.TITLE)
                    .font(.system(size: 26, weight: .black))
                    .foregroundStyle(Palette.accent)
                if pro.status.kind == ProStatus.Kind.expired {
                    Text(ProText.shared.TRIAL_ENDED)
                        .font(.subheadline.weight(.bold))
                }
                Text(ProText.shared.PITCH)
                    .font(.subheadline)
                    .foregroundStyle(Palette.muted)
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(ProText.shared.BENEFITS, id: \.self) { benefit in
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text("✓").fontWeight(.black).foregroundStyle(Palette.accent)
                            Text(benefit)
                        }
                        .font(.subheadline)
                    }
                }
                .padding(.vertical, 4)

                if pro.offers.isEmpty {
                    Text(ProText.shared.UNAVAILABLE)
                        .font(.subheadline)
                        .foregroundStyle(Palette.muted)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 20)
                } else {
                    ForEach(pro.offers) { offer in
                        PlanRow(offer: offer, selected: offer.plan == chosen) { chosen = offer.plan }
                    }
                }

                if let message = pro.message {
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(Palette.accent)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                }

                let canBuy = pro.offers.contains { $0.plan == chosen } && !pro.busy
                Button {
                    Task { await pro.buy(chosen) }
                } label: {
                    ZStack {
                        if pro.busy {
                            ProgressView().tint(.white)
                        } else {
                            Text(ProText.shared.CONTINUE)
                                .font(.system(size: 18, weight: .black))
                                .foregroundStyle(canBuy ? Palette.onAccent : Palette.muted)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                    .background(RoundedRectangle(cornerRadius: 16).fill(canBuy ? Palette.accent : Palette.surface))
                }
                .buttonStyle(.plain)
                .disabled(!canBuy)
                .padding(.top, 6)

                // Apple asks for these next to a subscription.
                HStack(spacing: 4) {
                    Button(ProText.shared.RESTORE) { Task { await pro.restore() } }
                        .disabled(pro.busy)
                    Text("·")
                    Button("Manage") { open("https://apps.apple.com/account/subscriptions") }
                    Text("·")
                    Button("Terms") { open(ProText.shared.TERMS_URL) }
                    Text("·")
                    Button("Privacy") { open(ProText.shared.PRIVACY_URL) }
                }
                .font(.caption)
                .foregroundStyle(Palette.muted)
                .tint(Palette.muted)
                .frame(maxWidth: .infinity)
                Text("Subscriptions renew automatically until cancelled in your Apple Account settings.")
                    .font(.caption2)
                    .foregroundStyle(Palette.muted)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            }
            .padding(22)
        }
        .background(Self.sheetColor.ignoresSafeArea())
        .presentationDetents([.large])
        .task { await pro.refreshOwned() }
        // Bought: the sheet has done its job.
        .onChange(of: pro.status.kind == ProStatus.Kind.owned) { _, owned in
            if owned { dismiss() }
        }
        .onDisappear { pro.message = nil }
    }

    private func open(_ link: String) {
        if let url = URL(string: link) { openURL(url) }
    }
}

private struct PlanRow: View {
    let offer: ProOffer
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(offer.plan.name).font(.system(size: 18, weight: .black))
                        if let badge = offer.badge {
                            Text(badge)
                                .font(.system(size: 10, weight: .black))
                                .foregroundStyle(Palette.onAccent)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 1)
                                .background(RoundedRectangle(cornerRadius: 6).fill(Palette.gold))
                        }
                    }
                    Text(offer.detail).font(.footnote).foregroundStyle(Palette.muted)
                }
                Spacer()
                Text(offer.price).font(.system(size: 19, weight: .black))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 16).fill(Palette.surface))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(selected ? Palette.accent : .clear, lineWidth: 2))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
