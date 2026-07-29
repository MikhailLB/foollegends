package com.legendfool.foollegends.stage

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.legendfool.foollegends.R
import com.legendfool.foollegends.strongbox.StrongBox

/**
 * Push-permission promo shown once before the WebView. Accept opens the system
 * dialog; Skip postpones the screen for three days. A system-level refusal is
 * recorded permanently, because after it the OS dialog never opens again.
 */
class HeraldStage : AppCompatActivity() {

    private lateinit var box: StrongBox
    private var landing: String? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            box.heraldGranted = true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            box.heraldBlockedByOs = true
        } else {
            box.postponeHerald()
        }
        moveOn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        box = StrongBox(applicationContext)
        landing = intent.getStringExtra(EXTRA_LANDING)

        setContentView(R.layout.stage_herald)
        StageChrome.installFade(this)
        StageChrome.immersive(this)
        StageChrome.allowCutout(this)
        StageChrome.keepClearOfBars(findViewById<View>(R.id.content))

        findViewById<View>(R.id.btnPrimary).setOnClickListener { requestPush() }
        findViewById<View>(R.id.btnSecondary).setOnClickListener {
            box.postponeHerald()
            moveOn()
        }
    }

    private fun requestPush() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            box.heraldGranted = true
            moveOn()
            return
        }
        val already = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (already) {
            box.heraldGranted = true
            moveOn()
        } else {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun moveOn() {
        startActivity(
            // The WebView keeps this screen on top of itself until the page is ready,
            // so the user never drops back to a loading screen after answering.
            Intent(this, CanvasStage::class.java).apply {
                landing?.let { putExtra(CanvasStage.EXTRA_LANDING, it) }
                putExtra(CanvasStage.EXTRA_FROM_HERALD, true)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        )
        StageChrome.fadeOutgoing(this)
        finish()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        StageChrome.immersive(this)
        ViewCompat.requestApplyInsets(findViewById(R.id.content))
    }

    override fun onResume() {
        super.onResume()
        StageChrome.immersive(this)
    }

    companion object {
        const val EXTRA_LANDING = "fl_herald_landing"
    }
}
