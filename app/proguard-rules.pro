# ══ Attribute preservation ═════════════════════════════════════════════════
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ══ WebView JS bridge ══════════════════════════════════════════════════════
# The @JavascriptInterface methods are called by name from JS; R8 must not
# rename them or strip them.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ══ Firebase ═══════════════════════════════════════════════════════════════
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# ══ AppsFlyer ══════════════════════════════════════════════════════════════
-keep class com.appsflyer.** { *; }
-keep class com.android.installreferrer.** { *; }
-dontwarn com.appsflyer.**

# ══ OkHttp ═════════════════════════════════════════════════════════════════
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keep class okhttp3.** { *; }

# ══ Security-crypto ═══════════════════════════════════════════════════════
-keep class androidx.security.crypto.** { *; }

# ══ FCM entry class ═══════════════════════════════════════════════════════
# rebrand.py updates the package path here when it renames the gray flow.
-keep class com.example.grayshell.signal.PushRelay
-keep class com.example.grayshell.startup.WelcomePortal
-keep class com.example.grayshell.startup.AppEntry
-keep class com.example.grayshell.NativeContentActivity

# ══ Strip debug-level logs in release ══════════════════════════════════════
# We keep Log.w and Log.e for post-mortem on user devices — Trace itself is
# already stripped by the BuildConfig.DEBUG guard, so what remains here is
# the last-ditch diagnostics from platform code paths.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
