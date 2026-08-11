package com.example.grayshell.portal

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.example.grayshell.BuildConfig
import com.example.grayshell.Fullscreen
import com.example.grayshell.blueprint.AppBlueprint
import com.example.grayshell.core.Trace
import com.example.grayshell.core.UserAgent
import com.example.grayshell.signal.PushBus
import com.example.grayshell.vault.DataVault
import com.example.grayshell.wire.NetWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 *  - Keyboard handled by [KeyboardPan] (the view slides, it never resizes) plus the
 *    safe-area CSS kill injection.
 *  - A loading cover over redirect hops and failed loads, so the user only ever sees
 *    a finished page — never an intermediate hop or the WebView's own error page.
 *  - Cold + warm push URL routing through Intent extras / onNewIntent.
 *  - User-Agent ends with "appid/<bundleId> appname/<AppName>".
 */
class StreamPortal : AppCompatActivity() {

    private lateinit var wv: WebView
    private lateinit var container: FrameLayout
    private lateinit var vault: DataVault
    private lateinit var wire: NetWire
    private lateinit var keyboard: KeyboardPan
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Last main-frame URL that actually settled. What a renderer recovery reloads. */
    private var lastMainFrameUrl: String? = null

    /**
     * Deepest main-frame URL seen, settled or not. A redirect loop is resumed
     * from here rather than from the last settled page — restarting the chain
     * from its entry point only walks into the same loop again (pitfalls #30).
     */
    private var deepestHop: String? = null

    private var redirectRetries = 0
    /** One fallback to the configured entry point per settled page. */
    private var entryPointRetried = false
    private var rendererRecoveries = 0

    /** A failed load still reaches onPageFinished; without this it resets the budget. */
    private var loadFailed = false
    /**
     * True once a page has *stayed* on screen. Until then every main-frame load
     * is treated as another hop of the entry chain and kept behind the cover —
     * see [raiseCover].
     */
    private var chainSettled = false
    /** Keeps the cover raised across the reload a retry queues up. */
    private var retryPending = false
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
        PushBus.shellAlive = true

