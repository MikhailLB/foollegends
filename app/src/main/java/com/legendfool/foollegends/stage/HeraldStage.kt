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
 * Push-permission promo shown before the WebView. Skip postpones it for three days;
 * Accept hands the user the system dialog and retires the promo for good, whichever
 * way that dialog is answered — see [StrongBox.heraldClosed].
 */
class HeraldStage : AppCompatActivity() {

    private lateinit var box: StrongBox
    private var landing: String? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { moveOn() }

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
        // Written before the dialog opens rather than once it answers: the answer does
        // not change what happens to this screen, and the app can be swiped away while
        // the dialog is up — which would leave the promo due again, over a permission
        // the user has already been asked for.
        box.heraldClosed = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            moveOn()
            return
        }
        val already = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (already) moveOn() else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
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
