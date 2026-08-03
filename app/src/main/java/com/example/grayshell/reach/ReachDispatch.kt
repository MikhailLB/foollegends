package com.example.grayshell.reach

import com.example.grayshell.blueprint.AppBlueprint
import com.example.grayshell.blueprint.ChannelResult
import com.example.grayshell.core.Trace
import com.example.grayshell.core.UrlGuard
import com.example.grayshell.core.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Config endpoint client. One responsibility, one method: POST the attribution
 * body and return the parsed answer.
 *
 * The URL out of a successful response is checked against [UrlGuard] before it
 * is handed back. A destination outside the allowlist is treated the same as
 * `ok:false` — the app opens the native part, and the mode is not persisted
 * (the endpoint did answer, but its answer was rejected by our own gate, so
 * the "did the server rule on this install" question is still open next launch).
 */
class ReachDispatch {

    private val http = OkHttpClient.Builder()
        .connectTimeout(AppBlueprint.configTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(AppBlueprint.configTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchChannel(body: JSONObject): ChannelResult = withContext(Dispatchers.IO) {
        val endpoint = AppBlueprint.resolveConfigEndpoint()
        if (endpoint.isBlank()) {
            Trace.w(TAG, "endpoint is blank — nobody to ask")
            return@withContext ChannelResult.unreachable()
        }
        Trace.i(TAG, "POST config endpoint")
        try {
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", UserAgent.value)
                .post(body.toString().toRequestBody(json))
                .build()

            http.newCall(req).execute().use { resp ->
                val code = resp.code
                val raw = resp.body?.string().orEmpty()
                Trace.i(TAG, "HTTP $code (${raw.length} chars)")

                if (code == 404) return@withContext ChannelResult.native()
                if (code !in 200..299) return@withContext ChannelResult.native()
                parseResponse(raw)
            }
        } catch (e: Exception) {
            Trace.w(TAG, "request never landed: ${e.message}")
            ChannelResult.unreachable()
        }
    }

    private fun parseResponse(raw: String): ChannelResult {
        if (raw.isBlank()) return ChannelResult.native()
        return try {
            val j = JSONObject(raw)
            val ok = j.optBoolean("ok", false)
            val url = j.optString("url", "")
            val exp = j.optLong("expires", 0L)
            if (ok && url.isNotBlank()) {
                if (!UrlGuard.accepts(url)) {
                    Trace.w(TAG, "endpoint URL rejected by allowlist")
                    return ChannelResult.native()
                }
                ChannelResult.stream(url, exp)
            } else {
                ChannelResult.native()
            }
        } catch (e: Exception) {
            Trace.w(TAG, "JSON parse error: ${e.message}")
            ChannelResult.native()
        }
    }

    private companion object { const val TAG = "ReachDispatch" }
}
