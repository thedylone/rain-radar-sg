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
        // CI passes -PversionCode=<run number> so every release can update the previous one.
        val ci = (project.findProperty("versionCode") as String?)?.toInt()
        versionCode = ci ?: 1
        versionName = if (ci != null) "1.0.$ci" else "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key so the APK installs directly without extra setup.
            signingConfig = signingConfigs.getByName("debug")
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
