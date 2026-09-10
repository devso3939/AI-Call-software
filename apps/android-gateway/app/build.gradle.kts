plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.opencall.gateway"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.opencall.gateway2"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.5.7"
        resourceConfigurations += listOf("en")
    }

    // v1.5.0 — TWO FLAVORS:
    //
    //  "full"  → opencall-gateway.apk   (package app.opencall.gateway2)
    //    Complete SIM gateway: SMS relay, dial/answer, audio bridge.
    //    Declares the SMS + PHONE permission groups, so Google's
    //    financial-fraud prevention may show its pre-install warning
    //    until the developer identity is registered. Register the app
    //    (limited-distribution account) and this installs clean forever.
    //
    //  "lite"  → opencall-bridge.apk    (package app.opencall.bridge)
    //    Internet-calling companion: mic + notifications only — ZERO
    //    sensitive permissions, so Play Protect's fraud scan has nothing
    //    to flag and it installs like a game. No SMS / no call control.
    //
    // Both flavors share ONE signing key (updates always install cleanly).
    flavorDimensions += "mode"
    productFlavors {
        create("full") {
            dimension = "mode"
            applicationId = "app.opencall.gateway2"
            versionNameSuffix = "-full"
        }
        create("lite") {
            dimension = "mode"
            applicationId = "app.opencall.bridge"
            versionNameSuffix = "-lite"
        }
    }

    // Consistent release signing: every build uses the SAME committed keystore,
    // so updates install over previous versions without signature conflicts.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/opencall-gateway-release.keystore")
            storePassword = "OpenCallGw2026"
            keyAlias = "opencall-gateway"
            keyPassword = "OpenCallGw2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // Sign with every scheme modern Android checks (v1 for older OEM installers,
    // v2 since Android 7, v3 for key rotation) — maximum Play Protect goodwill.
    signingConfigs {
        getByName("release") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildFeatures { buildConfig = true }

    // LITE flavor compiles only the shared files (Rpc, DeviceStore, WebRtcBridge,
    // MainActivity/BridgeActivity, BridgeService). SIM-gateway-only classes are
    // kept in src/full/java so the lite APK physically contains no SMS/call code.
    sourceSets {
        getByName("full") {
            java.srcDirs("src/full/java", "src/main/java")
        }
        getByName("lite") {
            java.srcDirs("src/lite/java", "src/main/java")
            // main/java holds shared files only — see layout below.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // prebuilt Google libwebrtc (org.webrtc.* namespace), maintained artifact
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
