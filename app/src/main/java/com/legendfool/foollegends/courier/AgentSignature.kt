package com.legendfool.foollegends.courier

import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import com.legendfool.foollegends.charter.AppCharter

/**
 * One User-Agent for both the WebView and the config client. Built from the real
 * system WebView UA with the "; wv" WebView marker removed, so the string always
 * matches the actual device instead of a frozen Chrome version.
 */
object AgentSignature {

    fun of(ctx: Context): String {
        val base = try {
            WebSettings.getDefaultUserAgent(ctx)
                .replace("; wv", "")
                .replace(" Version/4.0", "")
        } catch (_: Throwable) {
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.BRAND} " +
                "${Build.MODEL.replace(" ", "_")}) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/137.0.0.0 Mobile Safari/537.36"
        }
        return "$base appid/${AppCharter.bundleId} appname/${AppCharter.appNameToken}"
    }
}
