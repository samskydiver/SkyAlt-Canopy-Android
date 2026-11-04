package com.skyalt.canopy.sim

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.google.android.gms.auth.blockstore.Blockstore
import com.google.android.gms.auth.blockstore.RetrieveBytesRequest
import com.google.android.gms.auth.blockstore.StoreBytesData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume

enum class ProPlan(val productId: String, val fallbackPrice: String, val titleKey: String) {
    ANNUAL("com.skyaltix.pro.annual", "$19.99", "Annual"),
    MONTHLY("com.skyaltix.pro.monthly", "$4.99", "Monthly"),
}

/** Purchase outcomes; the English text is the strings key, as on iOS. */
enum class ProMessage(val key: String) {
    PRODUCT_UNAVAILABLE("AltiX Pro is temporarily unavailable. Please try again later."),
    PURCHASE_FAILED("Could not complete the purchase. Please try again."),
    RESTORE_FAILED("Could not restore purchases. Please try again."),
    UNLOCKED("AltiX Pro is unlocked."),
    RESTORED("AltiX Pro is restored."),
    NO_PURCHASE_FOUND("No active AltiX Pro subscription was found."),
    PURCHASE_PENDING("Purchase approval is pending."),
}

/**
 * AltiX Pro on Google Play Billing: the annual or monthly subscription
 * unlocks unlimited simulator sessions. The last known state is cached so
 * the app opens straight into the simulator offline. Port of
 * ProAccessManager.swift.
 */
class ProAccess(context: Context) {
    private val prefs = context.getSharedPreferences("canopy-simulator", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var isPro by mutableStateOf(prefs.getBoolean(CACHE_KEY, false))
        private set
    var isReady by mutableStateOf(false)
        private set
    var products by mutableStateOf<Map<String, ProductDetails>>(emptyMap())
        private set
    var isPurchasing by mutableStateOf(false)
        private set
    var isRestoring by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<ProMessage?>(null)
    var statusMessage by mutableStateOf<ProMessage?>(null)

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener { result, purchases -> scope.launch { onPurchasesUpdated(result, purchases) } }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun start() {
        scope.launch {
            if (connect()) {
                refreshEntitlement()
                loadProducts()
            }
            isReady = true
        }
    }

    fun price(plan: ProPlan): String =
        products[plan.productId]?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice
            ?: plan.fallbackPrice

    fun purchase(activity: Activity, plan: ProPlan) {
        errorMessage = null
        statusMessage = null
        scope.launch {
            if (!connect()) {
                errorMessage = ProMessage.PRODUCT_UNAVAILABLE; return@launch
            }
            if (products[plan.productId] == null) loadProducts()
            val details = products[plan.productId]
            val offer = details?.subscriptionOfferDetails?.firstOrNull()
            if (details == null || offer == null) {
                errorMessage = ProMessage.PRODUCT_UNAVAILABLE; return@launch
            }
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(details)
                            .setOfferToken(offer.offerToken)
                            .build(),
                    ),
                )
                .build()
            isPurchasing = true
            val result = client.launchBillingFlow(activity, params)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                isPurchasing = false
                if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) errorMessage = ProMessage.PURCHASE_FAILED
            }
        }
    }

    fun restore() {
        errorMessage = null
        statusMessage = null
        scope.launch {
            isRestoring = true
            if (!connect()) {
                errorMessage = ProMessage.RESTORE_FAILED
            } else if (refreshEntitlement()) {
                statusMessage = if (isPro) ProMessage.RESTORED else ProMessage.NO_PURCHASE_FOUND
            } else {
                errorMessage = ProMessage.RESTORE_FAILED
            }
            isRestoring = false
        }
    }

    /** Re-checks the subscription, as when the app comes back to the front. */
    fun refresh() {
        scope.launch { if (connect()) refreshEntitlement() }
    }

    private suspend fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        isPurchasing = false
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val ours = purchases.orEmpty().filter { p -> p.products.any { it in PRODUCT_IDS } }
                if (ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }) statusMessage = ProMessage.PURCHASE_PENDING
                acknowledge(ours)
                refreshEntitlement()
                if (isPro) statusMessage = ProMessage.UNLOCKED
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                refreshEntitlement()
                if (isPro) statusMessage = ProMessage.RESTORED
            }
            else -> errorMessage = ProMessage.PURCHASE_FAILED
        }
    }

    /** True when the query itself worked. */
    private suspend fun refreshEntitlement(): Boolean {
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
        )
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return false
        val active = result.purchasesList.filter { p ->
            p.purchaseState == Purchase.PurchaseState.PURCHASED && p.products.any { it in PRODUCT_IDS }
        }
        acknowledge(active)
        storePro(active.isNotEmpty())
        return true
    }

    private suspend fun acknowledge(purchases: List<Purchase>) {
        for (p in purchases) {
            if (p.purchaseState == Purchase.PurchaseState.PURCHASED && !p.isAcknowledged) {
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build())
            }
        }
    }

    private suspend fun loadProducts() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                ProPlan.entries.map {
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(it.productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                },
            )
            .build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            products = result.productDetailsList.orEmpty().associateBy { it.productId }
        }
    }

    private fun storePro(value: Boolean) {
        isPro = value
        prefs.edit().putBoolean(CACHE_KEY, value).apply()
    }

    private suspend fun connect(): Boolean {
        if (client.isReady) return true
        return suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (continuation.isActive) continuation.resume(false)
                }
            })
        }
    }

    companion object {
        private const val CACHE_KEY = "store.cached-pro-access"
        val PRODUCT_IDS = ProPlan.entries.map { it.productId }.toSet()
    }
}

/**
 * The free trial: two sessions (launches of the simulator) per Google
 * account, after which AltiX Pro is needed. The count is kept in Block
 * Store (backed up with the Google account and kept across a reinstall)
 * and in the app's preferences; the highest wins. Port of
 * CanopySimulatorTrial.swift.
 */
class SimulatorTrial(context: Context) {
    private val prefs = context.getSharedPreferences("canopy-simulator", Context.MODE_PRIVATE)
    private val blockStore = Blockstore.getClient(context)

    var sessionsUsed by mutableIntStateOf(prefs.getInt(KEY, 0))
        private set
    var isLoaded by mutableStateOf(false)
        private set

    val sessionsLeft get() = maxOf(0, FREE_SESSIONS - sessionsUsed)
    val hasSessionsLeft get() = sessionsLeft > 0

    /** Reads the count kept with the Google account. */
    suspend fun load() {
        val stored = try {
            val response = blockStore.retrieveBytes(RetrieveBytesRequest.Builder().setKeys(listOf(KEY)).build()).await()
            response.blockstoreDataMap[KEY]?.bytes?.let { String(it).toIntOrNull() } ?: 0
        } catch (e: Exception) {
            0
        }
        sessionsUsed = maxOf(sessionsUsed, stored, prefs.getInt(KEY, 0))
        isLoaded = true
    }

    /** Counts one entry into the simulator. */
    fun recordSession() {
        val count = maxOf(sessionsUsed, prefs.getInt(KEY, 0)) + 1
        sessionsUsed = count
        prefs.edit().putInt(KEY, count).apply()
        try {
            blockStore.storeBytes(
                StoreBytesData.Builder().setKey(KEY).setBytes(count.toString().toByteArray()).setShouldBackupToCloud(true).build(),
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val FREE_SESSIONS = 2
        private const val KEY = "canopy-simulator.sessions-used"
    }
}
