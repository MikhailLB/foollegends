package com.legendfool.foollegends.pulse

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Connectivity source of truth for the gray screens. */
class PulseMeter(ctx: Context) {

    private val manager = ctx.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val probe by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun linkUp(): Boolean {
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Confirms traffic actually flows, not just that an interface claims to be up. */
    suspend fun trafficFlows(): Boolean = withContext(Dispatchers.IO) {
        if (!linkUp()) return@withContext false
        try {
            val req = Request.Builder()
                .url("https://connectivitycheck.gstatic.com/generate_204")
                .head()
                .build()
            probe.newCall(req).execute().use { it.code in 200..399 }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Live default-network state. The default callback (rather than a filtered
     * request) delivers onLost the moment the last interface drops, so the WebView
     * can react even with no request in flight.
     */
    val pulses: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onUnavailable() {
                trySend(false)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        trySend(linkUp())
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
