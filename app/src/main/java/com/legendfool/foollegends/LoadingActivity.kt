package com.legendfool.foollegends

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Entry point. Shows the loading screen (portrait or landscape) and then opens the
 * portrait-only game. Loads only once even if the device is rotated mid-splash.
 */
class LoadingActivity : AppCompatActivity() {

    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(LoadingView(this) { goToGame() })
        Fullscreen.apply(this)
    }

    private fun goToGame() {
        if (navigated) return
        navigated = true
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
