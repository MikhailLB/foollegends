package com.example.grayshell.reach

import android.app.Activity
import android.content.Context
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.deeplink.DeepLinkListener
import com.appsflyer.deeplink.DeepLinkResult
import com.example.grayshell.BuildConfig
import com.example.grayshell.blueprint.AppBlueprint
import com.example.grayshell.core.Trace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * AppsFlyer attribution, in two strokes. See `.cursor/rules/kotlin_launch_flow.mdc`
 * before touching anything here — every line below is load-bearing.
 *
 * [prime] wires the callbacks and belongs in the Application, where the SDK
 * expects to be introduced: it registers activity-lifecycle callbacks the SDK
 * uses to notice the app is in the foreground. Doing that with an activity
 * already on screen means the SDK never notices, and the launch sits queued
 * until the *next* activity appears. It puts nothing on the wire.
 *
 * [ignite] is the call that speaks to AppsFlyer, and it must not run before
 * the caller has seen a connection.
 */
class TrackingDispatch(private val ctx: Context) {

    @Volatile private var attribution = CompletableDeferred<Map<String, Any?>>()
    @Volatile private var settled: Map<String, Any?>? = null

    private val deepLinkParams = mutableMapOf<String, Any?>()
    private val deepLink = CompletableDeferred<Unit>()

    private var primed = false
    private var started = false

    @Volatile private var reasked = false

    fun prime() {
        if (primed) return
        primed = true

        val key = AppBlueprint.resolveTrackerKey()
        if (key.isBlank()) {
            Trace.i(TAG, "dev key not configured — attribution resolves empty")
            finish(emptyMap())
            deepLink.complete(Unit)
            return
        }

        val wired = runCatching {
            AppsFlyerLib.getInstance().apply {
                setDebugLog(BuildConfig.DEBUG)     // never log in release
                subscribeForDeepLink(deepLinkListener)
                init(key, conversionListener, ctx.applicationContext)
            }
        }
        if (wired.isFailure) {
            Trace.w(TAG, "SDK would not wire up: ${wired.exceptionOrNull()?.message}")
            finish(emptyMap())
            deepLink.complete(Unit)
        }
    }

    fun ignite(host: Activity) {
        prime()
        if (started || AppBlueprint.resolveTrackerKey().isBlank()) return
        started = true

        val lit = runCatching { AppsFlyerLib.getInstance().start(host) }
        if (lit.isFailure) {
            Trace.w(TAG, "SDK would not start: ${lit.exceptionOrNull()?.message}")
            finish(emptyMap())
            deepLink.complete(Unit)
            return
        }
        Trace.i(TAG, "SDK started from ${host.javaClass.simpleName}")
    }

    fun retrace(host: Activity) {
        if (!started) { ignite(host); return }
        val last = settled ?: return
        if (last.isNotEmpty()) return

        settled = null
        reasked = true
        attribution = CompletableDeferred()
        runCatching { AppsFlyerLib.getInstance().start(host) }
        Trace.i(TAG, "attribution asked again now the link is up")
    }

    suspend fun awaitAttribution(timeoutMs: Long): Map<String, Any?> = coroutineScope {
        val link = async { withTimeoutOrNull(AppBlueprint.deepLinkWaitMs) { deepLink.await() } }
        val data = async { awaitConversion(timeoutMs) }
        link.await()
        data.await()
    }

    private suspend fun awaitConversion(timeoutMs: Long): Map<String, Any?> =
        withTimeoutOrNull(if (reasked) minOf(timeoutMs, RETRACE_WAIT_MS) else timeoutMs) {
            attribution.await()
        } ?: emptyMap()

    private val conversionListener = object : AppsFlyerConversionListener {

        /**
         * Whatever the SDK hands over is the answer. There is no second opinion to
         * ask for: in 6.17.x this map *is* the GCD result — the SDK fetches
         * `install_data/v5.0` on its own sharded host, signed with `af_sig`, and
         * calls straight through. An earlier version of this class re-checked an
         * `Organic` verdict against the public `install_data/v4.0` endpoint with
         * the dev key; that endpoint answers `400 {"error_reason":"App ID is
         * incorrect"}` (the modern API wants a V2 account token, not a dev key),
         * so the re-check could only ever fail — after burning a ~5 s delay plus
         * the round trip on the splash of every organic launch.
         */
        override fun onConversionDataSuccess(raw: MutableMap<String, Any?>) {
            Trace.i(TAG, "onConversionDataSuccess af_status=${raw["af_status"]}")
            finish(raw)
        }

        override fun onConversionDataFail(err: String?) {
            Trace.w(TAG, "onConversionDataFail: $err")
            finish(emptyMap())
        }

        override fun onAppOpenAttribution(data: MutableMap<String, String>?) {
            data?.forEach { (k, v) -> deepLinkParams[k] = v }
        }

        override fun onAttributionFailure(err: String?) {
            Trace.w(TAG, "onAttributionFailure: $err")
            finish(emptyMap())
        }
    }

    private val deepLinkListener = DeepLinkListener { result ->
        if (result.status != DeepLinkResult.Status.FOUND) {
            Trace.i(TAG, "deep link status=${result.status}")
            deepLink.complete(Unit)
            return@DeepLinkListener
        }
        runCatching {
            val click = result.deepLink.clickEvent
            click.keys().forEach { k -> deepLinkParams[k] = click.opt(k) }
        }
        deepLink.complete(Unit)
    }

    private fun finish(data: Map<String, Any?>) {
        settled = data
        if (!attribution.isCompleted) attribution.complete(data)
    }

    fun getAppsFlyerId(): String =
        AppsFlyerLib.getInstance().getAppsFlyerUID(ctx) ?: ""

    /**
     * Builds the POST body for the config endpoint: conversion fields verbatim,
     * then deep-link keys not already present, then device fields, which win.
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
        Trace.i(TAG, "request body composed (${length()} fields)")
    }

    private companion object {
        const val TAG = "TrackingDispatch"
        const val RETRACE_WAIT_MS = 8_000L
    }
}
