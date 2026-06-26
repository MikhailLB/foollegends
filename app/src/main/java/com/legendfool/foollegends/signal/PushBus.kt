package com.legendfool.foollegends.signal

/**
 * Process-wide hand-off for one-time push URLs. StreamPortal registers
 * [onWarmUrl] when it starts and clears it on stop. When a push arrives while
 * the WebView is on screen, [PushRelay] invokes the callback directly so the
 * URL is loaded into the existing WebView — never persisted (one-time rule).
 */
object PushBus {
    @Volatile
    var onWarmUrl: ((String) -> Unit)? = null
}
