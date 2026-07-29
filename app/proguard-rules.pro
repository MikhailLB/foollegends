# The game itself uses no reflection; the rules below cover the SDKs that do.

# AppsFlyer
-keep class com.appsflyer.** { *; }
-dontwarn com.appsflyer.**
-keep class com.android.installreferrer.** { *; }

# Firebase / Play Services
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# WebView bridges and clients
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void openFileChooser(...);
}
-keep class * extends android.webkit.WebViewClient { *; }

# The page calls these by name, so the name has to survive.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
