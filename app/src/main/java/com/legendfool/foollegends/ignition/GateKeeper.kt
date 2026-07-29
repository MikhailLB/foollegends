package com.legendfool.foollegends.ignition

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.messaging.FirebaseMessaging
import com.legendfool.foollegends.LoadingView
import com.legendfool.foollegends.MainActivity
import com.legendfool.foollegends.beacon.BeaconBus
import com.legendfool.foollegends.charter.AppCharter
import com.legendfool.foollegends.charter.GateVerdict
import com.legendfool.foollegends.chronicle.Chronicle
import com.legendfool.foollegends.courier.AgentSignature
import com.legendfool.foollegends.courier.ScoutCourier
import com.legendfool.foollegends.pulse.PulseMeter
import com.legendfool.foollegends.stage.CanvasStage
import com.legendfool.foollegends.stage.HeraldStage
import com.legendfool.foollegends.stage.StageChrome
import com.legendfool.foollegends.stage.VoidStage
import com.legendfool.foollegends.strongbox.StrongBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Launcher. Keeps the branded splash on screen while it decides between the web
 * shell and the native game.
 *
 *   unset  first run     no link -> offline screen on the first frame, nothing
 *                        started and nothing written down; otherwise attribution ->
 *                        config endpoint -> web or game, and a real answer sticks
 *   web    returning     cold push wins; then a fresh config call, falling back to
 *                        the stored landing, then to the offline screen
 *   app    returning     straight to the game, no network needed
 */
class GateKeeper : AppCompatActivity() {

    private lateinit var box: StrongBox
    private lateinit var pulse: PulseMeter
    private lateinit var loading: LoadingView
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = StrongBox(applicationContext)
        pulse = PulseMeter(applicationContext)

        val pushed = pushUrlIn(intent)

        // Tapping a push while the shell is still alive must not look like a launch:
        // hand the URL over and get out of the way, leaving the page the user left
        // on screen. No content view here — this instance is never meant to be seen.
        if (pushed != null && CanvasStage.isLive) {
            Log.i(TAG, "push tap onto live shell -> $pushed")
            box.takeChilledPush()
            BeaconBus.queue(pushed)
            finish()
            StageChrome.noTransition(this)
            return
        }

        // Installed through a link with the radio off. Nothing can be decided and
        // nothing is worth loading, so the offline screen is what the first frame
        // draws — no splash ahead of it to sit through and no bar to watch fill for
        // a decision that is not going to be made. The check is the instant one, not
        // the one with a grace period: waiting here is exactly what this avoids, and
        // the offline screen moves on by itself the moment a link appears.
        if (pushed == null && box.mode == StrongBox.MODE_UNSET && !pulse.linkUp()) {
            Log.i(TAG, "first run with no link -> offline screen on the first frame")
            startActivity(Intent(this, VoidStage::class.java))
            finish()
            StageChrome.noTransition(this)
            return
        }

        StageChrome.installFade(this)
        StageChrome.immersive(this)
        StageChrome.allowCutout(this)
        LoadingView.beginSession()
        loading = LoadingView(this, indeterminate = true) { }
        setContentView(loading)

        if (pushed != null) {
            Log.i(TAG, "push url on launch: $pushed")
            box.chilledPushUrl = pushed
        }

