package com.example.grayshell.startup

import android.app.Application
import com.example.grayshell.BuildConfig
import com.example.grayshell.core.Trace
import com.example.grayshell.reach.TrackingDispatch
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Application entry. Two responsibilities: initialise Firebase (best-effort)
 * and prime the AppsFlyer SDK before any activity runs.
 *
 * See `.cursor/rules/kotlin_launch_flow.mdc` for why the split between prime
 * and start belongs where it does — moving it out of the Application is one
 * of the three ways to lose attribution (#19 in the pitfalls).
 */
class AppEntry : Application() {

    lateinit var trackingDispatch: TrackingDispatch
        private set

    override fun onCreate() {
        super.onCreate()

        try {
            FirebaseApp.initializeApp(this)
            val fac = if (BuildConfig.DEBUG)
                DebugAppCheckProviderFactory.getInstance()
            else
                PlayIntegrityAppCheckProviderFactory.getInstance()
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(fac)
        } catch (e: Exception) {
            Trace.w(TAG, "Firebase not configured — gray flow will still try the config POST", e)
        }

        trackingDispatch = TrackingDispatch(this)
        trackingDispatch.prime()
    }

    private companion object { const val TAG = "AppEntry" }
}
