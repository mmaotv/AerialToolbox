package com.miaomiaotv.aerial

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.miaomiaotv.aerial.adb.AdbChannel
import com.miaomiaotv.aerial.adb.AdbIdentity
import com.miaomiaotv.aerial.adb.UnauthorizedException
import com.miaomiaotv.aerial.core.AdbSession
import com.miaomiaotv.aerial.core.AerialRunner
import com.miaomiaotv.aerial.core.AerialSettings
import com.miaomiaotv.aerial.core.AerialSpec
import com.miaomiaotv.aerial.core.ApkStore
import com.miaomiaotv.aerial.core.BundledRelease
import com.miaomiaotv.aerial.core.CleanTvRunner
import com.miaomiaotv.aerial.core.CleanTvSpec
import com.miaomiaotv.aerial.core.ReleaseChecker
import com.miaomiaotv.aerial.core.Target
import com.miaomiaotv.aerial.core.ToolboxInfo
import com.miaomiaotv.aerial.core.VersionCompare
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 主界面（TV 风格）。
 *
 * 整个工具就跑在电视/盒子本机，通过 127.0.0.1:5555 调自己的调试通道，
 * 等价于在电脑上执行 `adb install` 与 `adb shell ...`：
 *  安装 Aerial → 授予只有 adb 能给的权限 → 接管主页键与系统屏保 → 验收 / 还原。
 *
 * 安装默认用 APK 里内置的官方 Release，全程不联网；想升级时进「桌面更新」页，
 * 那里才会去 GitHub 查最新版、展示更新日志并下载。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var installedText: TextView
    private lateinit var latestText: TextView
    private lateinit var logText: TextView
    private lateinit var actionGrid: LinearLayout
    private lateinit var retryButton: Button

    private lateinit var overlay: FrameLayout
    private lateinit var blockProgress: LinearLayout
    private lateinit var blockDone: LinearLayout
    private lateinit var blockConfirm: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var percentText: TextView
    private lateinit var stepText: TextView
    private lateinit var doneTitle: TextView
    private lateinit var doneDetail: TextView
    private lateinit var confirmTitle: TextView
    private lateinit var confirmDetail: TextView
    private lateinit var okButton: Button
    private lateinit var restoreOnlyButton: Button
    private lateinit var restoreUninstallButton: Button
    private lateinit var cancelButton: Button

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var settings: AerialSettings
    private lateinit var bundled: BundledRelease
    private lateinit var chipAerial: TextView
    private lateinit var chipCleanTv: TextView

    /** 当前目标桌面：Aerial 或 CleanYourTV，两个上游互斥，一次只装一个 */
    private var target: Target = Target.AERIAL

    @Volatile
    private var channel: AdbChannel? = null

    @Volatile
    private var runner: AerialRunner? = null

    @Volatile
    private var cytRunner: CleanTvRunner? = null

    @Volatile
    private var installedVersion: String? = null

    @Volatile
    private var deviceLabel: String = ""

    /** 电视的 API 等级：CleanYourTV 有 5.1 门槛，卡片要据此禁用 */
    @Volatile
    private var deviceSdk: Int = 0

    @Volatile
    private var busy = false

    private val logLines = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Ui.apply(window.decorView)

        settings = AerialSettings(this)
        target = Target.current(this)
        bundled = BundledRelease(this, target.bundledDir)

        statusText = findViewById(R.id.tvStatus)
        deviceText = findViewById(R.id.tvDevice)
        installedText = findViewById(R.id.tvInstalled)
        latestText = findViewById(R.id.tvLatest)
        logText = findViewById(R.id.tvLog)
        actionGrid = findViewById(R.id.actionGrid)
        retryButton = findViewById(R.id.btnRetry)
        chipAerial = findViewById(R.id.chipAerial)
        chipCleanTv = findViewById(R.id.chipCleanTv)

        overlay = findViewById(R.id.overlay)
        blockProgress = findViewById(R.id.blockProgress)
        blockDone = findViewById(R.id.blockDone)
        blockConfirm = findViewById(R.id.blockConfirm)
        progressBar = findViewById(R.id.progressBar)
        percentText = findViewById(R.id.tvPercent)
        stepText = findViewById(R.id.tvStep)
        doneTitle = findViewById(R.id.tvDoneTitle)
        doneDetail = findViewById(R.id.tvDoneDetail)
        confirmTitle = findViewById(R.id.tvConfirmTitle)
        confirmDetail = findViewById(R.id.tvConfirmDetail)
        okButton = findViewById(R.id.btnOk)
        restoreOnlyButton = findViewById(R.id.btnRestoreOnly)
        restoreUninstallButton = findViewById(R.id.btnRestoreUninstall)
        cancelButton = findViewById(R.id.btnCancel)

        okButton.setOnClickListener { hideOverlay() }
        cancelButton.setOnClickListener { hideOverlay() }
        restoreOnlyButton.setOnClickListener {
            hideOverlay()
            startRestore(uninstall = false)
        }
        restoreUninstallButton.setOnClickListener {
            hideOverlay()
            startRestore(uninstall = true)
        }
        retryButton.setOnClickListener { connect() }
        chipAerial.setOnClickListener { switchTarget(Target.AERIAL) }
        chipCleanTv.setOnClickListener { switchTarget(Target.CLEAN_TV) }

        renderTargetChips()
        buildActionGrid()
        requestStoragePermissionIfNeeded()
        connect()
    }

    override fun onResume() {
        super.onResume()
        // 从「桌面更新」页回来时版本可能已经变了，重新读一次真实状态
        val ch = channel ?: return
        executor.execute {
            val version = targetInstalledVersion(ch)
            installedVersion = version
            AdbSession.installedVersion = version
            renderInfo()
            rebuildActionGrid()
        }
    }

    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE) {
            hideOverlay()
            return
        }
        super.onBackPressed()
    }

    // ===================== 连接本机调试通道 =====================

    private fun connect() {
        if (busy) return
        status(getString(R.string.connecting))
        executor.execute {
            val identity = runCatching { AdbIdentity(File(filesDir, KEYS_DIR)) }
                .onFailure { Log.e(TAG, "密钥初始化失败", it) }
                .getOrNull()
            if (identity == null) {
                status("密钥初始化失败", STATE_WARN)
                return@execute
            }

            channel?.let { runCatching { it.disconnect() } }
            channel = null
            runner = null
            cytRunner = null
            AdbSession.clear()

            val candidate = AdbChannel()
            candidate.attachIdentity(identity)
            candidate.onNeedAuthorization = { status("请在电视上选择「允许」调试授权", STATE_WARN) }

            try {
                candidate.connect(TARGET_HOST, TARGET_PORT, CONNECT_TIMEOUT_MS)
                channel = candidate
                val r = AerialRunner(
                    adb = candidate,
                    onLog = { line -> log(line) },
                    onStep = { label, percent -> updateProgress(label, percent) }
                )
                runner = r
                AdbSession.attach(candidate, r)
                status("调试通道已连接", STATE_OK)
                log("已连接本机调试通道 $TARGET_HOST:$TARGET_PORT")

                val info = r.deviceInfo()
                deviceLabel = "${info.model} · Android ${info.sdk}"
                deviceSdk = info.sdk
                AdbSession.deviceLabel = deviceLabel
                cytRunner = CleanTvRunner(
                    adb = candidate,
                    onLog = { line -> log(line) },
                    onStep = { label, percent -> updateProgress(label, percent) }
                )
                installedVersion = targetInstalledVersion(candidate)
                AdbSession.installedVersion = installedVersion
                renderInfo()
                buildActionGrid()
            } catch (e: UnauthorizedException) {
                candidate.disconnect()
                status("未获得调试授权", STATE_WARN)
                showDone(
                    "需要在电视上允许调试授权",
                    listOf(
                        "电视上应该弹出了「允许 USB 调试吗？」的窗口",
                        "用遥控器选「允许」并勾选「始终允许」",
                        "如果没看到弹窗，重新点一次「重新连接」"
                    ),
                    ok = false
                )
            } catch (e: Exception) {
                candidate.disconnect()
                status("未连接本机调试通道", STATE_WARN)
                log("连接失败：${e.message}")
                showDone(
                    "先打开电视的 ADB 调试",
                    listOf(
                        "本工具通过电视自己的调试通道执行安装命令",
                        "小米/Redmi：设置 → 账号与安全 → 打开「ADB 调试」",
                        "原生 Android TV / 索尼：连按「内部版本号」7 次，再到开发者选项打开「USB 调试」",
                        "TCL / 雷鸟：设置 → 关于 → 本机信息 → 遥控器按 上 下 左 右 → 打开 ADB",
                        "打开后回到本页点「重新连接」"
                    ),
                    ok = false
                )
            }
        }
    }

    /** 打开「桌面更新」页：联网查最新版、看日志、下载安装都在那一页 */
    private fun openUpdatePage() {
        if (busy) return
        startActivity(
            Intent(this, UpdateActivity::class.java)
                .putExtra(UpdateActivity.EXTRA_TARGET, target.key)
        )
    }

    /** 切换目标桌面：版本、内置包、卡片都要跟着换一套 */
    private fun switchTarget(next: Target) {
        if (busy || next == target) return
        target = next
        Target.select(this, next)
        bundled = BundledRelease(this, next.bundledDir)
        installedVersion = if (channel != null) targetInstalledVersion(channel!!) else null
        AdbSession.installedVersion = installedVersion
        log("目标桌面切换为 ${next.label}")
        renderTargetChips()
        renderInfo()
        buildActionGrid()
    }

    private fun renderTargetChips() {
        listOf(chipAerial to Target.AERIAL, chipCleanTv to Target.CLEAN_TV).forEach { (view, t) ->
            val on = t == target
            view.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0x8AFFFFFF.toInt())
            view.background = if (on) getDrawable(R.drawable.bg_chip) else null
            view.alpha = if (on) 1f else 0.75f
        }
    }

    /** 按当前目标读已装版本（两个包的查询方式一样，包名不同） */
    private fun targetInstalledVersion(ch: AdbChannel): String? = runCatching {
        val pkg = target.pkg
        val list = ch.shell("pm list packages $pkg")
        if (!list.contains("package:$pkg")) return@runCatching null
        val dump = ch.shell("dumpsys package $pkg")
        Regex("versionName=([^\\s]+)").find(dump)?.groupValues?.get(1).orEmpty()
    }.getOrNull()

    /** 打开「关于」页：工具箱自己的版本、更新日志与自更新 */
    private fun openAboutPage() {
        if (busy) return
        startActivity(Intent(this, AboutActivity::class.java))
    }

    // ===================== 操作卡片 =====================

    private data class Action(
        val title: String,
        val subtitle: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit
    )

    private fun buildActionGrid() {
        ui { rebuildActionGrid() }
    }

    /** 所有界面改动都必须回到主线程；后台线程直接调用也安全 */
    private fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun aerialActions(
        connected: Boolean,
        installed: String?,
        bundledVersion: String,
        installedIsNewer: Boolean,
        toolboxVersion: String
    ): List<Action> = listOf(
        Action(
            title = if (installed == null) "一键安装桌面" else "一键安装 / 升级桌面",
            subtitle = when {
                installedIsNewer -> "本机版本更新，重装会降级"
                bundledVersion.isNotBlank() -> "内置 v$bundledVersion，离线直装"
                else -> "内置包缺失，需联网下载"
            },
            enabled = connected && !busy
        ) { startInstall() },
        Action(
            title = "桌面更新",
            subtitle = "查 GitHub 新版，看日志再升级",
            enabled = !busy
        ) { openUpdatePage() },
        Action(
            title = "修复接管",
            subtitle = "主页键失灵、守护掉线时用",
            enabled = connected && installed != null && !busy
        ) { startRepair() },
        Action(
            title = "验收检查",
            subtitle = "确认主页键是否进入 Aerial",
            enabled = connected && installed != null && !busy
        ) { startVerify() },
        Action(
            title = if (settings.useGuard) "安装通道：guard 版" else "安装通道：标准版",
            subtitle = if (settings.useGuard)
                "靠无障碍守护接管（TCL/海信）"
            else "正常声明 HOME（小米推荐）",
            enabled = !busy
        ) { toggleFlavor() },
        Action(
            title = if (settings.keepAdb) "ADB 常开：开" else "ADB 常开：关",
            subtitle = "待机唤醒后自动恢复 ADB",
            enabled = !busy
        ) { toggleKeepAdb() },
        Action(
            title = if (settings.ownScreensaver) "屏保接管：开" else "屏保接管：关",
            subtitle = "把系统屏保换成航拍（TCL）",
            enabled = !busy
        ) { toggleScreensaver() },
        Action(
            title = "还原 / 卸载",
            subtitle = "撤销接管，可一并卸载",
            enabled = connected && installed != null && !busy
        ) { showRestoreConfirm() },
        Action(
            title = "关于 / 更新工具箱",
            subtitle = "v$toolboxVersion · 自更新与版本日志",
            enabled = !busy
        ) { openAboutPage() }
    )

    /** CleanYourTV 的卡片：没有守护与安装通道，换成遥控器按键与兼容性说明 */
    private fun cleanTvActions(
        connected: Boolean,
        installed: String?,
        bundledVersion: String,
        installedIsNewer: Boolean,
        toolboxVersion: String
    ): List<Action> {
        val tooOld = deviceSdk in 1 until target.minSdk
        return listOf(
            Action(
                title = if (installed == null) "一键安装桌面" else "一键安装 / 升级桌面",
                subtitle = when {
                    tooOld -> "这台是 Android ${CleanTvSpec.androidName(deviceSdk)}，装不了"
                    installedIsNewer -> "本机版本更新，重装会降级"
                    bundledVersion.isNotBlank() -> "内置 v$bundledVersion，离线直装"
                    else -> "内置包缺失，需联网下载"
                },
                enabled = connected && !busy && !tooOld
            ) { startInstall() },
            Action(
                title = "桌面更新",
                subtitle = "查本仓库代构建的新版",
                enabled = !busy
            ) { openUpdatePage() },
            Action(
                title = "修复接管",
                subtitle = "主页跑偏、被原桌面抢回时用",
                enabled = connected && installed != null && !busy
            ) { startRepair() },
            Action(
                title = "验收检查",
                subtitle = "确认主页归属与系统版本",
                enabled = connected && installed != null && !busy
            ) { startVerify() },
            Action(
                title = if (settings.cytInputKey) "输入源键：开" else "输入源键：关",
                subtitle = "遥控器输入源键可用",
                enabled = connected && !busy
            ) { toggleCytInputKey() },
            Action(
                title = if (settings.cytHomeKey) "Home 键兼容：开" else "Home 键兼容：关",
                subtitle = "小米等 Home 发成 ALL_APPS",
                enabled = connected && !busy
            ) { toggleCytHomeKey() },
            Action(
                title = if (settings.ownScreensaver) "屏保接管：开" else "屏保接管：关",
                subtitle = "换成 CleanYourTV 时钟",
                enabled = !busy
            ) { toggleScreensaver() },
            Action(
                title = "还原 / 卸载",
                subtitle = "恢复原桌面，可一并卸载",
                enabled = connected && installed != null && !busy
            ) { showRestoreConfirm() },
            Action(
                title = "关于 / 更新工具箱",
                subtitle = "v$toolboxVersion · 自更新与版本日志",
                enabled = !busy
            ) { openAboutPage() }
        )
    }

    private fun rebuildActionGrid() {
        val connected = channel != null && (runner != null || cytRunner != null)
        val installed = installedVersion
        val bundledVersion = bundled.info().version
        val toolboxVersion = ToolboxInfo.versionName(this)
        // 本机已经在线升级过时，再用内置包装就是降级，提前说清楚
        val installedIsNewer = installed != null && bundledVersion.isNotBlank() &&
            VersionCompare.compare(installed, bundledVersion) > 0
        val actions = if (target.isAerial) {
            aerialActions(connected, installed, bundledVersion, installedIsNewer, toolboxVersion)
        } else {
            cleanTvActions(connected, installed, bundledVersion, installedIsNewer, toolboxVersion)
        }

        val inflater = LayoutInflater.from(this)
        actionGrid.removeAllViews()
        var index = 0
        while (index < actions.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            for (column in 0 until GRID_COLUMNS) {
                val action = actions.getOrNull(index) ?: break
                val card = inflater.inflate(R.layout.item_action, row, false)
                Ui.apply(card)
                card.findViewById<TextView>(R.id.tvTitle).text = action.title
                card.findViewById<TextView>(R.id.tvSub).text = action.subtitle
                card.alpha = if (action.enabled) 1f else 0.4f
                card.isFocusable = action.enabled
                card.isClickable = action.enabled
                if (action.enabled) {
                    card.setOnClickListener { action.onClick() }
                    bindFocusZoom(card)
                }
                row.addView(card)
                index++
            }
            // 补齐最后一行不足时的空位，保证卡片宽度一致
            while (row.childCount < GRID_COLUMNS) {
                val placeholder = View(this)
                placeholder.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                row.addView(placeholder)
            }
            actionGrid.addView(row)
        }

        // 焦点落在第一个可用卡片上；当前焦点不在操作区里（比如刚关掉弹窗）时也要拉回来
        var focusInside = false
        var node: View? = currentFocus
        while (node != null) {
            if (node === actionGrid) {
                focusInside = true
                break
            }
            node = node.parent as? View
        }
        if (!focusInside) {
            var firstFocusable: View? = null
            for (rowIndex in 0 until actionGrid.childCount) {
                val row = actionGrid.getChildAt(rowIndex) as? LinearLayout ?: continue
                for (childIndex in 0 until row.childCount) {
                    val child = row.getChildAt(childIndex)
                    if (child.isFocusable) {
                        firstFocusable = child
                        break
                    }
                }
                if (firstFocusable != null) break
            }
            firstFocusable?.requestFocus()
        }
    }

    /** 遥控器焦点放大效果，TV 上必须有明显的焦点反馈 */
    private fun bindFocusZoom(view: View) {
        view.setOnFocusChangeListener { target, hasFocus ->
            val scale = if (hasFocus) FOCUS_SCALE else 1f
            target.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
        }
    }

    // ===================== 动作：安装 =====================

    /**
     * 一键安装（离线优先）。
     *
     * 先用 APK 里内置的官方 Release，整个流程不需要网络；
     * 只有在内置包被裁掉（体积优化版）时才退回联网下载，保证任何情况下都能装。
     */
    private fun startInstall() {
        if (target.isAerial) startInstallAerial() else startInstallCleanTv()
    }

    private fun startInstallAerial() {
        val ch = channel ?: return notice("调试通道未连接")
        if (busy) return
        busy = true
        buildActionGrid()
        showProgress()

        executor.execute {
            try {
                val guard = settings.useGuard

                updateProgress("准备安装包…", 4)
                val bundledApk = bundled.extract(guard)
                val apk: File
                if (bundledApk != null) {
                    val version = bundled.info().version.ifBlank { "内置版" }
                    log("使用内置离线包：${bundledApk.absolutePath}")
                    updateProgress("使用内置离线包 v$version（无需联网）", 8)
                    apk = bundledApk
                } else {
                    log("没有内置包，改为联网下载")
                    updateProgress("查询线上版本…", 6)
                    val release = runCatching { ReleaseChecker.latest() }.getOrNull()
                    if (release == null) {
                        busy = false
                        buildActionGrid()
                        showDone(
                            "拿不到安装包",
                            listOf(
                                "这个版本没有内置安装包，GitHub 与官网镜像也都访问不了",
                                "请确认电视已联网后再试",
                                "也可以进「桌面更新」页重试"
                            ),
                            ok = false
                        )
                        return@execute
                    }
                    val versionTag = release.version.ifBlank { "latest" }
                    val fileName = "aerial-" + (if (guard) "guard" else "standard") + "-" + versionTag + ".apk"
                    val dest = File(ApkStore.stagingDir(this), fileName)
                    if (ReleaseChecker.isUsableCache(dest)) {
                        log("复用已下载的安装包：${dest.absolutePath}")
                    } else {
                        updateProgress("下载安装包…", 8)
                        ReleaseChecker.downloadWithFallback(release.urlFor(guard), guard, dest) { read, total ->
                            val percent = if (total > 0) (read * 40 / total).toInt().coerceIn(0, 40) else 20
                            val mb = read / 1024.0 / 1024.0
                            updateProgress(String.format(Locale.US, "下载安装包 %.1f MB…", mb), 8 + percent)
                        }
                        log("安装包已下载：${dest.absolutePath}")
                    }
                    apk = dest
                }

                updateProgress("开始安装…", 50)
                val installer = AerialRunner(
                    adb = ch,
                    onLog = { line -> log(line) },
                    onStep = { label, percent -> updateProgress(label, 50 + percent / 2) }
                )
                val outcome = installer.install(apk, settings.keepAdb, settings.ownScreensaver, guard)
                installedVersion = installer.installedVersion()
                AdbSession.installedVersion = installedVersion
                busy = false
                renderInfo()
                buildActionGrid()
                bringToolboxToFront()
                showDone(outcome.title, outcome.details, outcome.ok)
            } catch (t: Throwable) {
                busy = false
                buildActionGrid()
                showDone("处理中断", listOf(t.message ?: t.javaClass.simpleName), ok = false)
            }
        }
    }

    /**
     * CleanYourTV 的一键安装。
     *
     * 与 Aerial 最大的不同：它没有官方发布包，在线更新查的是本仓库的 `cyt-v*`，
     * 而且安装过程会停用原桌面，所以装之前先把原桌面包名记下来，还原时要用。
     */
    private fun startInstallCleanTv() {
        val ch = channel ?: return notice("调试通道未连接")
        val cyt = cytRunner ?: return notice("调试通道未连接")
        if (busy) return
        busy = true
        buildActionGrid()
        showProgress()

        executor.execute {
            try {
                updateProgress("准备安装包…", 6)
                val bundledApk = bundled.extract(false)
                val apk: File
                if (bundledApk != null) {
                    log("使用内置离线包：${bundledApk.absolutePath}")
                    updateProgress("使用内置包 v${bundled.info().version}（无需联网）", 12)
                    apk = bundledApk
                } else {
                    log("没有内置包，改为联网下载")
                    updateProgress("查询本仓库发行版…", 8)
                    val release = runCatching {
                        ReleaseChecker.latestFor(
                            target.updateApi,
                            CleanTvSpec.ASSET_PREFIX,
                            target.releasePage,
                            target.tagPrefix
                        )
                    }.getOrNull()
                    if (release == null) {
                        busy = false
                        buildActionGrid()
                        showDone(
                            "拿不到安装包",
                            listOf(
                                "这个版本没有内置 CleanYourTV，本仓库也没有可下载的代构建包",
                                "请确认电视已联网后重试",
                                "或用「桌面更新」页手动检查"
                            ),
                            ok = false
                        )
                        return@execute
                    }
                    val dest = File(
                        ApkStore.stagingDir(this),
                        "cleanyourtv-" + release.version.ifBlank { "latest" } + ".apk"
                    )
                    if (ReleaseChecker.isUsableCache(dest, 64 * 1024)) {
                        log("复用已下载的安装包：${dest.absolutePath}")
                    } else {
                        updateProgress("下载安装包…", 10)
                        ReleaseChecker.download(release.standardUrl, dest) { read, total ->
                            val percent = if (total > 0) (read * 40 / total).toInt().coerceIn(0, 40) else 20
                            val mb = read / 1024.0 / 1024.0
                            updateProgress(String.format(Locale.US, "下载安装包 %.1f MB…", mb), 10 + percent)
                        }
                        log("安装包已下载：${dest.absolutePath}")
                    }
                    apk = dest
                }

                // 记录原桌面：装完之后它可能已被停用，届时就探测不到了
                val currentHome = runCatching { cyt.homeComponent() }.getOrDefault("")
                val currentPkg = currentHome.substringBefore('/')
                if (currentPkg.isNotBlank() && currentPkg != CleanTvSpec.PKG) {
                    settings.cytStockLauncher = currentPkg
                    log("记录原桌面：$currentPkg")
                }

                updateProgress("开始安装…", 46)
                val outcome = cyt.install(
                    apk,
                    settings.cytStockLauncher,
                    settings.cytInputKey,
                    settings.cytHomeKey,
                    settings.ownScreensaver
                )
                installedVersion = targetInstalledVersion(ch)
                AdbSession.installedVersion = installedVersion
                busy = false
                renderInfo()
                buildActionGrid()
                bringToolboxToFront()
                showDone(outcome.title, outcome.details, outcome.ok)
            } catch (t: Throwable) {
                busy = false
                buildActionGrid()
                showDone("处理中断", listOf(t.message ?: t.javaClass.simpleName), ok = false)
            }
        }
    }

    // ===================== 动作：修复 / 验收 / 还原 =====================

    private fun startRepair() {
        val ch = channel ?: return notice("调试通道未连接")
        if (busy) return
        busy = true
        buildActionGrid()
        showProgress()

        executor.execute {
            try {
                if (target.isAerial) {
                    val report = AerialRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { label, percent -> updateProgress(label, percent) }
                    ).repair(settings.keepAdb, settings.ownScreensaver, settings.useGuard)
                    busy = false
                    buildActionGrid()
                    bringToolboxToFront()
                    showDone(report.title, report.details, report.ok)
                } else {
                    val report = CleanTvRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { label, percent -> updateProgress(label, percent) }
                    ).repair(
                        settings.cytStockLauncher,
                        settings.cytInputKey,
                        settings.cytHomeKey,
                        settings.ownScreensaver
                    )
                    installedVersion = targetInstalledVersion(ch)
                    busy = false
                    renderInfo()
                    buildActionGrid()
                    bringToolboxToFront()
                    showDone(report.title, report.details, report.ok)
                }
            } catch (t: Throwable) {
                busy = false
                buildActionGrid()
                showDone("处理中断", listOf(t.message ?: t.javaClass.simpleName), ok = false)
            }
        }
    }

    private fun startVerify() {
        val ch = channel ?: return notice("调试通道未连接")
        if (busy) return
        busy = true
        buildActionGrid()
        showProgress()

        executor.execute {
            try {
                updateProgress("按一次主页键并观察前台窗口…", 30)
                if (target.isAerial) {
                    val checks = AerialRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { _, _ -> }
                    ).verify()
                    // 上游的结论：只有「按主页键进入 Aerial」能证明接管成功，其余三项仅供参考
                    val decisive = checks.lastOrNull()?.ok == true
                    val lines = checks.map { (if (it.ok) "✓ " else "✗ ") + it.label + "：" + it.detail }
                    busy = false
                    buildActionGrid()
                    bringToolboxToFront()
                    showDone(
                        if (decisive) "验收通过：主页键进入 Aerial" else "验收未通过",
                        lines,
                        ok = decisive
                    )
                } else {
                    val checks = CleanTvRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { _, _ -> }
                    ).verify()
                    // 决定性项是「主页归 CleanYourTV」，其余是参考
                    val decisive = checks.firstOrNull { it.label.contains("主页") }?.ok == true
                    val lines = checks.map { (if (it.ok) "✓ " else "✗ ") + it.label + "：" + it.detail }
                    busy = false
                    buildActionGrid()
                    bringToolboxToFront()
                    showDone(
                        if (decisive) "验收通过：主页归 CleanYourTV" else "验收未通过",
                        lines,
                        ok = decisive
                    )
                }
            } catch (t: Throwable) {
                busy = false
                buildActionGrid()
                showDone("验收中断", listOf(t.message ?: t.javaClass.simpleName), ok = false)
            }
        }
    }

    private fun showRestoreConfirm() {
        ui {
            blockProgress.visibility = View.GONE
            blockDone.visibility = View.GONE
            blockConfirm.visibility = View.VISIBLE
            overlay.visibility = View.VISIBLE
            confirmTitle.text = "还原桌面"
            confirmDetail.text = buildString {
                if (target.isAerial) {
                    append("会撤销授权、取消默认桌面、关掉桌面守护与屏保接管。\n")
                    append("电视上原来的桌面一直保留着，随时能切回去。\n")
                    append("「还原并卸载」会连 Aerial 一起卸掉。")
                } else {
                    append("会重新启用被停用的原桌面、把主页还给它，并关掉按键服务与屏保接管。\n")
                    append("这一步很重要：原桌面如果没恢复，电视会没有主页可用。\n")
                    append("「还原并卸载」会连 CleanYourTV 一起卸掉（桌面布局与主题设置一并清除）。")
                }
            }
            restoreOnlyButton.requestFocus()
        }
    }

    private fun startRestore(uninstall: Boolean) {
        val ch = channel ?: return notice("调试通道未连接")
        if (busy) return
        busy = true
        buildActionGrid()
        showProgress()

        executor.execute {
            try {
                if (target.isAerial) {
                    val report = AerialRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { label, percent -> updateProgress(label, percent) }
                    ).restore(uninstall, settings.ownScreensaver)
                    installedVersion = targetInstalledVersion(ch)
                    busy = false
                    renderInfo()
                    buildActionGrid()
                    showDone(report.title, report.details, report.ok)
                } else {
                    val report = CleanTvRunner(
                        adb = ch,
                        onLog = { line -> log(line) },
                        onStep = { label, percent -> updateProgress(label, percent) }
                    ).restore(uninstall, settings.cytStockLauncher)
                    installedVersion = targetInstalledVersion(ch)
                    if (uninstall) settings.cytStockLauncher = ""
                    busy = false
                    renderInfo()
                    buildActionGrid()
                    showDone(report.title, report.details, report.ok)
                }
            } catch (t: Throwable) {
                busy = false
                buildActionGrid()
                showDone("处理中断", listOf(t.message ?: t.javaClass.simpleName), ok = false)
            }
        }
    }

    // ===================== 动作：开关 =====================

    private fun toggleFlavor() {
        settings.useGuard = !settings.useGuard
        log("安装通道：${if (settings.useGuard) "guard 版" else "标准版"}（下次安装生效）")
        buildActionGrid()
    }

    private fun toggleKeepAdb() {
        settings.keepAdb = !settings.keepAdb
        log("ADB 常开：${if (settings.keepAdb) "开" else "关"}")
        buildActionGrid()
        applyQuietly { ch ->
            ch.shell(AerialSpec.configBroadcast("--ez keep_adb ${settings.keepAdb}"))
        }
    }

    private fun toggleScreensaver() {
        settings.ownScreensaver = !settings.ownScreensaver
        log("屏保接管：${if (settings.ownScreensaver) "开" else "关"}")
        buildActionGrid()
        applyQuietly { ch ->
            if (target.isAerial) {
                val runnerLocal = AerialRunner(ch, { line -> log(line) }, { _, _ -> })
                log(runnerLocal.updateScreensaver(settings.ownScreensaver))
            } else {
                val command = if (settings.ownScreensaver) {
                    CleanTvSpec.screenSaverOn()
                } else {
                    CleanTvSpec.screenSaverOff()
                }
                ch.shell(command)
                log(if (settings.ownScreensaver) "系统屏保已换成 CleanYourTV 时钟" else "已清空屏保接管")
            }
        }
    }

    private fun toggleCytInputKey() {
        settings.cytInputKey = !settings.cytInputKey
        log("输入源键：${if (settings.cytInputKey) "开" else "关"}")
        buildActionGrid()
        applyQuietly { ch ->
            val service = CleanTvSpec.INPUT_KEY_SERVICE
            ch.shell(
                if (settings.cytInputKey) CleanTvSpec.enableAccessibility(service)
                else CleanTvSpec.disableAccessibility(service)
            )
            log(if (settings.cytInputKey) "遥控器输入源键已交给 CleanYourTV" else "已关闭输入源键接管")
        }
    }

    private fun toggleCytHomeKey() {
        settings.cytHomeKey = !settings.cytHomeKey
        log("Home 键兼容：${if (settings.cytHomeKey) "开" else "关"}")
        buildActionGrid()
        applyQuietly { ch ->
            val service = CleanTvSpec.HOME_KEY_SERVICE
            ch.shell(
                if (settings.cytHomeKey) CleanTvSpec.enableAccessibility(service)
                else CleanTvSpec.disableAccessibility(service)
            )
            log(if (settings.cytHomeKey) "Home 键兼容已开启" else "已关闭 Home 键兼容")
        }
    }

    /** 开关类操作：连得上就顺手应用，连不上只保存设置，不打扰用户 */
    private fun applyQuietly(block: (AdbChannel) -> Unit) {
        val ch = channel ?: return
        if (installedVersion == null) return
        executor.execute { runCatching { block(ch) } }
    }

    // ===================== 弹窗 =====================

    private fun showProgress() {
        ui {
            blockProgress.visibility = View.VISIBLE
            blockDone.visibility = View.GONE
            blockConfirm.visibility = View.GONE
            overlay.visibility = View.VISIBLE
            progressBar.progress = 0
            percentText.text = getString(R.string.percent_zero)
            stepText.text = getString(R.string.preparing)
        }
    }

    private fun updateProgress(label: String, percent: Int) {
        handler.post {
            val value = percent.coerceIn(0, 100)
            progressBar.progress = value
            percentText.text = "$value%"
            stepText.text = label
        }
    }

    private fun showDone(title: String, details: List<String>, ok: Boolean) {
        ui {
            blockProgress.visibility = View.GONE
            blockConfirm.visibility = View.GONE
            blockDone.visibility = View.VISIBLE
            overlay.visibility = View.VISIBLE
            doneTitle.text = title
            doneTitle.setTextColor(resources.getColor(if (ok) R.color.ok else R.color.warn))
            doneDetail.text = details.filter { it.isNotBlank() }
                .take(12)
                .joinToString("\n") { "· $it" }
            okButton.requestFocus()
        }
        Log.i(TAG, "结果：$title ${details.joinToString(" | ")}")
    }

    private fun hideOverlay() {
        ui {
            overlay.visibility = View.GONE
            rebuildActionGrid()
        }
    }

    private fun notice(message: String) {
        showDone(message, emptyList(), ok = false)
    }

    // ===================== 界面辅助 =====================

    private fun renderInfo() {
        val bundledInfo = bundled.info()
        handler.post {
            deviceText.text = deviceLabel.ifBlank { getString(R.string.dash) }
            installedText.text = installedVersion?.let {
                if (it.isBlank()) "已安装" else "已安装 v$it"
            } ?: "未安装"
            latestText.text = when {
                !bundledInfo.available -> "未内置"
                bundledInfo.publishedAt.isNotBlank() -> "v${bundledInfo.version}（${bundledInfo.publishedAt}）"
                else -> "v${bundledInfo.version}"
            }
        }
    }

    /** 状态胶囊：文字前带一个状态点，绿=通道可用、橙=等待授权或失败、灰=未知 */
    @Suppress("DEPRECATION")
    private fun status(text: String, state: Int = STATE_NONE) {
        handler.post {
            val color = when (state) {
                STATE_OK -> resources.getColor(R.color.ok)
                STATE_WARN -> resources.getColor(R.color.warn)
                else -> resources.getColor(R.color.text_tertiary)
            }
            Ui.status(statusText, text, color)
        }
    }
    private fun log(line: String) {
        Log.i(TAG, line)
        handler.post {
            logLines.addLast(line)
            while (logLines.size > 2) logLines.removeFirst()
            logText.text = logLines.joinToString("\n")
        }
    }

    /**
     * 装完 / 验收完 Aerial 会跑到前台，把工具箱拉回来，用户才看得到结果弹窗。
     * 走本机 shell 的 am start，绕过「后台应用不能启动 Activity」的系统限制。
     */
    private fun bringToolboxToFront() {
        val ch = channel ?: return
        runCatching { ch.shell("am start -n $packageName/.MainActivity") }
    }

    /** 安装包落地位置统一由 ApkStore 决定（公共下载目录 → 应用外部目录） */

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P) {
            val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
            }
        }
    }

    private companion object {
        const val TAG = "AerialToolbox"

        /** 处理对象就是本机 */
        const val TARGET_HOST = "127.0.0.1"
        const val TARGET_PORT = 5555
        const val CONNECT_TIMEOUT_MS = 5000

        const val KEYS_DIR = "keys"
        const val FOCUS_SCALE = 1.03f

        /** 操作卡片列数：3 列更像 tvOS 的 app 宫格 */
        const val GRID_COLUMNS = 3

        const val STATE_NONE = 0
        const val STATE_OK = 1
        const val STATE_WARN = 2
        const val REQUEST_STORAGE = 1001
    }
}
