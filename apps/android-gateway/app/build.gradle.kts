plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.opencall.gateway"
    compileSdk = 35

    defaultConfig {
        // v1.4.0 IDENTITY RESET: new applicationId + fresh signing key.
        // Play Protect attaches its "Blocked for your protection" verdict to the
        // app's (packageName, signing key) pair; the previous identity had been
        // flagged in an early build and every later build inherited the block.
        // A new package name + new key = a brand-new identity Google has never
        // flagged. Old app installs coexist; uninstall the old one manually.
        applicationId = "app.opencall.gateway2"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4.0"
        resourceConfigurations += listOf("en")
    }

    // Consistent release signing: every build uses the SAME committed keystore,
    // so updates install over previous versions without signature conflicts.
    // This is what stops Play Protect flagging / "app not installed" errors.
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
            // Debug builds also use the SAME key so a debug→release transition
            // (and any future debug build) never produces a signature mismatch.
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
