package com.example.grayshell.core

import java.net.URI

/**
 * Shape check for URLs that arrive from outside the WebView: the config
 * endpoint's answer and push payload URLs.
 *
 * There is no host allowlist. The config endpoint is the authority on where a
 * user goes, and an affiliate chain crosses hosts nobody can enumerate in
 * advance — a list maintained by hand only ever ends up rejecting a working
 * destination and dropping the user into the game with no trace of why.
 *
 * What is still refused is anything that is not web content: a payload naming
 * `file:///…` or `javascript:…` would otherwise be loaded into a WebView that
 * has JavaScript enabled and can read local files.
 */
internal object UrlGuard {

    /** True if [url] is web content the shell can be handed. */
    fun accepts(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        return !uri.host.isNullOrBlank()
    }
}
