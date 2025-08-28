// module-level build.gradle.kts

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.android") version "1.9.10"
}

android {
    namespace = "com.example.spiketimer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.spiketimer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ✅ Chạy 64-bit để GPU delegate hoạt động
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // ✅ Không nén .tflite để load nhanh và tránh lỗi trên một số ROM
    androidResources {
        noCompress += listOf("tflite")
    }

    packaging {
        jniLibs {
            // ✅ Bật legacy packaging để chắc ăn khi load *.so từ AAR (một số thiết bị/ROM kén)
            useLegacyPackaging = true
        }
    }
}

// Cấu hình JVM target cho Kotlin
tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Định nghĩa biến phiên bản cho CameraX
    val camerax_version = "1.3.4"

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // CameraX: Sử dụng biến để đảm bảo các thư viện đồng bộ phiên bản
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")

    // ✅ TensorFlow Lite runtime + GPU + (tuỳ chọn) ops mở rộng
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.16.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
