package com.legendfool.foollegends.portal

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.legendfool.foollegends.vault.DataVault
import com.legendfool.foollegends.wire.NetWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen WebView shell. Handles keyboard scroll fix, safe-area kill,
 * redirect-loop retry, file picker, push URL redirect and offline detection.
 */
class StreamPortal : AppCompatActivity() {

    private lateinit var wv: WebView
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

        val url = intent.getStringExtra(EXTRA_STREAM_URL) ?: run {
            finish(); return
        }

        wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                setUserAgentString(buildUserAgent())
            }
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }

        wv.webViewClient = buildClient(url)
        wv.webChromeClient = buildChromeClient()
        setContentView(wv)
        hideSystemUi()

        // Back stays in WebView.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) wv.goBack()
            }
        })

        // Push URL redirect (warm notification tap).
        if (intent.getBooleanExtra(EXTRA_PUSH_WARM, false)) {
            val pushUrl = intent.getStringExtra(EXTRA_PUSH_URL)
            if (!pushUrl.isNullOrBlank()) {
                wv.loadUrl(pushUrl)
                return
            }
        }

        // Cold-start saved push URL.
        val coldUrl = vault.consumeColdPushUrl()
        wv.loadUrl(if (!coldUrl.isNullOrBlank()) coldUrl else url)

        // Offline monitoring.
        scope.launch {
            wire.connectivityFlow.collect { online ->
                if (!online) showOffline()
            }
        }

        // JS injections after short delay.
        scope.launch {
            delay(800L)
            injectKeyboardFix()
            injectSafeAreaKill()
        }
    }

    private fun buildClient(originalUrl: String) = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url.toString()
            return when {
                u.startsWith("http") || u.startsWith("about") || u.startsWith("data") || u.startsWith("blob") -> {
                    if (req.isForMainFrame) lastMainFrameUrl = u
                    false
                }
                else -> {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) {}
                    true
                }
            }
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame) return
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.errorCode else -1
            val isLoop = code == -9 || code == -1007 ||
                    err.description.toString().contains("too_many", ignoreCase = true)
            if (isLoop && redirectRetries < 3) {
                redirectRetries++
                val retry = lastMainFrameUrl ?: originalUrl
                view.loadUrl(retry)
            } else {
                scope.launch { if (!wire.hasRealInternet()) showOffline() }
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            redirectRetries = 0
            lastMainFrameUrl = url
        }
    }

    private fun buildChromeClient() = object : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(arrayOf())
            fileCallback = callback
            val intent = params.createIntent()
            try { filePicker.launch(intent) } catch (_: Exception) {
                fileCallback = null
                return false
            }
            return true
        }
    }

    private fun showOffline() {
        val cur = lastMainFrameUrl ?: wv.url
        val i = Intent(this, OfflinePortal::class.java).apply {
            if (!cur.isNullOrBlank()) putExtra(OfflinePortal.EXTRA_RETURN_URL, cur)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(i)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    @SuppressLint("JavascriptInterface")
    private fun injectKeyboardFix() {
        wv.evaluateJavascript("""
            (function(){
              document.addEventListener('focusin', function(e) {
                setTimeout(function(){
                  e.target.scrollIntoView({behavior:'auto', block:'center'});
                }, 350);
              });
              if(window.visualViewport) {
                window.visualViewport.addEventListener('resize', function() {
                  var el = document.activeElement;
                  if(el && el !== document.body) {
                    setTimeout(function(){
                      el.scrollIntoView({behavior:'auto', block:'center'});
                    }, 100);
                  }
                });
              }
            })();
        """.trimIndent(), null)
    }

    private fun injectSafeAreaKill() {
        wv.evaluateJavascript("""
            (function(){
              var s=document.createElement('style');
              s.textContent='*{padding-top:0!important;padding-bottom:0!important;}'
                +':root{--sat:0px;--sab:0px;--sal:0px;--sar:0px;}';
              document.head && document.head.appendChild(s);
              var m=document.querySelector('meta[name="viewport"]');
              if(m){m.setAttribute('content',m.getAttribute('content').replace('viewport-fit=cover','viewport-fit=contain'));}
              setInterval(function(){
                var sa=document.querySelector('.safe-area');
                if(sa)sa.style.display='none';
              },2500);
            })();
        """.trimIndent(), null)
    }

    private fun buildUserAgent(): String {
        val dm = android.os.Build.MODEL.replace(" ", "_")
        val brand = android.os.Build.BRAND
        return "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; $brand $dm)" +
               " AppleWebKit/537.36 (KHTML, like Gecko)" +
               " Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    private fun hideSystemUi() {
        val isLandscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
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
        val padding = if (isLandscape) {
            val vp = window.decorView.rootWindowInsets
            val left = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.left ?: 0
            else 0
            val right = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.systemBars())?.right ?: 0
            else 0
            android.view.ViewGroup.MarginLayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        } else null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PUSH_WARM, false)) {
            val url = intent.getStringExtra(EXTRA_PUSH_URL)
            if (!url.isNullOrBlank()) wv.loadUrl(url)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wv.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_PUSH_URL  = "push_url"
        const val EXTRA_PUSH_WARM = "push_warm"
    }
}
