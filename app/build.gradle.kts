plugins {
    id("com.android.application")
}

android {
    namespace = "com.yuukatts"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.yuukatts"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        // 排除冲突的 lib/arch 重复 so
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

dependencies {
    // Lite 版 — 不编译 NNPack，加载 .ptl 模型
    implementation("org.pytorch:pytorch_android_lite:2.1.0")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
