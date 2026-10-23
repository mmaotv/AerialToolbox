// Aerial 桌面工具箱 app 模块
//
// 关键取舍（沿用 XIAOMI 工具箱的结论）：
//  1) 纯 Java/Kotlin，ADB 客户端零第三方依赖（见 adb/AdbChannel.kt），minSdk 21。
//  2) 不含任何 .so，因此不限制 ABI：32 位老电视与 64 位新电视都能装。
//  3) UI 用原生 View + XML，遥控器方向键天然可获得焦点。
//  4) targetSdk 保持 28：安装包需要先落到公共下载目录，
//     传统存储策略在各版本电视上都成立。

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miaomiaotv.aerial"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.miaomiaotv.aerial"
        minSdk = 21
        targetSdk = 28
        versionCode = 8
        versionName = "1.3.4"
    }

    androidResources {
        // 内置的安装包已经是 zip，再压一遍只会让安装时多花几秒解压
        noCompress += listOf("apk")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 未单独配置签名时使用本机调试密钥，保证产出的包已签名、可直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 共享核心：Aerial / CleanYourTV 的设备操作与 ADB 客户端
    implementation(project(":core"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
