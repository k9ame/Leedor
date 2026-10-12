plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.leedor.tts"
    compileSdk = 35
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.leedor.tts"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.4.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }
    
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true // Also generate a universal APK
        }
    }
    
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "nekospeak123"
            keyAlias = "nekospeak"
            keyPassword = "nekospeak123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // 纯 JVM 单元测试里 android.util.Log 等桩方法会抛 "not mocked"，
    // 让它们返回默认值，这样 frontend 包可以脱离设备测试。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // ONNX Runtime Android
    // 1.22.0 是同时把 libonnxruntime.so 与 libonnxruntime4j_jni.so 都做成
    // 16 KB 页对齐的最早版本（1.20.0 只对齐了前者）。低于此版本会在
    // Android 15+ 的 16 KB 页设备上无法加载，Play 也会拒收。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    
    // Jetpack Compose - BOM
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Material Design
    implementation("com.google.android.material:material:1.11.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")
    
    // JTransforms FFT library for GTCRN denoising STFT/iSTFT
    implementation("com.github.wendykierp:JTransforms:3.2")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
