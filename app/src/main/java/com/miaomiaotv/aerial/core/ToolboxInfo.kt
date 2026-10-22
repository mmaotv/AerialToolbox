package com.miaomiaotv.aerial.core

import android.content.Context
import android.os.Build

/**
 * 工具箱自己的身份信息：仓库、版本、本版本更新日志。
 *
 * 与 [AerialSpec] 分开，因为这里描述的是「安装工具本身」，不是被安装的桌面。
 * 版本号不写死：一律从 PackageManager 读，改 `build.gradle.kts` 就够，
 * 免得界面上的版本和实际安装包对不上。
 */
object ToolboxInfo {

    const val REPO_OWNER = "mmaotv"
    const val REPO_NAME = "AerialToolbox"

    /**
     * 本仓库同时还有代构建的 CleanYourTV 发行版（`cyt-v*`），
     * 所以 `releases/latest` 未必是工具箱自己的那一个 —— 找自己必须扫列表、只认带安装包的那条。
     */
    const val API_LIST = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=30"

    const val RELEASE_PAGE = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    /** Release 里安装包的名字前缀：AerialToolbox-1.3.0.apk */
    const val APK_PREFIX = "AerialToolbox-"

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "未知" }

    fun versionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }.getOrNull() ?: 0L

    /** 展示用：v1.3.0（4） */
    fun display(context: Context): String = "v${versionName(context)}（${versionCode(context)}）"

    /**
     * 当前版本的更新日志。
     *
     * 发版时在这里加一段，格式与 GitHub Release 正文一致（markdown），
     * 会经 [Changelog.pretty] 清洗后显示在「关于」页。
     */
    val CHANGELOG: String = """
        ## v1.3.1 · 2026-10-22

        - 新增第二个上游：CleanYourTV（com.gathin.tvhome），主界面顶栏可切换 Aerial / CleanYourTV
        - CleanYourTV 支持 Android 5.1 以上：上游原版 minSdk 是 26，我们用补丁降到 22 后自行构建
        - 装 CleanYourTV 会先校验新桌面能否启动，再停用原桌面；主页切不过去会自动回滚
        - 新增遥控器「输入源键」「Home 键兼容」两个开关（无障碍服务，默认关）
        - 还原会重新启用原桌面并把主页还回去；可一并卸载 CleanYourTV
        - 内置 CleanYourTV v2.6.0 离线包（约 660 KB），装它不需要联网
        - 桌面更新页按目标分流：Aerial 查上游，CleanYourTV 查本仓库代构建的 cyt-v* 发行版

        ## v1.3.0 · 2026-10-22

        - 新增「关于 / 更新工具箱」页：本工具的版本信息、本版本更新日志，以及检查 GitHub 最新 Release
        - 工具箱可以自己升级自己：查版本 → 看更新日志 → 下载 → ADB 安装，ADB 不可用时交给系统安装器
        - 主界面宫格补满 9 格（三行三列），「关于」放在「还原 / 卸载」右边
        - 桌面安装、修复、验收、还原的行为没有变化

        ## v1.2.0 · 2026-10-22

        - 配色、字号、Inter 字体、图标与 TV banner 全部对齐 Aerial 桌面
        - 内置离线包升到 Aerial v1.30（2026-10-22）
        - 卡片改为三列，说明文案压到单行
    """.trimIndent()
}
