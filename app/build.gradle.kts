import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
if (keystorePropsFile.exists()) keystoreProps.load(keystorePropsFile.inputStream())

android {
    namespace = "com.example.grayshell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.grayshell" // TODO(you): your real bundle id
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release signing is configured only when keystore/keystore.properties exists.
    // Copy keystore/keystore.properties.example → keystore.properties and point it
    // at your .jks (both are gitignored). Without it, release builds stay unsigned.
    val hasKeystore = keystorePropsFile.exists()
    signingConfigs {
        if (hasKeystore) create("release") {
            storeFile     = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias      = keystoreProps["keyAlias"] as String
            keyPassword   = keystoreProps["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // No applicationIdSuffix — keep the production bundleId so AppsFlyer
            // attribution and the OneLink can recognize the debug install.
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    bundle {
        language { enableSplit = true }
        density  { enableSplit = true }
        abi      { enableSplit = true }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Secure storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Firebase (keys provided later — google-services.json required)
    implementation(platform("com.google.firebase:firebase-bom:33.8.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")

    // AppsFlyer (dev key provided later)
    implementation("com.appsflyer:af-android-sdk:6.16.2")
    implementation("com.android.installreferrer:installreferrer:2.2")
}
