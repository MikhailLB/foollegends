package com.example.grayshell

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * ░░ NATIVE / "WHITE" PART — PLACEHOLDER ░░
 *
 * This is the content shown to ORGANIC users (and to store reviewers). The gray
 * flow routes here whenever the backend does NOT return a WebView URL.
 *
 * TODO(you): replace this stub with your REAL native game / content. It must be
 *   a genuine, reviewable experience — this is what passes store review.
 *   - Keep it a normal Activity (or your game engine's host Activity).
 *   - WelcomePortal.goNative() launches this class; rename/repoint as needed.
 *   - Do NOT couple it to the gray-flow classes (startup/portal/reach/...).
 *
 * The stub below just renders a centered label so the template builds and runs.
 */
class NativeContentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fullscreen.apply(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#101014"))
        }
        root.addView(TextView(this).apply {
            text = "NATIVE CONTENT (white part)\n\nReplace NativeContentActivity\nwith your real game."
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        })
        setContentView(root)
    }
}
