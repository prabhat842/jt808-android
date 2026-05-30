plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.jt808terminal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.jt808terminal"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }
    aaptOptions {
        noCompress += "task"
        noCompress += "tflite"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    // CameraX — Phase 2 video streaming + Phase 4 DMS/ADAS frame analysis
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    // MediaPipe Tasks Vision — Phase 4 DMS (478 landmarks + blend shapes)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    // TFLite — Intel OMZ open-closed-eye-0001 dedicated eye state classifier
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    // ML Kit Object Detection — Phase 5 ADAS (FCW, LDW, pedestrian) + Phase 6 BSD
    implementation("com.google.mlkit:object-detection:17.0.2")
    // CameraX VideoCapture — Phase 7 local recording
    implementation("androidx.camera:camera-video:1.3.4")
    // Room — local DVR index + GPS offline buffer
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
}
