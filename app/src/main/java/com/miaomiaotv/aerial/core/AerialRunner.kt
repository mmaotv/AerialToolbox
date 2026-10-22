package com.miaomiaotv.aerial.core

import com.miaomiaotv.aerial.adb.AdbChannel
import java.io.File

/**
 * Aerial 桌面在本机的安装、接管、验收与还原。
 *
 * 所有动作都通过本机 ADB（127.0.0.1:5555）下发，等价于上游安装说明里的
 * `adb shell ...` 那几条；不 root、不停用任何系统应用、不删除用户数据。
 *
 * 设计原则：
 *  - 每一步都记录真实回显，失败不隐瞒，也不把「命令报错」直接当成整体失败
 *    （TCL 的 APP_AUTO_START、非系统屏保的电视上写屏保，报错都属正常）；
 *  - 涉及会切断自身的操作（切换到新桌面、按主页键）放在最后。
 */
class AerialRunner(
    private val adb: AdbChannel,
    private val onLog: (String) -> Unit,
    private val onStep: (label: String, percent: Int) -> Unit
) {

    data class Outcome(
        val ok: Boolean,
        val title: String,
        val details: List<String>
    )

    data class DeviceInfo(
        val model: String,
        val brand: String,
        val sdk: Int,
        val lowRam: Boolean
    )

    data class Check(val label: String, val ok: Boolean, val detail: String)

    // ===================== 基础查询 =====================

    private fun sh(command: String): String =
        runCatching { adb.shell(command) }
            .onFailure { onLog("命令未执行：$command（${it.message}）") }
            .getOrDefault("")

    fun deviceInfo(): DeviceInfo {
        val sdk = sh("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0
        return DeviceInfo(
            model = sh("getprop ro.product.model").trim().ifBlank { "未知型号" },
            brand = sh("getprop ro.product.brand").trim(),
            sdk = sdk,
            lowRam = sh("getprop ro.config.low_ram").trim().equals("true", ignoreCase = true)
        )
    }

    /** 已安装的 Aerial 版本号；没装返回 null */
    fun installedVersion(): String? {
        val dump = sh(AerialSpec.versionQuery())
        val name = Regex("versionName=([^\\s]+)").find(dump)?.groupValues?.get(1)
        if (!name.isNullOrBlank()) return name
        // 极老的系统上 dumpsys 可能拿不到 versionName，退一步只看包是否存在
        val list = sh("pm list packages ${AerialSpec.PKG}")
        return if (list.contains("package:${AerialSpec.PKG}")) "" else null
    }

    fun isInstalled(): Boolean = installedVersion() != null

    // ===================== 一键安装 =====================

    /**
     * 完整安装流程：装包 → 授权 → 接管桌面 → 屏保/常开 → 启动验收。
     *
     * @param apk 已下载到本机、shell 可读的安装包
     */
    fun install(
        apk: File,
        keepAdb: Boolean,
        ownScreensaver: Boolean,
        useGuard: Boolean
    ): Outcome {
        val progress = StepProgress(7, onStep)
        val notes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        // 1) 设备检查
        progress.step("检查设备")
        val info = deviceInfo()
        onLog("设备：${info.model}（Android ${info.sdk}）")
        notes += "设备：${info.model} · Android ${info.sdk}"
        if (isInstalled()) onLog("设备上已有 Aerial：${installedVersion()}")

        // 1.5) 解除另一个桌面（CleanYourTV）的接管：它的按键服务会抢遥控器
        progress.step("解除原接管")
        // 自己可能被「装 CleanYourTV」那一步停用过，先确保自己能用
        RivalTakeover.reenable({ sh(it) }, AerialSpec.PKG)?.let {
            notes += it
            onLog(it)
        }
        RivalTakeover.reenable({ sh(it) }, CleanTvSpec.PKG)?.let {
            notes += it
            onLog(it)
        }
        RivalTakeover.release({ sh(it) }, CleanTvSpec.PKG)?.let {
            notes += it
            onLog(it)
        }

        // 2) 安装
        progress.step("安装桌面")
        val installResult = installApk(apk)
        if (!installResult.ok) {
            failures += "安装失败：${installResult.reason}"
            return finish(progress, failures, notes)
        }
        notes += "已安装 ${installedVersion() ?: "Aerial"}"
        onLog("安装完成：${installedVersion() ?: ""}")

        // 3) 授权
        progress.step("授予运行权限")
        val grantWarnings = applyGrants()
        notes += if (grantWarnings.isEmpty()) "权限已授予" else "权限部分受限：${grantWarnings.size} 项"

        // 4) 接管桌面
        progress.step("接管桌面")
        val homeNotes = takeOverHome(useGuard)
        notes += homeNotes

        // 5) 屏保与常开
        progress.step("屏保与自启")
        notes += applyScreensaver(ownScreensaver)
        if (keepAdb) {
            sh(AerialSpec.configBroadcast("--ez keep_adb true"))
            notes += "已开启 ADB 常开"
        }

        // 6) 启动
        progress.step("启动桌面")
        val launched = launch()
        notes += if (launched) "已启动 Aerial" else "启动返回异常，可在电视上手动打开"
        ensureGuardBound().takeIf { it.isNotBlank() }?.let { notes += it }

        // 收尾加速编译：放在最后，避免拖慢前面每一步的等待
        runCatching { sh(AerialSpec.compileCommand()) }

        return finish(progress, failures, notes)
    }

    /** 只重跑接管相关的步骤（覆盖安装后守护掉了、按主页键又回原桌面时用） */
    fun repair(keepAdb: Boolean, ownScreensaver: Boolean, useGuard: Boolean): Outcome {
        val progress = StepProgress(6, onStep)
        val notes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        progress.step("检查设备")
        val version = installedVersion()
        if (version == null) {
            failures += "设备上还没有安装 Aerial，请先执行「一键安装」"
            return finish(progress, failures, notes)
        }
        notes += "已安装版本：${version.ifBlank { "未知" }}"

        progress.step("解除原接管")
        // 自己可能被「装 CleanYourTV」那一步停用过，先确保自己能用
        RivalTakeover.reenable({ sh(it) }, AerialSpec.PKG)?.let {
            notes += it
            onLog(it)
        }
        RivalTakeover.reenable({ sh(it) }, CleanTvSpec.PKG)?.let {
            notes += it
            onLog(it)
        }
        RivalTakeover.release({ sh(it) }, CleanTvSpec.PKG)?.let {
            notes += it
            onLog(it)
        }

        progress.step("授予运行权限")
        applyGrants()

        progress.step("接管桌面")
        notes += takeOverHome(useGuard)

        progress.step("屏保与自启")
        notes += applyScreensaver(ownScreensaver)
        if (keepAdb) {
            sh(AerialSpec.configBroadcast("--ez keep_adb true"))
            notes += "已开启 ADB 常开"
        }

        progress.step("启动桌面")
        notes += if (launch()) "已启动 Aerial" else "启动返回异常，可在电视上手动打开"
        ensureGuardBound().takeIf { it.isNotBlank() }?.let { notes += it }

        return finish(progress, failures, notes)
    }

    // ===================== 安装细节 =====================

    private data class InstallResult(val ok: Boolean, val reason: String)

    /**
     * 安装安装包。
     *
     * 为什么先复制到 /data/local/tmp：负责安装的系统进程在较新安卓上无权读取
     * 外部存储（fuse 上下文无权限），直接用 /sdcard 路径安装会失败；
     * shell 自己的目录系统进程可读，这条路径在小米盒子上已验证。
     */
    private fun installApk(apk: File): InstallResult {
        if (!apk.exists() || apk.length() < 1024L) {
            return InstallResult(false, "安装包不存在或已损坏")
        }
        val source = apk.absolutePath
        onLog("安装包：$source（${apk.length() / 1024 / 1024} MB）")

        runCatching { sh("cp \"$source\" ${AerialSpec.STAGING_APK}") }
        val stagedReady = sh("ls -l ${AerialSpec.STAGING_APK}").contains("aerial-install.apk")

        val target = if (stagedReady) AerialSpec.STAGING_APK else source
        var output = sh("pm install -r $target")
        onLog("pm install 返回：${output.trim().take(200)}")
        if (isInstalled() && !isFailureText(output)) {
            runCatching { sh("rm -f ${AerialSpec.STAGING_APK}") }
            return InstallResult(true, "")
        }

        // 备用路径：直接安装原文件（部分老设备只认这种方式）
        if (target != source) {
            output = sh("pm install -r \"$source\"")
            onLog("改用原路径安装返回：${output.trim().take(200)}")
        }

        // 本机已经在线升级过、现在拿内置包重装：系统按「降级」拒绝，加 -d 放行
        if (output.contains("VERSION_DOWNGRADE")) {
            onLog("本机版本比安装包新，改为允许降级覆盖安装")
            output = sh("pm install -r -d $target")
            onLog("降级安装返回：${output.trim().take(200)}")
            if (isInstalled() && !isFailureText(output)) {
                runCatching { sh("rm -f ${AerialSpec.STAGING_APK}") }
                return InstallResult(true, "")
            }
        }

        runCatching { sh("rm -f ${AerialSpec.STAGING_APK}") }

        if (isInstalled() && !isFailureText(output)) return InstallResult(true, "")

        // 固件彻底拒绝 ADB 安装（TCL 的包校验）：安装包已经在电视的下载目录里，
        // 交给电视自己的安装入口最省事——当贝市场的包管理器在就直接打开它。
        if (output.contains("VERIFICATION_FAILURE")) {
            val dangbei = "com.dangbeimarket"
            if (sh("pm path $dangbei").contains("package:")) {
                sh("am start -n $dangbei/.ui.apkmanager.ApkManagerActivity")
                onLog("已打开当贝的安装包管理，请在电视上选择 Aerial 安装")
            }
        }

        val reason = when {
            output.contains("INSTALL_FAILED_VERIFICATION_FAILURE") ->
                "固件拒绝 ADB 安装（TCL 常见）。安装包已放在电视「下载」目录，可用文件管理器或当贝市场「本地安装」打开；或改用 guard 版重试"
            output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
                "已存在签名不同的同包名应用，需先卸载旧版再安装（还原 → 还原并卸载 后重试）"
            output.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
                "本机已装版本比这个安装包新，系统不允许降级；请到「桌面更新」页装最新版"
            output.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") -> "设备存储空间不足"
            output.contains("INSTALL_FAILED_OLDER_SDK") -> "系统版本过旧，Aerial 最低要求 Android 5.0"
            output.contains("INSTALL_FAILED_USER_RESTRICTED") -> "固件禁止安装第三方应用，请到系统设置里放行"
            isFailureText(output) -> output.trim().take(160)
            else -> "未拿到成功回显，请查看下方日志"
        }
        return InstallResult(false, reason)
    }

    private fun isFailureText(output: String): Boolean {
        val text = output.trim()
        if (text.isEmpty()) return false
        return text.contains("Failure", ignoreCase = true) ||
            text.contains("INSTALL_FAILED", ignoreCase = true) ||
            text.contains("Error", ignoreCase = true) ||
            text.contains("not found", ignoreCase = true)
    }

    // ===================== 授权 =====================

    /** @return 有报错的授权项名称（仅作提示，不影响流程） */
    private fun applyGrants(): List<String> {
        val warnings = mutableListOf<String>()
        for (item in AerialSpec.grantCommands()) {
            val output = sh(item.command).trim()
            val bad = output.contains("Exception", ignoreCase = true) ||
                output.contains("Error", ignoreCase = true) ||
                output.contains("Failure", ignoreCase = true) ||
                output.contains("not found", ignoreCase = true) ||
                output.contains("Unknown", ignoreCase = true)
            if (bad) {
                onLog("授权项未生效：${item.command} → ${output.take(80)}")
                if (item.essential) warnings += item.command.substringAfter("PM grant").substringAfter("grant").trim()
                else warnings += describe(item.command)
            } else {
                onLog("授权完成：${describe(item.command)}")
            }
        }
        return warnings
    }

    private fun describe(command: String): String = when {
        command.contains("ACCESS_FINE_LOCATION") -> "定位（读取 Wi-Fi 名称）"
        command.contains("WRITE_SECURE_SETTINGS") -> "写安全设置"
        command.contains("SYSTEM_ALERT_WINDOW") -> "悬浮窗"
        command.contains("WRITE_SETTINGS") -> "修改系统设置"
        command.contains("allow_listener") -> "通知监听（被杀后复活）"
        command.contains("APP_AUTO_START") -> "TCL 开机自启"
        command.contains("APP_ASSOC_START") -> "TCL 关联启动"
        else -> command
    }

    // ===================== 接管桌面 =====================

    private fun takeOverHome(useGuard: Boolean): String {
        val notes = mutableListOf<String>()

        if (!useGuard) {
            var applied = false
            for (command in AerialSpec.homeRoleCommands()) {
                // 带上退出码：靠关键字猜「成功没成功」会翻车 ——
                // Android 9 上 `cmd role ...` 回的是「cmd: Can't find service: role」（rc=20），
                // 里面既没有 Error 也没有 not found，会被当成成功而跳过后面真正的 set-home-activity。
                val output = sh("$command; echo RC:\$?").trim()
                val code = Regex("RC:(-?\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
                onLog("设置默认桌面：$command → ${output.ifBlank { "无回显" }}")
                val failed = (code != null && code != 0) || roleCommandFailed(output)
                if (!failed) {
                    applied = true
                    break
                }
            }
            notes += if (applied) "已申请默认桌面" else "固件不支持直接设默认桌面"
        }

        // 海信 VIDAA：固件用系统属性指定桌面包
        applyHisenseHome()?.let { notes += it }

        // 关键判断：以系统实际解析到的 HOME 组件为准。
        // 原生 Android TV 到这一步就够了；小米、TCL 等固件会无视上面的设置，
        // 这时才需要桌面守护（无障碍服务）兜底。
        val homeNow = resolveHomeComponent()
        val homeIsAerial = homeNow.startsWith("${AerialSpec.PKG}/")
        if (homeIsAerial && !useGuard) {
            notes += "已设为系统默认桌面"
            onLog("系统默认桌面已是 Aerial（$homeNow），无需桌面守护")
            return notes.joinToString("；")
        }

        // 桌面守护：追加写入，绝不覆盖电视上已有的无障碍服务（TCL 自带两个）
        val raw = sh("settings get secure enabled_accessibility_services").trim()
        val current = raw.removePrefix("[").removeSuffix("]")
        when {
            current.isBlank() || current == "null" -> {
                sh("settings put secure enabled_accessibility_services '${AerialSpec.GUARD_SERVICE}'")
                onLog("已写入桌面守护（原先没有其它无障碍服务）")
            }
            current.contains(AerialSpec.GUARD_SERVICE) -> {
                onLog("桌面守护已在无障碍服务列表中")
            }
            else -> {
                sh("settings put secure enabled_accessibility_services '${current}:${AerialSpec.GUARD_SERVICE}'")
                onLog("已把桌面守护追加到无障碍服务列表，原有服务保留")
            }
        }
        sh("settings put secure accessibility_enabled 1")
        notes += if (homeIsAerial) "桌面守护已配置" else "固件仍把主页键交给原桌面，已启用桌面守护接管"
        return notes.joinToString("；")
    }

    /**
     * 设默认桌面的命令是否真的失败。
     *
     * 退出码拿不到时（个别固件的 sh 不回显）靠关键字兜底；这里必须把
     * `cmd: Can't find service: role` 这类也算失败 —— Android 9 及以下没有 role 服务，
     * 漏掉它就会把「服务不存在」当成「已经设好」，后面的 set-home-activity 永远不执行。
     */
    private fun roleCommandFailed(output: String): Boolean {
        val text = output.trim()
        if (text.isEmpty()) return false
        return listOf(
            "Error", "Unknown", "not found", "Exception", "Failure",
            "Can't find service", "cannot find service"
        ).any { text.contains(it, ignoreCase = true) }
    }

    /** 系统当前解析到的 HOME 组件；命令不存在或没解析到时返回空串 */
    private fun resolveHomeComponent(): String {
        val output = sh(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
        )
        if (output.isBlank() || isMissingCommand(output)) return ""
        return output.lineSequence()
            .map { it.trim().removePrefix("[").removeSuffix("]") }
            .lastOrNull { it.contains("/") }
            .orEmpty()
    }

    private fun isMissingCommand(output: String): Boolean {
        val text = output.trim()
        return text.contains("not found", ignoreCase = true) ||
            text.contains("Unknown command", ignoreCase = true) ||
            text.contains("inaccessible", ignoreCase = true) ||
            text.contains("No such file", ignoreCase = true)
    }

    private fun guardConfigured(): Boolean =
        sh("settings get secure enabled_accessibility_services").contains(AerialSpec.GUARD_SERVICE)

    /**
     * 确认桌面守护真的被系统绑定。
     *
     * 只看设置项不够：覆盖安装后小米上出现过「设置里还在、system_server 仍指向旧目录」的情况，
     * 这时必须强停一次让系统重新加载新装的 APK（上游 #72 的结论）。
     */
    private fun ensureGuardBound(): String {
        if (!guardConfigured()) return ""
        if (waitGuard(6)) return "桌面守护已运行"
        onLog("桌面守护未被绑定，重启 Aerial 一次让系统重新加载")
        sh("am force-stop ${AerialSpec.PKG}")
        Thread.sleep(1500)
        launch()
        return if (waitGuard(8)) "桌面守护已重新启动" else "桌面守护未能绑定，请重启电视后再试"
    }

    private fun waitGuard(seconds: Int): Boolean {
        repeat(seconds) {
            if (sh("dumpsys accessibility").contains("label=Aerial")) return true
            Thread.sleep(1000)
        }
        return false
    }

    /** 海信 VIDAA 把桌面包名写在 persist 属性里；装之前先记下原值以便还原 */
    private fun applyHisenseHome(): String? {
        val original = sh("getprop persist.vt.use_home_pkg").trim()
        if (original.isBlank() || original == "null") return null
        if (original == AerialSpec.PKG) return null
        sh("settings put global aerial_vt_home_pkg '$original'")
        val result = sh("setprop persist.vt.use_home_pkg ${AerialSpec.PKG}").trim()
        onLog("海信桌面属性：原值 $original → ${AerialSpec.PKG} ${result.take(60)}")
        return "已改海信桌面属性（原值 $original 已记录）"
    }

    // ===================== 屏保 =====================

    /** 界面上切换「屏保接管」开关时调用，立即生效并返回一句说明 */
    fun updateScreensaver(enabled: Boolean): String = applyScreensaver(enabled)

    private fun applyScreensaver(ownScreensaver: Boolean): String {
        if (!ownScreensaver) return "屏保接管已关闭"
        val enabled = sh("settings get secure screensaver_enabled").trim()
        if (enabled != "1") {
            onLog("系统未启用标准屏保（screensaver_enabled=$enabled），跳过屏保接管")
            return "系统未启用标准屏保，跳过（小米用自己的屏保，属正常）"
        }
        sh("settings put secure screensaver_components ${AerialSpec.DREAM_COMPONENT}")
        sh(AerialSpec.configBroadcast("--ez own_screensaver true"))
        onLog("已把系统屏保交给 Aerial")
        return "系统屏保已接管"
    }

    // ===================== 启动与验收 =====================

    private fun launch(): Boolean {
        val output = sh(AerialSpec.launchCommand()).trim()
        onLog("启动返回：${output.take(160)}")
        return output.contains("Status: ok", ignoreCase = true) ||
            (!output.contains("Error", ignoreCase = true) && output.contains("Activity", ignoreCase = true))
    }

    /**
     * 验收：与上游「验收（必须做）」一节一一对应。
     * 最后一条（按主页键进入 Aerial）是唯一能证明接管成功的判据。
     */
    /** 系统当前解析到的默认桌面组件，形如 `app.orchard.tv/.MainActivity`；取不到返回空串 */
    fun currentHome(): String = resolveHomeComponent()

    fun verify(): List<Check> {
        val checks = mutableListOf<Check>()

        val dump = sh(AerialSpec.versionQuery())
        val version = Regex("versionName=([^\\s]+)").find(dump)?.groupValues?.get(1)
        checks += Check("已安装", version != null, version?.let { "版本 $it" } ?: "未找到 Aerial")

        val a11y = sh("settings get secure enabled_accessibility_services").trim()
        val guardOn = a11y.contains(AerialSpec.GUARD_SERVICE)
        checks += Check(
            "桌面守护已配置",
            guardOn,
            if (guardOn) "已写入无障碍服务列表" else "未配置（原生 Android TV 设成默认桌面时属正常）"
        )

        val accessibility = sh("dumpsys accessibility")
        val running = Regex("label=Aerial").findAll(accessibility).count()
        checks += Check(
            "桌面守护已运行",
            running > 0,
            if (running > 0) "已绑定" else "未绑定（无需守护时属正常）"
        )

        // 模拟主页键只是佐证：部分固件（Amlogic 一类）直接忽略注入的 KEYCODE_HOME，
        // 按键没反应不代表接管失败。真正算数的是系统解析到的默认桌面。
        val homeComponent = resolveHomeComponent()
        val homeIsOurs = homeComponent.startsWith("${AerialSpec.PKG}/")
        sh("input keyevent KEYCODE_HOME")
        Thread.sleep(3000)
        val focus = focusLine()
        val inAerial = focus.contains(AerialSpec.PKG)
        checks += Check(
            "主页键进入 Aerial",
            inAerial || homeIsOurs,
            when {
                inAerial -> focus
                homeIsOurs -> "默认桌面已是 Aerial（$homeComponent），本机不响应模拟主页键"
                homeComponent.isNotBlank() -> "默认桌面是 $homeComponent"
                else -> focus.ifBlank { "未取到当前窗口" }
            }
        )

        return checks
    }

    /** 当前前台窗口；优先只取一行，避免整份 dumpsys 传输 */
    private fun focusLine(): String {
        val brief = sh("dumpsys window | grep -m1 mCurrentFocus").trim()
        if (brief.isNotBlank() && brief.contains("mCurrentFocus")) return brief
        val full = sh("dumpsys window")
        return full.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains("mCurrentFocus") }
            ?: ""
    }

    // ===================== 还原 =====================

    fun restore(uninstall: Boolean, ownScreensaver: Boolean, prevHome: String = ""): Outcome {
        val progress = StepProgress(6, onStep)
        val notes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        progress.step("关闭接管开关")
        sh(AerialSpec.configBroadcast("--ez keep_adb false --ez own_screensaver false"))
        notes += "已关闭 ADB 常开与屏保归属"

        progress.step("撤销授权")
        sh("cmd notification disallow_listener ${AerialSpec.REVIVAL_LISTENER}")
        sh("pm revoke ${AerialSpec.PKG} android.permission.WRITE_SECURE_SETTINGS")
        sh("appops set ${AerialSpec.PKG} SYSTEM_ALERT_WINDOW default")
        sh("appops set ${AerialSpec.PKG} APP_AUTO_START default")
        sh("appops set ${AerialSpec.PKG} APP_ASSOC_START default")
        notes += "授权已撤销"

        progress.step("取消默认桌面")
        // role 服务是 Android 10 才有的，8/9 上这条一定是「Can't find service」，得靠下面的显式指定
        sh("cmd role remove-role-holder --user 0 android.app.role.HOME ${AerialSpec.PKG}")
        if (prevHome.isNotBlank() && !prevHome.startsWith("${AerialSpec.PKG}/")) {
            val out = sh("cmd package set-home-activity --user 0 $prevHome").trim()
            onLog("归还主页：$prevHome → ${out.ifBlank { "无回显" }}")
            notes += if (out.contains("Success")) {
                "主页已还给 ${prevHome.substringBefore('/')}"
            } else {
                "主页还原失败（${out.take(60)}），请在电视上手动选择原桌面"
            }
        } else if (prevHome.isBlank()) {
            notes += "没有记录原桌面，可按主页键手动选择"
        }
        sh("am force-stop ${AerialSpec.PKG}")
        notes += "已取消默认桌面并停止 Aerial"

        progress.step("还原屏保")
        notes += restoreScreensaver(ownScreensaver)

        progress.step("还原无障碍与海信属性")
        notes += restoreAccessibility()
        restoreHisense()?.let { notes += it }

        progress.step(if (uninstall) "卸载应用" else "回到原桌面")
        if (uninstall) {
            val output = sh("pm uninstall ${AerialSpec.PKG}").trim()
            onLog("卸载返回：$output")
            if (output.contains("Success")) notes += "Aerial 已卸载" else failures += "卸载未成功：${output.take(80)}"
        }
        sh("input keyevent KEYCODE_HOME")

        return finish(progress, failures, notes)
    }

    private fun restoreScreensaver(ownScreensaver: Boolean): String {
        val current = sh("settings get secure screensaver_components").trim()
        if (!current.contains(AerialSpec.PKG)) return "屏保未指向 Aerial，无需还原"
        val defaultComponent = sh("settings get secure screensaver_default_component").trim()
        return if (defaultComponent.isNotBlank() &&
            defaultComponent != "null" &&
            !defaultComponent.contains(AerialSpec.PKG)
        ) {
            sh("settings put secure screensaver_components '$defaultComponent'")
            "屏保已还原为系统默认"
        } else {
            sh("settings delete secure screensaver_components")
            "已清除 Aerial 屏保设置"
        }
    }

    private fun restoreAccessibility(): String {
        val raw = sh("settings get secure enabled_accessibility_services").trim()
        val current = raw.removePrefix("[").removeSuffix("]")
        if (!current.contains(AerialSpec.GUARD_SERVICE)) return "无障碍服务无需还原"
        val others = current.split(":")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != AerialSpec.GUARD_SERVICE }
        return if (others.isEmpty()) {
            sh("settings delete secure enabled_accessibility_services")
            "已移除桌面守护（原先没有其它无障碍服务）"
        } else {
            sh("settings put secure enabled_accessibility_services '${others.joinToString(":")}'")
            "已移除桌面守护，其它无障碍服务保留"
        }
    }

    private fun restoreHisense(): String? {
        val current = sh("getprop persist.vt.use_home_pkg").trim()
        if (current != AerialSpec.PKG) return null
        val original = sh("settings get global aerial_vt_home_pkg").trim()
        if (original.isBlank() || original == "null" || original == AerialSpec.PKG) {
            return "海信桌面属性未记录原值，未改动"
        }
        sh("setprop persist.vt.use_home_pkg $original")
        sh("settings delete global aerial_vt_home_pkg")
        return "海信桌面属性已还原为 $original"
    }

    // ===================== 收尾 =====================

    private fun finish(progress: StepProgress, failures: List<String>, notes: List<String>): Outcome {
        progress.done(failures.isEmpty())
        val title = if (failures.isEmpty()) "处理完成" else "处理结束，有未完成项"
        val details = mutableListOf<String>()
        details += notes
        details += failures
        return Outcome(failures.isEmpty(), title, details)
    }

    /** 进度记账：把「第几步 / 共几步」换成百分比 */
    private class StepProgress(
        private val total: Int,
        private val onStep: (String, Int) -> Unit
    ) {
        private var index = 0

        fun step(label: String) {
            val percent = (index * 100 / total).coerceIn(0, 99)
            index++
            onStep(label, percent)
        }

        fun done(success: Boolean) {
            onStep(if (success) "处理完成" else "处理结束", 100)
        }
    }
}
