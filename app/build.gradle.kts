plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.srisomesh.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.srisomesh.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Personal app: one fixed key so every new APK installs over the old one.
    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "somesh-assistant"
            keyAlias = "assistant"
            keyPassword = "somesh-assistant"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Offline wake word / speech (Vosk) — pulls its native libs + JNA transitively
    implementation("com.alphacephei:vosk-android:0.3.47")
    // On-phone AI (Gemma via MediaPipe)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
}