        scope.launch { decide() }
    }

    /**
     * Everything that leaves this screen goes through here: the loading bar runs up
     * to full first, so a launch never cuts the bar off part way.
     */
    private fun leaveWhenBarFills(next: () -> Unit) {
        loading.complete { if (!isFinishing) next() }
    }

    /**
     * Where the tapped push URL can hide. Our own service passes it as an extra, but
     * a message carrying a `notification` block is posted by the system while the app
     * is backgrounded: the service never runs and FCM copies the data payload into
     * the launch intent instead.
     */
    private fun pushUrlIn(intent: Intent): String? {
        intent.getStringExtra(EXTRA_PUSH_URL)?.takeIf { it.isNotBlank() }?.let { return it }
        val extras = intent.extras ?: return null

        for (key in PUSH_URL_KEYS) {
            val value = runCatching { extras.getString(key) }.getOrNull()
            if (isWebUrl(value)) return value
        }
        for (key in extras.keySet()) {
            if (!key.contains("url", true) && !key.contains("link", true)) continue
            val value = runCatching { extras.getString(key) }.getOrNull()
            if (isWebUrl(value)) return value
        }
        return null
    }

    private fun isWebUrl(value: String?): Boolean =
        !value.isNullOrBlank() && (value.startsWith("http://") || value.startsWith("https://"))

    private suspend fun decide() {
        if (AppCharter.forcedLandingUrl.isNotBlank()) {
            Log.w(TAG, "forced landing url in use")
            openWeb(AppCharter.forcedLandingUrl)
            return
        }

        box.takeChilledPush()?.takeIf { it.isNotBlank() }?.let { pushed ->
            Log.i(TAG, "cold push takes priority")
            if (box.mode == StrongBox.MODE_UNSET) box.mode = StrongBox.MODE_WEB
            openWeb(pushed)
            return
        }

        when (box.mode) {
            StrongBox.MODE_APP -> {
                Chronicle.note(TAG, "mode=app -> game")
                openGame()
            }

            StrongBox.MODE_WEB -> {
                Chronicle.note(TAG, "mode=web -> refresh")
                resumeWeb()
            }

            else -> {
                Chronicle.note(TAG, "mode=unset -> first decision")
                firstDecision()
            }
        }
    }

    private suspend fun firstDecision() {
        if (!online()) {
            // Nothing has been decided yet and there is no way to ask, so the run
            // stops here rather than guessing. The offline screen comes back through
            // this launcher once the link returns, and the first decision is made
            // then, in full — attribution and endpoint — exactly as it would have
            // been on a first run that had a connection from the start.
            Log.i(TAG, "no link on first run -> offline screen, decision deferred")
            openVoid(null)
            return
        }

        // Only now, with a link in hand, is attribution started — see TraceCourier.
        // A run that came here from the offline screen is lighting it for the first
        // time, so it gets the same answer a run with a connection would have got.
        courier().ignite(this)
        courier().retrace(this)
        val conversion = courier().awaitAttribution(AppCharter.traceFirstMs)
        Chronicle.note(TAG, "conversion=$conversion")

        val verdict = askGate(conversion)
        if (verdict.granted && !verdict.landing.isNullOrBlank()) {
            box.mode = StrongBox.MODE_WEB
            box.landingUrl = verdict.landing
            box.landingExpiresAt = verdict.expiresAt
            Chronicle.note(TAG, "gate granted -> web")
            openWeb(verdict.landing)
        } else {
            // A "no" sticks for the life of the install, so it has to be a real one:
            // the endpoint has to have answered, and it has to have been asked with
            // this install's attribution in hand. A request that never arrived, or
            // one sent with nothing to identify where the install came from, has
            // settled nothing — the game opens either way, but the question is left
            // for the next launch instead of being closed on a technicality.
            when {
                !verdict.answered ->
                    Chronicle.warn(TAG, "endpoint unreachable -> game, decision left open")

                conversion.isEmpty() ->
                    Chronicle.warn(TAG, "no attribution behind the answer -> game, decision left open")

                else -> {
                    box.mode = StrongBox.MODE_APP
                    Chronicle.warn(TAG, "gate refused with attribution in hand -> game for good")
                }
            }
            openGame()
        }
    }

    private suspend fun resumeWeb() {
        val stored = box.landingUrl?.takeIf { box.hasLiveLanding() }

        if (!online()) {
            Log.i(TAG, "no link -> offline screen")
            openVoid(stored)
            return
        }

        courier().ignite(this)
        courier().retrace(this)
        val conversion = courier().awaitAttribution(AppCharter.traceReturnMs)
        Chronicle.note(TAG, "conversion=$conversion")
        val verdict = askGate(conversion)
        when {
            verdict.granted && !verdict.landing.isNullOrBlank() -> {
                box.landingUrl = verdict.landing
                box.landingExpiresAt = verdict.expiresAt
                Chronicle.note(TAG, "gate granted -> web")
                openWeb(verdict.landing)
            }

            !stored.isNullOrBlank() -> {
                Chronicle.note(TAG, "endpoint gave nothing, reusing stored landing")
                openWeb(stored)
            }

            else -> {
                Chronicle.warn(TAG, "no landing available -> offline screen")
                openVoid(null)
            }
        }
    }

    private suspend fun askGate(conversion: Map<String, Any?>): GateVerdict {
        val token = box.pushToken ?: fetchPushToken()?.also { box.pushToken = it }
        val payload = courier().buildPayload(conversion, token)
        return ScoutCourier(AgentSignature.of(this)).askGate(payload)
    }

    /** Immediate check, with a short grace period for a link that is still coming up. */
    private suspend fun online(): Boolean {
        if (pulse.linkUp()) return true
        return withTimeoutOrNull(LINK_GRACE_MS) { pulse.pulses.first { it } } ?: false
    }

    private suspend fun fetchPushToken(): String? = withTimeoutOrNull(TOKEN_WAIT_MS) {
        suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    private fun courier() = (applicationContext as JesterApp).trace

    private fun openWeb(url: String) {
        if (box.shouldOfferHerald()) {
            leaveWhenBarFills {
                startActivity(
                    Intent(this, HeraldStage::class.java)
                        .putExtra(HeraldStage.EXTRA_LANDING, url)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                StageChrome.fadeOutgoing(this)
                finish()
            }
            return
        }
        // Straight to the page: the loading session carries on inside the WebView host
        // and the bar fills there, once the page it is waiting for is actually ready.
        startActivity(
            Intent(this, CanvasStage::class.java)
                .putExtra(CanvasStage.EXTRA_LANDING, url)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        StageChrome.fadeOutgoing(this)
        finish()
    }

    private fun openGame() = leaveWhenBarFills {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        StageChrome.fadeOutgoing(this)
        finish()
    }

    private fun openVoid(resume: String?) = leaveWhenBarFills {
        startActivity(
            Intent(this, VoidStage::class.java).apply {
                resume?.let { putExtra(VoidStage.EXTRA_RESUME_URL, it) }
            }
        )
        StageChrome.fadeOutgoing(this)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val pushed = pushUrlIn(intent) ?: return

        if (CanvasStage.isLive) {
            Log.i(TAG, "push tap onto live shell -> $pushed")
            box.takeChilledPush()
            BeaconBus.queue(pushed)
            finish()
            StageChrome.noTransition(this)
            return
        }

        // Still routing: let the pushed URL win and keep the bar running until the
        // page behind it is ready.
        Log.i(TAG, "push tap while routing -> $pushed")
        startActivity(
            Intent(this, CanvasStage::class.java)
                .putExtra(CanvasStage.EXTRA_LANDING, pushed)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        StageChrome.fadeOutgoing(this)
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_PUSH = "fl_from_push"
        const val EXTRA_PUSH_URL = "fl_push_url"

        private const val TAG = "GateKeeper"
        private const val LINK_GRACE_MS = 3_000L
        private const val TOKEN_WAIT_MS = 5_000L

        private val PUSH_URL_KEYS = listOf(
            "url", "link", "deeplink", "deep_link", "target_url",
            "gcm.notification.url", "gcm.notification.link", "gcm.notification.click_action"
        )
    }
}
