// 共享核心模块：设备操作（Aerial / CleanYourTV 安装接管与还原）与极简 ADB 客户端。
//
// 电视端 app 与手机端 phone 都依赖它，避免两份逻辑各改各的。
// 这里只放「与设备对话」的部分，不依赖任何 UI 与 AndroidX。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miaomiaotv.aerial.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        // 库模块没有 applicationId；consumer 的 applicationId 才是最终包名
        // 注意：targetSdk 取 consumer 的值，这里只声明一个下限
        consumerProguardFiles("consumer-rules.pro")
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
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
