package com.pockivo.nowmark.billing

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.pockivo.nowmark.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

sealed interface PriceState {
    data object Loading : PriceState
    data class Ready(val formatted: String) : PriceState
    /** Price could not be fetched. Never show a made-up price. */
    data object Unavailable : PriceState
}

enum class PurchaseFlow { IDLE, PENDING, SUCCESS, CANCELLED, ERROR, RESTORED_NONE, RESTORE_FAILED }

/**
 * Non-consumable "remove ads" purchase through Google Play Billing, without our own server.
 *
 * Limits (documented in docs/BILLING_ADS.md): without a server we cannot verify a purchase
 * token with the Play Developer API. We check the purchase signature locally when a Play
 * license key is configured, cache the verified state so an existing install stays ad-free
 * offline, and drop it only when Google Play answers successfully that no purchase exists.
 */
object BillingManager : PurchasesUpdatedListener {
    private const val PREFS = "billing"
    private const val KEY_AD_FREE = "ad_free_verified"

    private lateinit var prefs: SharedPreferences
    private var client: BillingClient? = null
    private var details: ProductDetails? = null

    private val _adFree = MutableStateFlow(false)
    val adFree: StateFlow<Boolean> = _adFree
    private val _price = MutableStateFlow<PriceState>(PriceState.Loading)
    val price: StateFlow<PriceState> = _price
    private val _flow = MutableStateFlow(PurchaseFlow.IDLE)
    val flow: StateFlow<PurchaseFlow> = _flow

    private var restoreRequested = false

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _adFree.value = prefs.getBoolean(KEY_AD_FREE, false)
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        connect()
    }

    private fun connect() {
        val c = client ?: return
        if (c.isReady) { onReady(); return }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) onReady()
                else _price.value = PriceState.Unavailable
            }
            override fun onBillingServiceDisconnected() { /* auto reconnection is enabled */ }
        })
    }

    private fun onReady() {
        queryProduct()
        queryPurchases()
    }

    /** Called on app start and every time the app returns to the foreground. */
    fun refresh() {
        if (client?.isReady == true) onReady() else connect()
    }

    fun restore() {
        restoreRequested = true
        refresh()
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(BuildConfig.REMOVE_ADS_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )
        ).build()
        client?.queryProductDetailsAsync(params) { result, queryResult ->
            val d = queryResult.productDetailsList.firstOrNull()
            if (result.responseCode == BillingClient.BillingResponseCode.OK && d != null) {
                details = d
                val offer = d.oneTimePurchaseOfferDetails
                _price.value = offer?.let { PriceState.Ready(it.formattedPrice) } ?: PriceState.Unavailable
            } else {
                _price.value = PriceState.Unavailable
            }
        }
    }

    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client?.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                // A failed lookup is NOT "no purchase": keep the cached state.
                if (restoreRequested) _flow.value = PurchaseFlow.RESTORE_FAILED
                restoreRequested = false
                return@queryPurchasesAsync
            }
            val ours = purchases.filter { BuildConfig.REMOVE_ADS_PRODUCT_ID in it.products }
            val owned = ours.any { it.purchaseState == Purchase.PurchaseState.PURCHASED && isSignatureValid(it) }
            ours.forEach { handle(it) }
            if (!owned) setAdFree(false) // Play answered: not owned (never bought, refunded or revoked)
            if (restoreRequested) _flow.value = if (owned) PurchaseFlow.SUCCESS else PurchaseFlow.RESTORED_NONE
            restoreRequested = false
        }
    }

    fun launch(activity: Activity): Boolean {
        val d = details ?: run { refresh(); return false }
        // The product has a single purchase option ("lifetime"), so no offer token is needed.
        val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d)
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product.build())).build()
        val r = client?.launchBillingFlow(activity, params)
        return r?.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach { handle(it, fromPurchase = true) }
            BillingClient.BillingResponseCode.USER_CANCELED -> _flow.value = PurchaseFlow.CANCELLED
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> _flow.value = PurchaseFlow.ERROR
        }
    }

    private fun handle(p: Purchase, fromPurchase: Boolean = false) {
        if (BuildConfig.REMOVE_ADS_PRODUCT_ID !in p.products) return
        when (p.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                // No entitlement and no acknowledgement until the payment completes.
                if (fromPurchase) _flow.value = PurchaseFlow.PENDING
            }
            Purchase.PurchaseState.PURCHASED -> {
                if (!isSignatureValid(p)) { _flow.value = PurchaseFlow.ERROR; return }
                setAdFree(true)
                if (fromPurchase || _flow.value == PurchaseFlow.PENDING) _flow.value = PurchaseFlow.SUCCESS
                if (!p.isAcknowledged) acknowledge(p, attempt = 0)
            }
            else -> Unit
        }
    }

    /** Non-consumable: acknowledge (never consume). Retried; also re-run on every refresh. */
    private fun acknowledge(p: Purchase, attempt: Int) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
        client?.acknowledgePurchase(params) { r ->
            if (r.responseCode != BillingClient.BillingResponseCode.OK && attempt < 3) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ acknowledge(p, attempt + 1) }, 2_000L shl attempt)
            }
        }
    }

    private fun setAdFree(v: Boolean) {
        _adFree.value = v
        prefs.edit().putBoolean(KEY_AD_FREE, v).apply()
    }

    fun clearFlow() { _flow.value = PurchaseFlow.IDLE }

    private fun isSignatureValid(p: Purchase): Boolean {
        val key = BuildConfig.PLAY_LICENSE_KEY
        if (key.isBlank()) return true
        return try {
            val pub = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)))
            val sig = Signature.getInstance("SHA1withRSA")
            sig.initVerify(pub)
            sig.update(p.originalJson.toByteArray())
            sig.verify(Base64.decode(p.signature, Base64.DEFAULT))
        } catch (e: Exception) {
            false
        }
    }
}
