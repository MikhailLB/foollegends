package com.legendfool.foollegends.portal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.legendfool.foollegends.R
import com.legendfool.foollegends.vault.DataVault

/**
 * Push-notification permission screen. Shown before the WebView if the player
 * hasn't granted or permanently declined notifications.
 *
 * Buttons: ACCEPT  /  SKIP
 * Assets: portrait/landscape branded PNG backgrounds.
 */
class AlertPortal : AppCompatActivity() {

    private lateinit var vault: DataVault
    private var pendingUrl: String? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vault.notifGranted = true
        } else {
            val denied = !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (denied) vault.notifOsDenied = true
            else vault.skipNotifFor3Days()
        }
        proceed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = DataVault(applicationContext)
        pendingUrl = intent.getStringExtra(EXTRA_TARGET_URL)

        val isLandscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val bgRes = if (isLandscape) R.drawable.gray_notif_landscape
                    else R.drawable.gray_notif_portrait

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        // Background image.
        val bg = ImageView(this).apply {
            setImageResource(bgRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(bg)

        // Button row pinned to bottom.
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
            lp.bottomMargin = dpToPx(40)
            layoutParams = lp
        }

        val acceptBtn = buildButton("ACCEPT", accent = true)
        val skipBtn   = buildButton("SKIP",   accent = false)

        acceptBtn.setOnClickListener { onAccept() }
        skipBtn.setOnClickListener   { onSkip()   }

        btnRow.addView(acceptBtn)
        btnRow.addView(space(16))
        btnRow.addView(skipBtn)
        root.addView(btnRow)

        setContentView(root)
        hideSystemUi()
    }

    private fun onAccept() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
                vault.notifGranted = true
                proceed()
            } else {
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            vault.notifGranted = true
            proceed()
        }
    }

    private fun onSkip() {
        vault.skipNotifFor3Days()
        proceed()
    }

    private fun proceed() {
        val next = Intent(this, StreamPortal::class.java).apply {
            pendingUrl?.let { putExtra(StreamPortal.EXTRA_STREAM_URL, it) }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(next)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun buildButton(label: String, accent: Boolean): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = if (accent) 17f else 16f
        tv.gravity = Gravity.CENTER
        tv.setPadding(dpToPx(28), dpToPx(14), dpToPx(28), dpToPx(14))
        tv.setTypeface(null, android.graphics.Typeface.BOLD)
        tv.letterSpacing = 0.06f
        if (accent) {
            tv.setBackgroundResource(R.drawable.gray_btn_accept)
            tv.setTextColor(Color.parseColor("#1A0A00"))
            tv.setShadowLayer(2f, 0f, 1f, Color.parseColor("#55FFFFFF"))
        } else {
            tv.setBackgroundResource(R.drawable.gray_btn_skip)
            tv.setTextColor(Color.parseColor("#FFD700"))
            tv.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        val lp = LinearLayout.LayoutParams(dpToPx(150), dpToPx(54))
        tv.layoutParams = lp
        return tv
    }

    private fun space(dp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dpToPx(dp), 1)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

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

    companion object {
        const val EXTRA_TARGET_URL = "alert_target_url"
    }
}
