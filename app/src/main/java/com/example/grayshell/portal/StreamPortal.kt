package com.example.grayshell.portal

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.grayshell.blueprint.AppBlueprint
import com.example.grayshell.signal.PushBus
import com.example.grayshell.vault.DataVault
import com.example.grayshell.wire.NetWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen WebView shell.
 *
 *  - Black background everywhere (no Android system flash on load / page exit).
 *  - Safe-area paddings: top inset in portrait, left+right insets in landscape
 *    (handles notch / cutout cameras).
 *  - Instant OfflinePortal navigation on connectivity loss — no DNS probe.
 *  - Keyboard scroll fix + safe-area CSS kill JS injections.
 *  - Cold + warm push URL routing through Intent extras / onNewIntent.
 *  - User-Agent ends with "appid/<bundleId> appname/<AppName>".
 */
class StreamPortal : AppCompatActivity() {

    private lateinit var wv: WebView
    private lateinit var container: FrameLayout
    private lateinit var vault: DataVault
    private lateinit var wire: NetWire
    private val scope = CoroutineScope(Dispatchers.Main)

    private var lastMainFrameUrl: String? = null
    private var redirectRetries = 0
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fc = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        fc.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                ?: arrayOf()
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = DataVault(applicationContext)
        wire  = NetWire(applicationContext)

