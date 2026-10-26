package com.miaomiaotv.aerial.core

import com.miaomiaotv.aerial.adb.AdbChannel
import java.io.File

/**
 * CleanYourTV 桌面在本机的安装、接管、验收与还原。
 *
 * 流程严格对齐上游 `launcher/build-and-install.sh`，原因是它每一步都在为下一步兜底：
 *  1. 先问电视的 SDK —— 版本不够时 `pm install` 会 INSTALL_FAILED_OLDER_SDK，
 *     但那是构建、推送都做完之后才知道，太晚；
 *  2. 装完**先启动一次**并确认 `Status: ok`，再动原桌面 —— 上游注释里写得很清楚：
 *     启动失败时如果已经禁用了原桌面，电视就没有主页了；
 *  3. 最后设默认桌面并复查 `resolve-activity`，不对就立刻把原桌面还回去。
 *
 * 与 Aerial 的差别：这里没有无障碍守护，取而代之的是遥控器按键服务；
 * 默认桌面的归属靠 `cmd package set-home-activity`（Android 7.0+），
 * 更老的系统上该命令不存在，只能靠「只留一个桌面」让系统自己接管。
 */
class CleanTvRunner(
    private val adb: AdbChannel,
    private val onLog: (String) -> Unit,
    private val onStep: (label: String, percent: Int) -> Unit
) {

    // ===================== 基础 =====================

    private fun sh(command: String): String =
        runCatching { adb.shell(command) }
            .onFailure { onLog("命令未执行：$command（${it.message}）") }
            .getOrDefault("")

    fun sdk(): Int = sh("getprop ro.build.version.sdk").trim().toIntOrNull() ?: 0

    fun installedVersion(): String? {
        val dump = sh(CleanTvSpec.versionQuery())
        val name = Regex("versionName=([^\\s]+)").find(dump)?.groupValues?.get(1)
        if (!name.isNullOrBlank()) return name
        val list = sh("pm list packages ${CleanTvSpec.PKG}")
        return if (list.contains("package:${CleanTvSpec.PKG}")) "" else null
    }

    fun isInstalled(): Boolean = installedVersion() != null

    /** 当前默认桌面组件，形如 `com.google.android.tvlauncher/.MainActivity`；拿不到返回空串 */
    fun homeComponent(): String {
        val modern = lastComponent(sh(CleanTvSpec.HOME_QUERY_MODERN))
        if (modern != null) return modern
        // Android 6 及以下没有 cmd，用 pm 的同一查询
        return lastComponent(sh(CleanTvSpec.HOME_QUERY_LEGACY)).orEmpty()
    }

    private fun lastComponent(output: String): String? =
        Regex("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)")
            .findAll(output)
            .map { it.value }
            .lastOrNull()

    /** 是否是本工具认识的、可以安全禁用的原厂桌面 */
    private fun isKnownStock(pkg: String): Boolean =
        CleanTvSpec.KNOWN_STOCK_LAUNCHERS.contains(pkg)

    private fun isDisabled(pkg: String): Boolean =
        sh(CleanTvSpec.disabledPackages()).lineSequence().any { it.trim() == "package:$pkg" }

    private fun isMissingCommand(output: String): Boolean {
        val text = output.lowercase()
        return text.contains("not found") || text.contains("unknown command") ||
            text.contains("inaccessible") || text.contains("no shell command")
    }

    // ===================== 安装 =====================

    /**
     * @param apkPath 安装包在**设备上**的路径（不是本机路径！）
     * @param stockHint 上一次记下的原桌面包名；为空时现场探测
     */
    fun install(
        apkPath: String,
        stockHint: String?,
        inputKey: Boolean,
        homeKey: Boolean,
        screensaver: Boolean
    ): Outcome {
        val total = 9 + (if (screensaver) 1 else 0) + (if (inputKey) 1 else 0) + (if (homeKey) 1 else 0)
        val progress = StepProgress(total, onStep)
        val notes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        // 1. 系统版本门槛
        progress.step("检查系统版本")
        val sdk = sdk()
        onLog("电视系统：Android ${CleanTvSpec.androidName(sdk)}（API $sdk）")
        if (sdk in 1 until CleanTvSpec.MIN_SDK) {
            return Outcome(
                false,
                "这台电视装不了",
                listOf(
                    "它跑的是 Android ${CleanTvSpec.androidName(sdk)}（API $sdk），",
                    "CleanYourTV 最低要 Android 5.1（API ${CleanTvSpec.MIN_SDK}）。",
                    "请改用 Aerial 桌面：它支持到 Android 5.0。"
                )
            )
        }

        // 2. 解除另一个桌面（Aerial）的接管
        //    不撤掉它的无障碍守护，按主页键会被它的 HomeGuardService 抢回去，
        //    CleanYourTV 一次都留不住（真机 Amlogic/Android 9 上就是这么翻车的）。
        progress.step("解除原接管")
        RivalTakeover.release(
            { sh(it) }, AerialSpec.PKG, AerialSpec.REVIVAL_LISTENER, disable = true
        )?.let {
            notes += it
            onLog(it)
        }

        // 3. 探测原桌面
        progress.step("探测原桌面")
        val currentHome = homeComponent()
        val currentPkg = currentHome.substringBefore('/')
        var stockLauncher = stockHint.orEmpty().substringBefore('/')
        var disableStock = false
        when {
            currentPkg == CleanTvSpec.PKG -> {
                notes += "主页已经在 CleanYourTV 上，这次只做修复与重设"
                if (stockLauncher.isNotBlank()) disableStock = isKnownStock(stockLauncher)
            }
            currentPkg.isBlank() || currentPkg == "android" -> {
                // 系统没有默认桌面（落在选择器上），只保留上次的记录
                onLog("主页当前没有默认桌面（落到了系统选择器）")
                if (stockLauncher.isNotBlank()) {
                    disableStock = isKnownStock(stockLauncher)
                } else {
                    notes += "主页没有默认桌面，装好后按主页键，在弹窗里选 CleanYourTV 并点「始终」"
                }
            }
            else -> {
                // 关键：不管认不认识这个桌面，都要记下来 —— 还原时得还给它。
                // 「能不能停用」和「要不要归还」是两件事，老版本混为一谈：
                // 不在白名单的桌面被清成空，还原时整段被跳过，
                // 主页就留在一个马上要被卸载的应用上，卸载后按主页键就没反应了。
                stockLauncher = currentPkg
                disableStock = isKnownStock(currentPkg)
                onLog(
                    if (disableStock) "原桌面：$stockLauncher（安装成功后会停用）"
                    else "原桌面：$currentHome（不在可停用名单，只记录、不停用）"
                )
            }
        }
        onLog(if (disableStock) "原桌面：$stockLauncher（安装成功后会禁用）" else "原桌面：不禁用")

        // 3. 装包
        progress.step("安装桌面")
        val install = installApk(apkPath)
        if (!install.first) {
            progress.done(false)
            return Outcome(false, "安装失败", listOf(install.second).filter { it.isNotBlank() })
        }
        onLog("CleanYourTV ${installedVersion().orEmpty()} 已安装")

        // 4. 启动校验：起不来就到此为止，原桌面一根手指都不碰
        progress.step("启动校验")
        val start = sh(CleanTvSpec.launchCommand())
        onLog(start.trim().take(160))
        val started = start.contains("Status: ok") && !start.contains("Error: Activity not started")
        if (!started) {
            progress.done(false)
            return Outcome(
                false,
                "桌面启动失败",
                listOf(
                    "CleanYourTV 装上了，但启动没有成功（回显里没有 Status: ok）。",
                    "原桌面没有被禁用，电视现在还能正常用。",
                    "可以先到「验收检查」看详情，或换一个安装包重试。"
                )
            )
        }

        // 5. 定位权限（Android 6+ 才需要运行时授权，5.1 上会报错，忽略）
        progress.step("授权")
        val grant = sh(CleanTvSpec.grantLocation())
        if (grant.contains("not a changeable permission")) {
            onLog("Android 5.1 的定位权限是安装即授予，跳过运行时授权")
        } else {
            onLog(grant.trim().take(120))
        }

        // 5.5 写安全设置权限：让 CleanYourTV 也能在开机/待机后把 ADB 重新打开（与 Aerial 对齐）。
        // 这个权限只能经 ADB 授予、授权后持久化；CleanYourTV 自己的开机接收器会用它恢复 ADB。
        val wss = sh(CleanTvSpec.grantSecureSettings())
        if (wss.contains("not a changeable permission")) {
            onLog("Android 5.1 上写安全设置权限无需运行时授权，已默认可用")
        } else {
            onLog("写安全设置权限：${wss.trim().take(80)}")
        }

        // 6. 编译优化（Android 7.0+ 才有，老系统跳过）
        progress.step("编译优化")
        val compile = sh(CleanTvSpec.compileCommand())
        if (isMissingCommand(compile)) {
            onLog("这台电视没有 cmd package compile（Android 7.0 以下），跳过编译优化")
        } else {
            notes += "编译优化：${compile.trim().take(80)}"
        }

        // 7. 禁用原桌面
        progress.step(if (disableStock) "停用原桌面" else "跳过原桌面")
        if (disableStock && stockLauncher.isNotBlank()) {
            val out = sh(CleanTvSpec.disableStock(stockLauncher))
            onLog(out.trim().take(120))
            if (isFailure(out)) {
                notes += "原桌面 $stockLauncher 没能停用（部分固件不允许），不影响使用"
            } else {
                notes += "原桌面 $stockLauncher 已停用，可在「还原」里恢复"
            }
        }

        // 8. 设为默认桌面
        progress.step("设为默认桌面")
        val homeOut = sh(CleanTvSpec.setHome())
        onLog(homeOut.trim().take(120))
        if (isMissingCommand(homeOut)) {
            onLog("Android 7.0 以下没有 cmd package set-home-activity，交由系统在只剩一个桌面时接管")
        }

        // 9. 复查；不对就回滚
        progress.step("复查主页归属")
        val after = homeComponent()
        val onUs = after.startsWith(CleanTvSpec.PKG + "/")
        if (!onUs) {
            onLog("主页仍指向 ${after.ifBlank { "未知" }}，开始回滚")
            val rolled = rollback(stockLauncher, disableStock)
            progress.done(false)
            return Outcome(
                false,
                "接管没有完成",
                listOf(
                    "CleanYourTV 已安装，但主页没有切过去（当前指向 ${after.ifBlank { "未知组件" }}）。",
                    rolled,
                    "在电视上按主页键，选 CleanYourTV 并点「始终」即可。"
                )
            )
        }
        notes += "主页现在归 CleanYourTV"

        // 10. 屏保
        if (screensaver) {
            progress.step("接管屏保")
            sh(CleanTvSpec.screenSaverOn())
            notes += "系统屏保已换成 CleanYourTV 的时钟"
        }

        // 11. 遥控器按键服务（默认关闭，按需开启）
        if (inputKey) {
            progress.step("启用输入源键")
            sh(CleanTvSpec.enableAccessibility(CleanTvSpec.INPUT_KEY_SERVICE))
            notes += "遥控器「输入源」键已交给 CleanYourTV"
        }
        if (homeKey) {
            progress.step("启用 Home 键兼容")
            sh(CleanTvSpec.enableAccessibility(CleanTvSpec.HOME_KEY_SERVICE))
            notes += "遥控器 Home 键兼容已开启（小米盒子/小米电视常见）"
        }

        progress.done(true)
        return Outcome(true, "CleanYourTV 已接管主页", notes)
    }

    /** 只重设接管，不重新装包 */
    fun repair(
        stockHint: String?,
        inputKey: Boolean,
        homeKey: Boolean,
        screensaver: Boolean
    ): Outcome {
        val progress = StepProgress(6, onStep)
        val notes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        if (!isInstalled()) {
            return Outcome(false, "还没有装 CleanYourTV", listOf("先用「一键安装桌面」装上，再来修复接管"))
        }

        progress.step("解除原接管")
        RivalTakeover.release(
            { sh(it) }, AerialSpec.PKG, AerialSpec.REVIVAL_LISTENER, disable = true
        )?.let {
            notes += it
            onLog(it)
        }

        progress.step("启动桌面")
        val start = sh(CleanTvSpec.launchCommand())
        if (!start.contains("Status: ok")) {
            failures += "桌面启动没有返回 Status: ok：${start.trim().take(120)}"
        }

        progress.step("复查主页")
        val home = homeComponent()
        val onUs = home.startsWith(CleanTvSpec.PKG + "/")
        onLog("主页：${home.ifBlank { "未知" }}")

        if (!onUs) {
            val stock = stockHint.orEmpty().substringBefore('/')
            if (stock.isNotBlank() && isKnownStock(stock) && !isDisabled(stock)) {
                progress.step("停用原桌面")
                sh(CleanTvSpec.disableStock(stock))
            } else {
                progress.step("停用原桌面")
            }
            progress.step("设为默认桌面")
            // 先补 HOME role：只写 set-home-activity 的话 role 还是空的，
            // 应用一卸载系统就判成「没有默认桌面」，按主页键没反应
            sh("cmd role add-role-holder --user 0 android.app.role.HOME ${CleanTvSpec.PKG}")
            val out = sh(CleanTvSpec.setHome())
            if (isMissingCommand(out)) notes += "本机没有 set-home-activity，请在电视上按主页键手动选择"
        } else {
            progress.step("停用原桌面")
            progress.step("设为默认桌面")
            notes += "主页已经归 CleanYourTV，无需重复设置"
        }

        progress.step("按键与屏保")
        if (inputKey) sh(CleanTvSpec.enableAccessibility(CleanTvSpec.INPUT_KEY_SERVICE))
        if (homeKey) sh(CleanTvSpec.enableAccessibility(CleanTvSpec.HOME_KEY_SERVICE))
        if (screensaver) sh(CleanTvSpec.screenSaverOn())

        progress.step("复查主页归属")
        val finalHome = homeComponent()
        if (!finalHome.startsWith(CleanTvSpec.PKG + "/")) {
            failures += "主页仍指向 ${finalHome.ifBlank { "未知" }}"
        } else {
            notes += "主页现在归 CleanYourTV"
        }

        progress.done(failures.isEmpty())
        return Outcome(failures.isEmpty(), if (failures.isEmpty()) "接管已修复" else "修复未完全成功", notes + failures)
    }

    /** 失败时把原桌面还回去 */
    private fun rollback(stockLauncher: String, disabled: Boolean): String {
        if (!disabled || stockLauncher.isBlank()) {
            return "没有禁用过原桌面，电视不受影响"
        }
        sh(CleanTvSpec.enableStock(stockLauncher))
        val component = lastComponent(sh(CleanTvSpec.HOME_QUERY_MODERN)) ?: ""
        if (component.startsWith(stockLauncher)) {
            sh("cmd role add-role-holder --user 0 android.app.role.HOME $stockLauncher")
            sh(CleanTvSpec.setStockHome(component))
        }
        return if (isDisabled(stockLauncher)) {
            "原桌面没能恢复，请在电视「设置 → 应用」里重新启用 $stockLauncher"
        } else {
            "原桌面 $stockLauncher 已恢复启用"
        }
    }

    // ===================== 验收 =====================

    fun verify(): List<Check> {
        val checks = mutableListOf<Check>()
        val version = installedVersion()
        checks += Check(
            "CleanYourTV 已安装",
            version != null,
            version?.let { "版本 ${it.ifBlank { "未知" }}" } ?: "没找到 ${CleanTvSpec.PKG}"
        )

        val home = homeComponent()
        checks += Check(
            "主页归 CleanYourTV",
            home.startsWith(CleanTvSpec.PKG + "/"),
            home.ifBlank { "拿不到当前桌面组件" }
        )

        // isDefault=false 意味着系统其实没认定任何默认桌面 ——
        // 平时看着能用（override 顶着），一旦卸载或重启，按主页键就会没反应
        val hasDefault = HomeRoles.hasDefault { sh(it) }
        checks += Check(
            "系统承认默认桌面",
            hasDefault,
            if (hasDefault) "isDefault=true，按主页键直达桌面"
            else "isDefault=false：按主页键可能弹选择框或没反应，请到「还原」重新归还一次主页"
        )

        val disabled = sh(CleanTvSpec.disabledPackages())
        val disabledStocks = CleanTvSpec.KNOWN_STOCK_LAUNCHERS.filter { disabled.contains("package:$it") }
        checks += Check(
            "原桌面状态",
            disabledStocks.isEmpty() || home.startsWith(CleanTvSpec.PKG + "/"),
            if (disabledStocks.isEmpty()) "原桌面仍在（可以随时切回去）"
            else "已停用：${disabledStocks.joinToString("、")}"
        )

        val sdk = sdk()
        checks += Check(
            "系统版本",
            sdk >= CleanTvSpec.MIN_SDK,
            "Android ${CleanTvSpec.androidName(sdk)}（API $sdk），要求 ≥ ${CleanTvSpec.MIN_SDK}"
        )

        val wraith = sh("pm list packages ${CleanTvSpec.SETUP_WRAITH}")
        val wraithOn = wraith.contains("package:${CleanTvSpec.SETUP_WRAITH}") &&
            !disabled.contains("package:${CleanTvSpec.SETUP_WRAITH}")
        checks += Check(
            "安装向导未占主页",
            !wraithOn || home.startsWith(CleanTvSpec.PKG + "/"),
            if (!wraithOn) "未检测到 ${CleanTvSpec.SETUP_WRAITH}"
            else "它还在，Google TV 上可能抢主页；如遇主页乱跳，把它加入停用清单"
        )

        val accessibility = sh("settings get secure enabled_accessibility_services")
        val inputOn = accessibility.contains("InputKeyService")
        val homeKeyOn = accessibility.contains("HomeKeyService")
        checks += Check(
            "遥控器按键服务",
            true,
            when {
                inputOn && homeKeyOn -> "输入源键、Home 键都已开启"
                inputOn -> "已开输入源键，Home 键未开"
                homeKeyOn -> "已开 Home 键，输入源键未开"
                else -> "都没开（默认，不影响主页）"
            }
        )
        return checks
    }

    // ===================== 还原 =====================

    fun restore(uninstall: Boolean, stockHint: String?): Outcome {
        val progress = StepProgress(if (uninstall) 6 else 5, onStep)
        val notes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        val stock = stockHint.orEmpty().substringBefore('/')

        // 装 CleanYourTV 时把 Aerial 停用了，还原第一步就要让它能用，
        // 否则用户切回 Aerial 会发现「装了但打不开」
        progress.step("恢复另一个桌面")
        RivalTakeover.reenable({ sh(it) }, AerialSpec.PKG)?.let {
            notes += it
            onLog(it)
        }

        progress.step("恢复原桌面")
        if (stock.isNotBlank() && isKnownStock(stock)) {
            val out = sh(CleanTvSpec.enableStock(stock))
            onLog(out.trim().take(120))
            if (isDisabled(stock)) {
                failures += "原桌面 $stock 没能恢复启用"
            } else {
                notes += "原桌面 $stock 已恢复启用"
            }
        } else if (stock.isNotBlank()) {
            // 不是被我们停用的那类桌面，本来就没禁用过，不用 enable
            onLog("$stock 不在停用名单里，无需恢复启用")
        }

        progress.step("把主页还回去")
        // 交给 HomeRoles：它会先补 HOME role 再写默认桌面，并复查 isDefault。
        // 老版本这里只写 set-home-activity，role 一直是空的，
        // 卸载 CleanYourTV 后系统就变成「没有默认桌面」，按主页键没反应。
        notes += HomeRoles.giveBack(
            { sh(it) }, { onLog(it) }, stockHint.orEmpty(), setOf(CleanTvSpec.PKG, AerialSpec.PKG)
        )

        progress.step("关闭按键服务")
        sh(CleanTvSpec.disableAccessibility())

        progress.step("恢复屏保")
        sh(CleanTvSpec.screenSaverOff())
        notes += "系统屏保设置已清空（电视会回到自己的默认屏保）"

        if (uninstall) {
            progress.step("卸载 CleanYourTV")
            val out = sh("pm uninstall ${CleanTvSpec.PKG}")
            onLog(out.trim().take(120))
            if (out.contains("Success")) {
                notes += "CleanYourTV 已卸载（桌面布局与主题设置一并清除）"
            } else {
                failures += "卸载返回：${out.trim().take(100)}"
            }
        }

        progress.done(failures.isEmpty())
        return Outcome(failures.isEmpty(), if (failures.isEmpty()) "已还原" else "还原完成，有未完成项", notes + failures)
    }

    // ===================== 内部 =====================

    /** @return (成功, 失败原因) */
    private fun installApk(apkPath: String): Pair<Boolean, String> {
        // 设备上的路径要原样交给设备 shell，不做本机文件系统相关的规范化
        val source = apkPath
        // 大小必须在设备上量：手机端远程模式下，安装包在电视上，
        // 用 File.exists() 查的是手机，永远查不到（详见 DeviceFiles 的说明）
        val size = DeviceFiles.size({ sh(it) }, source)
        if (size < 64 * 1024) {
            return false to "安装包不存在或已损坏（设备上读不到 $source）"
        }
        onLog("安装包：$source（${size / 1024} KB）")
        runCatching { sh("cp \"$source\" ${CleanTvSpec.STAGING_APK}") }
        val staged = sh("ls -l ${CleanTvSpec.STAGING_APK}").contains("tvhome.apk")
        val target = if (staged) CleanTvSpec.STAGING_APK else source

        var output = sh("pm install -r $target")
        onLog("pm install 返回：${output.trim().take(200)}")
        if (isInstalled() && !isFailure(output)) {
            runCatching { sh("rm -f ${CleanTvSpec.STAGING_APK}") }
            return true to ""
        }

        if (output.contains("VERSION_DOWNGRADE")) {
            onLog("本机版本比安装包新，改为允许降级覆盖")
            output = sh("pm install -r -d $target")
            onLog("降级安装返回：${output.trim().take(200)}")
            if (isInstalled() && !isFailure(output)) {
                runCatching { sh("rm -f ${CleanTvSpec.STAGING_APK}") }
                return true to ""
            }
        }
        runCatching { sh("rm -f ${CleanTvSpec.STAGING_APK}") }

        val reason = when {
            output.contains("INSTALL_FAILED_OLDER_SDK") ->
                "这台电视的系统版本低于安装包要求（INSTALL_FAILED_OLDER_SDK），请换用内置的兼容版"
            output.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
                "已存在签名不同的 CleanYourTV（可能是自己按上游文档构建的），请先卸载旧版"
            output.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") -> "电视存储空间不足"
            output.contains("INSTALL_FAILED_USER_RESTRICTED") -> "固件禁止安装第三方应用，请到系统设置里放行"
            isFailure(output) -> output.trim().take(160)
            else -> "未拿到成功回显"
        }
        return false to reason
    }

    private fun isFailure(output: String): Boolean {
        val text = output.trim()
        if (text.isEmpty()) return false
        return text.contains("Failure", true) || text.contains("INSTALL_FAILED", true) ||
            text.contains("Error", true)
    }

    private class StepProgress(private val total: Int, private val onStep: (String, Int) -> Unit) {
        private var index = 0
        fun step(label: String) {
            onStep(label, (index * 100 / total).coerceIn(0, 99))
            index++
        }
        fun done(success: Boolean) = onStep(if (success) "处理完成" else "处理结束", 100)
    }
}
