package com.fancybears.eqhifi

import android.app.Activity
import android.webkit.WebView
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import java.util.concurrent.ConcurrentHashMap

object StoreBillingProvider {
    fun create(activity: Activity, webView: WebView): StoreBillingHandler {
        return GooglePlayBillingHandler(activity, webView)
    }
}

class GooglePlayBillingHandler(
    private val activity: Activity,
    private val webView: WebView
) : StoreBillingHandler {

    private var billingClient: BillingClient? = null
    private val coffeeProductIds = listOf("coffee_2", "coffee_5", "coffee_10", "coffee_20", "coffee_50", "coffee_100")
    private val cachedProducts = ConcurrentHashMap<String, ProductDetails>()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handleCompletedPurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            sendFeedback("Zakup anulowany / Purchase canceled.")
        } else {
            sendFeedback("Google Play Billing: code ${billingResult.responseCode}")
        }
    }

    override fun getFlavorId(): String = "googlePlay"

    override fun initialize() {
        try {
            billingClient = BillingClient.newBuilder(activity)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases()
                .build()

            billingClient?.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryPrices()
                    }
                }
                override fun onBillingServiceDisconnected() {}
            })
        } catch (_: Throwable) {}
    }

    override fun queryPrices() {
        val client = billingClient ?: return
        if (!client.isReady) return
        try {
            val productList = coffeeProductIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()

            client.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    for (pd in productDetailsList) {
                        cachedProducts[pd.productId] = pd
                        val localPrice = pd.oneTimePurchaseOfferDetails?.formattedPrice ?: continue
                        val js = "if(window.onLocalizedPriceLoaded) window.onLocalizedPriceLoaded('${pd.productId}', '${localPrice.replace("'", "\'")}');"
                        activity.runOnUiThread {
                            try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    override fun purchaseCoffee(productId: String) {
        val client = billingClient
        if (client == null || !client.isReady) {
            sendFeedback("Łączenie ze Sklepem Google Play...")
            initialize()
            return
        }
        val pd = cachedProducts[productId]
        if (pd == null) {
            queryPrices()
            sendFeedback("Produkt '$productId' czeka na aktywację w Google Play Console.")
            return
        }
        val paramsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(pd)
                .build()
        )
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(paramsList)
            .build()
        client.launchBillingFlow(activity, flowParams)
    }

    private fun handleCompletedPurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            val consumeParams = ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient?.consumeAsync(consumeParams) { _, _ -> }
            sendFeedback("Dziękujemy za wsparcie FancyBears EQ HiFi! ☕💛")
        }
    }

    private fun sendFeedback(msg: String) {
        val safe = msg.replace("'", "\'")
        val js = "if(window.onSupportActionResult) window.onSupportActionResult('$safe');"
        activity.runOnUiThread {
            try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
    }

    override fun destroy() {
        try { billingClient?.endConnection() } catch (_: Throwable) {}
    }
}
