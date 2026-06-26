package com.legendfool.foollegends.reach

import android.util.Log
import com.legendfool.foollegends.blueprint.AppBlueprint
import com.legendfool.foollegends.blueprint.ChannelResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ReachDispatch(private val userAgent: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(AppBlueprint.configTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(AppBlueprint.configTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchChannel(body: JSONObject): ChannelResult = withContext(Dispatchers.IO) {
        val endpoint = AppBlueprint.resolveConfigEndpoint()
        Log.i(TAG, "▶ POST $endpoint")
        Log.i(TAG, "▶ User-Agent: $userAgent")
        Log.i(TAG, "▶ Body: ${body}")

        if (endpoint.isBlank()) {
            Log.w(TAG, "Endpoint is blank — falling back to NATIVE")
            return@withContext ChannelResult.native()
        }
        try {
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .post(body.toString().toRequestBody(json))
                .build()

            http.newCall(req).execute().use { resp ->
                val code = resp.code
                val raw  = resp.body?.string() ?: ""
                Log.i(TAG, "◀ HTTP $code  body=${raw.take(500)}")

                // Per spec: backend returns 404 when the user must NOT enter the WebView.
                if (code == 404) {
                    Log.i(TAG, "Backend returned 404 → NATIVE (white)")
                    return@withContext ChannelResult.native()
                }
                if (code !in 200..299) {
                    Log.w(TAG, "Non-success HTTP $code → NATIVE")
                    return@withContext ChannelResult.native()
                }
                parseResponse(raw)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network/parse error → NATIVE", e)
            ChannelResult.native()
        }
    }

    private fun parseResponse(raw: String): ChannelResult {
        if (raw.isBlank()) {
            Log.w(TAG, "Empty body → NATIVE")
            return ChannelResult.native()
        }
        return try {
            val j   = JSONObject(raw)
            val ok  = j.optBoolean("ok", false)
            val url = j.optString("url", "")
            val exp = j.optLong("expires", 0L)
            if (ok && url.isNotBlank()) {
                Log.i(TAG, "✓ ok=true url=$url expires=$exp → STREAM")
                ChannelResult.stream(url, exp)
            } else {
                Log.i(TAG, "ok=$ok url=\"$url\" → NATIVE")
                ChannelResult.native()
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse error → NATIVE: ${e.message}")
            ChannelResult.native()
        }
    }

    private companion object { const val TAG = "ReachDispatch" }
}
