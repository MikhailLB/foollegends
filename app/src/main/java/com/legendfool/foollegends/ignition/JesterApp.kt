package com.legendfool.foollegends.ignition

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.legendfool.foollegends.BuildConfig
import com.legendfool.foollegends.chronicle.Chronicle
import com.legendfool.foollegends.courier.TraceCourier

class JesterApp : Application() {

    lateinit var trace: TraceCourier
        private set

    override fun onCreate() {
        super.onCreate()

        // First thing of all: the attribution answer can arrive before the launcher
        // has drawn a frame, and a diary opened after it has nothing to say about it.
        Chronicle.open(this)

        try {
            FirebaseApp.initializeApp(this)
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                if (BuildConfig.DEBUG) {
                    DebugAppCheckProviderFactory.getInstance()
                } else {
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Firebase unavailable: ${e.message}")
        }

        // Wired up here and nowhere else — the SDK learns the app is in the
        // foreground from the activity callbacks it registers now, and registering
        // them once an activity is already on screen means it never notices. Not a
        // byte is sent until GateKeeper strikes the match, which it does only after
        // it has seen a connection.
        trace = TraceCourier(this)
        trace.prime()
    }

    private companion object {
        const val TAG = "JesterApp"
    }
}
