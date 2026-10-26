plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.literec"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.literec"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-signed so the release APK installs directly. Replace with your own keystore to publish.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources.excludes += setOf("/META-INF/*.version", "/DebugProbesKt.bin", "/META-INF/*.kotlin_module")
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Intentionally no dependencies: platform widgets only (no AndroidX, no Material, no Compose).
