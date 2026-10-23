package com.miaomiaotv.aerial.core

import android.content.Context

/**
 * 工具箱支持的目标桌面。
 *
 * 两个上游的发行方式完全不同，所以这里只放「元信息」，具体流程各自在
 * [AerialRunner] / [CleanTvRunner] 里：
 *  - Aerial：官方 Release 直接给两个 APK，版本源是上游 GitHub；
 *  - CleanYourTV：上游只发源码，APK 由我们 CI 代构建后挂在本仓库的 `cyt-v*` Release。
 */
enum class Target(
    val key: String,
    val label: String,
    val pkg: String,
    /** 我们这份安装包支持的最低 API */
    val minSdk: Int,
    /** 内置离线包所在的 assets 目录 */
    val bundledDir: String,
    /** 内置包缺失时给用户的说明 */
    val installHint: String
) {
    AERIAL(
        key = "aerial",
        label = "Aerial",
        pkg = AerialSpec.PKG,
        minSdk = 21,
        bundledDir = "bundled",
        installHint = "内置 v1.30，离线直装"
    ),
    CLEAN_TV(
        key = "clean_tv",
        label = "CleanYourTV",
        pkg = CleanTvSpec.PKG,
        minSdk = CleanTvSpec.MIN_SDK,
        bundledDir = "bundled-cyt",
        installHint = "内置 v2.6，离线直装（支持 Android 5.1+）"
    );

    val isAerial: Boolean get() = this == AERIAL

    /** 更新检查走的 GitHub API 地址 */
    val updateApi: String
        get() = when (this) {
            AERIAL -> AerialSpec.API_LATEST
            // 上游不发二进制，只能查本仓库代构建的 release；
            // 清单接口而非 latest——本仓库同时有工具箱自己的发行版
            CLEAN_TV -> "https://api.github.com/repos/${CleanTvSpec.REPO_SLUG}/releases?per_page=30"
        }

    /** 更新页展示的 release 页面 */
    /** 发行版 tag 前缀：工具箱自己的是 v1.3.x，CleanYourTV 用 cyt-v* */
    val tagPrefix: String?
        get() = when (this) {
            AERIAL -> null
            CLEAN_TV -> "cyt-v"
        }

    val releasePage: String
        get() = when (this) {
            AERIAL -> AerialSpec.RELEASE_PAGE
            CLEAN_TV -> "https://github.com/${CleanTvSpec.REPO_SLUG}/releases"
        }

    companion object {
        fun of(key: String?): Target = values().firstOrNull { it.key == key } ?: AERIAL

        /** 当前选中的目标，存在 SharedPreferences 里，下次打开还是它 */
        fun current(context: Context): Target =
            of(context.getSharedPreferences("aerial_toolbox", Context.MODE_PRIVATE)
                .getString("target", AERIAL.key))

        fun select(context: Context, target: Target) {
            context.getSharedPreferences("aerial_toolbox", Context.MODE_PRIVATE)
                .edit().putString("target", target.key).apply()
        }
    }
}
