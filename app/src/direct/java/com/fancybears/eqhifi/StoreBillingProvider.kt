package com.fancybears.eqhifi

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView

object StoreBillingProvider {
    fun create(activity: Activity, webView: WebView): StoreBillingHandler {
        return DirectDonationBillingHandler(activity, webView)
    }
}

class DirectDonationBillingHandler(
    private val activity: Activity,
    private val webView: WebView
) : StoreBillingHandler {

    override fun getFlavorId(): String = "direct"

    override fun initialize() {}

    override fun queryPrices() {}

    override fun purchaseCoffee(productId: String) {
        when (productId) {
            "direct_blik" -> {
                try {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("BLIK", "+48664313176"))
                } catch (_: Throwable) {}
                sendFeedback("Skopiowano numer BLIK: +48664313176 ✓")
            }
            else -> {
                val amount = productId.removePrefix("coffee_").toIntOrNull() ?: 5
                val url = "https://www.paypal.com/cgi-bin/webscr?cmd=_xclick&business=Marcin.sub21%40int.pl&item_name=FancyBears+EQ+HiFi+Coffee&amount=$amount&currency_code=USD"
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.startActivity(intent)
                    sendFeedback("Otwieranie PayPal ($$amount USD)...")
                } catch (_: Throwable) {
                    sendFeedback("PayPal: Marcin.sub21@int.pl")
                }
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
