package com.legendfool.foollegends.courier

import android.util.Log
import com.legendfool.foollegends.charter.AppCharter
import com.legendfool.foollegends.charter.GateVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Config-endpoint client. Any non-2xx answer (404 included) is a legitimate
 * "no landing for this user", never an error state.
 */
class ScoutCourier(private val userAgent: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(AppCharter.landingTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(AppCharter.landingTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun askGate(payload: JSONObject): GateVerdict = withContext(Dispatchers.IO) {
        val endpoint = AppCharter.landingEndpoint()
        if (endpoint.isBlank()) {
            Log.w(TAG, "endpoint missing -> app mode")
            return@withContext GateVerdict.unreachable()
        }

        Log.i(TAG, "POST $endpoint")
        Log.i(TAG, "UA  $userAgent")
        Log.i(TAG, "body $payload")

        try {
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .post(payload.toString().toRequestBody(jsonType))
                .build()

            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                Log.i(TAG, "HTTP ${resp.code} body=${raw.take(500)}")
                if (resp.code !in 200..299) GateVerdict.refused() else read(raw)
            }
        } catch (e: Exception) {
            // Nobody said no here — nobody said anything. The caller decides what to
            // show, but must not write the answer down.
            Log.w(TAG, "request never landed: ${e.message}")
            GateVerdict.unreachable()
        }
    }

    private fun read(raw: String): GateVerdict {
        if (raw.isBlank()) return GateVerdict.refused()
        return try {
            val obj = JSONObject(raw)
            val ok = obj.optBoolean("ok", false)
            val url = obj.optString("url").orEmpty()
            val expires = obj.optLong("expires", 0L)
            if (ok && url.isNotBlank()) {
                Log.i(TAG, "granted url=$url expires=$expires")
                GateVerdict.allowed(url, expires)
            } else {
                Log.i(TAG, "ok=$ok url=\"$url\" -> app mode")
                GateVerdict.refused()
            }
        } catch (e: Exception) {
            Log.w(TAG, "unparsable body -> app mode: ${e.message}")
            GateVerdict.refused()
        }
    }

    private companion object {
        const val TAG = "ScoutCourier"
    }
}
