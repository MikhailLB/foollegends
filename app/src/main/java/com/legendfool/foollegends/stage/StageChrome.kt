package com.legendfool.foollegends.stage

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.legendfool.foollegends.R

/** Window handling shared by the three gray screens. */
object StageChrome {

    fun immersive(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Cross-fades instead of the default slide, so switching gray screens never
     * shows a bare window behind them. Call from onCreate.
     */
    fun installFade(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        activity.overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_OPEN,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
        activity.overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
    }

    /** Pre-34 counterpart of [installFade]; call right after startActivity. */
    @Suppress("DEPRECATION")
    fun fadeOutgoing(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /** For an activity that finishes before it is ever drawn. */
    @Suppress("DEPRECATION")
    fun noTransition(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            activity.overridePendingTransition(0, 0)
        }
    }

    fun allowCutout(activity: Activity) {
        activity.window.attributes = activity.window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * Padding for the WebView: the physical cutout, and nothing else.
     *
     * System bars are excluded because they come and go — the navigation bar returns
     * whenever the keyboard opens — and reacting to that would shove the page sideways
     * every time. They float above the content instead.
     *
     * The keyboard is excluded too, and deliberately: taking height away from a WebView
     * makes the page reflow and re-scroll under it, which is the flick this app kept
     * being blamed for. [KeyboardPan] slides the view out of the keyboard's way instead,
     * leaving its size alone.
     */
    fun keepClearOfCutout(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val landscape = target.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            if (landscape) {
                target.setPadding(cutout.left, 0, cutout.right, 0)
            } else {
                target.setPadding(0, cutout.top, 0, 0)
            }
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /** Full inset padding for the static gray screens, where nothing animates. */
    fun keepClearOfBars(view: View) {
        val mask = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val safe = insets.getInsets(mask)
            val base = target.getTag(TAG_BASE_PADDING) as? IntArray ?: intArrayOf(
                target.paddingLeft, target.paddingTop, target.paddingRight, target.paddingBottom
            ).also { target.setTag(TAG_BASE_PADDING, it) }
            target.setPadding(
                base[0] + safe.left,
                base[1] + safe.top,
                base[2] + safe.right,
                base[3] + safe.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private val TAG_BASE_PADDING = R.id.tag_base_padding
}
