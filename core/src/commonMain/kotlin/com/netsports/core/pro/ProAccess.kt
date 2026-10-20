package com.netsports.core.pro

/**
 * PadelSync Pro: what hosting a court costs, and whether this device may host.
 *
 * Scoring on one phone ("New match") and joining someone else's court are
 * always free. Opening a court for others to join ("Host a match", "Play
 * with others") needs Pro. Only the host pays, so one Pro covers the court.
 *
 * Every device gets a 14-day trial of its own, counted from the first time it
 * opens a court, with no card and no store involved. After that it needs a
 * plan bought from the store, which the store keeps track of.
 *
 * The rules live here so the Android and Apple apps apply them the same way;
 * each app only stores the trial's start and asks its store what is owned.
 */
object ProAccess {
    const val TRIAL_DAYS = 14
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Where this device stands.
     *
     * [owned] is true while the store reports a plan in force: a lifetime
     * purchase, or a subscription that has not lapsed. [trialStartedAt] is
     * when this device first opened a court, in milliseconds since the epoch,
     * or null if it never has. A clock set back before the trial started
     * counts as the trial's first day rather than adding days to it.
     */
    fun status(owned: Boolean, trialStartedAt: Long?, now: Long): ProStatus {
        if (owned) return ProStatus(ProStatus.Kind.OWNED, daysLeft = 0)
        if (trialStartedAt == null) return ProStatus(ProStatus.Kind.TRIAL_NOT_STARTED, daysLeft = TRIAL_DAYS)
        val elapsedDays = ((now - trialStartedAt).coerceAtLeast(0) / DAY_MILLIS).toInt()
        val left = TRIAL_DAYS - elapsedDays
        return if (left > 0) {
            ProStatus(ProStatus.Kind.TRIAL, daysLeft = left)
        } else {
            ProStatus(ProStatus.Kind.EXPIRED, daysLeft = 0)
        }
    }
}

/** The answer of [ProAccess.status]. */
data class ProStatus(val kind: Kind, val daysLeft: Int) {
    enum class Kind { OWNED, TRIAL_NOT_STARTED, TRIAL, EXPIRED }

    /** Whether this device may open a court now. Opening one starts the trial if it has not started. */
    val canHost: Boolean get() = kind != Kind.EXPIRED

    /** Whether to show the PRO mark next to hosting. Not once it is bought. */
    val showsProMark: Boolean get() = kind != Kind.OWNED

    /**
     * The short line under "Host a match" on the home screen, or null for
     * none: `14-day free trial`, `Free trial · 11 days left`, or
     * `Free trial · last day`.
     */
    val homeLine: String?
        get() = when (kind) {
            Kind.OWNED -> null
            Kind.TRIAL_NOT_STARTED -> "${ProAccess.TRIAL_DAYS}-day free trial"
            Kind.TRIAL -> if (daysLeft == 1) "Free trial · last day" else "Free trial · $daysLeft days left"
            Kind.EXPIRED -> "Free trial ended · get Pro to host"
        }
}

/** The plans for sale, in the order the Pro sheet shows them. */
enum class ProPlan { MONTHLY, YEARLY, LIFETIME }

/** The words of the Pro sheet, the same on every app. Prices come from the store. */
object ProText {
    const val TITLE = "PadelSync Pro"
    const val PITCH =
        "Host the court: everyone's phone and watch shows the same score. " +
            "Only the host needs Pro; joining is always free."
    val BENEFITS = listOf(
        "Host as many matches as you like",
        "Anyone joined can score from their phone or watch",
        "Android and iPhone players can join the same court",
    )
    const val TRIAL_ENDED = "Your free trial has ended. Get Pro to keep hosting."
    const val CONTINUE = "Continue"
    const val RESTORE = "Restore purchase"
    const val OWNED = "You have PadelSync Pro. Thank you!"
    const val UNAVAILABLE = "The store is not available right now. Check your connection and try again."
    const val PENDING = "Your payment is pending. Pro switches on as soon as the store confirms it."
    const val NOTHING_TO_RESTORE = "No Pro purchase was found for this store account."

    /** Linked from the Pro sheet; Apple requires both next to a subscription. */
    const val PRIVACY_URL = "https://crdevtool.github.io/padelsync-privacy/"
    const val TERMS_URL = "https://www.apple.com/legal/internet-services/itunes/dev/stdeula/"

    /** Under "Join a court" and "New match" on the home screen. */
    const val FREE = "free"

    fun planName(plan: ProPlan): String = when (plan) {
        ProPlan.MONTHLY -> "Monthly"
        ProPlan.YEARLY -> "Yearly"
        ProPlan.LIFETIME -> "Lifetime"
    }

    /**
     * The small line under a plan's name. [perMonth] is the yearly price
     * divided by twelve, already formatted by the store's currency, or null
     * when the store does not give the price as a number.
     */
    fun planDetail(plan: ProPlan, perMonth: String?): String = when (plan) {
        ProPlan.MONTHLY -> "Cancel anytime"
        ProPlan.YEARLY -> if (perMonth != null) "$perMonth a month" else "Best value"
        ProPlan.LIFETIME -> "Pay once, keep it"
    }

    /**
     * The saving of the yearly plan over twelve months of the monthly one, as
     * a whole percentage (58 for 9.99 against 1.99), or null if there is none
     * worth showing. Prices are in the store's micro-units of one currency.
     */
    fun yearlySaving(monthlyMicros: Long, yearlyMicros: Long): Int? {
        if (monthlyMicros <= 0 || yearlyMicros <= 0) return null
        val fullYear = monthlyMicros * 12
        val saving = ((fullYear - yearlyMicros) * 100 / fullYear).toInt()
        return saving.takeIf { it >= 5 }
    }
}
