// 手机端：在同一台手机上远程连接电视/盒子（网络 ADB），把 Aerial / CleanYourTV 装上去并接管桌面。
//
// 与电视端 app 共用 :core（设备操作与 ADB 客户端）。差异只在「连接方式」：
// 电视端连本机 127.0.0.1:5555；手机端连用户输入的电视 IP:5555，且要把安装包推送过去。
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miaomiaotv.aerial.phone"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.miaomiaotv.aerial.phone"
        minSdk = 21
        targetSdk = 28
        versionCode = 3
        versionName = "1.0.2"
    }

    androidResources {
        // 内置的安装包已经是 zip，再压一遍只会让安装时多花几秒解压
        noCompress += listOf("apk")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(project(":core"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