        // Background stays black at all times — windowBackground in the theme is black,
        // and we keep the root view black too.
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = false
        }
        wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                userAgentString = buildUserAgent()
                // Performance + compatibility.
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadsImagesAutomatically = true
                blockNetworkImage = false
                // Keep target="_blank" links inside this WebView (no external Chrome tab).
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
            }
            setBackgroundColor(Color.BLACK)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
        container.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(container)
        applyInsets()

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient   = buildClient()
        wv.webChromeClient = buildChromeClient()

        hideSystemUi()
        enableNotchCutout()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) wv.goBack()
            }
        })

        // Choose the initial URL: warm push > intent extra > saved.
        val warmPush = intent.takeIf { it.getBooleanExtra(EXTRA_PUSH_WARM, false) }
            ?.getStringExtra(EXTRA_PUSH_URL)
        val coldPush = vault.consumeColdPushUrl()
        val initial  = warmPush
            ?: coldPush
            ?: intent.getStringExtra(EXTRA_STREAM_URL)
            ?: vault.destinationUrl

        if (initial.isNullOrBlank()) {
            Log.w(TAG, "No URL to load — finishing")
            finish(); return
        }
        Log.i(TAG, "Loading: $initial  (warm=${warmPush != null}, cold=${coldPush != null})")
        wv.loadUrl(initial)

        // Connectivity monitoring — react instantly on OS callback.
        scope.launch {
            wire.connectivityFlow.collect { online ->
                if (!online) {
                    Log.i(TAG, "Connectivity lost (callback) → OfflinePortal")
                    goOffline()
                }
            }
        }

        // Heartbeat — covers the case where the page is already loaded and the user
        // turns off the internet: no WebView request fails, so we actively probe.
        scope.launch {
            while (true) {
                delay(4_000L)
                if (navigatedOffline) continue
                if (!wire.isConnected()) {
                    Log.i(TAG, "Heartbeat: no network → OfflinePortal")
                    goOffline()
                }
            }
        }

        scope.launch {
            delay(800L)
            injectKeyboardFix()
            injectSafeAreaKill()
        }
    }

    // ── WebView clients ────────────────────────────────────────────────

    private var pageStartMs = 0L

    private fun buildClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url.toString()
            return when {
                u.startsWith("http") || u.startsWith("about") ||
                u.startsWith("data") || u.startsWith("blob") -> {
                    if (req.isForMainFrame) lastMainFrameUrl = u
                    false  // load inside this WebView
                }
                // Known external app schemes — open externally.
                u.startsWith("tel:") || u.startsWith("mailto:") || u.startsWith("sms:") ||
                u.startsWith("market:") || u.startsWith("intent:") || u.startsWith("whatsapp:") -> {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) {}
                    true
                }
                else -> false  // everything else stays in the WebView
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            pageStartMs = System.currentTimeMillis()
            Log.i(TAG, "onPageStarted: $url")
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame) return
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.errorCode else -1
            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.description.toString() else ""
            Log.w(TAG, "Main-frame error $code $desc on ${req.url}")

            // Redirect loop fix.
            val isLoop = code == -9 || code == -1007 ||
                    desc.contains("too_many", ignoreCase = true)
            if (isLoop && redirectRetries < 3) {
                redirectRetries++
                lastMainFrameUrl?.let { view.loadUrl(it) }
                return
            }

            // Network-related errors → instantly hide the native Android error page
            // (the "Android robot" black screen) and route to OfflinePortal.
            val isNetErr = code in setOf(-2 /* HOST_LOOKUP */, -6 /* CONNECT */,
                                         -7 /* TIMEOUT */, -8 /* REDIRECT_LOOP */,
                                         -11 /* FAILED_SSL_HANDSHAKE */)
            if (isNetErr || !wire.isConnected()) {
                view.stopLoading()
                view.loadUrl("about:blank")
                goOffline()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            val took = if (pageStartMs > 0) System.currentTimeMillis() - pageStartMs else -1
            Log.i(TAG, "onPageFinished: $url  (took ${took}ms)")
            redirectRetries = 0
            lastMainFrameUrl = url
            // Re-inject safe-area kill on SPA route changes.
            injectSafeAreaKill()
        }
    }

    private fun buildChromeClient() = object : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(arrayOf())
            fileCallback = callback
            return try {
                filePicker.launch(params.createIntent())
                true
            } catch (_: Exception) {
                fileCallback = null
                false
            }
        }

        // target="_blank" / window.open() → load in the SAME WebView (no external Chrome tab).
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = view
            resultMsg.sendToTarget()
            return true
        }
    }

    // ── Navigation ──────────────────────────────────────────────────────

    @Volatile private var navigatedOffline = false

    private fun goOffline() {
        if (navigatedOffline) return
        navigatedOffline = true
        val cur = lastMainFrameUrl ?: wv.url
        // Black out the WebView immediately so the user never sees the native
        // Android error page (black screen with the green robot icon).
        try { wv.stopLoading(); wv.loadUrl("about:blank") } catch (_: Exception) {}
        startActivity(Intent(this, OfflinePortal::class.java).apply {
            if (!cur.isNullOrBlank()) putExtra(OfflinePortal.EXTRA_RETURN_URL, cur)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        overridePendingTransition(0, 0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigatedOffline = false

        // Warm push tap — load the URL inside the existing WebView.
        if (intent.getBooleanExtra(EXTRA_PUSH_WARM, false)) {
            val url = intent.getStringExtra(EXTRA_PUSH_URL)
            if (!url.isNullOrBlank()) {
                Log.i(TAG, "Warm push → loading $url")
                wv.loadUrl(url)
                return
            }
        }

        // Coming back from OfflinePortal (retry) — reload the stream URL because
        // the WebView was blanked to about:blank when we went offline.
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val current = wv.url
        val target = streamUrl ?: vault.destinationUrl
        if (!target.isNullOrBlank() &&
            (current.isNullOrBlank() || current == "about:blank" || current != target)) {
            Log.i(TAG, "onNewIntent → reloading $target (was $current)")
            wv.loadUrl(target)
        }
    }

    // ── Insets / safe area ──────────────────────────────────────────────

    private fun enableNotchCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * Apply orientation-aware padding so the WebView never sits under the camera
     * notch / cutout.
     *   portrait  → top inset only
     *   landscape → left + right insets (cutout on either side)
     */
    private fun applyInsets() {
        container.setOnApplyWindowInsetsListener { v, insets ->
            val isLandscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                insets.displayCutout else null
            val topPad   = if (!isLandscape) (cutout?.safeInsetTop   ?: 0).coerceAtLeast(insetTop(insets))   else 0
            val leftPad  = if (isLandscape)  (cutout?.safeInsetLeft  ?: 0).coerceAtLeast(insetLeft(insets))  else 0
            val rightPad = if (isLandscape)  (cutout?.safeInsetRight ?: 0).coerceAtLeast(insetRight(insets)) else 0
            v.setPadding(leftPad, topPad, rightPad, 0)
            insets
        }
        container.requestApplyInsets()
    }

    private fun insetTop(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            insets.getInsets(WindowInsets.Type.systemBars()).top else 0
    private fun insetLeft(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            insets.getInsets(WindowInsets.Type.systemBars()).left else 0
    private fun insetRight(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            insets.getInsets(WindowInsets.Type.systemBars()).right else 0

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        container.requestApplyInsets()
        hideSystemUi()
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    // ── JS injections ───────────────────────────────────────────────────

    private fun injectKeyboardFix() {
        wv.evaluateJavascript("""
            (function(){
              document.addEventListener('focusin', function(e) {
                setTimeout(function(){
                  try { e.target.scrollIntoView({behavior:'auto', block:'center'}); } catch(_){}
                }, 350);
              });
              if(window.visualViewport) {
                window.visualViewport.addEventListener('resize', function() {
                  var el = document.activeElement;
                  if(el && el !== document.body) {
                    setTimeout(function(){
                      try { el.scrollIntoView({behavior:'auto', block:'center'}); } catch(_){}
                    }, 100);
                  }
                });
              }
            })();
        """.trimIndent(), null)
    }

    /**
     * Safe-area CSS kill — targets ONLY html/body/common root containers and the
     * safe-area CSS vars. We do NOT use a universal "*" selector — that breaks
     * button padding on real sites.
     */
    private fun injectSafeAreaKill() {
        wv.evaluateJavascript("""
            (function(){
              if(window.__flsaRunning) return; window.__flsaRunning = true;
              var CSS_ID = '__flsa';
              var CSS_TEXT =
                ':root{' +
                  '--safe-area-inset-top:0px!important;' +
                  '--safe-area-inset-right:0px!important;' +
                  '--safe-area-inset-bottom:0px!important;' +
                  '--safe-area-inset-left:0px!important;' +
                  '--sat:0px!important;--sar:0px!important;' +
                  '--sab:0px!important;--sal:0px!important;' +
                  '--safe-top:0px!important;--safe-right:0px!important;' +
                  '--safe-bottom:0px!important;--safe-left:0px!important;' +
                '}' +
                'html,body,#__nuxt,#__layout,#app,#root,' +
                '.gameview-mobile-header{' +
                  'padding-top:0!important;' +
                  'padding-left:0!important;' +
                  'padding-right:0!important;' +
                  'margin-top:0!important;' +
                '}';
              function apply(){
                var head = document.head || document.documentElement;
                if (!head) return;
                var m = document.querySelector('meta[name="viewport"]');
                if (m && !/viewport-fit\s*=\s*contain/i.test(m.getAttribute('content') || '')) {
                  var c = (m.getAttribute('content') || '')
                    .replace(/,?\s*viewport-fit\s*=\s*\w+/ig, '').trim();
                  m.setAttribute('content', c + (c ? ', ' : '') + 'viewport-fit=contain');
                }
                var s = document.getElementById(CSS_ID);
                if (!s) {
                  s = document.createElement('style');
                  s.id = CSS_ID;
                  head.appendChild(s);
                }
                if (s.textContent !== CSS_TEXT) s.textContent = CSS_TEXT;
                if (head.lastElementChild !== s) head.appendChild(s);
              }
              apply();
              ['pushState','replaceState'].forEach(function(fn){
                var orig = history[fn];
                history[fn] = function(){
                  var r = orig.apply(this, arguments);
                  setTimeout(apply, 80);
                  setTimeout(apply, 400);
                  return r;
                };
              });
              window.addEventListener('popstate', function(){ setTimeout(apply, 80); });
              setInterval(apply, 2500);
            })();
        """.trimIndent(), null)
    }

    // ── User agent ──────────────────────────────────────────────────────

    private fun buildUserAgent(): String {
        val base = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE};" +
                " ${Build.BRAND} ${Build.MODEL.replace(" ", "_")} Build/${Build.ID})" +
                " AppleWebKit/537.36 (KHTML, like Gecko)" +
                " Chrome/131.0.6778.135 Mobile Safari/537.36"
        return "$base appid/${AppBlueprint.bundleId} appname/${AppBlueprint.appNameToken}"
    }

    override fun onStart() {
        super.onStart()
        navigatedOffline = false
        // Warm-push hand-off: PushRelay will call this directly while the WebView is visible.
        PushBus.onWarmUrl = { url ->
            runOnUiThread {
                Log.i(TAG, "PushBus warm URL → loading $url")
                try { wv.loadUrl(url) } catch (_: Exception) {}
            }
        }
    }

    override fun onStop() {
        if (PushBus.onWarmUrl != null) PushBus.onWarmUrl = null
        super.onStop()
    }

    override fun onDestroy() {
        if (PushBus.onWarmUrl != null) PushBus.onWarmUrl = null
        scope.cancel()
        try { wv.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_PUSH_URL   = "push_url"
        const val EXTRA_PUSH_WARM  = "push_warm"
        private const val TAG = "StreamPortal"
    }
}
