// Aerial 桌面工具箱：运行在电视/盒子本机，通过本机 ADB 安装并接管 Aerial 桌面
//
// 仓库顺序分两种：
//  - 国内开发机（默认）：阿里云镜像在前，官方仓库兜底，装依赖快
//  - CI（环境变量 CI 有值，即 GitHub Actions）：只用官方仓库。
//    镜像对境外 IP 常返回 502，而 Gradle 一旦某个仓库报错就会把整个仓库禁用，
//    不会自动往后回落，所以 CI 里必须把镜像摘掉。
// pluginManagement 是独立脚本，读不到外层变量，所以两条都各自算一次。
pluginManagement {
    val useMirror = System.getenv("CI").isNullOrBlank()
    repositories {
        if (useMirror) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val useMirror = System.getenv("CI").isNullOrBlank()
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useMirror) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "AerialToolbox"
include(":app")
