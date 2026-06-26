package com.legendfool.foollegends.portal

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.legendfool.foollegends.R
import com.legendfool.foollegends.startup.WelcomePortal
import com.legendfool.foollegends.wire.NetWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * No-internet screen. Branded background PNG (portrait / landscape) with a RETRY button.
 * On retry, checks real connectivity and returns to WelcomePortal or StreamPortal.
 */
class OfflinePortal : AppCompatActivity() {

    private lateinit var wire: NetWire
    private val scope = CoroutineScope(Dispatchers.Main)
    private var retryBtn: TextView? = null
    private var returnUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wire = NetWire(applicationContext)
        returnUrl = intent.getStringExtra(EXTRA_RETURN_URL)

        val isLandscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val bgRes = if (isLandscape) R.drawable.gray_nowifi_landscape
                    else R.drawable.gray_nowifi_portrait

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        val bg = ImageView(this).apply {
            setImageResource(bgRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(bg)

        val btn = buildRetryButton()
        retryBtn = btn
        btn.setOnClickListener { tryRetry() }

        val lp = FrameLayout.LayoutParams(dpToPx(200), dpToPx(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dpToPx(48)
        btn.layoutParams = lp
        root.addView(btn)

        setContentView(root)
        hideSystemUi()

        // Auto-retry when connectivity is restored.
        scope.launch {
            wire.connectivityFlow.collect { online ->
                if (online) tryRetry()
            }
        }
    }

    private fun tryRetry() {
        retryBtn?.text = "Connecting..."
        retryBtn?.isEnabled = false
        scope.launch {
            val ok = wire.hasRealInternet()
            if (ok) {
                val next = if (!returnUrl.isNullOrBlank()) {
                    Intent(this@OfflinePortal, StreamPortal::class.java)
                        .putExtra(StreamPortal.EXTRA_STREAM_URL, returnUrl)
                        .setFlags(FLAG_ACTIVITY_CLEAR_TOP)
                } else {
                    Intent(this@OfflinePortal, WelcomePortal::class.java)
                        .setFlags(FLAG_ACTIVITY_CLEAR_TASK or FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(next)
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } else {
                retryBtn?.text = "RETRY"
                retryBtn?.isEnabled = true
            }
        }
    }

    private fun buildRetryButton(): TextView = TextView(this).apply {
        text = "RETRY"
        textSize = 17f
        gravity = Gravity.CENTER
        setTypeface(null, android.graphics.Typeface.BOLD)
        setBackgroundResource(R.drawable.gray_btn_accept)
        setTextColor(Color.parseColor("#1A0A00"))
        setPadding(0, 0, 0, 0)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RETURN_URL = "offline_return_url"
    }
}
