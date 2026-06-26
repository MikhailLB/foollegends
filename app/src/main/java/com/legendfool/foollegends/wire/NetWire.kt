package com.legendfool.foollegends.wire

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.InetSocketAddress
import java.net.Socket

class NetWire(private val ctx: Context) {

    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isConnected(): Boolean {
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** True if a TCP socket can actually reach the network (avoids captive-portal false positives). */
    suspend fun hasRealInternet(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress("8.8.8.8", 53), 3_000)
                true
            }
        } catch (_: Exception) { false }
    }

    /** Emits true when any network is available, false when all are lost. */
    val connectivityFlow: Flow<Boolean> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) {
                val still = cm.activeNetwork != null
                if (!still) trySend(false)
            }
        }
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, cb)
        trySend(isConnected())
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()
}