        // Background stays black at all times — windowBackground in the theme is black,
        // and we keep the root view black too.
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = false
        }
        setContentView(container)
        applyInsets()

        keyboard = KeyboardPan(window.decorView, vault)
        keyboard.install()
        recreateWebView()

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
            Trace.w(TAG, "No URL to load — finishing")
            finish(); return
        }
        Trace.i(TAG, "loading initial URL (warm=${warmPush != null}, cold=${coldPush != null})")
        // Up before the load, not on the first onPageStarted: the frames in
        // between are what the splash would otherwise hand over to.
        raiseCover()
        wv.loadUrl(initial)

        // Connectivity monitoring — react instantly on OS callback.
        scope.launch {
            wire.connectivityFlow.collect { online ->
                if (!online) goOffline("default network lost")
            }
        }

        // Heartbeat. The OS callback only speaks up when a network goes away, and
        // the network a loaded page rides on can stop working without going
        // anywhere — a VPN over a switched-off Wi-Fi keeps its default network
        // and every capability it had, and a captive portal never loses one. That
        // is why this beat ends in an actual reachability probe and not in another
        // reading of the same capabilities the callback already watches.
        scope.launch {
            while (true) {
                delay(AppBlueprint.heartbeatMs)
                if (!resumed || navigatedOffline) continue
                if (!wire.isConnected()) {
                    goOffline("no network")
                    continue
                }
                if (!wire.hasRealInternet()) goOffline("network unreachable")
            }
        }

        scope.launch {
            delay(AppBlueprint.safeAreaDelayMs)
            injectSafeAreaKill()
        }
    }

    /** Builds the WebView, puts it in the container and hooks everything to it. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun recreateWebView() {
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
                // Popups stay in this view. Asking for real second windows is what
                // makes the WebView demand a host for them and throw when it cannot
                // get one ("Parent WebView cannot host its own popup window").
                setSupportMultipleWindows(false)
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
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient   = buildClient()
        wv.webChromeClient = buildChromeClient()
        keyboard.bind(wv)
    }

    // ── Loading cover ───────────────────────────────────────────────────

    private var cover: View? = null
    private var coverJob: Job? = null

    /**
     * Holds a loading frame over the WebView until the entry redirect chain has
     * resolved, so the user is handed the destination site and never one of the
     * hops on the way to it. An affiliate chain is several full page loads: each
     * hop commits, paints whatever it carries — often a tracking pixel and a
     * broken image on black — and only then runs the script that moves on.
     *
     * A dim scrim was not enough for the same reason: at 70% black the hop was
     * still legible through it, which read as "a dark screen with an error". The
     * cover has to be opaque, and once the project has real artwork it should be
     * the splash's own frame (`<prefix>_loading_portrait` / `_landscape`, drawn
     * CENTER_CROP) so the two are one continuous screen.
     *
     * Once a page stays put (see [dropCover]) the chain is over and nothing gets
     * a cover again: an ordinary navigation resolves behind the page the user is
     * already reading, which is better than a loading screen between them.
     *
     * Note what this is NOT: a snapshot of the view. `WebView.draw` into a software
     * canvas on a hardware-accelerated view yields solid black, which is precisely the
     * "black screen between redirects" this replaced.
     */
    private fun raiseCover() {
        coverJob?.cancel()
        coverJob = null
        val existing = cover
        if (existing != null) {
            existing.animate().cancel()
            existing.alpha = 1f
            return
        }
        val fresh = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(
                android.widget.ProgressBar(this@StreamPortal).apply {
                    isIndeterminate = true
                    indeterminateTintList =
                        android.content.res.ColorStateList.valueOf(COVER_ACCENT)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = (56f * resources.displayMetrics.density).toInt()
                }
            )
        }
        cover = fresh
        // The window root, not [container]: that one is padded away from the
        // cutout, and a frame that stops short of it would not line up with the
        // splash the cover continues.
        coverHost().addView(
            fresh,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // A page that never reports back must not hold the screen for good.
        scope.launch {
            delay(COVER_MAX_MS)
            if (cover === fresh) {
                Trace.w(TAG, "Loading cover timed out")
                dropCover(0L)
            }
        }
    }

    private fun coverHost(): FrameLayout = findViewById(android.R.id.content)

    /**
     * @param after grace to wait for another hop before deciding this page is the
     *   destination. A hop's script runs after its own load finishes, so the next
     *   navigation starts a moment *later* than this one ended — waiting is the
     *   only way to tell a chain that is still going from one that has arrived.
     *   [raiseCover] cancels this, which is what keeps the cover from blinking off
     *   and on between hops.
     */
    private fun dropCover(after: Long = CHAIN_SETTLE_MS) {
        val current = cover ?: return
        coverJob?.cancel()
        coverJob = scope.launch {
            delay(after)
            if (cover !== current) return@launch
            chainSettled = true
            cover = null
            current.animate().alpha(0f).setDuration(150L).withEndAction {
                coverHost().removeView(current)
            }.start()
        }
    }

    // ── WebView clients ────────────────────────────────────────────────

    private var pageStartMs = 0L

    private fun buildClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url.toString()
            val scheme = u.substringBefore(':').lowercase()
            // A tap is proof that a page the user could read is on screen, so
            // whatever it leads to is a navigation and not another hop.
            if (req.isForMainFrame && req.hasGesture()) chainSettled = true
            return when {
                scheme in WEB_SCHEMES -> {
                    if (req.isForMainFrame) deepestHop = u
                    false  // load inside this WebView
                }
                scheme == "intent" -> { openIntentUri(u); true }
                // Everything else is an app link: banks, wallets, messengers, stores.
                // Handing it to the WebView would only produce ERR_UNKNOWN_URL_SCHEME,
                // and the list of schemes worth knowing about has no end.
                else -> { openExternally(u); true }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            pageStartMs = System.currentTimeMillis()
            loadFailed = false
            retryPending = false
            keyboard.forget()
            // shouldOverrideUrlLoading does not see every server-side 30x, so the
            // URL the engine actually committed to is the other half of the trail.
            if (url != BLANK) deepestHop = url
            // Every hop of the entry chain is covered; once a page has settled,
            // nothing is — a later navigation resolves behind the page the user
            // is already reading.
            if (url != BLANK && !chainSettled) raiseCover()
            Trace.i(TAG, "onPageStarted")
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame) return
            loadFailed = true
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.errorCode else -1
            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.description.toString() else ""
            Trace.w(TAG, "main-frame error $code on ${req.url.host}")

            // A custom scheme reaching this point was already handed to the system;
            // the page behind it is still fine, so give it straight back.
            if (code == ERROR_UNSUPPORTED_SCHEME) {
                dropCover(0L)
                return
            }

            val isLoop = code == -9 || code == -1007 ||
                    desc.contains("too_many", ignoreCase = true)
            if (isLoop) {
                handleRedirectLoop(view, req.url.toString())
                return
            }

            val isNetErr = code in setOf(-2, -6, -7, -8, -11)
            if (isNetErr || !wire.isConnected()) {
                view.stopLoading()
                view.loadUrl(BLANK)
                goOffline("main-frame network error $code")
                return
            }

            // Anything else: the page is what it is. Never leave the user under
            // an overlay waiting on a load that already failed.
            dropCover(0L)
        }

        override fun onPageFinished(view: WebView, url: String) {
            Trace.i(TAG, "onPageFinished")
            if (loadFailed || url == BLANK) return
            redirectRetries = 0
            entryPointRetried = false
            retryPending = false
            lastMainFrameUrl = url
            deepestHop = url
            injectSafeAreaKill()
            view.evaluateJavascript(keyboard.script, null)
            dropCover()
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: android.webkit.RenderProcessGoneDetail
        ): Boolean {
            Trace.w(TAG, "render process gone, crashed=${detail.didCrash()}")
            if (isFinishing || view !== wv) {
                runCatching { view.destroy() }
                return true
            }
            if (rendererRecoveries >= MAX_RENDERER_RECOVERIES) {
                goOffline("renderer recovery budget exhausted")
                return true
            }
            rendererRecoveries++
            replaceWebView()
            return true
        }
    }

    /**
     * ERR_TOO_MANY_REDIRECTS. Chromium gives up after 20 hops and affiliate
     * chains are routinely longer, so this is an ordinary condition rather than
     * a failure — the chain has to be resumed, not restarted.
     *
     * Three things this gets right that the obvious version does not:
     *
     *  - It resumes from [deepestHop]. Reloading the entry point walks the same
     *    hops again and burns the budget on the identical loop. `lastMainFrameUrl`
     *    is the wrong field for this: `onPageFinished` overwrites it with the
     *    page that settled, so by error time it names the chain's start.
     *  - It posts the reload instead of calling `loadUrl` from inside the
     *    callback. The engine is still unwinding the failed navigation at that
     *    point and swallows or defers a re-entrant load — which is where the
     *    multi-second stalls between attempts came from.
     *  - When the budget is gone it does not leave the user under an overlay
     *    until the cover's own timeout. ERR_TOO_MANY_REDIRECTS is not in the
     *    network-error set, so before this the exhausted path did nothing at all.
     *
     * Nothing here raises the cover. A loop in an affiliate chain is dead time
     * mid-navigation, not a state worth putting a screen in front of the user
     * for — the retry is queued within 60 ms and the page underneath is
     * replaced before it has drawn.
     */
    private fun handleRedirectLoop(view: WebView, failedUrl: String) {
        if (redirectRetries < AppBlueprint.redirectRetryMax) {
            redirectRetries++
            retryPending = true
            val resumeAt = deepestHop ?: failedUrl
            Trace.i(TAG, "redirect loop, resuming attempt $redirectRetries")
            postLoad(view, resumeAt)
            return
        }

        // Budget spent. The chain itself is stuck; the entry point the backend
        // named usually still resolves, and cookies picked up along the way are
        // often what the chain was missing.
        val entryPoint = vault.destinationUrl
        if (!entryPointRetried && !entryPoint.isNullOrBlank() && entryPoint != deepestHop) {
            entryPointRetried = true
            retryPending = true
            Trace.w(TAG, "redirect budget spent → retrying the configured entry point")
            postLoad(view, entryPoint)
            return
        }

        Trace.w(TAG, "redirect chain unresolvable — handing the page back")
        retryPending = false
        dropCover(0L)
    }

    /**
     * A load queued out of a WebViewClient callback. The short pause is dead
     * time in the middle of a navigation, not a delay the user can feel.
     */
    private fun postLoad(view: WebView, url: String) {
        view.postDelayed({
            if (!isFinishing && !isDestroyed) view.loadUrl(url)
        }, RETRY_PAUSE_MS)
    }

    /**
     * Builds a fresh WebView after a renderer death and puts the last good page back.
     * The dead one cannot be reused for anything, including being asked what it was
     * showing, so [lastMainFrameUrl] is what there is to go on.
     */
    private fun replaceWebView() {
        val resumeAt = lastMainFrameUrl ?: vault.destinationUrl ?: return
        val dead = wv
        container.removeView(dead)
        runCatching { dead.destroy() }
        recreateWebView()
        wv.loadUrl(resumeAt)
    }

    private fun buildChromeClient() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            // Backstop for a page that reports progress but never a finished load.
            // about:blank is only ever loaded on the way out to the offline screen,
            // so its progress says nothing about the page the user is waiting for.
            if (newProgress < 100 || view.url == BLANK) return
            dropCover()
        }

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
    }

    // ── Links the WebView cannot take ───────────────────────────────────

    private fun openExternally(url: String) {
        val intent = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull() ?: return
        launchOrIgnore(intent)
    }

    /**
     * intent:// URIs name a target app and usually carry a browser_fallback_url, so
     * there are three things to try before the user is left looking at nothing.
     */
    private fun openIntentUri(url: String) {
        val parsed = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return
        val fallback = parsed.getStringExtra("browser_fallback_url")
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.component = null
        parsed.selector = null

        if (launchOrIgnore(parsed)) return
        // The named app may be missing while some other app handles the scheme.
        parsed.`package` = null
        if (launchOrIgnore(parsed)) return
        if (!fallback.isNullOrBlank()) wv.loadUrl(fallback)
    }

    private fun launchOrIgnore(intent: Intent): Boolean =
        runCatching { startActivity(intent) }.isSuccess

    // ── Navigation ──────────────────────────────────────────────────────

    @Volatile private var navigatedOffline = false

    /** Only true between onResume and onPause — see [goOffline]. */
    @Volatile private var resumed = false

    /** A loss that arrived while we were in the background, owed a screen. */
    @Volatile private var offlineDeferred = false

    private fun goOffline(why: String) {
        if (navigatedOffline) return
        // Android refuses an activity start from the background, and the call
        // would fail silently while this flag said the screen had been shown.
        // onResume settles it instead.
        if (!resumed) {
            offlineDeferred = true
            Trace.i(TAG, "offline while backgrounded ($why) — deferred to resume")
            return
        }
        navigatedOffline = true
        Trace.i(TAG, "offline ($why) → OfflinePortal")
        val cur = lastMainFrameUrl ?: wv.url
        try { wv.stopLoading(); wv.loadUrl(BLANK) } catch (_: Exception) {}
        startActivity(Intent(this, OfflinePortal::class.java).apply {
            if (!cur.isNullOrBlank()) putExtra(OfflinePortal.EXTRA_RETURN_URL, cur)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigatedOffline = false

        if (intent.getBooleanExtra(EXTRA_PUSH_WARM, false)) {
            val url = intent.getStringExtra(EXTRA_PUSH_URL)
            if (!url.isNullOrBlank() && com.example.grayshell.core.UrlGuard.accepts(url)) {
                Trace.i(TAG, "warm push → loading")
                wv.loadUrl(url)
                return
            }
        }

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        val current = wv.url
        val target = streamUrl ?: vault.destinationUrl
        if (!target.isNullOrBlank() &&
            (current.isNullOrBlank() || current == BLANK || current != target)) {
            Trace.i(TAG, "onNewIntent → reloading target")
            wv.loadUrl(target)
        }
    }

    // ── Insets / safe area ──────────────────────────────────────────────

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
        keyboard.remeasure()
        Fullscreen.apply(this)
    }

    private fun hideSystemUi() = Fullscreen.apply(this)

    private fun enableNotchCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    // ── JS injections ───────────────────────────────────────────────────

    /**
     * Safe-area CSS kill. The window already pads for the cutout, so a page that
     * also honours `env(safe-area-inset-*)` would leave a second empty band on
     * top of ours. Zeroing the variables removes that band.
     *
     * What it must not do is lay a finger on the page's own box model. An
     * earlier version zeroed `padding-left`, `padding-right` and `margin` on
     * `html, body, #__nuxt, #app, #root` — but sites build their gutters with
     * exactly those declarations, so the whole layout got squeezed flat against
     * both edges (pitfalls #10). Only `padding-top`, and only on the chrome
     * wrappers that are known to add a status-bar offset of their own.
     */
    private fun injectSafeAreaKill() {
        val sentinel = BuildConfig.JS_SAFE_AREA_SENTINEL
        val running  = sentinel + "R"
        wv.evaluateJavascript("""
            (function(){
              if(window.$running) return; window.$running = true;
              var CSS_ID = '$sentinel';
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
                '.gameview-mobile-header,.app-header{' +
                  'padding-top:0!important;' +
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

    private fun buildUserAgent(): String = UserAgent.value

    override fun onStart() {
        super.onStart()
        navigatedOffline = false
        PushBus.onWarmUrl = { url ->
            runOnUiThread {
                Trace.i(TAG, "PushBus warm URL → loading")
                try { wv.loadUrl(url) } catch (_: Exception) {}
            }
        }
        PushBus.consume()?.let { url ->
            Trace.i(TAG, "queued push URL → loading")
            runCatching { wv.loadUrl(url) }
        }
    }

    /**
     * The link is re-checked on every return to the foreground. Time spent in
     * another app is exactly when a connection is lost without a WebView request
     * being there to fail, and it is also the window where [goOffline] is not
     * allowed to start anything.
     */
    override fun onResume() {
        super.onResume()
        resumed = true
        val deferred = offlineDeferred
        offlineDeferred = false
        scope.launch {
            val live = wire.isConnected() && wire.hasRealInternet()
            when {
                !live    -> goOffline("offline on resume")
                deferred -> restoreAfterLoss()
            }
        }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    /**
     * The link came back while the app was in the background. Nothing navigated at
     * the time, so if the loss had already emptied the WebView the page it
     * interrupted has to be put back — otherwise the user returns to a black view
     * with a working connection.
     */
    private fun restoreAfterLoss() {
        val current = wv.url
        if (!current.isNullOrBlank() && current != BLANK) return
        val resumeAt = lastMainFrameUrl ?: deepestHop ?: vault.destinationUrl ?: return
        Trace.i(TAG, "link back after a loss while backgrounded → reloading")
        wv.loadUrl(resumeAt)
    }

    override fun onStop() {
        if (PushBus.onWarmUrl != null) PushBus.onWarmUrl = null
        super.onStop()
    }

    override fun onDestroy() {
        if (PushBus.onWarmUrl != null) PushBus.onWarmUrl = null
        PushBus.shellAlive = false
        scope.cancel()
        try { wv.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_PUSH_URL   = "push_url"
        const val EXTRA_PUSH_WARM  = "push_warm"
        private const val TAG = "StreamPortal"

        /** Everything the WebView itself can take. Anything else belongs to an app. */
        private val WEB_SCHEMES =
            setOf("http", "https", "about", "data", "blob", "file", "javascript")

        private const val BLANK = "about:blank"

        /**
         * How long a page must hold the screen before it counts as the end of the
         * chain. Long enough for the next hop's script or meta refresh to fire,
         * short enough to pass for the tail of the splash.
         */
        private const val CHAIN_SETTLE_MS = 600L

        /** No page may hold the screen longer than this, finished or not. */
        private const val COVER_MAX_MS = 20_000L

        /** Renderer recoveries per Activity — beyond this we go offline. */
        private const val MAX_RENDERER_RECOVERIES = 3

        /** Spinner tint on the cover — the accent the splash and the game use. */
        private const val COVER_ACCENT = 0xFFF3C247.toInt()

        /** Pause before a queued redirect-loop retry. Long enough to let the
         *  engine finish unwinding the failed navigation, short enough to be
         *  invisible. */
        private const val RETRY_PAUSE_MS = 60L
    }
}
