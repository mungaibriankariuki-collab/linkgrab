plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linkgrab.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.linkgrab.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // The bundled Python/ffmpeg are large; ship only modern 64-bit ARM phones.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Fixed key (from CI secrets) so new builds install over old ones without uninstalling.
    // Without the secrets, release builds fall back to the debug key.
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val keystoreFile = file("linkgrab.keystore")
    signingConfigs {
        if (keystorePassword != null && keystoreFile.exists()) {
            create("fixed") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "linkgrab"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("fixed") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // youtubedl-android needs its native libraries extracted to disk.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.2")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
