package com.legendfool.foollegends.startup

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.legendfool.foollegends.reach.TrackingDispatch
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

        // AppsFlyer — dev key encoded in AppBlueprint (provided separately).
        trackingDispatch = TrackingDispatch(this)
        trackingDispatch.init()
    }

    private fun isDebugBuild(): Boolean {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            (info.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Exception) { false }
    }
}
