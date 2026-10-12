plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sonalika.chassisscan"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sonalika.chassisscan"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.3"

        // Cloud reader (n8n webhook). Filled from GitHub secrets N8N_URL / N8N_KEY at build time.
        // Left empty, the app reads stamped numbers on the phone only.
        val n8nUrl = System.getenv("N8N_URL") ?: ""
        val n8nKey = System.getenv("N8N_KEY") ?: ""
        buildConfigField("String", "N8N_URL", "\"${n8nUrl.trim()}\"")
        buildConfigField("String", "N8N_KEY", "\"${n8nKey.trim()}\"")
    }

    buildTypes {
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
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // On-device ML Kit: works offline, models bundled in the APK
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
