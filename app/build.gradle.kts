plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.digitalaidit.mychateasy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.digitalaidit.mychateasy"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.2.3"
    }

    // A fixed debug key, so each new APK installs over the previous one without uninstalling
    signingConfigs {
        getByName("debug") {
            storeFile = file("mychat-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            // Shared with users, so behave like a release build: not debuggable
            // (phone security checks flag debuggable apps, and it would let a USB cable read the app's saved keys)
            isDebuggable = false
            isJniDebuggable = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Google sign-in through Android's Credential Manager
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    // credentials-play-services-auth needs these at runtime but doesn't bring them itself
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.core:core:1.13.1")
}
