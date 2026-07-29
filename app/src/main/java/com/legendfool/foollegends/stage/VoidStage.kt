package com.legendfool.foollegends.stage

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.legendfool.foollegends.R
import com.legendfool.foollegends.ignition.GateKeeper
import com.legendfool.foollegends.pulse.PulseMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** No-internet screen with a manual retry and an automatic one once the link returns. */
class VoidStage : AppCompatActivity() {

    private lateinit var pulse: PulseMeter
    private lateinit var retry: TextView
    private val scope = CoroutineScope(Dispatchers.Main)
    private var resumeUrl: String? = null
    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pulse = PulseMeter(applicationContext)
        resumeUrl = intent.getStringExtra(EXTRA_RESUME_URL)

        setContentView(R.layout.stage_void)
        StageChrome.installFade(this)
        StageChrome.immersive(this)
        StageChrome.allowCutout(this)
        StageChrome.keepClearOfBars(findViewById<View>(R.id.content))

        retry = findViewById(R.id.btnRetry)
        retry.setOnClickListener { attempt() }

        scope.launch {
            pulse.pulses.collect { up -> if (up) attempt() }
        }
    }

    private fun attempt() {
        if (checking) return
        checking = true
        retry.text = getString(R.string.void_checking)
        retry.isEnabled = false
        scope.launch {
            if (pulse.trafficFlows()) {
                leave()
            } else {
                checking = false
                retry.text = getString(R.string.void_retry)
                retry.isEnabled = true
            }
        }
    }

    private fun leave() {
        val next = if (!resumeUrl.isNullOrBlank()) {
            Intent(this, CanvasStage::class.java)
                .putExtra(CanvasStage.EXTRA_LANDING, resumeUrl)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            Intent(this, GateKeeper::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(next)
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESUME_URL = "fl_resume_url"
    }
}
