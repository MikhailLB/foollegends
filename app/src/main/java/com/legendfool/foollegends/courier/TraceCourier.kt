package com.legendfool.foollegends.courier

import android.app.Activity
import android.content.Context
import android.util.Log
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.deeplink.DeepLinkListener
import com.appsflyer.deeplink.DeepLinkResult
import com.legendfool.foollegends.charter.AppCharter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AppsFlyer attribution, in two strokes.
 *
 * [prime] wires the callbacks and belongs in the Application, where the SDK insists
 * on being introduced. It puts nothing on the wire. [ignite] is the one that speaks
 * to AppsFlyer, and it is held back until the caller has seen a connection — an SDK
 * started blind reaches for its servers, misses, and reports no attribution within
 * milliseconds, a verdict it then keeps for the whole session. Route on that and an
 * install that came through a link is filed as organic and sent to the native app
 * for good. So a first run that begins offline sends nothing at all: it shows the
 * offline screen, and the launch goes out when the user comes back with a link, which
 * yields the same attribution a run that was never offline would have had.
 *
 * Splitting the two is also the only way the deferred start can work. The SDK learns
 * the app is in the foreground from the activity callbacks it registers during init,
 * so an init that happens when an activity is already on screen misses the moment and
 * sits on the launch until the next activity appears — in this app, the native game,
 * half a minute later and long past the point where the answer was needed. Hence init
 * before any activity exists, and a start that is handed the activity itself: passing
 * the application context from inside an activity, AppsFlyer's own words, "will not
 * trigger the SDK, thus losing attribution data".
 *
 * [retrace] covers the narrower case of a link that dies after the match was struck.
 */
class TraceCourier(private val ctx: Context) {

    @Volatile
    private var conversion = CompletableDeferred<Map<String, Any?>>()

    /** What the last completed attempt came back with. Null while one is in flight. */
    @Volatile
    private var settled: Map<String, Any?>? = null

    private val deepLinkParams = mutableMapOf<String, Any?>()

    /** Settles as soon as the deferred deep link is resolved, found or not. */
    private val deepLink = CompletableDeferred<Unit>()

    private var primed = false
    private var started = false

    @Volatile
    private var reasked = false
    private var reaskedAt = 0L

