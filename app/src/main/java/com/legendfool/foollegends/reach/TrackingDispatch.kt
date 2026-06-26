package com.legendfool.foollegends.reach

import android.content.Context
import android.util.Log
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.legendfool.foollegends.blueprint.AppBlueprint
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * AppsFlyer attribution wrapper. Dev key must be configured in AppBlueprint
 * before this class will return paid attribution data.
 */
class TrackingDispatch(private val ctx: Context) {

    private var lastAttribution: Map<String, Any?>? = null

    fun init() {
        val key = AppBlueprint.resolveTrackerKey()
        if (key.isBlank()) {
            Log.d("TrackingDispatch", "dev key not configured — attribution skipped")
            return
        }
        AppsFlyerLib.getInstance().apply {
            setDebugLog(false)
            init(key, null, ctx)
            start(ctx)
        }
    }

    /**
     * Waits up to [timeoutMs] for attribution data, returns null on timeout or when
     * the dev key is not yet configured.
     */
    suspend fun awaitAttribution(timeoutMs: Long): Map<String, Any?>? {
        val key = AppBlueprint.resolveTrackerKey()
        if (key.isBlank()) return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : AppsFlyerConversionListener {
                    override fun onConversionDataSuccess(data: MutableMap<String, Any?>) {
                        lastAttribution = data
                        if (cont.isActive) cont.resume(data)
                    }
                    override fun onConversionDataFail(err: String) {
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onAppOpenAttribution(p0: MutableMap<String, String>?) {}
                    override fun onAttributionFailure(p0: String?) {}
                }
                AppsFlyerLib.getInstance().registerConversionListener(ctx, listener)
                cont.invokeOnCancellation {
                    AppsFlyerLib.getInstance().unregisterConversionListener()
                }
            }
        }
    }

    fun isOrganic(data: Map<String, Any?>?): Boolean {
        if (data == null) return true
        val status = data["af_status"]?.toString() ?: ""
        return status.equals("Organic", ignoreCase = true)
    }

    fun getAppsFlyerId(): String =
        AppsFlyerLib.getInstance().getAppsFlyerUID(ctx) ?: ""

    fun buildRequestBody(
        attribution: Map<String, Any?>?,
        deepLink: String?,
        os: String,
        locale: String,
        pushToken: String?,
        firebaseProject: String
    ): JSONObject = JSONObject().apply {
        put("bundle_id", AppBlueprint.bundleId)
        put("os", os)
        put("locale", locale)
        put("af_id", getAppsFlyerId())
        put("firebase_project_id", firebaseProject)
        if (!pushToken.isNullOrBlank()) put("push_token", pushToken)
        if (!deepLink.isNullOrBlank()) put("deep_link", deepLink)
        attribution?.forEach { (k, v) ->
            if (v != null) put(k, v.toString())
        }
    }
}
