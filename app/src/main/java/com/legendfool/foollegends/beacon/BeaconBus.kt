package com.legendfool.foollegends.beacon

/**
 * Hand-off for push URLs aimed at a WebView that already exists.
 *
 * [offer] is the live route, used while the page is on screen. [queue] is for a
 * notification tapped after the shell went to the background: the URL waits until
 * the stage comes back and picks it up. Neither route persists anything — a URL that
 * has to survive the process going away is stored as a cold push instead.
 */
object BeaconBus {

    @Volatile
    var warmSink: ((String) -> Unit)? = null

    @Volatile
    private var queued: String? = null

    /** @return true when a visible stage took the URL. */
    fun offer(url: String): Boolean {
        val sink = warmSink ?: return false
        return runCatching { sink(url) }.isSuccess
    }

    fun queue(url: String) {
        if (!offer(url)) queued = url
    }

    fun takeQueued(): String? {
        val url = queued
        queued = null
        return url
    }
}
