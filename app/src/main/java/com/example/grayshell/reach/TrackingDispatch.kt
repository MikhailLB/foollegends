package com.example.grayshell.reach

import android.app.Activity
import android.content.Context
import android.util.Log
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.deeplink.DeepLinkListener
import com.appsflyer.deeplink.DeepLinkResult
import com.example.grayshell.blueprint.AppBlueprint
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
import java.util.concurrent.TimeUnit

/**
 * AppsFlyer attribution, in two strokes. Read `.cursor/rules/kotlin_launch_flow.mdc`
 * before changing anything here — every line below is load-bearing.
 *
 * [prime] wires the callbacks and belongs in the Application, where the SDK insists
 * on being introduced: it registers the activity-lifecycle callbacks the SDK uses to
 * notice the app is in the foreground, and doing that when an activity is already on
 * screen means it never notices, so the launch sits queued until the next activity
 * appears. It puts nothing on the wire.
 *
 * [ignite] is the call that speaks to AppsFlyer, and it must not run before the
 * caller has seen a connection. An SDK started blind reaches for its servers, misses,
 * and reports no attribution within milliseconds — a verdict it keeps for the whole
 * session. Route on that and a paid install is filed as organic. It takes the
 * Activity, not the application context: AppsFlyer's own words, passing the latter
 * from inside an activity "will not trigger the SDK, thus losing attribution data".
 */
class TrackingDispatch(private val ctx: Context) {

