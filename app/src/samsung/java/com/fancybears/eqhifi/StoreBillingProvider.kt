package com.fancybears.eqhifi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.WebView

object StoreBillingProvider {
    fun create(activity: Activity, webView: WebView): StoreBillingHandler {
        return SamsungGalaxyStoreHandler(activity, webView)
    }
}

class SamsungGalaxyStoreHandler(
    private val activity: Activity,
    private val webView: WebView
) : StoreBillingHandler {

    override fun getFlavorId(): String = "samsung"

    override fun initialize() {}

    override fun queryPrices() {}

    override fun purchaseCoffee(productId: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("samsungapps://ProductDetail/${activity.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            sendFeedback("Opening Samsung Galaxy Store page...")
        } catch (_: Throwable) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://galaxystore.samsung.com/detail/${activity.packageName}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(webIntent)
            } catch (_: Throwable) {
                sendFeedback("Thank you! Use Sugar for You [AD] to support us on Galaxy Store.")
            }
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
