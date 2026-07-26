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
        versionCode = 1
        versionName = "1.0.0"
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

    // 排除 lite 库自带的 libpytorch_jni.so 冲突（如果有的话）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // PyTorch Mobile Lite (TorchScript .pt 模型推理)
    implementation("org.pytorch:pytorch_android_lite:2.1.0")
    implementation("org.pytorch:pytorch_android_torchvision_lite:2.1.0")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