    @Volatile
    private var attribution = CompletableDeferred<Map<String, Any?>>()

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
            .connectTimeout(AppBlueprint.gcdTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(AppBlueprint.gcdTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Introductions only, from Application.onCreate. Nothing leaves the device. */
    fun prime() {
        if (primed) return
        primed = true

        val key = AppBlueprint.resolveTrackerKey()
        if (key.isBlank()) {
            log("dev key not configured — attribution resolves empty")
            finish(emptyMap())
            deepLink.complete(Unit)
            return
        }

        val wired = runCatching {
            AppsFlyerLib.getInstance().apply {
                setDebugLog(true)
                subscribeForDeepLink(deepLinkListener)   // before init, always
                init(key, conversionListener, ctx.applicationContext)
            }
        }
        if (wired.isFailure) {
            log("SDK would not wire up: ${wired.exceptionOrNull()?.message}")
            finish(emptyMap())
            deepLink.complete(Unit)
        }
    }

    /**
     * Sends the launch. Call only once connectivity is confirmed.
     *
     * @param host the activity on screen — the SDK ignores an application context
     *   handed to it from inside an activity.
     */
    fun ignite(host: Activity) {
        prime()
        if (started || AppBlueprint.resolveTrackerKey().isBlank()) return
        started = true

        val lit = runCatching { AppsFlyerLib.getInstance().start(host) }
        if (lit.isFailure) {
            log("SDK would not start: ${lit.exceptionOrNull()?.message}")
            finish(emptyMap())
            deepLink.complete(Unit)
            return
        }
        log("SDK started from ${host.javaClass.simpleName}, uid=${getAppsFlyerId()}")
    }

    /**
     * Puts the question again, for a run that found a link after starting without one.
     *
     * Only an attempt that already came back empty is worth repeating: one still in
     * flight is left to arrive, and one that brought an answer — organic included,
     * since that is an answer — is kept. So a launch that had a connection all along
     * never gets here and the SDK is never told to start twice for nothing.
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
        attribution = CompletableDeferred()
        runCatching { AppsFlyerLib.getInstance().start(host) }
        log("attribution asked again now the link is up")
    }

    /**
     * Everything AppsFlyer has to say about this install, waited for at once. The
     * deferred deep link gets its own, shorter grace so a run without one is not held
     * up for the length of a conversion wait.
     */
    suspend fun awaitAttribution(timeoutMs: Long): Map<String, Any?> = coroutineScope {
        val link = async { withTimeoutOrNull(AppBlueprint.deepLinkWaitMs) { deepLink.await() } }
        val data = async { awaitConversion(timeoutMs) }
        link.await()
        data.await()
    }

    /**
     * The listener's answer, or — where it has none — AppsFlyer's own record of the
     * install, fetched over HTTP. The direct call is the safety net for a session
     * whose conversion failed: the SDK holds that failure whether or not it retries.
     */
    private suspend fun awaitConversion(timeoutMs: Long): Map<String, Any?> {
        // A repeat question gets less time: the SDK considers this install's
        // conversion settled and may never call back at all, and the splash cannot be
        // left standing while that plays out.
        val window = if (reasked) minOf(timeoutMs, RETRACE_WAIT_MS) else timeoutMs
        val fromSdk = withTimeoutOrNull(window) { attribution.await() } ?: emptyMap()
        if (fromSdk.isNotEmpty()) return fromSdk

        // A second failure can come back in milliseconds, before the SDK has filed
        // the install it was holding — and AppsFlyer cannot report attribution for an
        // install it has not been told about. So the direct call waits out the same
        // stretch the SDK would have had.
        if (reasked) {
            val waited = System.currentTimeMillis() - reaskedAt
            if (waited < RETRACE_WAIT_MS) delay(RETRACE_WAIT_MS - waited)
        }

        val fromGcd = fetchGcd()
        if (fromGcd.isNullOrEmpty()) return fromSdk
        log("attribution recovered from GCD: $fromGcd")
        settled = fromGcd
        return fromGcd
    }

    private val conversionListener = object : AppsFlyerConversionListener {

        override fun onConversionDataSuccess(raw: MutableMap<String, Any?>) {
            log("onConversionDataSuccess: $raw")
            CoroutineScope(Dispatchers.IO).launch {
                val status = raw["af_status"]?.toString().orEmpty()
                val resolved = if (status.equals("Organic", ignoreCase = true)) {
                    // First-run organic is often a false positive; GCD knows better.
                    delay(AppBlueprint.organicGcdDelayMs)
                    val gcd = fetchGcd()
                    log("organic re-check via GCD: $gcd")
                    gcd ?: raw
                } else {
                    raw
                }
                finish(resolved)
            }
        }

        override fun onConversionDataFail(err: String?) {
            log("onConversionDataFail: $err")
            finish(emptyMap())
        }

        override fun onAppOpenAttribution(data: MutableMap<String, String>?) {
            data?.forEach { (k, v) -> deepLinkParams[k] = v }
        }

        override fun onAttributionFailure(err: String?) {
            log("onAttributionFailure: $err")
            finish(emptyMap())
        }
    }

    /** Completes on every branch, or the parallel wait always burns its full 5s. */
    private val deepLinkListener = DeepLinkListener { result ->
        if (result.status != DeepLinkResult.Status.FOUND) {
            log("deep link status=${result.status}")
            deepLink.complete(Unit)
            return@DeepLinkListener
        }
        runCatching {
            val click = result.deepLink.clickEvent
            click.keys().forEach { k -> deepLinkParams[k] = click.opt(k) }
            log("deep link params: $deepLinkParams")
        }
        deepLink.complete(Unit)
    }

    private fun finish(data: Map<String, Any?>) {
        settled = data
        attribution.complete(data)
    }

    /** GCD API call — returns AppsFlyer's own record of the install, or null. */
    private suspend fun fetchGcd(): Map<String, Any?>? = withContext(Dispatchers.IO) {
        try {
            val uid = AppsFlyerLib.getInstance().getAppsFlyerUID(ctx) ?: return@withContext null
            val base = AppBlueprint.resolveGcdBase()
            if (base.isBlank()) return@withContext null
            val req = Request.Builder()
                .url("$base${AppBlueprint.bundleId}?device_id=$uid")
                .addHeader("Authorization", "Bearer ${AppBlueprint.resolveTrackerKey()}")
                .get()
                .build()
            gcdHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log("GCD HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                if (body.isBlank()) return@withContext null
                jsonToMap(JSONObject(body))
            }
        } catch (e: Exception) {
            log("GCD fetch failed: ${e.message}")
            null
        }
    }

    fun getAppsFlyerId(): String =
        AppsFlyerLib.getInstance().getAppsFlyerUID(ctx) ?: ""

    /**
     * Builds the POST body for the config endpoint: conversion fields verbatim, then
     * deep-link keys not already present, then device fields, which always win.
     */
    fun buildRequestBody(
        attributionData: Map<String, Any?>,
        os: String,
        locale: String,
        pushToken: String?,
        firebaseProject: String
    ): JSONObject = JSONObject().apply {
        attributionData.forEach { (k, v) -> if (v != null) put(k, v.toString()) }
        deepLinkParams.forEach { (k, v) -> if (v != null && !has(k)) put(k, v.toString()) }

        put("af_id", getAppsFlyerId())
        put("bundle_id", AppBlueprint.bundleId)
        put("os", os)
        put("store_id", AppBlueprint.bundleId)
        put("locale", locale)
        if (!pushToken.isNullOrBlank()) put("push_token", pushToken)
        if (firebaseProject.isNotBlank()) put("firebase_project_id", firebaseProject)
        log("Request body: ${toString(2)}")
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associateWith { obj.opt(it) }

    private fun log(msg: String) = Log.i(TAG, msg)

    private companion object {
        const val TAG = "TrackingDispatch"

        /** Ceiling on a second wait for the SDK, before AppsFlyer is asked directly. */
        const val RETRACE_WAIT_MS = 8_000L
    }
}
