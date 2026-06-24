package com.legendfool.foollegends

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(MenuView(this))
        Fullscreen.apply(this)
    }

    override fun onResume() {
        super.onResume()
        Fullscreen.apply(this)
    }
}
