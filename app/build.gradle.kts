plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.skyalt.canopy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.skyalt.canopy"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.2"
    }

    // The upload key comes from the environment in CI (GitHub secrets);
    // without it the release build falls back to the debug key.
    val uploadKeystore = System.getenv("UPLOAD_KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = uploadKeystore
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // The development build skips the free-session limit.
            buildConfigField("boolean", "TRIAL_ENFORCED", "false")
        }
        release {
            buildConfigField("boolean", "TRIAL_ENFORCED", "true")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
            // Ships the native libraries' symbols in the AAB so Play can read native crashes and ANRs.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // Motion capture: the front camera and ML Kit's body pose.
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    // AltiX Pro on Google Play, and the free-session count kept with the Google account.
    implementation("com.android.billingclient:billing-ktx:8.0.0")
    implementation("com.google.android.gms:play-services-auth-blockstore:16.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
