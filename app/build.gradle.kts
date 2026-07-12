plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.autotapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.autotapper"
        minSdk = 30          // AccessibilityService.takeScreenshot() needs Android 11
        targetSdk = 34
        // versionCode grows every CI build (from the GitHub run number) so
        // Android accepts each new APK as an in-place UPDATE, not a downgrade.
        versionCode = (System.getenv("VERSION_CODE")?.toIntOrNull()) ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    // Optional fixed signing key. When present, every build is signed with the
    // SAME key + applicationId, so a newer APK updates the app IN PLACE (data
    // kept) instead of forcing an uninstall. The key file is NEVER committed:
    // in CI it is restored from the KEYSTORE_B64 GitHub secret. Without it, the
    // build falls back to the default per-build debug key (updates then need a
    // reinstall). This is a throwaway key for personal sideloading only.
    val keystoreFile = file("autotapper.keystore")
    val hasKeystore = keystoreFile.exists()
    if (hasKeystore) {
        signingConfigs {
            create("shared") {
                storeFile = keystoreFile
                storePassword = "autotapper"
                keyAlias = "autotapper"
                keyPassword = "autotapper"
            }
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("shared")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true   // exposes BuildConfig.VERSION_CODE for the updater
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
