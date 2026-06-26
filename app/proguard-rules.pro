# Flutter and Android essentials
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# WebView JavaScript bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# AppsFlyer
-keep class com.appsflyer.** { *; }
-keep class com.android.installreferrer.** { *; }
-dontwarn com.appsflyer.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Security Crypto
-keep class androidx.security.crypto.** { *; }

# App classes needed for FCM
-keep class com.example.grayshell.signal.** { *; }

# Strip logs in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
