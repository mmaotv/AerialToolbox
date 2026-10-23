package com.miaomiaotv.aerial.core

/**
 * Aerial 桌面的全部固定标识与指令模板。
 *
 * 这里的每条指令都对应上游安装说明（aerial-tv / site/agent.md）里的 Windows 逐条步骤，
 * 只是把「电脑上的 adb shell」换成了本工具连接本机 ADB 后执行。
 * 改动任何一条前请先对照上游文档，否则会装得上但接管不了桌面。
 */
object AerialSpec {

    // ===================== 应用标识 =====================

    /** 桌面包名 */
    const val PKG = "app.orchard.tv"

    /** 桌面主界面组件 */
    const val MAIN_COMPONENT = "app.orchard.tv/.MainActivity"

    /** 系统屏保（Dream）组件，TCL 等使用系统屏保的电视需要写入 secure 设置 */
    const val DREAM_COMPONENT = "app.orchard.tv/app.orchard.tv.dream.AerialDream"

    /** 桌面守护（无障碍服务）组件 */
    const val GUARD_SERVICE = "app.orchard.tv/app.orchard.tv.guard.HomeGuardService"

    /** 被杀后复活用的通知监听组件（不读取任何通知） */
    const val REVIVAL_LISTENER = "app.orchard.tv/app.orchard.tv.guard.RevivalListener"

    /** 接收配置广播的组件（保持 ADB 常开、屏保归属、清除家长密码等） */
    const val CONFIG_RECEIVER = "app.orchard.tv/.guard.ConfigReceiver"
    const val CONFIG_ACTION = "app.orchard.tv.CONFIG"

    /** 安装包的临时落地位置：shell 自己的目录，安装进程可读 */
    const val STAGING_APK = "/data/local/tmp/aerial-install.apk"

    // ===================== GitHub Release =====================

    const val REPO_OWNER = "pengchujin"
    const val REPO_NAME = "aerial-tv"
    const val API_LATEST = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val RELEASE_PAGE = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    const val ASSET_STANDARD = "aerial-standard.apk"
    const val ASSET_GUARD = "aerial-guard.apk"

    /** 官网镜像：GitHub 打不开（国内常见）时用于下载与兜底 */
    const val MIRROR_BASE = "https://aerial.qust.me/download/"

    // ===================== 指令构造 =====================

    /** 授权类指令：全部尽力而为，个别报错（比如非 TCL 的 APP_AUTO_START）属正常 */
    fun grantCommands(): List<GrantCommand> = listOf(
        GrantCommand("pm grant $PKG android.permission.ACCESS_FINE_LOCATION", essential = false),
        GrantCommand("pm grant $PKG android.permission.WRITE_SECURE_SETTINGS", essential = true),
        GrantCommand("appops set $PKG SYSTEM_ALERT_WINDOW allow", essential = false),
        GrantCommand("appops set $PKG WRITE_SETTINGS allow", essential = false),
        GrantCommand("cmd notification allow_listener $REVIVAL_LISTENER", essential = true),
        // 下面两条只有 TCL 认，别的电视报错是正常的
        GrantCommand("appops set $PKG APP_AUTO_START allow", essential = false),
        GrantCommand("appops set $PKG APP_ASSOC_START allow", essential = false)
    )

    /** 设为默认桌面；失败时退回旧的 set-home-activity 写法 */
    fun homeRoleCommands(): List<String> = listOf(
        "cmd role add-role-holder --user 0 android.app.role.HOME $PKG",
        "cmd package set-home-activity --user 0 $MAIN_COMPONENT"
    )

    fun configBroadcast(vararg extras: String): String =
        "am broadcast -a $CONFIG_ACTION -n $CONFIG_RECEIVER ${extras.joinToString(" ")}"

    fun launchCommand(): String = "am start -W -n $MAIN_COMPONENT"

    /** 让 Aerial 在电视上更流畅；慢的机器要几十秒 */
    fun compileCommand(): String = "cmd package compile -m speed -f $PKG"

    /** 一次性统计：已安装的 Aerial 版本 */
    fun versionQuery(): String = "dumpsys package $PKG"

    data class GrantCommand(val command: String, val essential: Boolean)
}
