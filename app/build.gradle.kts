plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release key never lives in this repository: the build on GitHub gets it from the Actions secrets
// (SIGNING_KEY_FILE, SIGNING_KEY_PASSWORD, SIGNING_KEY_ALIAS). Without them the build is simply unsigned.
val keyFile: String? = System.getenv("SIGNING_KEY_FILE")

android {
    namespace = "ru.i91.pushishi"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.i91.pushishi"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = System.getenv("GITHUB_REF_NAME")?.takeIf { it.startsWith("v") }?.removePrefix("v") ?: "dev"
    }

    signingConfigs {
        if (keyFile != null) create("release") {
            storeFile = file(keyFile)
            storeType = "pkcs12"
            storePassword = System.getenv("SIGNING_KEY_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keyFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // «back» asks the game page first and calls the system's back only when the page has nothing to close
    lint { disable += "MissingSuperCall" }

    // no Google libraries, no trackers: only Android itself and the Kotlin standard library
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
