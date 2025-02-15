package com.ivy.wallet.android.billing

import androidx.appcompat.app.AppCompatActivity
import com.android.billingclient.api.*
import com.ivy.wallet.utils.ioThread
import com.ivy.wallet.utils.sendToCrashlytics
import timber.log.Timber

class IvyBilling(

) {
    companion object {
        private const val MONTHLY_V1 = "monthly_v1"
        private const val SIX_MONTH_V1 = "six_month_v1"
        private const val YEARLY_V1 = "yearly_v1"

        private const val LIFETIME_V1 = "ivy_wallet_lifetime_v1"

        val SUBSCRIPTIONS = listOf(
            MONTHLY_V1,
            SIX_MONTH_V1,
            YEARLY_V1,
        )

        val ONE_TIME_PLANS = listOf(
            LIFETIME_V1
        )
    }

    private lateinit var billingClient: BillingClient

    fun init(
        activity: AppCompatActivity,
        onReady: () -> Unit,
        onPurchases: (List<Purchase>) -> Unit,
        onError: (code: Int, msg: String) -> Unit,
    ) {
        val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                onPurchases(purchases)
            } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                onError(billingResult.responseCode, billingResult.debugMessage)
            } else {
                onError(billingResult.responseCode, billingResult.debugMessage)
            }

        }

        billingClient = BillingClient.newBuilder(activity)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // The BillingClient is ready. You can query purchases here.
                    onReady()
                } else {
                    onError(billingResult.responseCode, billingResult.debugMessage)
                }
            }

            override fun onBillingServiceDisconnected() {
                // Try to restart the connection on the next request to
                // Google Play by calling the startConnection() method.
                onError(-666, "onBillingServiceDisconnected")
            }
        })
    }

    suspend fun queryPurchases(): List<Purchase> {
        return ioThread {
            try {
                queryBoughtSubscriptions()
                    .plus(queryBoughtOneTimeOffers())
            } catch (e: Exception) {
                e.printStackTrace()
                e.sendToCrashlytics("IvyBilling CRITICAL: failed to fetch subscription purchases")
                emptyList()
            }
        }
    }

    private suspend fun queryBoughtSubscriptions(): List<Purchase> {
        return billingClient.queryPurchasesAsync(BillingClient.SkuType.SUBS).purchasesList
    }

    private suspend fun queryBoughtOneTimeOffers(): List<Purchase> {
        return try {
            billingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP).purchasesList
        } catch (e: Exception) {
            e.printStackTrace()
            e.sendToCrashlytics("IvyBilling: failed to fetch ONE_TIME purchases")
            emptyList()
        }
    }

    suspend fun fetchPlans(): List<Plan> {
        return fetchSubscriptions().plus(fetchOneTimePlans())
    }

    private suspend fun fetchSubscriptions(): List<Plan> {
        val params = SkuDetailsParams.newBuilder()
            .setSkusList(SUBSCRIPTIONS)
            .setType(BillingClient.SkuType.SUBS)

        // leverage querySkuDetails Kotlin extension function
        val skuDetailsResult = ioThread {
            billingClient.querySkuDetails(params.build())
        }

        return skuDetailsResult.skuDetailsList
            .orEmpty()
            .map {
                val type = when (it.subscriptionPeriod) {
                    "P1M" -> PlanType.MONTHLY
                    "P6M" -> PlanType.SIX_MONTH
                    "P1Y" -> PlanType.YEARLY
                    else -> return@map null
                }
                Plan(
                    sku = it.sku,
                    type = type,
                    price = it.price,
                    skuDetails = it
                )
            }
            .filterNotNull()
    }

    private suspend fun fetchOneTimePlans(): List<Plan> {
        val params = SkuDetailsParams.newBuilder()
            .setSkusList(ONE_TIME_PLANS)
            .setType(BillingClient.SkuType.INAPP)

        // leverage querySkuDetails Kotlin extension function
        val skuDetailsResult = ioThread {
            billingClient.querySkuDetails(params.build())
        }

        return skuDetailsResult.skuDetailsList
            .orEmpty()
            .map {
                Plan(
                    sku = it.sku,
                    type = PlanType.LIFETIME,
                    price = it.price,
                    skuDetails = it
                )
            }
    }

    fun buy(
        activity: AppCompatActivity,
        skuToBuy: SkuDetails,
        oldSubscriptionPurchaseToken: String?
    ) {
        val flowBuilder = BillingFlowParams.newBuilder()
            .setSkuDetails(skuToBuy)

        if (oldSubscriptionPurchaseToken != null && oldSubscriptionPurchaseToken.isNotBlank()) {
            flowBuilder.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams
                    .newBuilder()
                    .setOldSkuPurchaseToken(oldSubscriptionPurchaseToken)
                    .build()
            )
        }

        val billingResult = billingClient.launchBillingFlow(activity, flowBuilder.build())
        Timber.i("buy(): code=${billingResult.responseCode}, msg: ${billingResult.debugMessage}")
    }

    suspend fun checkPremium(
        purchase: Purchase,
        onActivatePremium: (Purchase) -> Unit
    ) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            // Grant entitlement to the user.
            onActivatePremium(purchase)

            if (!purchase.isAcknowledged) {
                val acknowledgeResult = ioThread {
                    billingClient.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken)
                            .build()
                    )
                }
                Timber.i("Acknowledge purchase result, code=${acknowledgeResult.responseCode}: ${acknowledgeResult.debugMessage}")
            }
        }
    }
}