import Foundation
import PadelSyncCore
import StoreKit

/// One plan as the App Store sells it in this player's country.
struct ProOffer: Identifiable, Equatable {
    let plan: ProStore.Plan
    /// The price as the store formats it, `$9.99` or `QAR 36.99`.
    let price: String
    /// The small line under the plan's name.
    let detail: String
    /// `SAVE 58%` on the yearly plan, or nil.
    let badge: String?
    var id: ProStore.Plan { plan }
}

/// PadelSync Pro on the App Store: which plan this Apple Account owns, the
/// prices to show, buying, and this iPhone's trial. The rules are the shared
/// core's `ProAccess`, the same as on Android.
///
/// The App Store is the record of what was bought; the answer is also kept on
/// the phone so a court can be hosted with no connection.
///
/// Products in App Store Connect: the auto-renewable subscriptions
/// `padelsync.pro.monthly` and `padelsync.pro.yearly`, in one subscription
/// group, and the non-consumable `padelsync.pro.lifetime`.
@MainActor
final class ProStore: ObservableObject {
    enum Plan: String, CaseIterable {
        case monthly = "padelsync.pro.monthly"
        case yearly = "padelsync.pro.yearly"
        case lifetime = "padelsync.pro.lifetime"

        var name: String {
            switch self {
            case .monthly: return ProText.shared.planName(plan: .monthly)
            case .yearly: return ProText.shared.planName(plan: .yearly)
            case .lifetime: return ProText.shared.planName(plan: .lifetime)
            }
        }
    }

    @Published private(set) var status: ProStatus
    /// Empty until the App Store answers.
    @Published private(set) var offers: [ProOffer] = []
    /// A purchase or a restore is under way.
    @Published private(set) var busy = false
    /// A sentence for the player about the last purchase or restore.
    @Published var message: String?

    private var products: [Plan: Product] = [:]
    private var updates: Task<Void, Never>?
    private let defaults = UserDefaults.standard

    private static let trialKey = "pro.trialStartedAt"
    private static let ownedKey = "pro.owned"

    init() {
        status = ProAccess.shared.status(owned: false, trialStartedAt: nil, now: 0)
        // The walkthrough test shows what a player sees once the trial is over.
        if ProcessInfo.processInfo.arguments.contains("-trialEnded") {
            defaults.set(Self.nowMillis() - 30 * 24 * 60 * 60 * 1000, forKey: Self.trialKey)
            defaults.set(false, forKey: Self.ownedKey)
        }
        publishStatus()
        // Purchases made on another device, renewals, refunds and Ask to Buy.
        updates = Task { [weak self] in
            for await result in Transaction.updates {
                if case .verified(let transaction) = result { await transaction.finish() }
                await self?.refreshOwned()
            }
        }
        Task { [weak self] in
            await self?.loadProducts()
            await self?.refreshOwned()
        }
    }

    deinit { updates?.cancel() }

    /// Whether this phone may open a court now.
    var canHost: Bool { currentStatus().canHost }

    /// Called as a court opens: starts the trial the first time. Returns
    /// false, and opens nothing, once the trial is over and no plan is owned;
    /// the caller then shows the Pro sheet.
    func beginHosting() -> Bool {
        let now = currentStatus()
        guard now.canHost else { return false }
        if now.kind == ProStatus.Kind.trialNotStarted {
            defaults.set(Self.nowMillis(), forKey: Self.trialKey)
        }
        publishStatus()
        return true
    }

    /// Recomputes the trial's days, which change with the date and not with any event.
    func publishStatus() {
        let fresh = currentStatus()
        if fresh != status { status = fresh }
    }

    func buy(_ plan: Plan) async {
        guard let product = products[plan] else {
            message = ProText.shared.UNAVAILABLE
            await loadProducts()
            return
        }
        busy = true
        message = nil
        defer { busy = false }
        do {
            switch try await product.purchase() {
            case .success(let verification):
                if case .verified(let transaction) = verification {
                    await transaction.finish()
                    await refreshOwned()
                    message = ProText.shared.OWNED
                } else {
                    message = ProText.shared.UNAVAILABLE
                }
            case .pending:
                // Ask to Buy, or a payment still to be approved.
                message = ProText.shared.PENDING
            case .userCancelled:
                break
            @unknown default:
                break
            }
        } catch {
            message = ProText.shared.UNAVAILABLE
        }
    }

    /// "Restore purchase": asks the App Store to sync this Apple Account's purchases.
    func restore() async {
        busy = true
        message = nil
        defer { busy = false }
        do {
            try await AppStore.sync()
        } catch {
            message = ProText.shared.UNAVAILABLE
            return
        }
        let owned = await refreshOwned()
        message = owned ? ProText.shared.OWNED : ProText.shared.NOTHING_TO_RESTORE
    }

    /// Asks the App Store what is owned now. A subscription that lapsed is
    /// no longer listed, so it stops counting. Returns whether Pro is owned.
    @discardableResult
    func refreshOwned() async -> Bool {
        var owned = false
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result,
               Plan(rawValue: transaction.productID) != nil,
               transaction.revocationDate == nil {
                owned = true
            }
        }
        defaults.set(owned, forKey: Self.ownedKey)
        publishStatus()
        return owned
    }

    private func loadProducts() async {
        guard let loaded = try? await Product.products(for: Plan.allCases.map(\.rawValue)) else { return }
        for product in loaded {
            if let plan = Plan(rawValue: product.id) { products[plan] = product }
        }
        publishOffers()
    }

    private func publishOffers() {
        var list: [ProOffer] = []
        let monthly = products[.monthly]
        let yearly = products[.yearly]
        if let monthly {
            list.append(ProOffer(plan: .monthly, price: monthly.displayPrice,
                                 detail: ProText.shared.planDetail(plan: .monthly, perMonth: nil), badge: nil))
        }
        if let yearly {
            let perMonth = (yearly.price / 12).formatted(yearly.priceFormatStyle)
            var badge: String?
            if let monthly, monthly.priceFormatStyle.currencyCode == yearly.priceFormatStyle.currencyCode,
               let saving = ProText.shared.yearlySaving(monthlyMicros: Self.micros(monthly.price),
                                                       yearlyMicros: Self.micros(yearly.price)) {
                badge = "SAVE \(saving.intValue)%"
            }
            list.append(ProOffer(plan: .yearly, price: yearly.displayPrice,
                                 detail: ProText.shared.planDetail(plan: .yearly, perMonth: perMonth), badge: badge))
        }
        if let lifetime = products[.lifetime] {
            list.append(ProOffer(plan: .lifetime, price: lifetime.displayPrice,
                                 detail: ProText.shared.planDetail(plan: .lifetime, perMonth: nil), badge: nil))
        }
        offers = list
    }

    private func currentStatus() -> ProStatus {
        let started = defaults.object(forKey: Self.trialKey) as? Int64
        return ProAccess.shared.status(
            owned: defaults.bool(forKey: Self.ownedKey),
            trialStartedAt: started.map { KotlinLong(value: $0) },
            now: Self.nowMillis()
        )
    }

    private static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private static func micros(_ price: Decimal) -> Int64 {
        NSDecimalNumber(decimal: price * 1_000_000).int64Value
    }
}