    private val gcdHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AppCharter.gcdTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(AppCharter.gcdTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Introductions only, from the Application. Nothing leaves the device. */
    fun prime() {
        if (primed) return
        primed = true

        val key = AppCharter.traceKey()
        if (key.isBlank()) {
            Log.w(TAG, "no dev key — attribution resolves empty")
            finish(emptyMap())
            deepLink.complete(Unit)
            return
        }

        val wired = runCatching {
            AppsFlyerLib.getInstance().apply {
                setDebugLog(true)
                subscribeForDeepLink(buildDeepLinkListener())
                init(key, listener, ctx.applicationContext)
            }
        }
        if (wired.isFailure) {
            Log.w(TAG, "AppsFlyer would not wire up: ${wired.exceptionOrNull()?.message}")
            finish(emptyMap())
            deepLink.complete(Unit)
        }
    }

    /**
     * Sends the launch, and with it the question this whole flow turns on.
     *
     * @param host the activity on screen. The SDK will not act on an application
     *   context handed to it from inside an activity.
     */
    fun ignite(host: Activity) {
        prime()
        if (started || AppCharter.traceKey().isBlank()) return
        started = true

        val lit = runCatching { AppsFlyerLib.getInstance().start(host) }
        if (lit.isFailure) {
            Log.w(TAG, "AppsFlyer would not start: ${lit.exceptionOrNull()?.message}")
            finish(emptyMap())
            deepLink.complete(Unit)
            return
        }
        Log.i(TAG, "AppsFlyer started from ${host.javaClass.simpleName}, uid=${traceId()}")
    }

    private val listener = object : AppsFlyerConversionListener {

        override fun onConversionDataSuccess(data: MutableMap<String, Any?>) {
            Log.i(TAG, "onConversionDataSuccess: $data")
            CoroutineScope(Dispatchers.IO).launch {
                val status = data["af_status"]?.toString().orEmpty()
                val resolved = if (status.equals("Organic", ignoreCase = true)) {
                    // First-run organic is often a false positive; GCD knows better.
                    delay(AppCharter.organicRetryDelayMs)
                    val gcd = askGcd()
                    Log.i(TAG, "organic re-check via GCD: $gcd")
                    gcd ?: data
                } else {
                    data
                }
                finish(resolved)
            }
        }

        override fun onConversionDataFail(error: String?) {
            Log.w(TAG, "onConversionDataFail: $error")
            finish(emptyMap())
        }

        override fun onAppOpenAttribution(data: MutableMap<String, String>?) {
            data?.forEach { (k, v) -> deepLinkParams[k] = v }
        }

        override fun onAttributionFailure(error: String?) {
            Log.w(TAG, "onAttributionFailure: $error")
            finish(emptyMap())
        }
    }

    private fun buildDeepLinkListener() = DeepLinkListener { result ->
        if (result.status != DeepLinkResult.Status.FOUND) {
            Log.i(TAG, "deep link status=${result.status}")
            deepLink.complete(Unit)
            return@DeepLinkListener
        }
        runCatching {
            val click = result.deepLink.clickEvent
            click.keys().forEach { k -> deepLinkParams[k] = click.opt(k) }
            Log.i(TAG, "deep link params: $deepLinkParams")
        }
        deepLink.complete(Unit)
    }

    private fun finish(data: Map<String, Any?>) {
        settled = data
        conversion.complete(data)
    }

    /**
     * Puts the question again, for a run that has just found a link after starting
     * without one.
     *
     * Only an attempt that has already come back empty is worth repeating: one still
     * in flight is left to arrive, and one that brought an answer — organic included,
     * since that is an answer — is kept. So a launch that had a connection all along
     * never gets here, and the SDK is never told to start twice for nothing.
     */
    fun retrace(host: Activity) {
        if (!started) {
            ignite(host)
            return
        }
        val last = settled ?: return
        if (last.isNotEmpty()) return

        settled = null
        reasked = true
        reaskedAt = System.currentTimeMillis()
        conversion = CompletableDeferred()
        runCatching { AppsFlyerLib.getInstance().start(host) }
        Log.i(TAG, "attribution asked again now the link is up")
    }

    /**
     * Everything AppsFlyer has to say about this install, waited for at once.
     *
     * The deferred deep link arrives on its own callback and carries the click's own
     * parameters, which the config endpoint is entitled to see alongside the
     * conversion. It is given its own, shorter grace: a run with no deep link at all
     * must not be held up for the length of a conversion wait.
     */
    suspend fun awaitAttribution(timeoutMs: Long): Map<String, Any?> = coroutineScope {
        val link = async { withTimeoutOrNull(AppCharter.deepLinkWaitMs) { deepLink.await() } }
        val data = async { awaitConversion(timeoutMs) }
        link.await()
        data.await()
    }

    /**
     * The listener's answer, or — where it has none — AppsFlyer's own record of the
     * install, fetched over HTTP.
     *
     * The direct call is the safety net for a session whose conversion failed: the
     * SDK holds that failure whether or not it retries, and an empty map here would
     * route a linked install into the native app.
     */
    suspend fun awaitConversion(timeoutMs: Long): Map<String, Any?> {
        // A repeat question is given less time than the first: the SDK has already
        // decided this install's conversion is settled, so it may well never call
        // back at all, and the splash cannot be left standing while that plays out.
        val window = if (reasked) minOf(timeoutMs, RETRACE_WAIT_MS) else timeoutMs
        val fromSdk = withTimeoutOrNull(window) { conversion.await() } ?: emptyMap()
        if (fromSdk.isNotEmpty()) return fromSdk

        // A second failure can come back in milliseconds, before the SDK has managed
        // to file the install it was holding — and AppsFlyer cannot report attribution
        // for an install it has not been told about yet. So the direct call waits out
        // the same stretch the SDK would have had.
        if (reasked) {
            val waited = System.currentTimeMillis() - reaskedAt
            if (waited < RETRACE_WAIT_MS) delay(RETRACE_WAIT_MS - waited)
        }

        val fromGcd = askGcd()
        if (fromGcd.isNullOrEmpty()) return fromSdk
        Log.i(TAG, "attribution recovered from GCD: $fromGcd")
        settled = fromGcd
        return fromGcd
    }

    fun traceId(): String = AppsFlyerLib.getInstance().getAppsFlyerUID(ctx).orEmpty()

    private suspend fun askGcd(): Map<String, Any?>? = withContext(Dispatchers.IO) {
        try {
            val uid = AppsFlyerLib.getInstance().getAppsFlyerUID(ctx) ?: return@withContext null
            val root = AppCharter.gcdRoot()
            if (root.isBlank()) return@withContext null
            val req = Request.Builder()
                .url("$root${AppCharter.bundleId}?device_id=$uid")
                .addHeader("Authorization", "Bearer ${AppCharter.traceKey()}")
                .get()
                .build()
            gcdHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GCD HTTP ${resp.code}")
                    return@withContext null
                }
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@withContext null
                val obj = JSONObject(raw)
                obj.keys().asSequence().associateWith { obj.opt(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GCD failed: ${e.message}")
            null
        }
    }

    /**
     * Body for the config endpoint: conversion fields verbatim, then deep-link keys
     * that aren't already present, then device fields which always win.
     */
    fun buildPayload(
        conversionData: Map<String, Any?>,
        pushToken: String?
    ): JSONObject = JSONObject().apply {
        conversionData.forEach { (k, v) -> if (v != null) put(k, v.toString()) }
        deepLinkParams.forEach { (k, v) -> if (v != null && !has(k)) put(k, v.toString()) }

        put("af_id", traceId())
        put("bundle_id", AppCharter.bundleId)
        put("os", "Android")
        put("store_id", AppCharter.bundleId)
        put("locale", Locale.getDefault().toLanguageTag().replace('-', '_'))
        if (!pushToken.isNullOrBlank()) put("push_token", pushToken)
        AppCharter.signalProject().takeIf { it.isNotBlank() }
            ?.let { put("firebase_project_id", it) }
    }

    private companion object {
        const val TAG = "TraceCourier"

        /** Ceiling on a second wait for the SDK, before AppsFlyer is asked directly. */
        const val RETRACE_WAIT_MS = 8_000L
    }
}
