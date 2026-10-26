package com.miaomiaotv.aerial.core

/**
 * CleanYourTV 桌面的固定标识与指令模板。
 *
 * 对齐上游 `launcher/build-and-install.sh`（v3.0.0）。与 Aerial 的区别要说清楚：
 *  - 上游**不发布 APK**，安装包由我们自己的 CI 代构建后挂在 AerialToolbox 仓库的 `cyt-v*` Release；
 *  - 上游 minSdk 是 26，我们用 `upstream/cleanyourtv/patch.py` 打到 22（Android 5.1）后自行构建；
 *  - 安装流程会 `pm disable-user` 原桌面，顺序错一步电视就没有桌面，必须照上游的顺序来。
 */
object CleanTvSpec {

    // ===================== 应用标识 =====================

    const val PKG = "com.gathin.tvhome"
    const val MAIN_COMPONENT = "com.gathin.tvhome/.MainActivity"

    /** 时钟屏保（Dream） */
    const val DREAM_COMPONENT = "com.gathin.tvhome/.ClockDream"

    /** 遥控器输入源键、Home 键的无障碍服务 */
    const val INPUT_KEY_SERVICE = "com.gathin.tvhome/.InputKeyService"
    const val HOME_KEY_SERVICE = "com.gathin.tvhome/.HomeKeyService"

    /** 安装包落地位置 */
    const val STAGING_APK = "/data/local/tmp/tvhome.apk"

    /** 我们构建的包支持 Android 5.1（上游原版是 26 / Android 8.0） */
    const val MIN_SDK = 22

    /** 上游原版的最低要求，用于向用户解释差异 */
    const val UPSTREAM_MIN_SDK = 26

    // ===================== 默认桌面探测 =====================

    /** Android 7.0+ 的写法 */
    const val HOME_QUERY_MODERN =
        "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

    /** Android 5.x/6.x 的写法：没有 cmd，用 pm 的同名子命令 */
    const val HOME_QUERY_LEGACY =
        "pm resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

    /**
     * 可以被安全禁用的原厂桌面。上游只认这几个：猜错会把「正在用的桌面」禁掉，
     * 电视当场没有主页。返回 `android` 表示系统选择器（多个桌面并列，没有默认）。
     */
    val KNOWN_STOCK_LAUNCHERS = listOf(
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
        "com.mitv.tvhome"
    )

    /** Google TV 的安装向导会抢 Home 并把 system_server 拖进崩溃循环 */
    const val SETUP_WRAITH = "com.google.android.tungsten.setupwraith"

    // ===================== 我们自己的发行渠道 =====================

    const val ASSET_PREFIX = "CleanYourTV-"
    const val REPO_SLUG = "mmaotv/AerialToolbox"

    // ===================== 指令 =====================

    fun versionQuery(): String = "dumpsys package $PKG"

    fun launchCommand(): String = "am start -W -n $MAIN_COMPONENT"

    /** 让桌面在电视上更流畅；Android 7.0 以下没有 cmd package compile，会报错，属正常 */
    fun compileCommand(): String = "cmd package compile -m speed -f $PKG"

    fun grantLocation(): String = "pm grant $PKG android.permission.ACCESS_FINE_LOCATION"

    fun disableStock(pkg: String): String = "pm disable-user --user 0 $pkg"

    fun enableStock(pkg: String): String = "pm enable --user 0 $pkg"

    fun setHome(): String = "cmd package set-home-activity --user 0 $MAIN_COMPONENT"

    fun setStockHome(component: String): String =
        "cmd package set-home-activity --user 0 $component"

    fun disabledPackages(): String = "pm list packages -d"

    fun screenSaverOn(): String =
        "settings put secure screensaver_components $DREAM_COMPONENT; settings put secure screensaver_enabled 1"

    fun screenSaverOff(): String = "settings put secure screensaver_components null"

    /** 追加式写无障碍服务，不覆盖电视上已有的其它服务 */
    fun enableAccessibility(service: String): String =
        "appops set $PKG AUTO_START allow 2>/dev/null; " +
            "s=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$s\" in *${service.substringAfterLast('.')}*) " +
            "settings put secure enabled_accessibility_services ''; " +
            "settings put secure accessibility_enabled 0; sleep 1; " +
            "settings put secure enabled_accessibility_services \"\$s\" ;; " +
            "null|'') settings put secure enabled_accessibility_services $service ;; " +
            "*) settings put secure enabled_accessibility_services \"\$s:$service\" ;; esac; " +
            "settings put secure accessibility_enabled 1; echo enabled"

    fun disableAccessibility(): String =
        "settings put secure enabled_accessibility_services null; settings put secure accessibility_enabled 0"

    /** 只摘掉某个服务，保留电视上原有的其它无障碍服务 */
    fun disableAccessibility(service: String): String =
        "s=\$(settings get secure enabled_accessibility_services); " +
            "n=\$(echo \"\$s\" | sed -e 's#$service##' -e 's#::#:#g' -e 's#^:##' -e 's#:\$##'); " +
            "if [ -z \"\$n\" ] || [ \"\$n\" = null ]; then " +
            "settings put secure enabled_accessibility_services null; else " +
            "settings put secure enabled_accessibility_services \"\$n\"; fi; echo disabled"

    /** Android 版本号的展示名，用于「装不上」时把原因讲清楚 */
    fun androidName(sdk: Int): String = when {
        sdk <= 0 -> "未知版本"
        sdk <= 21 -> "5.0"
        sdk == 22 -> "5.1"
        sdk == 23 -> "6.0"
        sdk == 24 -> "7.0"
        sdk == 25 -> "7.1"
        sdk == 26 -> "8.0"
        sdk == 27 -> "8.1"
        sdk == 28 -> "9"
        sdk == 29 -> "10"
        sdk == 30 -> "11"
        sdk == 31 -> "12"
        sdk == 32 -> "12L"
        sdk == 33 -> "13"
        sdk == 34 -> "14"
        else -> "$sdk"
    }
}
