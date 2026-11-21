package com.fancybears.eqhifi

import android.app.Activity
import android.webkit.WebView
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.UserDataResponse

object StoreBillingProvider {
    fun create(activity: Activity, webView: WebView): StoreBillingHandler {
        return AmazonIapBillingHandler(activity, webView)
    }
}

class AmazonIapBillingHandler(
    private val activity: Activity,
    private val webView: WebView
) : StoreBillingHandler {

    private val coffeeSkus = setOf("coffee_2", "coffee_5", "coffee_10", "coffee_20", "coffee_50", "coffee_100")

    override fun getFlavorId(): String = "amazon"

    override fun initialize() {
        try {
            PurchasingService.registerListener(activity.applicationContext, object : PurchasingListener {
                override fun onUserDataResponse(response: UserDataResponse) {}

                override fun onProductDataResponse(response: ProductDataResponse) {
                    if (response.requestStatus == ProductDataResponse.RequestStatus.SUCCESSFUL) {
                        for ((sku, product) in response.productData) {
                            val price = product.price ?: continue
                            val js = "if(window.onLocalizedPriceLoaded) window.onLocalizedPriceLoaded('$sku', '${price.replace("'", "\'")}');"
                            activity.runOnUiThread {
                                try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
                            }
                        }
                    }
                }

                override fun onPurchaseResponse(response: PurchaseResponse) {
                    when (response.requestStatus) {
                        PurchaseResponse.RequestStatus.SUCCESSFUL -> {
                            val receipt = response.receipt
                            PurchasingService.notifyFulfillment(receipt.receiptId, FulfillmentResult.FULFILLED)
                            sendFeedback("Thank you for supporting FancyBears EQ HiFi on Amazon! ☕💛")
                        }
                        else -> {
                            sendFeedback("Amazon Appstore: ${response.requestStatus}")
                        }
                    }
                }

                override fun onPurchaseUpdatesResponse(response: PurchaseUpdatesResponse) {
                    if (response.requestStatus == PurchaseUpdatesResponse.RequestStatus.SUCCESSFUL) {
                        for (receipt in response.receipts) {
                            if (!receipt.isCanceled) {
                                PurchasingService.notifyFulfillment(receipt.receiptId, FulfillmentResult.FULFILLED)
                            }
                        }
                    }
                }
            })
            queryPrices()
        } catch (_: Throwable) {}
    }

    override fun queryPrices() {
        try {
            PurchasingService.getProductData(coffeeSkus)
        } catch (_: Throwable) {}
    }

    override fun purchaseCoffee(productId: String) {
        try {
            PurchasingService.purchase(productId)
        } catch (_: Throwable) {
            sendFeedback("Amazon Appstore IAP unavailable.")
        }
    }

    private fun sendFeedback(msg: String) {
        val safe = msg.replace("'", "\'")
        val js = "if(window.onSupportActionResult) window.onSupportActionResult('$safe');"
        activity.runOnUiThread {
            try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
    }

    override fun destroy() {}
}
