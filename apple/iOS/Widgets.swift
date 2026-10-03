import PadelSyncCore
import SwiftUI

/// Whether animations that never end are switched off.
///
/// The automated walkthrough passes `-stillAnimations`: the test framework
/// waits for the app to come to rest before every tap, and a ball that never
/// stops bouncing would keep it waiting.
enum Motion {
    static let still = ProcessInfo.processInfo.arguments.contains("-stillAnimations")
}

/// What only the automated walkthrough switches on.
enum Walkthrough {
    /// A simulator has no Bluetooth, so a court can never be open in the
    /// walkthrough's screenshots. With `-pretendCourtOpen`, once the player
    /// has chosen "Play with others", the match screen draws its status line
    /// and the keep-on-screen hint as they look with a court open. Nothing
    /// else changes: the store still says the court is closed, and Bluetooth
    /// is not involved.
    static let pretendCourtOpen = ProcessInfo.processInfo.arguments.contains("-pretendCourtOpen")
}

/// The look of a full-width button tall enough to hit without looking.
struct BigButtonLabel: View {
    let title: String
    let filled: Bool

    var body: some View {
        Text(title)
            .font(.title3.weight(.bold))
            .frame(maxWidth: .infinity, minHeight: 56)
            .foregroundStyle(filled ? Palette.onAccent : Palette.accent)
            .background(
                RoundedRectangle(cornerRadius: 28)
                    .fill(filled ? Palette.accent : Color.clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 28)
                    .stroke(Palette.muted, lineWidth: filled ? 0 : 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 28))
    }
}

struct BigButton: View {
    let title: String
    let filled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            BigButtonLabel(title: title, filled: filled)
        }
        .buttonStyle(.plain)
    }
}

/// A screen's title with a Back button before it.
struct ScreenHeader: View {
    let title: String
    let onBack: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button("Back", action: onBack)
            Text(title)
                .font(.title.weight(.bold))
                .foregroundStyle(.white)
            Spacer(minLength: 0)
        }
    }
}

/// A titled row of mutually exclusive choices.
struct OptionGroup<Option: Equatable>: View {
    let title: String
    let options: [Option]
    let selected: Option
    let label: (Option) -> String
    let onSelect: (Option) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Palette.muted)
            HStack(spacing: 8) {
                ForEach(Array(options.enumerated()), id: \.offset) { _, option in
                    let isSelected = option == selected
                    Button {
                        onSelect(option)
                    } label: {
                        Text(label(option))
                            .font(.subheadline.weight(.bold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                            .padding(.horizontal, 6)
                            .frame(maxWidth: .infinity, minHeight: 56)
                            .foregroundStyle(isSelected ? Palette.onAccent : Color.white)
                            .background(
                                RoundedRectangle(cornerRadius: 14)
                                    .fill(isSelected ? Palette.accent : Palette.surface)
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }
        }
    }
}

/// A setting that is either on or off, with a line explaining it.
struct SwitchRow: View {
    let title: String
    var caption: String?
    let isOn: Bool
    var enabled = true
    let onChange: (Bool) -> Void

    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: { onChange($0) })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(.bold))
                    .foregroundStyle(enabled ? Color.white : Palette.muted)
                if let caption = caption {
                    Text(caption)
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                }
            }
        }
        .tint(Palette.accent)
        .disabled(!enabled)
        .padding(.vertical, 4)
    }
}

/// One player's name.
struct NameField: View {
    let label: String
    @Binding var text: String
    var last = false

    /// Names are cut to 20 bytes on the wire; this keeps typing within sight of that.
    private static let maxCharacters = 20

    var body: some View {
        TextField(label, text: $text)
            .textInputAutocapitalization(.words)
            .autocorrectionDisabled()
            .submitLabel(last ? .done : .next)
            .padding(.horizontal, 12)
            .frame(minHeight: 48)
            .background(RoundedRectangle(cornerRadius: 12).stroke(Palette.muted.opacity(0.6), lineWidth: 1))
            .onChange(of: text) { _, typed in
                if typed.count > NameField.maxCharacters {
                    text = String(typed.prefix(NameField.maxCharacters))
                }
            }
    }
}

/// The two rows of name fields, shared by the setup screen and the Players sheet.
struct PlayerFields: View {
    let doubles: Bool
    @Binding var a1: String
    @Binding var a2: String
    @Binding var b1: String
    @Binding var b2: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Team A players")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(Palette.teamA)
            HStack(spacing: 10) {
                NameField(label: doubles ? "Player 1" : "Player", text: $a1)
                if doubles { NameField(label: "Player 2", text: $a2) }
            }
            Text("Team B players")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(Palette.teamB)
                .padding(.top, 4)
            HStack(spacing: 10) {
                NameField(label: doubles ? "Player 1" : "Player", text: $b1, last: !doubles)
                if doubles { NameField(label: "Player 2", text: $b2, last: true) }
            }
        }
    }
}

/// The roster for the names typed into the form.
///
/// In doubles the order of a team's two players is its serving order, so a
/// name typed only into the second field must stay second: the empty first
/// field is filled in rather than dropped. In singles the second field of
/// each team is hidden, and ignored.
func rosterOf(doubles: Bool, a1: String, a2: String, b1: String, b2: String) -> Roster {
    if !doubles {
        return Sessions.shared.roster(playerA1: a1, playerA2: "", playerB1: b1, playerB2: "")
    }
    func leading(_ typed: String, _ second: String) -> String {
        isBlank(typed) && !isBlank(second) ? "Player 1" : typed
    }
    return Sessions.shared.roster(
        playerA1: leading(a1, a2),
        playerA2: a2,
        playerB1: leading(b1, b2),
        playerB2: b2
    )
}

private func isBlank(_ text: String) -> Bool {
    text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
}
