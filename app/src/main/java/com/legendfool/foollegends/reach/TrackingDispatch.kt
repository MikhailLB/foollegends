package com.legendfool.foollegends.reach

import android.content.Context
import android.util.Log
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.legendfool.foollegends.blueprint.AppBlueprint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AppsFlyer attribution wrapper for Fool Legends.
 *
 * CRITICAL: the conversion listener MUST be passed to init() — not registered
 * separately after start(). CompletableDeferred bridges the async callback.
 */
class TrackingDispatch(private val ctx: Context) {

    private val attributionDeferred = CompletableDeferred<Map<String, Any?>>()
    private var initialized = false
    private val gcdHttp by lazy {
        OkHttpClient.Builder()
            .connectTimeout(AppBlueprint.gcdTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(AppBlueprint.gcdTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Call once from Application.onCreate() — registers listener then starts SDK. */
    fun init() {
        if (initialized) return
        initialized = true

        val key = AppBlueprint.resolveTrackerKey()
        if (key.isBlank()) {
            log("dev key not configured — completing with empty attribution")
            attributionDeferred.complete(emptyMap())
            return
        }

        val listener = object : AppsFlyerConversionListener {
            override fun onConversionDataSuccess(raw: MutableMap<String, Any?>) {
                log("onConversionDataSuccess: $raw")
                // Handle organic false-positive in IO scope.
                CoroutineScope(Dispatchers.IO).launch {
                    val afStatus = raw["af_status"]?.toString() ?: ""
                    val finalData = if (afStatus.equals("Organic", ignoreCase = true)) {
                        log("af_status=Organic — waiting ${AppBlueprint.organicGcdDelayMs}ms then GCD retry")
                        delay(AppBlueprint.organicGcdDelayMs)
                        val gcd = fetchGcd()
                        log("GCD retry data: $gcd")
                        gcd ?: raw
                    } else {
                        raw
                    }
                    if (!attributionDeferred.isCompleted) {
                        attributionDeferred.complete(finalData)
                    }
                }
            }

            override fun onConversionDataFail(err: String) {
                log("onConversionDataFail: $err")
                if (!attributionDeferred.isCompleted) attributionDeferred.complete(emptyMap())
            }

            override fun onAppOpenAttribution(p0: MutableMap<String, String>?) {}
            override fun onAttributionFailure(p0: String?) {
                if (!attributionDeferred.isCompleted) attributionDeferred.complete(emptyMap())
            }
        }

        AppsFlyerLib.getInstance().apply {
            setDebugLog(true)
            init(key, listener, ctx)
            start(ctx)
        }
        log("SDK started, dev key len=${key.length}, uid=${getAppsFlyerId()}")
    }

    /**
     * Await attribution data. Use [AppBlueprint.attributionFirstMs] on first launch,
     * [AppBlueprint.attributionReturnMs] on returning online users.
     */
    suspend fun awaitAttribution(timeoutMs: Long): Map<String, Any?> =
        withTimeoutOrNull(timeoutMs) { attributionDeferred.await() } ?: emptyMap()

    /** GCD API call — returns fresh attribution or null on failure. */
    private suspend fun fetchGcd(): Map<String, Any?>? = withContext(Dispatchers.IO) {
        try {
            val uid = AppsFlyerLib.getInstance().getAppsFlyerUID(ctx)
                ?: return@withContext null
            val base = AppBlueprint.resolveGcdBase()
            val url  = "${base}${AppBlueprint.bundleId}?device_id=$uid"
            val key  = AppBlueprint.resolveTrackerKey()
            val req  = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()
            gcdHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
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
     * Builds the POST body for the config endpoint.
     * Attribution fields are added first (as-is), device fields overwrite duplicates.
     */
    fun buildRequestBody(
        attribution: Map<String, Any?>,
        os: String,
        locale: String,
        pushToken: String?,
        firebaseProject: String
    ): JSONObject = JSONObject().apply {
        // 1. Attribution data — all fields, as-is (never filter or rename).
        attribution.forEach { (k, v) -> if (v != null) put(k, v.toString()) }
        // 2. Device fields — always overwrite.
        put("af_id",              getAppsFlyerId())
        put("bundle_id",          AppBlueprint.bundleId)
        put("os",                 os)
        put("store_id",           AppBlueprint.bundleId)
        put("locale",             locale)
        if (!pushToken.isNullOrBlank())     put("push_token",         pushToken)
        if (firebaseProject.isNotBlank())   put("firebase_project_id",firebaseProject)
        log("Request body: ${toString(2)}")
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        obj.keys().forEach { k -> map[k] = obj.opt(k) }
        return map
    }

    private fun log(msg: String) {
        Log.i("TrackingDispatch", msg)
    }
}
