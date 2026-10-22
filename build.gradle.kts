// 根构建脚本：仅声明插件版本（与 XIAOMI / 手机端保持一致，复用本地缓存）
plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
