plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.rainradar.sg"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.rainradar.sg"
        minSdk = 31
        targetSdk = 35
        // CI passes -PbuildNumber=<workflow run number> so every release updates the previous one.
        // (+1 so the first CI build is newer than the original hand-built 1.0.)
        val build = (project.findProperty("buildNumber") as String?)?.toInt()
        versionCode = if (build != null) build + 1 else 1
        versionName = if (build != null) "1.0.$build" else "1.0"
    }

    // Release key comes from the environment (GitHub Actions secrets). Without it, local builds
    // fall back to the debug key. Every release must use the same key, or Android refuses
    // to install it over the previous version.
    val keystore = System.getenv("SIGNING_KEYSTORE_PATH")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
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
    implementation("androidx.work:work-runtime:2.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
