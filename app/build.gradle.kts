plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.localshare.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.localshare.app"
        minSdk = 26              // Android 8.0+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        aidl = true          // Shizuku UserService 需要 AIDL
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 关闭 lint 阻断，便于本地直接 assembleDebug
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")

    // Shizuku：用 shell 权限给自己授予 READ_CLIPBOARD，从而完全不依赖无障碍服务
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
}
