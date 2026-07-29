package com.legendfool.foollegends.stage

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.legendfool.foollegends.LoadingView
import com.legendfool.foollegends.R
import com.legendfool.foollegends.beacon.BeaconBus
import com.legendfool.foollegends.courier.AgentSignature
import com.legendfool.foollegends.pulse.PulseMeter
import com.legendfool.foollegends.strongbox.StrongBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What covers the whole screen while the first page of a session arrives. Everything
 * after that is handled by the loading cover, which dims the page in place instead of
 * replacing it.
 */
private enum class Curtain {
    /** Continues the launcher splash on a cold start. */
    SPLASH,

    /** Keeps the push-permission screen up after the system dialog closes. */
    HERALD
}

/** Full-screen WebView host used in web mode. */
class CanvasStage : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var shell: FrameLayout
    private lateinit var web: WebView
    private var veil: View? = null
    private lateinit var box: StrongBox
    private lateinit var pulse: PulseMeter
    private lateinit var keyboard: KeyboardPan
    private val scope = CoroutineScope(Dispatchers.Main)

    private var lastMainFrame: String? = null
    private var settledUrl: String? = null
    private var redirectRetries = 0
    private var recoveryAttempts = 0
    private var loadFailed = false
    private var retryPending = false
    private var leavingForVoid = false
    private var pageStartedAt = 0L
    private var revealJob: Job? = null
    private var guardJob: Job? = null
    private var uploadSink: ValueCallback<Array<Uri>>? = null

    private var cover: View? = null
    private var coverJob: Job? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val sink = uploadSink ?: return@registerForActivityResult
        uploadSink = null
        sink.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                ?: emptyArray()
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isLive = true
        box = StrongBox(applicationContext)
        pulse = PulseMeter(applicationContext)

        // The veil sits in the unpadded root so the artwork stays full-bleed, while
        // only the WebView is kept clear of the cutout.
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        shell = FrameLayout(this).apply { fitsSystemWindows = false }
        keyboard = KeyboardPan(root, box)
        web = createWebView()
        shell.addView(web, matchParent())
        root.addView(shell, matchParent())
        setContentView(root)

        StageChrome.installFade(this)
        StageChrome.immersive(this)
        StageChrome.allowCutout(this)
        StageChrome.keepClearOfCutout(shell)
        keyboard.install()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack()
            }
        })

        // The launcher resolves the destination and passes it in; the stored ones are
        // only a fallback for a stage restored on its own.
        val target = intent.getStringExtra(EXTRA_LANDING)
            ?: box.takeChilledPush()
            ?: box.landingUrl

        if (target.isNullOrBlank()) {
            Log.w(TAG, "nothing to load — closing")
            finish()
            return
        }
        Log.i(TAG, "loading $target")
        val opening = if (intent.getBooleanExtra(EXTRA_FROM_HERALD, false)) {
            Curtain.HERALD
        } else {
            Curtain.SPLASH
        }
        navigate(target, opening)

        watchConnection()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val view = StageWebView(this)
        view.setBackgroundColor(Color.BLACK)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            blockNetworkImage = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // Popups stay in this view. Asking for real second windows is what makes
            // the WebView demand a host for them and throw when it cannot get one.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = AgentSignature.of(this@CanvasStage)
        }
        view.webViewClient = pageClient()
        view.webChromeClient = chromeClient()
        view.setDownloadListener { url, _, disposition, mime, _ ->
            download(url, disposition, mime)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        keyboard.bind(view)
        return view
    }

    /**
     * The renderer runs in its own process and can be killed on heavy pages. Without
     * handling that here the system tears the whole app down, so swap in a fresh
     * WebView and reload behind the veil instead.
     */
    private fun replaceWebView() {
        val dead = web
        shell.removeView(dead)
        runCatching { dead.destroy() }
        web = createWebView()
        shell.addView(web, matchParent())
        val target = settledUrl ?: lastMainFrame ?: box.landingUrl
        if (target.isNullOrBlank()) finish() else navigate(target, Curtain.SPLASH)
    }

    // ── loading veil ────────────────────────────────────────────────────

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    /**
     * @param curtain full-screen artwork to hold over the load, for the two moments
     *   there is no page worth keeping: the launch, and the return from the push
     *   screen. Everything else passes null and is covered by the loading cover, which
     *   leaves the page the user was on visible underneath.
     */
    private fun navigate(url: String, curtain: Curtain? = null) {
        if (curtain != null) raiseVeil(curtain)
        recoveryAttempts = 0
        runCatching { web.loadUrl(url) }
    }

    private fun raiseVeil(kind: Curtain) {
        revealJob?.cancel()
        revealJob = null
        val existing = veil
        if (existing != null) {
            existing.animate().cancel()
            existing.alpha = 1f
            existing.bringToFront()
            return
        }
        val fresh = buildCurtain(kind)
        veil = fresh
        // Full-bleed, outside the cutout padding: this is artwork, not page content.
        root.addView(fresh, matchParent())

        // A page that never finishes must not hold the screen for good.
        guardJob?.cancel()
        guardJob = scope.launch {
            delay(VEIL_MAX_MS)
            Log.w(TAG, "launch curtain timed out")
            dropVeil()
        }
    }

    private fun buildCurtain(kind: Curtain): View = when (kind) {
        Curtain.SPLASH -> LoadingView(this, indeterminate = true) { }.apply { isClickable = true }

        Curtain.HERALD -> layoutInflater.inflate(R.layout.stage_herald, root, false).apply {
            isClickable = true
            StageChrome.keepClearOfBars(findViewById(R.id.content))
        }
    }

    // ── loading cover ───────────────────────────────────────────────────

    /**
     * Dims the page and shows a spinner while the next one is on its way. The page
     * underneath keeps being painted by the WebView, so a click on a link leaves the
     * user looking at what they were reading, dimmed, until the destination is ready
     * — including through a chain of redirects, which never gets a moment on screen
     * of its own.
     *
     * @param solid hides the page completely instead of dimming it. Used after a
     *   failed load, where what sits underneath is the WebView's own error page.
     */
    private fun raiseCover(solid: Boolean = false) {
        coverJob?.cancel()
        coverJob = null
        val existing = cover
        if (existing != null) {
            existing.animate().cancel()
            existing.alpha = 1f
            if (solid) existing.setBackgroundColor(COVER_SOLID)
            existing.bringToFront()
            return
        }
        val fresh = FrameLayout(this).apply {
            setBackgroundColor(if (solid) COVER_SOLID else COVER_SCRIM)
            // Swallows taps: the page underneath is on its way out.
            isClickable = true
            addView(
                ProgressBar(this@CanvasStage).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(GOLD)
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        cover = fresh
        shell.addView(fresh, matchParent())

        // A page that never reports back must not hold the screen for good.
        scope.launch {
            delay(COVER_MAX_MS)
            if (cover === fresh) {
                Log.w(TAG, "loading cover timed out")
                dropCover(0L)
            }
        }
    }

    /**
     * @param after grace period before the page is handed back. A redirect hop
     *   finishes and starts the next load within a frame or two, and this is what
     *   keeps the cover from blinking off and on between them.
     */
    private fun dropCover(after: Long = COVER_LINGER_MS) {
        val current = cover ?: return
        // A reload is already on its way in; handing the screen back now would show
        // the failed page for the gap in between.
        if (retryPending) return
        coverJob?.cancel()
        coverJob = scope.launch {
            delay(after)
            if (cover !== current) return@launch
            cover = null
            current.animate().alpha(0f).setDuration(150L).withEndAction {
                (current.parent as? ViewGroup)?.removeView(current)
            }.start()
        }
    }

    /** Hides the veil once the page has stopped moving. */
    private fun scheduleReveal() {
        if (veil == null) return
        revealJob?.cancel()
        // A redirect hop finishes and immediately starts the next one, which cancels
        // this job — so the curtain only lifts once nothing else follows.
        revealJob = scope.launch {
            delay(VEIL_SETTLE_MS)
            dropVeil()
        }
    }

    private fun dropVeil() {
        revealJob?.cancel()
        revealJob = null
        val current = veil ?: return
        // A launch curtain owes the user a full progress bar before it goes.
        if (current is LoadingView) {
            current.complete { fadeOut(current) }
        } else {
            fadeOut(current)
        }
    }

    private fun fadeOut(curtain: View) {
        guardJob?.cancel()
        guardJob = null
        if (veil !== curtain) return
        veil = null
        curtain.animate().alpha(0f).setDuration(200L).withEndAction {
            (curtain.parent as? ViewGroup)?.removeView(curtain)
        }.start()
    }

    // ── connectivity ────────────────────────────────────────────────────

    private fun watchConnection() {
        scope.launch {
            pulse.pulses.collect { up ->
                if (!up) {
                    Log.i(TAG, "network callback reports offline")
                    leaveForVoid()
                }
            }
        }
        // The callback stays silent when a page is already rendered and the user
        // toggles the connection off, so poll as well.
        scope.launch {
            while (true) {
                delay(4_000L)
                if (!leavingForVoid && !pulse.linkUp()) {
                    Log.i(TAG, "heartbeat reports offline")
                    leaveForVoid()
                }
            }
        }
    }

    private fun leaveForVoid() {
        if (leavingForVoid || isFinishing) return
        leavingForVoid = true
        val current = settledUrl ?: lastMainFrame ?: web.url
        blank()
        runCatching {
            startActivity(
                Intent(this, VoidStage::class.java).apply {
                    if (!current.isNullOrBlank()) putExtra(VoidStage.EXTRA_RESUME_URL, current)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    /** Blanks the page so the built-in error screen is never rendered. */
    private fun blank() = runCatching {
        web.stopLoading()
        web.loadUrl(BLANK)
    }

    // ── web clients ─────────────────────────────────────────────────────

    private fun pageClient() = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = runCatching { request.url.toString() }.getOrNull() ?: return false
            if (request.isForMainFrame && request.isRedirect) Log.i(TAG, "hop $url")
            return try {
                route(url, request.isForMainFrame)
            } catch (e: Throwable) {
                Log.w(TAG, "routing failed for $url: ${e.message}")
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageStartedAt = System.currentTimeMillis()
            revealJob?.cancel()
            loadFailed = false
            keyboard.forget()
            if (url != BLANK) raiseCover()
            Log.i(TAG, "started $url")
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (url == BLANK) return
            injectSafeAreaReset(view)
            injectKeyboardAssist(view)
        }

        override fun onPageFinished(view: WebView, url: String) {
            val took = if (pageStartedAt > 0) System.currentTimeMillis() - pageStartedAt else -1
            Log.i(TAG, "finished $url in ${took}ms")
            if (url == BLANK) return
            // A failed load still lands here, with the error page committed and a
            // reload already queued. Counting it as settled would reset the retry
            // budget and hand the error page back to the user.
            if (loadFailed) return
            redirectRetries = 0
            lastMainFrame = url
            settledUrl = url
            recoveryAttempts = 0
            injectSafeAreaReset(view)
            injectKeyboardAssist(view)
            dropCover()
            scheduleReveal()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "render process gone, crashed=${detail.didCrash()}")
            if (isFinishing || view !== web) {
                runCatching { view.destroy() }
                return true
            }
            replaceWebView()
            return true
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            loadFailed = true
            val code = error.errorCode
            val text = error.description?.toString().orEmpty()
            val failing = runCatching { request.url.toString() }.getOrNull()
            Log.w(TAG, "main frame error $code $text on $failing")

            // A custom scheme reaching this point was already handed to the system;
            // the page behind it is still fine, so give it straight back.
            if (code == ERROR_UNSUPPORTED_SCHEME) {
                dropCover(0L)
                return
            }

            // Before anything else, and before any probing that could take seconds:
            // the WebView paints its own error page for this, and it must not be seen.
            raiseCover(solid = true)

            val loop = code == ERROR_REDIRECT_LOOP || code == ERROR_TOO_MANY_REDIRECTS ||
                text.contains("too_many", ignoreCase = true)
            if (loop && redirectRetries < MAX_REDIRECT_RETRIES) {
                redirectRetries++
                // Chromium gives up after 20 hops; affiliate chains are routinely
                // longer than that. Reloading the deepest hop we saw picks the chain
                // up where it stopped instead of starting it over, so resume at once
                // — a pause here is dead time in the middle of a navigation.
                val resumeAt = lastMainFrame ?: failing
                if (resumeAt != null) {
                    Log.i(TAG, "redirect loop, resuming attempt $redirectRetries at $resumeAt")
                    retryAfterPause(resumeAt, LOOP_RETRY_PAUSE_MS)
                    return
                }
            }

            // A DNS or disconnect code is conclusive — no point probing the link.
            if (code in NETWORK_ERRORS) {
                leaveForVoid()
                return
            }

            val retryTarget = failing?.takeIf { it.startsWith("http") } ?: settledUrl
            if (recoveryAttempts < MAX_RECOVERY_ATTEMPTS && retryTarget != null) {
                recoveryAttempts++
                retryAfterPause(retryTarget)
                return
            }

            if (!pulse.linkUp()) {
                leaveForVoid()
                return
            }
            // The link is alive, so the offline screen would only bounce straight
            // back here. Keep retrying instead.
            val fallback = settledUrl?.takeIf { it != failing } ?: failing
            if (fallback != null) {
                retryAfterPause(fallback, SLOW_RETRY_PAUSE_MS)
            } else {
                leaveForVoid()
            }
        }
    }

    /** Reloads behind the solid cover, leaving the error page hidden underneath. */
    private fun retryAfterPause(url: String, pause: Long = RETRY_PAUSE_MS) {
        raiseCover(solid = true)
        retryPending = true
        scope.launch {
            delay(pause)
            retryPending = false
            if (!isFinishing) runCatching { web.loadUrl(url) }
        }
    }

    private fun chromeClient() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            // about:blank is only ever loaded on the way out to the offline screen;
            // it reaching 100% says nothing about the page the user is waiting for.
            if (newProgress < 100 || view.url == BLANK) return
            // Backstop for a page that reports progress but never a finished load.
            dropCover()
            scheduleReveal()
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            uploadSink?.onReceiveValue(emptyArray())
            uploadSink = callback
            return try {
                filePicker.launch(params.createIntent())
                true
            } catch (_: Exception) {
                uploadSink = null
                false
            }
        }

    }

    // ── URL routing ─────────────────────────────────────────────────────

    /** @return true when the URL was consumed here and must not reach the WebView. */
    private fun route(url: String, isMainFrame: Boolean): Boolean {
        val scheme = url.substringBefore(':').lowercase()
        return when {
            scheme in WEB_SCHEMES -> {
                if (isMainFrame) lastMainFrame = url
                false
            }

            scheme == "intent" -> {
                openIntentUri(url)
                true
            }

            // Everything else is an app link: banks, wallets, messengers, stores.
            // Handing it to the WebView would only produce ERR_UNKNOWN_URL_SCHEME.
            else -> {
                openExternally(url)
                true
            }
        }
    }

    /**
     * A link that turns out to be a file: the page never navigates, so the curtain
     * raised for the click has to come back down, and the download goes to the
     * system with the session cookies attached.
     */
    private fun download(url: String, disposition: String?, mime: String?) {
        // A load that turns into a download never reports a finished page, so the
        // cover raised for it has to be taken down here.
        dropCover(0L)
        scheduleReveal()
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme?.lowercase() !in HTTP_SCHEMES) {
            openExternally(url)
            return
        }
        val queued = runCatching {
            val request = DownloadManager.Request(uri)
                .setMimeType(mime)
                .addRequestHeader("User-Agent", AgentSignature.of(this))
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, disposition, mime)
                )
            CookieManager.getInstance().getCookie(url)
                ?.let { request.addRequestHeader("Cookie", it) }
            getSystemService(DownloadManager::class.java).enqueue(request)
        }.isSuccess
        if (!queued) openExternally(url)
    }

    private fun openExternally(url: String) {
        val intent = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull() ?: return
        start(intent)
    }

    /**
     * intent:// URIs carry both a target app and, usually, a browser_fallback_url —
     * use the fallback in-page when the app is missing instead of dead-ending.
     */
    private fun openIntentUri(url: String) {
        val parsed = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return

        val fallback = parsed.getStringExtra("browser_fallback_url")
        val target = parsed.`package`
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.component = null
        parsed.selector = null

        if (start(parsed)) return
        // The named app may be missing while some other app handles the scheme.
        parsed.`package` = null
        if (start(parsed)) return

        if (!fallback.isNullOrBlank()) {
            Log.i(TAG, "intent uri falling back to $fallback")
            runCatching { web.loadUrl(fallback) }
            return
        }
        if (target != null) {
            openExternally("market://details?id=$target")
        }
    }

    /** No handler installed is a normal outcome; staying silent beats an error page. */
    private fun start(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Log.i(TAG, "no app for ${intent.data}")
        false
    } catch (e: Exception) {
        Log.w(TAG, "could not open ${intent.data}: ${e.message}")
        false
    }

    // ── window ──────────────────────────────────────────────────────────

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        StageChrome.immersive(this)
        ViewCompat.requestApplyInsets(shell)
        // The field sits somewhere else in the new viewport, so anything measured in
        // the old one has to be thrown away.
        keyboard.remeasure()
    }

    // ── JS injections ───────────────────────────────────────────────────

    /** Lets the page report where the focused field is; [KeyboardPan] does the rest. */
    private fun injectKeyboardAssist(view: WebView) {
        view.evaluateJavascript(keyboard.script, null)
    }

    /**
     * Native padding already keeps the page out of the cutout, so the page's own
     * safe-area insets would double it. Only root containers and the CSS vars are
     * reset — a universal selector would strip padding from real buttons.
     *
     * Nothing here runs while the keyboard is up: rewriting the viewport meta forces
     * a layout pass, and one landing mid-keyboard-animation is felt as a jolt.
     */
    private fun injectSafeAreaReset(view: WebView) {
        view.evaluateJavascript(
            """
            (function(){
              if (window.__flSafe) { window.__flSafeApply && window.__flSafeApply(); return; }
              window.__flSafe = true;
              var ID = '__fl_safe';
              var CSS =
                ':root{' +
                  '--safe-area-inset-top:0px!important;--safe-area-inset-right:0px!important;' +
                  '--safe-area-inset-bottom:0px!important;--safe-area-inset-left:0px!important;' +
                  '--sat:0px!important;--sar:0px!important;--sab:0px!important;--sal:0px!important;' +
                  '--safe-top:0px!important;--safe-right:0px!important;' +
                  '--safe-bottom:0px!important;--safe-left:0px!important;' +
                '}' +
                'html,body,#__nuxt,#__layout,#app,#root,#__next{' +
                  'padding-top:0!important;padding-left:0!important;' +
                  'padding-right:0!important;margin-top:0!important;' +
                '}';
              function kbOpen(){
                if (!window.visualViewport) return false;
                return window.visualViewport.height < window.innerHeight * 0.75;
              }
              function apply(){
                if (kbOpen()) return;
                var head = document.head || document.documentElement;
                if (!head) return;
                var vp = document.querySelector('meta[name="viewport"]');
                if (vp) {
                  var c = (vp.getAttribute('content') || '');
                  if (!/viewport-fit\s*=\s*contain/i.test(c)) {
                    c = c.replace(/,?\s*viewport-fit\s*=\s*\w+/ig, '').trim();
                    vp.setAttribute('content', c + (c ? ', ' : '') + 'viewport-fit=contain');
                  }
                }
                var tag = document.getElementById(ID);
                if (!tag) { tag = document.createElement('style'); tag.id = ID; head.appendChild(tag); }
                if (tag.textContent !== CSS) tag.textContent = CSS;
                if (head.lastElementChild !== tag) head.appendChild(tag);
              }
              window.__flSafeApply = apply;
              apply();
              ['pushState','replaceState'].forEach(function(fn){
                var original = history[fn];
                history[fn] = function(){
                  var r = original.apply(this, arguments);
                  setTimeout(apply, 80); setTimeout(apply, 400);
                  return r;
                };
              });
              window.addEventListener('popstate', function(){ setTimeout(apply, 80); });
              setInterval(apply, 2500);
            })();
            """.trimIndent(),
            null
        )
    }

    // ── intents / lifecycle ─────────────────────────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        leavingForVoid = false

        // Returning from the offline screen: the WebView was blanked on the way out,
        // so there is nothing on screen to preserve and the splash covers the reload.
        val target = intent.getStringExtra(EXTRA_LANDING) ?: box.landingUrl
        val current = web.url
        if (!target.isNullOrBlank() &&
            (current.isNullOrBlank() || current == BLANK || current != target)
        ) {
            Log.i(TAG, "reloading $target (was $current)")
            navigate(target, Curtain.SPLASH)
        }
    }

    override fun onStart() {
        super.onStart()
        leavingForVoid = false
        BeaconBus.warmSink = { url ->
            runOnUiThread {
                Log.i(TAG, "warm sink -> $url")
                navigate(url)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StageChrome.immersive(this)
        ViewCompat.requestApplyInsets(shell)
        // A push tapped while the shell sat in the background. The page the user left
        // is what they come back to; it stays up, dimmed, until the pushed one loads.
        BeaconBus.takeQueued()?.let { url ->
            Log.i(TAG, "queued push -> $url")
            navigate(url)
        }
    }

    override fun onStop() {
        BeaconBus.warmSink = null
        super.onStop()
    }

    override fun onDestroy() {
        isLive = false
        BeaconBus.warmSink = null
        scope.cancel()
        runCatching {
            web.stopLoading()
            shell.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LANDING = "fl_landing"
        const val EXTRA_FROM_HERALD = "fl_from_herald"

        /**
         * Whether a shell exists in this process. Read by the launcher to tell a push
         * tap that resumes a running app from one that starts it.
         */
        @Volatile
        var isLive = false
            private set

        private const val TAG = "CanvasStage"
        private const val BLANK = "about:blank"
        private const val MAX_REDIRECT_RETRIES = 6
        private const val MAX_RECOVERY_ATTEMPTS = 2
        private const val RETRY_PAUSE_MS = 500L
        private const val LOOP_RETRY_PAUSE_MS = 60L
        private const val SLOW_RETRY_PAUSE_MS = 3_000L
        private const val VEIL_SETTLE_MS = 400L
        private const val VEIL_MAX_MS = 25_000L

        private const val COVER_LINGER_MS = 220L
        private const val COVER_MAX_MS = 20_000L
        private const val COVER_SCRIM = 0xB2000000.toInt()
        private const val COVER_SOLID = 0xFF0B0203.toInt()
        private const val GOLD = 0xFFF2C14E.toInt()

        private const val ERROR_REDIRECT_LOOP = -9
        private const val ERROR_TOO_MANY_REDIRECTS = -1007
        private const val ERROR_UNSUPPORTED_SCHEME = -10

        private val NETWORK_ERRORS = setOf(-2, -6, -7, -8, -11)
        private val HTTP_SCHEMES = setOf("http", "https")
        private val WEB_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript", "file")
    }
}
