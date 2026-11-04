plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wasla.media"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.wasla.media"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
