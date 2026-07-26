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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // PyTorch Android 完整版（支持标准 TorchScript 格式，含 bytecode）
    implementation("org.pytorch:pytorch_android:2.1.0")
    implementation("org.pytorch:pytorch_android_torchvision:2.1.0")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
