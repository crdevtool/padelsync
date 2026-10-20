package com.padelsync.kit

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.netsports.core.pro.ProAccess
import com.netsports.core.pro.ProPlan
import com.netsports.core.pro.ProStatus
import com.netsports.core.pro.ProText
import java.text.NumberFormat
import java.util.Currency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One plan as the store sells it in this player's country. */
data class ProOffer(
    val plan: ProPlan,
    /** The price as the store formats it, `$9.99` or `QAR 36.99`. */
    val price: String,
    /** The small line under the plan's name. */
    val detail: String,
    /** `SAVE 58%` on the yearly plan, or null. */
    val badge: String? = null,
)

data class ProUiState(
    val status: ProStatus = ProAccess.status(owned = false, trialStartedAt = null, now = 0),
    /** Empty until the store answers, and on a device without Google Play. */
    val offers: List<ProOffer> = emptyList(),
    /** A purchase or a restore is under way. */
    val busy: Boolean = false,
    /** A sentence for the player about the last purchase or restore, or null. */
    val message: String? = null,
)

/**
 * PadelSync Pro on Google Play: which plan this store account owns, the
 * prices to show, buying, and this device's trial. See [ProAccess] for the
 * rules.
 *
 * The store is the record of what was bought; the answer is also kept on the
 * device so a court can be hosted with no connection, as on a padel club's
 * basement court. Play keeps purchases for the Google account, so a phone
 * and a watch on the same account see the same Pro.
 *
 * Products in Play Console:
 * - subscription [SUBSCRIPTION_ID] with base plans [BASE_PLAN_MONTHLY] and [BASE_PLAN_YEARLY]
 * - one-time product [LIFETIME_ID]
 */
class ProStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("padelsync_pro", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(ProUiState(status = currentStatus()))
    val ui: StateFlow<ProUiState> = _ui.asStateFlow()

    private var subscription: ProductDetails? = null
    private var lifetime: ProductDetails? = null

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingResponseCode.OK -> handlePurchases(purchases.orEmpty(), fromPurchase = true)
            BillingResponseCode.USER_CANCELED -> _ui.update { it.copy(busy = false) }
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _ui.update { it.copy(busy = false) }
                refresh()
            }
            else -> {
                Log.w(TAG, "purchase failed: ${result.responseCode} ${result.debugMessage}")
                _ui.update { it.copy(busy = false, message = ProText.UNAVAILABLE) }
            }
        }
    }

    private val billing: BillingClient = BillingClient.newBuilder(app)
        .setListener(purchasesListener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        connect()
    }

    /** Whether this device may open a court now. */
    val canHost: Boolean get() = currentStatus().canHost

    /**
     * Called as a court opens: starts this device's trial the first time.
     * Returns false, and opens nothing, when the trial is over and no plan is
     * owned; the caller then shows the Pro sheet.
     */
    fun beginHosting(): Boolean {
        val status = currentStatus()
        if (!status.canHost) return false
        if (status.kind == ProStatus.Kind.TRIAL_NOT_STARTED) {
            prefs.edit().putLong(KEY_TRIAL_STARTED, System.currentTimeMillis()).apply()
        }
        publishStatus()
        return true
    }

    /** Recomputes the trial's days, which change with the date and not with any event. */
    fun publishStatus() {
        _ui.update { it.copy(status = currentStatus()) }
    }

    /** Opens Google Play's purchase screen for [plan]. */
    fun buy(activity: Activity, plan: ProPlan) {
        val params = when (plan) {
            ProPlan.LIFETIME -> lifetime?.let { BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(it).build() }
            else -> {
                val details = subscription
                val token = details?.subscriptionOfferDetails
                    ?.firstOrNull { it.basePlanId == basePlanOf(plan) && it.offerId == null }
                    ?.offerToken
                if (details != null && token != null) {
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(token)
                        .build()
                } else {
                    null
                }
            }
        }
        if (params == null) {
            _ui.update { it.copy(message = ProText.UNAVAILABLE) }
            connect()
            return
        }
        _ui.update { it.copy(busy = true, message = null) }
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build()
        val result = billing.launchBillingFlow(activity, flow)
        if (result.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "could not open the purchase screen: ${result.responseCode} ${result.debugMessage}")
            _ui.update { it.copy(busy = false, message = ProText.UNAVAILABLE) }
        }
    }

    /** "Restore purchase": asks Play again what this account owns, and says what it found. */
    fun restore() {
        _ui.update { it.copy(busy = true, message = null) }
        queryOwned(report = true)
    }

    /** Asks Play again, quietly: on launch and when the app comes back to the front. */
    fun refresh() {
        if (billing.isReady) queryOwned(report = false) else connect()
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    /** For the emulator tests only: pretends the trial ended a month ago. */
    fun endTrialForTest() {
        prefs.edit()
            .putLong(KEY_TRIAL_STARTED, System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
            .putBoolean(KEY_OWNED, false)
            .apply()
        publishStatus()
    }

    private fun connect() {
        if (billing.isReady) {
            queryOwned(report = false)
            if (subscription == null || lifetime == null) queryProducts()
            return
        }
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) {
                    queryOwned(report = false)
                    queryProducts()
                } else {
                    // No Google Play (an emulator, some watches): the trial and
                    // the last known answer still apply.
                    Log.i(TAG, "billing unavailable: ${result.responseCode} ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection reconnects on the next call.
            }
        })
    }

    private fun queryProducts() {
        val products = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SUBSCRIPTION_ID).setProductType(ProductType.SUBS).build(),
        )
        billing.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build(),
        ) { result, details ->
            if (result.responseCode == BillingResponseCode.OK) {
                subscription = details.productDetailsList.firstOrNull()
                publishOffers()
            }
        }
        val oneTime = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(LIFETIME_ID).setProductType(ProductType.INAPP).build(),
        )
        billing.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(oneTime).build(),
        ) { result, details ->
            if (result.responseCode == BillingResponseCode.OK) {
                lifetime = details.productDetailsList.firstOrNull()
                publishOffers()
            }
        }
    }

    /** Asks for subscriptions and one-time products together, then decides once. */
    private fun queryOwned(report: Boolean) {
        billing.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build(),
        ) { subsResult, subs ->
            billing.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build(),
            ) { inappResult, inapp ->
                if (subsResult.responseCode != BillingResponseCode.OK || inappResult.responseCode != BillingResponseCode.OK) {
                    _ui.update { it.copy(busy = false, message = if (report) ProText.UNAVAILABLE else it.message) }
                    return@queryPurchasesAsync
                }
                // A full answer: what is not listed is not owned, so a
                // cancelled subscription stops counting once it lapses.
                val owned = handlePurchases(subs + inapp, fromPurchase = false)
                if (report) {
                    _ui.update { it.copy(busy = false, message = if (owned) ProText.OWNED else ProText.NOTHING_TO_RESTORE) }
                }
            }
        }
    }

    /**
     * Records what [purchases] say is owned. From a purchase it only adds;
     * from a full query it also takes away. Returns whether Pro is owned.
     */
    private fun handlePurchases(purchases: List<Purchase>, fromPurchase: Boolean): Boolean {
        val ours = purchases.filter { purchase -> purchase.products.any { it == SUBSCRIPTION_ID || it == LIFETIME_ID } }
        val bought = ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        bought.filterNot { it.isAcknowledged }.forEach(::acknowledge)

        val owned = bought.isNotEmpty() || (fromPurchase && prefs.getBoolean(KEY_OWNED, false))
        prefs.edit().putBoolean(KEY_OWNED, owned).apply()
        _ui.update {
            it.copy(
                status = currentStatus(),
                busy = if (fromPurchase) false else it.busy,
                message = when {
                    fromPurchase && bought.isNotEmpty() -> ProText.OWNED
                    fromPurchase && pending -> ProText.PENDING
                    else -> it.message
                },
            )
        }
        return owned
    }

    /** Play refunds a purchase that is not acknowledged within three days. */
    private fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billing.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingResponseCode.OK) {
                // Tried again on the next launch or refresh.
                Log.w(TAG, "acknowledge failed: ${result.responseCode} ${result.debugMessage}")
            }
        }
    }

    private fun publishOffers() {
        val subs = subscription?.subscriptionOfferDetails.orEmpty().filter { it.offerId == null }
        fun basePhase(basePlan: String) = subs.firstOrNull { it.basePlanId == basePlan }
            ?.pricingPhases?.pricingPhaseList?.lastOrNull()
        val monthly = basePhase(BASE_PLAN_MONTHLY)
        val yearly = basePhase(BASE_PLAN_YEARLY)
        @Suppress("DEPRECATION") // The single offer of a one-time product; the list form is for several.
        val once = lifetime?.oneTimePurchaseOfferDetails

        val offers = buildList {
            if (monthly != null) {
                add(ProOffer(ProPlan.MONTHLY, monthly.formattedPrice, ProText.planDetail(ProPlan.MONTHLY, null)))
            }
            if (yearly != null) {
                val perMonth = runCatching {
                    NumberFormat.getCurrencyInstance().apply {
                        currency = Currency.getInstance(yearly.priceCurrencyCode)
                    }.format(yearly.priceAmountMicros / 12 / 1_000_000.0)
                }.getOrNull()
                val saving = if (monthly != null && monthly.priceCurrencyCode == yearly.priceCurrencyCode) {
                    ProText.yearlySaving(monthly.priceAmountMicros, yearly.priceAmountMicros)
                } else {
                    null
                }
                add(
                    ProOffer(
                        ProPlan.YEARLY,
                        yearly.formattedPrice,
                        ProText.planDetail(ProPlan.YEARLY, perMonth),
                        badge = saving?.let { "SAVE $it%" },
                    ),
                )
            }
            if (once != null) {
                add(ProOffer(ProPlan.LIFETIME, once.formattedPrice, ProText.planDetail(ProPlan.LIFETIME, null)))
            }
        }
        _ui.update { it.copy(offers = offers) }
    }

    private fun currentStatus(): ProStatus = ProAccess.status(
        owned = prefs.getBoolean(KEY_OWNED, false),
        trialStartedAt = prefs.getLong(KEY_TRIAL_STARTED, 0L).takeIf { it > 0 },
        now = System.currentTimeMillis(),
    )

    private fun basePlanOf(plan: ProPlan) = if (plan == ProPlan.YEARLY) BASE_PLAN_YEARLY else BASE_PLAN_MONTHLY

    companion object {
        const val SUBSCRIPTION_ID = "pro"
        const val BASE_PLAN_MONTHLY = "monthly"
        const val BASE_PLAN_YEARLY = "yearly"
        const val LIFETIME_ID = "pro_lifetime"

        private const val TAG = "PadelSyncPro"
        private const val KEY_TRIAL_STARTED = "trial_started_at"
        private const val KEY_OWNED = "owned"

        @Volatile private var instance: ProStore? = null

        fun get(context: Context): ProStore =
            instance ?: synchronized(this) { instance ?: ProStore(context).also { instance = it } }
    }
}
