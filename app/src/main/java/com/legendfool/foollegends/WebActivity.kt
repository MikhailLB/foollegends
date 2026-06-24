package com.legendfool.foollegends

import android.os.Bundle
import android.view.KeyEvent
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/** Simple in-app browser used to show the Privacy Policy and Support pages. */
class WebActivity : AppCompatActivity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            webViewClient = WebViewClient()
        }
        setContentView(web)

        val url = intent.getStringExtra(EXTRA_URL) ?: "https://foollegends.com"
        if (savedInstanceState == null) web.loadUrl(url)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBack(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { onBack(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun onBack() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val PRIVACY_URL = "https://foollegends.com/privacy-policy.html"
        const val SUPPORT_URL = "https://foollegends.com/support.html"
    }
}
