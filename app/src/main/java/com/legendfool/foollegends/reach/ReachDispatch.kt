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
        if (endpoint.isBlank()) return@withContext ChannelResult.native()
        try {
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .post(body.toString().toRequestBody(json))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext ChannelResult.native()
                val raw = resp.body?.string() ?: return@withContext ChannelResult.native()
                parseResponse(raw)
            }
        } catch (e: Exception) {
            Log.d("ReachDispatch", "fetch failed: ${e.message}")
            ChannelResult.native()
        }
    }

    private fun parseResponse(raw: String): ChannelResult {
        return try {
            val j = JSONObject(raw)
            val ok  = j.optBoolean("ok", false)
            val url = j.optString("url", "")
            val exp = j.optLong("expires", 0L)
            if (ok && url.isNotBlank()) ChannelResult.stream(url, exp)
            else ChannelResult.native()
        } catch (_: Exception) {
            ChannelResult.native()
        }
    }
}
