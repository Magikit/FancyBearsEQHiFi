package com.fancybears.eqhifi

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object DspEngineManager {
    @Volatile var enabled: Boolean = true
    @Volatile var preampDb: Float = 0.5f
    @Volatile var lampAmount: Float = 12.0f
    @Volatile var subBassDb: Float = 1.5f
    val bands: FloatArray = floatArrayOf(1.8f, 1.5f, 0.6f, -0.4f, 0.2f, 0.4f, 0.8f, 1.1f, 1.4f, 1.2f)
    @Volatile var routeMode: String = "phone"
    @Volatile var activePreset: String = "prosty"

    @Volatile var statusText: String = "Inicjalizacja silnika Hi-Fi DSP..."
    var onStatusChanged: ((String) -> Unit)? = null

    private val dspExecutor = Executors.newSingleThreadExecutor()

    private class SessionDspUnit(
        val sessionId: Int,
        var dp: DynamicsProcessing?,
        var eq: Equalizer?,
        var hasControl: Boolean
    )

    private val activeUnits = ConcurrentHashMap<Int, SessionDspUnit>()

    // Skalibrowane częstotliwości graniczne (cutOffFrequency = górna krawędź pasma +0.5 oktawy),
    // dzięki czemu każde z 10 pasm ma środek dokładnie na 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz!
    private val cutoffFreqs = floatArrayOf(
        44f, 88f, 175f, 350f, 700f, 1400f, 2800f, 5600f, 11200f, 20000f
    )

    private fun publishStatus() {
        val controlled = activeUnits.values.filter { it.hasControl }.map { it.sessionId }.sorted()
        val allIds = activeUnits.keys.sorted()

        val text = when {
            controlled.isNotEmpty() -> {
                val list = controlled.joinToString(", ") { if (it == 0) "Global(0)" else "#$it" }
                "Hi-Fi DSP Aktywny • Sesje: $list"
            }
            allIds.isNotEmpty() -> {
                "Podpięto sesje ${allIds.joinToString(", ")} (Wyłącz OReality/Dolby w ust. dźwięku)"
            }
            else -> "Oczekiwanie na strumień audio..."
        }
        statusText = text
        onStatusChanged?.invoke(text)
    }

    fun attachSession(context: Context, sessionId: Int) {
        if (sessionId < 0) return
        dspExecutor.execute {
            try {
                val closeIntent = Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                }
                context.sendBroadcast(closeIntent)
            } catch (_: Throwable) {}

            val existing = activeUnits[sessionId]
            if (existing != null && (existing.dp != null || existing.eq != null)) {
                applyToUnit(existing)
                publishStatus()
                return@execute
            }

            var createdDp: DynamicsProcessing? = null
            var createdEq: Equalizer? = null
            var controlGranted = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    // Studyjny tor Hi-Fi: 10-pasmowy Pre-EQ + 3-pasmowy analogowy MBC + 10-pasmowy Post-EQ + Limiter
                    val config = DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                        1,
                        true, 10,   // Pre-EQ (10 pasm)
                        true, 3,    // 3-pasmowy Multi-Band Compressor (dół / środek / góra)
                        true, 10,   // Post-EQ (10 pasm)
                        true        // Precyzyjny limiter końcowy
                    ).build()

                    val dp = DynamicsProcessing(Int.MAX_VALUE, sessionId, config)
                    dp.enabled = enabled
                    controlGranted = dp.hasControl()
                    if (controlGranted) {
                        createdDp = dp
                    } else {
                        try { dp.release() } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                    createdDp = null
                }
            }

            if (createdDp == null) {
                try {
                    val eq = Equalizer(Int.MAX_VALUE, sessionId)
                    eq.enabled = enabled
                    controlGranted = eq.hasControl()
                    createdEq = eq
                } catch (_: Throwable) {
                    createdEq = null
                }
            }

            if (createdDp != null || createdEq != null) {
                val unit = SessionDspUnit(sessionId, createdDp, createdEq, controlGranted)
                activeUnits[sessionId] = unit
                applyToUnit(unit)
            }
            publishStatus()
        }
    }

    fun shutdownAll() {
        enabled = false
        dspExecutor.execute {
            for (unit in activeUnits.values) {
                try { unit.dp?.enabled = false } catch (_: Throwable) {}
                try { unit.dp?.release() } catch (_: Throwable) {}
                try { unit.eq?.enabled = false } catch (_: Throwable) {}
                try { unit.eq?.release() } catch (_: Throwable) {}
            }
            activeUnits.clear()
        }
    }

    fun detachSession(sessionId: Int) {
        if (sessionId <= 0) return
        dspExecutor.execute {
            activeUnits.remove(sessionId)?.let { unit ->
                try { unit.dp?.release() } catch (_: Throwable) {}
                try { unit.eq?.release() } catch (_: Throwable) {}
            }
            publishStatus()
        }
    }

    fun requestUpdate(context: Context) {
        dspExecutor.execute {
            if (!activeUnits.containsKey(0)) {
                attachSession(context, 0)
            }
            for (unit in activeUnits.values) {
                applyToUnit(unit)
            }
            publishStatus()
        }
    }

    private fun applyToUnit(unit: SessionDspUnit) {
        val isOn = enabled
        val preGains = FloatArray(10)
        val postGains = FloatArray(10)
        var maxCombinedBoost = 0f

        val lampFactor = (lampAmount / 100f)
        val subFactor = subBassDb

        for (i in 0 until 10) {
            // 1. Pre-EQ: główne ustawienie barwy z interfejsu
            var pre = if (isOn) bands[i] else 0f
            if (isOn) {
                if (i == 0) pre += subFactor * 0.35f
                if (i == 1) pre += subFactor * 0.25f
                if (i in 2..4) pre += lampFactor * 0.9f
            }
            pre = pre.coerceIn(-12.0f, 12.0f)
            preGains[i] = pre

            // 2. Post-EQ: subtelne, audiofilskie wykończenie akustyczne (bez sztucznego przerysowania)
            var post = 0f
            if (isOn) {
                when (activePreset) {
                    "bluetooth" -> {
                        val c = floatArrayOf(0.8f, 0.6f, 0.1f, -0.4f, 0.0f, 0.2f, 0.4f, 0.6f, 0.9f, 1.1f)
                        post = c[i]
                    }
                    "prosty" -> {
                        val c = floatArrayOf(0.4f, 0.3f, 0.0f, -0.2f, 0.1f, 0.1f, 0.3f, 0.4f, 0.5f, 0.4f)
                        post = c[i]
                    }
                    "audiophile" -> {
                        // Maksymalna czystość, powietrze i holografia sceny bez podbarwiania środka
                        val c = floatArrayOf(0.4f, 0.2f, -0.1f, -0.3f, 0.1f, 0.1f, 0.4f, 0.6f, 0.9f, 1.3f)
                        post = c[i]
                    }
                    "old_amps" -> {
                        val c = floatArrayOf(0.7f, 0.5f, 0.1f, -0.6f, -0.1f, 0.3f, 0.7f, 1.0f, 1.2f, 1.4f)
                        post = c[i]
                    }
                    "headphones" -> {
                        // Dopełnienie krzywej Harman Target dla słuchawek
                        val c = floatArrayOf(1.1f, 0.8f, 0.1f, -0.5f, -0.1f, 0.2f, 0.6f, 0.2f, 0.7f, 1.0f)
                        post = c[i]
                    }
                    "new_amps" -> {
                        // Analogowe zaokrąglenie sterylnej góry wzmacniaczy cyfrowych
                        val c = floatArrayOf(0.5f, 0.7f, 0.6f, 0.2f, 0.5f, 0.1f, -0.3f, -0.2f, 0.3f, 0.6f)
                        post = c[i]
                    }
                    "lamp_effect" -> {
                        // Charakterystyka triody lampowej: ciepły korpus + jedwabiste wygładzenie 2-4 kHz
                        val c = floatArrayOf(0.6f, 1.0f, 1.1f, 0.5f, 0.8f, 0.4f, -0.3f, -0.5f, 0.4f, 0.8f)
                        post = c[i]
                    }
                    "bass" -> {
                        // Punktowy, głęboki sub-bas z lekkim wycofaniem 250 Hz, żeby wokal pozostał kryształowy
                        val c = floatArrayOf(1.6f, 1.2f, 0.3f, -0.9f, -0.3f, 0.1f, 0.5f, 0.7f, 0.8f, 0.7f)
                        post = c[i]
                    }
                    else -> {
                        if (i in 1..4) post += lampFactor * 0.5f
                        if (i == 3) post -= 0.2f
                    }
                }

                when (routeMode) {
                    "usb" -> if (i == 9) post += 0.3f
                    "bt" -> if (i == 0 || i == 8) post += 0.3f
                    "hp" -> if (i == 0) post += 0.3f
                }
            }

            post = post.coerceIn(-4.0f, 4.0f)
            postGains[i] = post

            val total = pre + post
            if (total > maxCombinedBoost) maxCombinedBoost = total
        }

        // Audiofilski Headroom: zachowuje 100% naturalnej dynamiki nagrania i chroni przed przesterem
        val autoHeadroom = if (isOn && maxCombinedBoost > 3.5f) {
            -(maxCombinedBoost - 3.5f) * 0.40f
        } else {
            0f
        }
        val finalInputGain = if (isOn) {
            (preampDb + autoHeadroom).coerceIn(-10.0f, 5.0f)
        } else {
            0f
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && unit.dp != null) {
            try {
                val dp = unit.dp!!
                dp.enabled = isOn
                unit.hasControl = dp.hasControl()

                for (i in 0 until 10) {
                    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffFreqs[i], preGains[i]))
                    dp.setPostEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoffFreqs[i], postGains[i]))
                }

                // 3-pasmowy analogowy kompresor MBC (11 argumentów w konstruktorze MbcBand):
                // Aktywny subtelnie tylko w trybach wymagających analogowego zagęszczenia (Lamp Effect, New amps, Bass)
                val useAnalogMbc = isOn && (activePreset == "lamp_effect" || activePreset == "new_amps" || activePreset == "bass")
                val mbcCutoffs = floatArrayOf(220f, 3200f, 20000f)
                for (b in 0 until 3) {
                    val ratio = when {
                        !useAnalogMbc -> 1.0f // Brak kompresji w trybie Audiophile / Prosty / Headphones (100% dynamiki)
                        activePreset == "bass" && b == 0 -> 2.2f // Kontrola sprężystości samego dołu
                        activePreset == "lamp_effect" && b == 1 -> 1.8f // Analogowe zagęszczenie średnicy lampowej
                        activePreset == "new_amps" && b == 1 -> 1.6f // Ocieplenie średnicy wzmacniaczy cyfrowych
                        else -> 1.0f
                    }
                    val postCompGain = if (useAnalogMbc && ratio > 1.0f) 0.6f else 0.0f
                    val mbcBand = DynamicsProcessing.MbcBand(
                        useAnalogMbc,       // 1. enabled (Boolean)
                        mbcCutoffs[b],      // 2. cutoffFrequency (Float)
                        12.0f,              // 3. attackTime (Float)
                        95.0f,              // 4. releaseTime (Float)
                        ratio,              // 5. ratio (Float)
                        -16.0f,             // 6. threshold (Float)
                        4.0f,               // 7. kneeWidth (Float)
                        -65.0f,             // 8. noiseGateThreshold (Float)
                        1.0f,               // 9. expanderRatio (Float)
                        0.0f,               // 10. preGain (Float)
                        postCompGain        // 11. postGain (Float)
                    )
                    dp.setMbcBandAllChannelsTo(b, mbcBand)
                }

                // Indywidualny Limiter Hi-Fi dopasowany do charakteru wybranego trybu
                val limiter = when (activePreset) {
                    "audiophile" -> DynamicsProcessing.Limiter(
                        true, true, 0,
                        8.0f, 80.0f, 1.5f, -0.2f, 0.0f // Przezroczysty, zachowuje pełną mikro-dynamikę
                    )
                    "old_amps" -> DynamicsProcessing.Limiter(
                        true, true, 0,
                        6.0f, 85.0f, 2.5f, -0.6f, 0.0f // Ciepłe, analogowe podtrzymanie wybrzmienia
                    )
                    "bass" -> DynamicsProcessing.Limiter(
                        true, true, 0,
                        3.0f, 55.0f, 3.5f, -0.8f, 0.0f // Twarda ochrona przed przesterem przy mocnym sub-basie
                    )
                    else -> DynamicsProcessing.Limiter(
                        true, true, 0,
                        5.0f, 65.0f, 2.5f, -0.5f, 0.0f
                    )
                }
                dp.setLimiterAllChannelsTo(limiter)
                dp.setInputGainAllChannelsTo(finalInputGain)
            } catch (_: Throwable) {}
            return
        }

        unit.eq?.let { eq ->
            try {
                eq.enabled = isOn
                unit.hasControl = eq.hasControl()
                val numBands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val minMb = range[0].toInt()
                val maxMb = range[1].toInt()
                for (b in 0 until numBands) {
                    val idx = ((b.toFloat() / numBands) * 10).toInt().coerceIn(0, 9)
                    val g = (preGains[idx] + postGains[idx] + finalInputGain * 0.4f)
                    val targetMb = (g * 100).toInt().coerceIn(minMb, maxMb)
                    eq.setBandLevel(b.toShort(), targetMb.toShort())
                }
            } catch (_: Throwable) {}
        }
    }
}

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var audioManager: AudioManager? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.parseColor("#08080A")
            window.navigationBarColor = Color.parseColor("#08080A")
        } catch (_: Throwable) {}

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#08080A"))
        }

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#08080A"))
        rootLayout.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Dolny kontener na stały prostokątny banner reklamowy na całą szerokość ekranu
        val bottomBannerContainer = FrameLayout(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.parseColor("#0E0E12"))
            visibility = View.GONE
        }
        val bottomParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        rootLayout.addView(bottomBannerContainer, bottomParams)

        // Pełnoekranowy kontener na reklamę startową (pojawia się chwilę po załadowaniu i sama znika)
        val fullScreenAdOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F208080A"))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        rootLayout.addView(
            fullScreenAdOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootLayout)
        initFancyBearsAdMob(bottomBannerContainer, fullScreenAdOverlay)
        storeBilling = StoreBillingProvider.create(this, webView)
        storeBilling?.initialize()

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    openUrlSafe(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                detectOutputDevices()
                pushDspStatusToUi(DspEngineManager.statusText)
            }
        }

        webView.addJavascriptInterface(NativeAudioBridge(), "AndroidAudioBridge")

        DspEngineManager.onStatusChanged = { status ->
            pushDspStatusToUi(status)
        }

        requestAllRequiredPermissions()
        DspEngineManager.attachSession(this, 0)
        startSafeForegroundService()
        setupHardwareDeviceMonitor()

        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        DspEngineManager.requestUpdate(this)
    }

    private fun requestAllRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val perms = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            if (perms.isNotEmpty()) {
                try {
                    requestPermissions(perms.toTypedArray(), 202)
                } catch (_: Throwable) {}
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startSafeForegroundService()
        DspEngineManager.requestUpdate(this)
    }

    private fun pushDspStatusToUi(status: String) {
        val safe = status.replace("'", "\'")
        val js = "var el=document.getElementById('device-status-label'); if(el) el.textContent='$safe';"
        runOnUiThread {
            try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
    }

    private fun setupHardwareDeviceMonitor() {
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                        detectOutputDevices()
                    }
                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                        detectOutputDevices()
                    }
                }, null)
            }
        } catch (_: Throwable) {}
    }

    private fun detectOutputDevices() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return
            var route = "phone"
            var label = "Głośnik telefonu"

            for (d in devices) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                        route = "usb"
                        label = "Wzmacniacz USB DAC"
                        break
                    }
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                        route = "bt"
                        label = "Bluetooth Audio"
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                        if (route != "usb") {
                            route = "hp"
                            label = "Słuchawki przewodowe"
                        }
                    }
                }
            }

            val js = "if(window.onNativeDeviceChanged) window.onNativeDeviceChanged('$route', '$label');"
            runOnUiThread {
                try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun startSafeForegroundService() {
        try {
            val intent = Intent(this, EqForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Throwable) {}
    }

    private fun openUrlSafe(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Throwable) {}
    }

    inner class NativeAudioBridge {

        @JavascriptInterface
        fun updateEq(
            enabled: Boolean,
            preampDb: Float,
            lampAmount: Float,
            subBassDb: Float,
            bandsJson: String,
            mode: String,
            presetKey: String
        ) {
            try {
                val arr = JSONArray(bandsJson)
                for (i in 0 until minOf(10, arr.length())) {
                    DspEngineManager.bands[i] = arr.getDouble(i).toFloat()
                }
                DspEngineManager.enabled = enabled
                DspEngineManager.preampDb = preampDb
                DspEngineManager.lampAmount = lampAmount
                DspEngineManager.subBassDb = subBassDb
                DspEngineManager.routeMode = mode
                DspEngineManager.activePreset = presetKey
                DspEngineManager.requestUpdate(this@MainActivity)
            } catch (_: Throwable) {}
        }

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            openUrlSafe(url)
        }

        @JavascriptInterface
        fun requestIgnoreBatteryOptimizations() {
            runOnUiThread {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (_: Throwable) {
                    try {
                        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(fallback)
                    } catch (_: Throwable) {}
                }
            }
        }

        @JavascriptInterface
        fun getStoreFlavor(): String {
            return storeBilling?.getFlavorId() ?: "googlePlay"
        }

        @JavascriptInterface
        fun queryPlayStorePrices() {
            runOnUiThread {
                storeBilling?.queryPrices()
            }
        }

        @JavascriptInterface
        fun launchGooglePlayPurchase(productId: String) {
            runOnUiThread {
                storeBilling?.purchaseCoffee(productId)
            }
        }

        @JavascriptInterface
        fun showRewardedSupportAd() {
            runOnUiThread {
                launchSugarRewardedVideo()
            }
        }

        @JavascriptInterface
        fun shutdownApp() {
            runOnUiThread {
                try {
                    DspEngineManager.shutdownAll()
                    val stopIntent = Intent(this@MainActivity, EqForegroundService::class.java).apply {
                        action = "ACTION_STOP_SERVICE"
                    }
                    stopService(stopIntent)
                } catch (_: Throwable) {}
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        finishAndRemoveTask()
                    } else {
                        finish()
                    }
                } catch (_: Throwable) {
                    finish()
                }
            }
        }
    }


    private var startupNativeAd: NativeAd? = null
    private var bottomBannerNativeAd: NativeAd? = null
    private var startupAdShown = false
    private val adHandler = Handler(Looper.getMainLooper())

    private fun initFancyBearsAdMob(bottomContainer: FrameLayout, fullScreenOverlay: FrameLayout) {
        try {
            MobileAds.initialize(this) {
                loadBottomFullWidthBanner(bottomContainer)
                loadStartupAutoDismissFullScreenAd(fullScreenOverlay)
                preloadSugarRewardedAd()
            }
        } catch (_: Throwable) {}
    }

    // 1. Pełnoekranowa reklama natywna tuż po ekranie ładowania — sama znika po 5 sekundach
    private fun loadStartupAutoDismissFullScreenAd(overlay: FrameLayout) {
        val adUnitId = "ca-app-pub-7787916379206469/5574320830"
        val adLoader = AdLoader.Builder(this, adUnitId)
            .forNativeAd { nativeAd ->
                if (isDestroyed || startupAdShown) {
                    nativeAd.destroy()
                    return@forNativeAd
                }
                startupNativeAd?.destroy()
                startupNativeAd = nativeAd

                // Pokaż chwilę po zniknięciu splash screena (2.7s od startu)
                adHandler.postDelayed({
                    if (isDestroyed || startupAdShown) return@postDelayed
                    startupAdShown = true
                    showFullScreenNativeAd(overlay, nativeAd)
                }, 2700L)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    overlay.visibility = View.GONE
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    private fun showFullScreenNativeAd(overlay: FrameLayout, nativeAd: NativeAd) {
        overlay.removeAllViews()
        val dp = resources.displayMetrics.density

        val adView = NativeAdView(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121216"))
            setPadding((16 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Górny pasek z odliczaniem i przyciskiem zamknięcia
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val badge = TextView(this).apply {
            text = "REKLAMA • Zniknie automatycznie za 5s"
            setTextColor(Color.parseColor("#D4A853"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = Button(this).apply {
            text = "✕ Zamknij"
            setTextColor(Color.parseColor("#08080A"))
            setBackgroundColor(Color.parseColor("#D4A853"))
            textSize = 12f
            setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener {
                overlay.visibility = View.GONE
            }
        }
        topBar.addView(badge)
        topBar.addView(closeBtn)
        card.addView(topBar)

        // MediaView (główna grafika / wideo reklamy pełnoekranowej)
        val mediaView = MediaView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (260 * dp).toInt()
            ).apply {
                topMargin = (14 * dp).toInt()
                bottomMargin = (14 * dp).toInt()
            }
        }
        adView.mediaView = mediaView
        card.addView(mediaView)

        // Nagłówek reklamy
        val headlineView = TextView(this).apply {
            text = nativeAd.headline ?: "FancyBears Sponsor"
            setTextColor(Color.parseColor("#F3F3F7"))
            textSize = 19f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        adView.headlineView = headlineView
        card.addView(headlineView)

        // Opis reklamy
        val bodyView = TextView(this).apply {
            text = nativeAd.body ?: ""
            setTextColor(Color.parseColor("#9E9EAE"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (16 * dp).toInt())
        }
        adView.bodyView = bodyView
        card.addView(bodyView)

        // Przycisk akcji (CTA)
        val ctaBtn = Button(this).apply {
            text = nativeAd.callToAction ?: "Sprawdź"
            setTextColor(Color.parseColor("#08080A"))
            setBackgroundColor(Color.parseColor("#F1CF82"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (46 * dp).toInt()
            )
        }
        adView.callToActionView = ctaBtn
        card.addView(ctaBtn)

        adView.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        adView.setNativeAd(nativeAd)
        overlay.addView(adView)
        overlay.visibility = View.VISIBLE

        // Automatyczne odliczanie i samoczynne zniknięcie po 5 sekundach
        for (sec in 4 downTo 1) {
            adHandler.postDelayed({
                if (overlay.visibility == View.VISIBLE) {
                    badge.text = "REKLAMA • Zniknie automatycznie za ${sec}s"
                }
            }, ((5 - sec) * 1000L))
        }
        adHandler.postDelayed({
            overlay.visibility = View.GONE
        }, 5000L)
    }

    // 2. Stały mały prostokątny banner na dole po całej szerokości aplikacji
    private fun loadBottomFullWidthBanner(container: FrameLayout) {
        val adUnitId = "ca-app-pub-7787916379206469/5574320830"
        val adLoader = AdLoader.Builder(this, adUnitId)
            .forNativeAd { nativeAd ->
                if (isDestroyed) {
                    nativeAd.destroy()
                    return@forNativeAd
                }
                bottomBannerNativeAd?.destroy()
                bottomBannerNativeAd = nativeAd

                val dp = resources.displayMetrics.density
                val adView = NativeAdView(this)
                val bar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor("#14141A"))
                    setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (58 * dp).toInt()
                    )
                }

                // Mała ikona aplikacji reklamodawcy
                val iconView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply {
                        rightMargin = (10 * dp).toInt()
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                nativeAd.icon?.drawable?.let {
                    iconView.setImageDrawable(it)
                    adView.iconView = iconView
                    bar.addView(iconView)
                }

                // Tekst w środku (tytuł + etykieta Ad)
                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val titleRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val adTag = TextView(this).apply {
                    text = "Ad "
                    setTextColor(Color.parseColor("#D4A853"))
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                val headline = TextView(this).apply {
                    text = nativeAd.headline ?: ""
                    setTextColor(Color.parseColor("#F3F3F7"))
                    textSize = 12.5f
                    maxLines = 1
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                titleRow.addView(adTag)
                titleRow.addView(headline)
                adView.headlineView = headline

                val sub = TextView(this).apply {
                    text = nativeAd.body ?: nativeAd.advertiser ?: ""
                    setTextColor(Color.parseColor("#8E8E9E"))
                    textSize = 10.5f
                    maxLines = 1
                }
                adView.bodyView = sub

                textCol.addView(titleRow)
                textCol.addView(sub)
                bar.addView(textCol)

                // Przycisk CTA po prawej stronie bannera
                val cta = Button(this).apply {
                    text = nativeAd.callToAction ?: "Otwórz"
                    setTextColor(Color.parseColor("#08080A"))
                    setBackgroundColor(Color.parseColor("#D4A853"))
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        (34 * dp).toInt()
                    ).apply {
                        leftMargin = (8 * dp).toInt()
                    }
                }
                adView.callToActionView = cta
                bar.addView(cta)

                adView.addView(bar)
                adView.setNativeAd(nativeAd)

                container.removeAllViews()
                container.addView(adView)
                container.visibility = View.VISIBLE
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    container.visibility = View.GONE
                }
            })
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    private var storeBilling: StoreBillingHandler? = null
    private var rewardedSupportAd: RewardedAd? = null
    private var isLoadingRewarded = false

    private fun preloadSugarRewardedAd() {
        if (isLoadingRewarded || rewardedSupportAd != null) return
        isLoadingRewarded = true
        val rewardedUnitId = "ca-app-pub-7787916379206469/6674828676"
        RewardedAd.load(
            this,
            rewardedUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedSupportAd = ad
                    isLoadingRewarded = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedSupportAd = null
                    isLoadingRewarded = false
                }
            }
        )
    }

    private fun launchSugarRewardedVideo() {
        val ad = rewardedSupportAd
        if (ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedSupportAd = null
                    preloadSugarRewardedAd()
                }
            }
            ad.show(this) {
                sendSupportFeedbackToUi("Dziękujemy za obejrzenie spotu i wsparcie! 🧊💛")
            }
        } else {
            preloadSugarRewardedAd()
            sendSupportFeedbackToUi("Przygotowywanie spotu wideo... Kliknij ponownie za 3 sekundy.")
        }
    }

    private fun sendSupportFeedbackToUi(message: String) {
        val safe = message.replace("'", "\'")
        val js = "if(window.onSupportActionResult) window.onSupportActionResult('$safe');"
        runOnUiThread {
            try { webView.evaluateJavascript(js, null) } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        adHandler.removeCallbacksAndMessages(null)
        try { storeBilling?.destroy() } catch (_: Throwable) {}
        try { startupNativeAd?.destroy() } catch (_: Throwable) {}
        try { bottomBannerNativeAd?.destroy() } catch (_: Throwable) {}
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}

class EqForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            scanActivePlaybacks(audioManager?.activePlaybackConfigurations)
            DspEngineManager.requestUpdate(this@EqForegroundService)
            mainHandler.postDelayed(this, 2500)
        }
    }

    private val audioSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            if (sessionId <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    DspEngineManager.attachSession(this@EqForegroundService, sessionId)
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    DspEngineManager.detachSession(sessionId)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        registerSessionListeners()
        mainHandler.postDelayed(watchdogRunnable, 2000)

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FancyBearsEQ::DspWakeLock")
            wakeLock?.acquire()
        } catch (_: Throwable) {}
    }

    private fun startForegroundNotification() {
        try {
            val channelId = "fancybears_eq_persistent_v3"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "FancyBears EQ HiFi (Praca w tle)",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }

            val pendingIntent = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
                    .setContentTitle("FancyBears EQ HiFi")
                    .setContentText("Korektor dźwięku aktywny w tle (Spotify / YT / BT / USB)")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle("FancyBears EQ HiFi")
                    .setContentText("Korektor dźwięku aktywny w tle")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .build()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(101, notification)
            }
        } catch (_: Throwable) {}
    }

    private fun registerSessionListeners() {
        try {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(audioSessionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(audioSessionReceiver, filter)
            }
        } catch (_: Throwable) {}

        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val cb = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                        scanActivePlaybacks(configs)
                    }
                }
                playbackCallback = cb
                audioManager?.registerAudioPlaybackCallback(cb, mainHandler)
                scanActivePlaybacks(audioManager?.activePlaybackConfigurations)
            }
        } catch (_: Throwable) {}

        DspEngineManager.attachSession(this, 0)
    }

    private fun scanActivePlaybacks(configs: List<AudioPlaybackConfiguration>?) {
        if (configs == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        for (cfg in configs) {
            try {
                val method = AudioPlaybackConfiguration::class.java.getMethod("getSessionId")
                val sid = method.invoke(cfg) as? Int ?: 0
                if (sid > 0) {
                    DspEngineManager.attachSession(this, sid)
                }
            } catch (_: Throwable) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_SERVICE") {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Throwable) {}
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        DspEngineManager.requestUpdate(this)
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(watchdogRunnable)
        try { unregisterReceiver(audioSessionReceiver) } catch (_: Throwable) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
                audioManager?.unregisterAudioPlaybackCallback(playbackCallback!!)
            }
        } catch (_: Throwable) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

interface StoreBillingHandler {
    fun getFlavorId(): String
    fun initialize()
    fun queryPrices()
    fun purchaseCoffee(productId: String)
    fun destroy()
}
