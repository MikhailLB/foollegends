package com.example.grayshell.startup

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.example.grayshell.reach.TrackingDispatch
import kotlinx.coroutines.MainScope

class AppEntry : Application() {

    lateinit var trackingDispatch: TrackingDispatch
        private set

    override fun onCreate() {
        super.onCreate()

        // Firebase — requires google-services.json (provided separately).
        try {
            FirebaseApp.initializeApp(this)
            val fac = if (isDebugBuild())
                DebugAppCheckProviderFactory.getInstance()
            else
                PlayIntegrityAppCheckProviderFactory.getInstance()
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(fac)
        } catch (_: Exception) {
            // Firebase not configured yet — gray flow will still attempt config fetch.
        }

        // AppsFlyer — wired up here and nowhere else, because the SDK learns the
        // app is in the foreground from the activity callbacks it registers now,
        // and registering them once an activity is already on screen means it
        // never notices. Not a byte is sent until WelcomePortal calls ignite(),
        // which it does only after seeing a connection. See
        // .cursor/rules/kotlin_launch_flow.mdc — this split is not optional.
        trackingDispatch = TrackingDispatch(this)
        trackingDispatch.prime()
    }

    private fun isDebugBuild(): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            (info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) { false }
    }
}
