package com.example.grayshell.wire

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class NetWire(ctx: Context) {

    private val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    /** True if there is an active network that reports internet capability. */
    fun isConnected(): Boolean {
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * True if a TCP handshake actually completes somewhere out on the internet.
     *
     * The capability check above cannot answer this, and a VPN is the case that
     * makes the difference impossible to ignore. With a tunnel up, `activeNetwork`
     * for this uid is the tunnel; switching Wi-Fi off leaves the tunnel exactly
     * where it was — CONNECTED, INTERNET, VALIDATED — with nothing underneath it.
     * No `onLost` is delivered for a network that never went away, so a loaded
     * page simply stops loading and nothing in the app is any the wiser. Captive
     * portals behave the same way.
     *
     * Two targets, and on two different ports: a network that refuses outbound 53
     * to public resolvers, or blocks one address, must not read as "the internet
     * is gone". Both are raw IPs on purpose — a hostname would need DNS, and DNS
     * down a dead tunnel is precisely what hangs.
     */
    suspend fun hasRealInternet(): Boolean = withContext(Dispatchers.IO) {
        PROBES.any { (host, port) ->
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                    true
                }
            } catch (_: Exception) { false }
        }
    }

    /**
     * Emits the live online/offline state of the DEFAULT network. Using the default
     * network callback (rather than a capability-filtered request) means we get an
     * immediate onLost the moment Wi-Fi / cellular is turned off, so the WebView host
     * can react instantly even when no page request is in flight.
     */
    val connectivityFlow: Flow<Boolean> = callbackFlow {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(false) }
            override fun onUnavailable() { trySend(false) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        trySend(isConnected())
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()

    private companion object {
        val PROBES = listOf("1.1.1.1" to 443, "8.8.8.8" to 53)
        const val PROBE_TIMEOUT_MS = 2_000
    }
}
