package com.legendfool.foollegends

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity

class GameActivity : AppCompatActivity() {

    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gameView = GameView(this)
        gameView.level = intent.getIntExtra("level", 1)
        gameView.onMenuRequested = { finish() }
        setContentView(gameView)
        Fullscreen.apply(this)
    }

    override fun onResume() {
        super.onResume()
        Fullscreen.apply(this)
        gameView.startRound()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }
}
